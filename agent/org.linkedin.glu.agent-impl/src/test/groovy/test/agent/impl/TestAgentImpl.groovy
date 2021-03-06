/*
 * Copyright 2010-2010 LinkedIn, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */


package test.agent.impl

import org.linkedin.glu.agent.api.MountPoint
import org.linkedin.glu.agent.api.NoSuchMountPointException
import org.linkedin.glu.agent.impl.AgentImpl
import org.linkedin.glu.agent.impl.capabilities.ShellImpl
import org.linkedin.glu.agent.impl.script.FromClassNameScriptFactory
import org.linkedin.glu.agent.impl.script.RootScript
import org.linkedin.glu.agent.impl.script.ScriptDefinition
import org.linkedin.glu.agent.impl.storage.RAMStorage
import org.linkedin.glu.agent.impl.storage.Storage
import org.linkedin.glu.agent.api.ScriptExecutionException
import org.linkedin.glu.agent.api.ScriptIllegalStateException
import org.linkedin.util.concurrent.ThreadControl
import org.linkedin.util.clock.Timespan
import org.linkedin.util.clock.SettableClock
import org.linkedin.util.io.ram.RAMDirectory
import org.linkedin.util.io.resource.internal.RAMResourceProvider
import org.linkedin.groovy.util.io.fs.FileSystemImpl
import org.linkedin.groovy.util.io.fs.FileSystem
import org.linkedin.groovy.util.state.StateMachine
import org.linkedin.groovy.util.concurrent.GroovyConcurrentUtils

/**
 * Test for AgentImpl
 *
 * @author ypujante@linkedin.com
 */
def class TestAgentImpl extends GroovyTestCase
{
  // we must use a static global because the glu script will be destroyed and recreated
  // by the agent.. so there is no real way to recover one except making it static global...
  public static ThreadControl GLOBAL_TC = new ThreadControl(Timespan.parse('5s'))

  def ram
  def fileSystem
  def logFileSystem
  def shell
  def ramStorage = [:]
  AgentImpl agent
  def clock = new SettableClock()

  protected void setUp()
  {
    super.setUp();

    ram = new RAMDirectory()
    RAMResourceProvider rp = new RAMResourceProvider(ram)
    fileSystem = [
            mkdirs: { dir ->
              ram.mkdirhier(dir.toString())
              return rp.createResource(dir.toString())
            },
            rmdirs: { dir ->
              ram.rm(dir.toString())
            },

            getRoot: { rp.createResource('/') },

            getTmpRoot: { rp.createResource('/tmp') },

            newFileSystem: { r,t -> fileSystem }
    ] as FileSystem

    // the agent is logging for each script... we don't want the output in the test
    // TODO MED YP: how do I do this with slf4j ?
    // Log.setFactory(new RAMLoggerFactory())

    logFileSystem = FileSystemImpl.createTempFileSystem()

    shell = new ShellImpl(fileSystem: fileSystem)

    agent = new AgentImpl(clock: clock)
    agent.boot(shellForScripts: shell,
               rootShell: new ShellImpl(fileSystem: logFileSystem,
                                        env: ['org.linkedin.app.name': 'glu-agent']),
               agentLogDir: logFileSystem.root,
               storage: createStorage() as Storage)
  }

  protected void tearDown()
  {
    try
    {
      logFileSystem.destroy()
      agent.shutdown()
      agent.waitForShutdown('5s')
    }
    finally
    {
      super.tearDown()
    }
  }

  private def createStorage()
  {
    return new RAMStorage(ramStorage)
  }

  /**
   * Basic test for the script manager
   */
  void testScriptManager()
  {
    assertEquals([MountPoint.ROOT], agent.getMountPoints())

    // we install a script under /s
    def scriptMountPoint = MountPoint.fromPath('/s')
    agent.installScript(mountPoint: scriptMountPoint,
                        initParameters: [p1: 'v1'],
                        scriptFactory: new FromClassNameScriptFactory(MyScriptTestAgentImpl))

    assertEquals([MountPoint.ROOT, scriptMountPoint], agent.getMountPoints())

    def res = [:]
    // then we run the 'install' action
    agent.executeAction(mountPoint: scriptMountPoint,
                        action: 'install',
                        actionArgs: [value: 1,
                         expectedMountPoint: scriptMountPoint,
                         expectedParentRootPath: MountPoint.ROOT,
                         res: res])

    // then we wait for the action to be completed
    assertTrue agent.waitForState(mountPoint: scriptMountPoint, state: 'installed')

    // make sure the install closure is actually called!
    assertEquals('1/v1', res.install)

    // we cannot uninstall the script because it is not in NONE state
    shouldFail(ScriptIllegalStateException) {
      agent.uninstallScript(mountPoint: scriptMountPoint)         
    }

    // test shortcut method: run uninstall and wait for the state to be NONE
    assertTrue agent.executeActionAndWaitForState(mountPoint: scriptMountPoint,
                                                  action: 'uninstall',
                                                  state: StateMachine.NONE)

    // script is already in NONE state
    assertTrue agent.waitForState(mountPoint: scriptMountPoint,
                                  state: StateMachine.NONE)

    // now uninstall should work
    agent.uninstallScript(mountPoint: scriptMountPoint)

    assertEquals([MountPoint.ROOT], agent.getMountPoints())
    
    // the script is not found => null
    assertEquals('/s', shouldFail(NoSuchMountPointException) {
                 agent.waitForState(mountPoint: scriptMountPoint,
                                    state: StateMachine.NONE)
                 })
  }

  /**
   * The agent behaves in an asynchronous fashion so this test will make sure that it is the
   * case.
   */
  void testAsynchronism()
  {
    def tc = new ThreadControl()

    // we install a script under /s
    def scriptMountPoint = MountPoint.fromPath('/s')
    agent.installScript(mountPoint: scriptMountPoint,
                        initParameters: [p1: 'v1'],
                        scriptFactory: new FromClassNameScriptFactory(MyScriptTestAgentImpl2))

    def res = [:]
    // then we run the 'install' action
    agent.executeAction(mountPoint: scriptMountPoint,
                        action: 'install',
                        actionArgs: [res: res, tc: tc])

    tc.waitForBlock('s1')

    assertFalse agent.waitForState(mountPoint: scriptMountPoint, state: 'installed', timeout: 200)

    tc.unblock('s1')

    assertTrue agent.waitForState(mountPoint: scriptMountPoint, state: 'installed')

    assertEquals('v1', res.install)
  }


  /**
   * Test that an action can be interrupted properly
   */
  void testInterrupt()
  {
    def tc = new ThreadControl()

    // we install a script under /s
    def scriptMountPoint = MountPoint.fromPath('/s')
    agent.installScript(mountPoint: scriptMountPoint,
                        scriptFactory: new FromClassNameScriptFactory(MyScriptTestInterrupt))

    def res = [:]
    // then we run the 'install' action
    agent.executeAction(mountPoint: scriptMountPoint,
                        action: 'install',
                        actionArgs: [res: res, tc: tc])

    tc.unblock('start')

    // we make sure that we are sleeping...
    assertFalse agent.waitForState(mountPoint: scriptMountPoint, state: 'installed', timeout: '500')

    // wrong action.. should return false
    assertFalse agent.interruptAction(mountPoint: scriptMountPoint,
                                      action: 'foobar')

    // wrong mountpoint.. should return false
    assertFalse agent.interruptAction(mountPoint: '/foobar',
                                      action: 'install')

    // interrupt
    assertTrue agent.interruptAction(mountPoint: scriptMountPoint,
                                     action: 'install')

    tc.unblock('exception') 

    shouldFail(ScriptExecutionException) {
      agent.waitForState(mountPoint: scriptMountPoint, state: 'installed', timeout: '1s')
    }

    assertNull res.notReached

    assertEquals('sleep interrupted', shouldFail(InterruptedException) {
      throw res.exception
    })
  }

  /**
   * we make sure that the state is being stored correctly
   */
  void testStateKeeperStore()
  {
    // should be root in the state...
    assertEquals(1, ramStorage.size())
    def rootValues = [
            scriptDefinition: new ScriptDefinition(MountPoint.ROOT,
                                                   null,
                                                   new FromClassNameScriptFactory(RootScript),
                                                   [:]),
            scriptState: [
                    script: [rootPath: MountPoint.ROOT],
                    stateMachine: [currentState: 'installed']
            ]
    ]

    // we check root
    checkStorage(MountPoint.ROOT, rootValues)

    // we install a script under /s
    def scriptMountPoint = MountPoint.fromPath('/s')
    agent.installScript(mountPoint: scriptMountPoint,
                        initParameters: [p1: 'v1'],
                        scriptFactory: new FromClassNameScriptFactory(MyScriptTestAgentImpl3))

    def scriptValues = [
            scriptDefinition: new ScriptDefinition(scriptMountPoint,
                                                   MountPoint.ROOT,
                                                   new FromClassNameScriptFactory(MyScriptTestAgentImpl3),
                                                   [p1: 'v1']),
            scriptState: [
                    script: [:],
                    stateMachine: [currentState: StateMachine.NONE],
            ]
    ]

    // we check root (to be sure) and /s
    checkStorage(MountPoint.ROOT, rootValues)
    checkStorage(scriptMountPoint, scriptValues)

    // we run the install action
    agent.executeAction(mountPoint: scriptMountPoint,
                        action: 'install',
                        actionArgs: [p: 'c'])
    assertTrue agent.waitForState(mountPoint: scriptMountPoint, state: 'installed')

    // we verify that the new state is stored
    scriptValues.scriptState.stateMachine.currentState = 'installed'
    scriptValues.scriptState.script.vp1 = 'v1c'
    checkStorage(scriptMountPoint, scriptValues)

    // we then force an exception to be raised
    Exception sex = new ScriptExecutionException("${MyScriptTestAgentImpl3.class.name} [/s]".toString(),
                                                 "configure", [exception: true],
                                                 new Exception('mine'))
    def actionId = agent.executeAction(mountPoint: scriptMountPoint,
                                       action: 'configure',
                                       actionArgs: [exception: true])
    assertEquals(sex.message,
                 shouldFail(ScriptExecutionException) {
                     agent.waitForAction(mountPoint: scriptMountPoint, actionId: actionId)
                 })

    assertEquals(sex.message,
                 shouldFail(ScriptExecutionException) {
                   agent.waitForState(mountPoint: scriptMountPoint, state: 'installed')
                 })

    // we verify that the new state is stored
    scriptValues.scriptState.stateMachine.error = sex
    checkStorage(scriptMountPoint, scriptValues)

    // we clear the error generated in the previous call
    agent.clearError(mountPoint: scriptMountPoint)

    // we verify that the error state is cleared
    scriptValues.scriptState.stateMachine.remove('error')
    checkStorage(scriptMountPoint, scriptValues)

    agent.executeAction(mountPoint: scriptMountPoint,
                        action: 'uninstall')
    assertTrue agent.waitForState(mountPoint: scriptMountPoint, state: StateMachine.NONE)

    // we verify that the new state is stored
    scriptValues.scriptState.stateMachine.currentState = StateMachine.NONE
    checkStorage(scriptMountPoint, scriptValues)

    // we then uninstall the script... only remaining should be root
    agent.uninstallScript(mountPoint: scriptMountPoint)

    assertEquals(1, ramStorage.size())
    checkStorage(MountPoint.ROOT, rootValues)

    // we reinstall the script and bring it up to installed state
    agent.installScript(mountPoint: scriptMountPoint,
                        initParameters: [p1: 'v1'],
                        scriptFactory: new FromClassNameScriptFactory(MyScriptTestAgentImpl3))
    agent.executeAction(mountPoint: scriptMountPoint,
                        action: 'install',
                        actionArgs: [p: 'c'])
    assertTrue agent.waitForState(mountPoint: scriptMountPoint, state: 'installed')
    scriptValues.scriptState.stateMachine.currentState = 'installed'
    checkStorage(scriptMountPoint, scriptValues)

    // we set a timer and we make sure it gets executed
    def timerMountPoint = MountPoint.fromPath('/t')
    agent.installScript(mountPoint: timerMountPoint,
                        initParameters: [p1: 'v1'],
                        scriptFactory: new FromClassNameScriptFactory(MyScriptTestTimer))
    actionId = agent.executeAction(mountPoint: timerMountPoint,
                                   action: 'install',
                                   actionArgs: [repeatFrequency: '1s'])
    assertEquals('v1', agent.waitForAction(mountPoint: timerMountPoint, actionId: actionId))

    def timerNode = agent.executeCall(mountPoint: timerMountPoint, call: 'getScriptNode')

    // we advance the clock by 1s: the timer should fire
    advanceClock('1s', timerNode)

    // should fire the timer
    GLOBAL_TC.unblock('timer1')

    assertEquals([currentState: 'installed'], agent.getState(mountPoint: timerMountPoint))

    // we advance the clock by 1s: the timer should fire
    advanceClock('1s', timerNode)

    // should fire the timer and force the state machine in a new state
    GLOBAL_TC.unblock('timer1', [currentState: 'stopped', error: null])

    // the timer is running in a separate thread.. so the condition will happen asynchronously
    GroovyConcurrentUtils.waitForCondition(clock, '5s', 10) {
      [currentState: 'stopped'] == agent.getState(mountPoint: timerMountPoint)
    }

    // now we shutdown the current agent and we recreate a new one
    agent.shutdown()
    agent.waitForShutdown(0)

    agent = new AgentImpl(clock: clock)
    agent.boot(shellForScripts: shell, storage: createStorage() as Storage)

    // we verify that the scripts have been restored properly
    assertEquals([currentState: 'installed'],
                 agent.getState(mountPoint: scriptMountPoint))

    checkStorage(MountPoint.ROOT, rootValues)
    checkStorage(scriptMountPoint, scriptValues)

    // this will test the value
    agent.executeAction(mountPoint: scriptMountPoint,
                        action: 'configure',
                        actionArgs: [value: 'v1c'])
    assertTrue agent.waitForState(mountPoint: scriptMountPoint, state: 'stopped')

    scriptValues.scriptState.stateMachine.currentState = 'stopped'
    checkStorage(scriptMountPoint, scriptValues)

    // we revert back to installed state
    agent.executeAction(mountPoint: scriptMountPoint,
                        action: 'unconfigure',
                        actionArgs: [value: 'v1c'])
    assertTrue agent.waitForState(mountPoint: scriptMountPoint, state: 'installed')

    scriptValues.scriptState.stateMachine.currentState = 'installed'
    checkStorage(scriptMountPoint, scriptValues)

    // we now force an exception
    agent.executeAction(mountPoint: scriptMountPoint,
                        action: 'configure',
                        actionArgs: [exception: true])
    assertEquals(sex.message,
                 shouldFail(ScriptExecutionException) {
      agent.waitForState(mountPoint: scriptMountPoint, state: 'stopped')
    })

    assertEquals([currentState: 'installed', error: sex],
                 agent.getState(mountPoint: scriptMountPoint))
    
    // now we shutdown the current agent and we recreate a new one
    agent.shutdown()
    agent.waitForShutdown(0)

    agent = new AgentImpl()
    agent.boot(shellForScripts: shell, storage: createStorage() as Storage)

    // we verify that the scripts have been restored properly
    assertEquals([currentState: 'installed', error: sex],
                 agent.getState(mountPoint: scriptMountPoint))

    // /s
    def exepectedFullState = [
        scriptDefinition:
        [
            mountPoint: scriptMountPoint,
            parent: MountPoint.ROOT,
            scriptFactory:
            [
                'class': 'org.linkedin.glu.agent.impl.script.FromClassNameScriptFactory',
                className: 'test.agent.impl.MyScriptTestAgentImpl3'
            ],
            initParameters: [p1: 'v1']
        ],
        scriptState:
        [
            script: [vp1: 'v1c'],
            stateMachine:
            [
                currentState: 'installed',
                error:sex
            ]
        ]

    ]
    assertEquals(exepectedFullState, agent.getFullState(mountPoint: scriptMountPoint))

    // /t
    exepectedFullState = [
        scriptDefinition:
        [
            mountPoint: timerMountPoint,
            parent: MountPoint.ROOT,
            scriptFactory:
            [
                'class': 'org.linkedin.glu.agent.impl.script.FromClassNameScriptFactory',
                className: 'test.agent.impl.MyScriptTestTimer'
            ],
            initParameters: [p1: 'v1']
        ],
        scriptState:
        [
            script: [:],
            stateMachine: [ currentState: 'stopped' ],
            timers: [[timer: 'timer1', repeatFrequency: '1s']]
        ]

    ]
    assertEquals(exepectedFullState, agent.getFullState(mountPoint: timerMountPoint))

    // we make sure that the timers get restored properly after shutdown
    timerNode = agent.executeCall(mountPoint: timerMountPoint, call: 'getScriptNode')

    // we advance the clock by 1s: the timer should fire
    advanceClock('1s', timerNode)

    // should fire the timer
    GLOBAL_TC.unblock('timer1', [currentState: 'installed', error: null])

    // the timer is running in a separate thread.. so the condition will happen asynchronously
    GroovyConcurrentUtils.waitForCondition(clock, '5s', 10) {
      [currentState: 'installed'] == agent.getState(mountPoint: timerMountPoint)
    }

    // we advance the clock by 1s: the timer should fire
    advanceClock('1s', timerNode)

    // should fire the timer (which will raise an exception)
    GLOBAL_TC.unblock('timer1', [currentState: 'invalid', error: null])

    // we advance the clock by 1s: the timer should fire
    advanceClock('1s', timerNode)

    // make sure that exception did not cause the timer to not fire anymore and did not change the
    // state
    GLOBAL_TC.waitForBlock('timer1')
    assertEquals([currentState: 'installed'], agent.getState(mountPoint: timerMountPoint))
    GLOBAL_TC.unblock('timer1', [currentState: 'running', error: null])

    // the timer is running in a separate thread.. so the condition will happen asynchronously
    GroovyConcurrentUtils.waitForCondition(clock, '5s', 10) {
      [currentState: 'running'] == agent.getState(mountPoint: timerMountPoint)
    }
  }

  // we advance the clock and wake up the execution thread to make sure it processes the event
  private void advanceClock(timeout, node)
  {
    // we wait first for the timeline to not be empty
    GroovyConcurrentUtils.waitForCondition(clock, '5s', 10) {
      node.scriptExecution.timeline
    }
    clock.addDuration(Timespan.parse(timeout.toString()))
    synchronized(node.scriptExecution.lock)
    {
      node.scriptExecution.lock.notifyAll()
    }
  }

  /**
   * Test for tailing the log
   */
  public void testTailAgentLog()
  {
    assertNull(agent.tailAgentLog([maxLine: 4]))

    def agentLog = logFileSystem.withOutputStream('glu-agent.out') { file, out ->
      (1..1000).each { lineNumber ->
        out.write("gal: ${lineNumber}\n".getBytes('UTF-8'))
      }
      return file
    }

    assertEquals("""gal: 997
gal: 998
gal: 999
gal: 1000
""", agent.tailAgentLog([maxLine: 4]).text)

    def gcLog = logFileSystem.withOutputStream('gc.log') { file, out ->
      (1..1000).each { lineNumber ->
        out.write("gc: ${lineNumber}\n".getBytes('UTF-8'))
      }
      return file
    }

    assertEquals("""gc: 999
gc: 1000
""", agent.tailAgentLog([log: gcLog.name, maxLine: 2]).text)
  }

  private void checkStorage(mountPoint, args)
  {
    def state = ramStorage[mountPoint]

    assertNotNull("state is null for ${mountPoint}", state)

    args.each { k,v ->
      assertEquals("testing ${k}", args[k], state[k])
    }

    // we make sure it is serializable
    new ObjectOutputStream(new ByteArrayOutputStream()).writeObject(ramStorage) 
  }
}

private def class MyScriptTestAgentImpl
{
  // YP Note: contrary to the test in TestScriptManager, the result is not returned (due to
  // asynchronism), so we put the return value in args.res thus insuring that the closure
  // is actually called...
  def install = { args ->
    GroovyTestCase.assertEquals(args.expectedMountPoint, mountPoint)
    GroovyTestCase.assertEquals(args.expectedParentRootPath, parent.rootPath)
    shell.mkdirs(mountPoint)
    args.res.install = "${args.value}/${params.p1}".toString()
  }
}

private def class MyScriptTestAgentImpl2
{
  def install = { args ->
    args.tc.block('s1')
    args.res.install = params.p1
  }
}

private def class MyScriptTestAgentImpl3
{
  def vp1

  def install = { args ->
    vp1 = params.p1 + args.p
  }

  def configure = { args ->
    if(args.exception)
      throw new Exception('mine')
    assert vp1 == args.value
    return vp1
  }

  def unconfigure = { args ->
    assert vp1 == args.value
    return vp1
  }
}

private def class MyScriptTestInterrupt
{
  def install = { args ->

    args.tc.block('start')

    try
    {
      shell.waitFor() { duration ->
        return false
      }
    }
    catch (InterruptedException e)
    {
      args.tc.block('exception')
      args.res.exception = e
      throw e
    }

    // should never reach this line as an exception should be thrown!
    args.res.notReached = true
  }
}

private def class MyScriptTestTimer
{
  def timer1 = {
    def args = TestAgentImpl.GLOBAL_TC.block('timer1')

    if(args)
    {
      stateManager.forceChangeState(args.currentState, args.error)
    }
  }

  def install = { args ->
    timers.schedule(timer: timer1, repeatFrequency: args.repeatFrequency)
    return params.p1
  }

  def uninstall = {
    timers.cancel(timer: timer1)
  }

  // this is a way to get the underlying script node
  def getScriptNode = {
    self
  }
}

