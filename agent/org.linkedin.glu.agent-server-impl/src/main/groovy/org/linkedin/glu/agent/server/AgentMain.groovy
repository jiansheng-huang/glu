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

package org.linkedin.glu.agent.server

import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.ZooDefs.Ids
import org.hyperic.sigar.Sigar
import org.hyperic.sigar.SigarException
import org.linkedin.glu.agent.api.Agent
import org.linkedin.glu.agent.api.Shell
import org.linkedin.glu.agent.impl.AgentImpl
import org.linkedin.glu.agent.impl.capabilities.ShellImpl
import org.linkedin.glu.agent.impl.storage.DualWriteStorage
import org.linkedin.glu.agent.impl.storage.FileSystemStorage
import org.linkedin.glu.agent.impl.storage.Storage
import org.linkedin.glu.agent.impl.zookeeper.ZooKeeperStorage
import org.linkedin.glu.agent.rest.resources.AgentResource
import org.linkedin.glu.agent.rest.resources.FileResource
import org.linkedin.glu.agent.rest.resources.HostResource
import org.linkedin.glu.agent.rest.resources.LogResource
import org.linkedin.glu.agent.rest.resources.MountPointResource
import org.linkedin.glu.agent.rest.resources.ProcessResource
import org.linkedin.groovy.util.ant.AntUtils
import org.linkedin.groovy.util.io.GroovyIOUtils
import org.linkedin.groovy.util.io.fs.FileSystemImpl
import org.linkedin.groovy.util.ivy.IvyURLHandler
import org.linkedin.groovy.util.json.JsonUtils
import org.linkedin.groovy.util.net.GroovyNetUtils
import org.linkedin.groovy.util.net.SingletonURLStreamHandlerFactory
import org.linkedin.util.clock.Timespan
import org.linkedin.util.codec.Base64Codec
import org.linkedin.util.codec.Codec
import org.linkedin.util.codec.CodecUtils
import org.linkedin.util.codec.OneWayCodec
import org.linkedin.util.codec.OneWayMessageDigestCodec
import org.linkedin.util.lifecycle.Shutdown
import org.linkedin.util.lifecycle.ShutdownProxy
import org.linkedin.util.reflect.ObjectProxyBuilder
import org.linkedin.zookeeper.client.IZKClient
import org.linkedin.zookeeper.client.LifecycleListener
import org.linkedin.zookeeper.client.ZooKeeperURLHandler
import org.restlet.util.Series
import org.restlet.routing.Router
import org.restlet.ext.simple.HttpsServerHelper
import org.restlet.ext.simple.HttpServerHelper
import org.linkedin.groovy.util.config.Config
import org.restlet.Component
import org.restlet.data.Protocol
import org.restlet.routing.Template
import org.linkedin.groovy.util.log.JulToSLF4jBridge

/**
 * This is the main class to start the agent.
 *
 * @author ypujante@linkedin.com
 */
class AgentMain implements LifecycleListener
{
  public static final String MODULE = AgentMain.class.getName();
  public static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MODULE);

  private static final Codec TWO_WAY_CODEC
  private static final OneWayCodec ONE_WAY_CODEC
  private static final OneWayCodec ONE_WAY_CODEC_2

  public static final Timespan PROCESS_TRACKER_HEARTBEAT = Timespan.parse('10s')

  static {
    String p0 = "gluos2way"
    TWO_WAY_CODEC = new Base64Codec(p0)
    String p1 = "gluos1way1"
    ONE_WAY_CODEC =
      OneWayMessageDigestCodec.createSHA1Instance(p1, TWO_WAY_CODEC)
    String p2 = "gluos1way2"
    ONE_WAY_CODEC_2 =
      OneWayMessageDigestCodec.createSHA1Instance(p1, new Base64Codec(p2))
  }

  protected IZKClient _zkClient
  protected def _urlFactory
  protected String _fabric
  protected Sigar _sigar
  protected long _pid

  protected def _config
  protected  def _agentProperties = [:]

  protected File _agentTempDir
  protected String _agentName
  protected String _zooKeeperRoot
  protected Shutdown _shutdown
  protected Agent _proxiedAgent
  protected AgentImpl _agent
  protected def _restServer
  protected DualWriteStorage _dwStorage = null

  protected final Object _lock = new Object()
  protected volatile boolean _receivedShutdown = false

  AgentMain()
  {
    JulToSLF4jBridge.installBridge()
  }

  void init(args)
  {
    // register url factory
    _urlFactory = new SingletonURLStreamHandlerFactory()
    URL.setURLStreamHandlerFactory(_urlFactory)

    // read all the properties provided as urls on the command line
    def properties = new Properties()

    args.each {
      readConfig(it, properties)
    }

    def config = properties

    // make sure all directories have their canonical representation
    config.keySet().findAll { it.endsWith('Dir') }.each { toCanonicalPath(config, it) }

    // determine the host name of the agent
    def hostname = InetAddress.getLocalHost().canonicalHostName
    _agentName = Config.getOptionalString(config,
                                          "${prefix}.agent.name",
                                          hostname)

    properties["${prefix}.agent.name".toString()] = _agentName
    properties["${prefix}.agent.hostname".toString()] = hostname
    properties["${prefix}.agent.version".toString()] = config['org.linkedin.app.version']

    log.info "Agent ZooKeeper name: ${_agentName}"

    _zooKeeperRoot = Config.getRequiredString(config, "${prefix}.agent.zookeeper.root")

    // create zookeeper client and registers a url handler with it
    _zkClient = createZooKeeperClient(config)

    if(_zkClient)
    {
      // add support for zookeeper:/a/b links
      _urlFactory.registerHandler('zookeeper') {
        return new ZooKeeperURLHandler(_zkClient)
      }
    }

    _fabric = computeFabric(config)

    // makes the fabric available
    properties["${prefix}.agent.fabric".toString()] = _fabric

    log.info "Agent fabric: ${_fabric}"

    // read the config provided in configURL (most likely in zookeeper, this is why we
    // need to register the handler beforehand)
    readConfig(Config.getOptionalString(config, "${prefix}.agent.configURL", null), properties)

    _agentProperties.putAll(properties.groupBy { k,v ->
      k.toLowerCase().contains('password') ? 'passwordKeys' : 'nonPasswordKeys'
    }.nonPasswordKeys)

    _agentProperties.remove('line.separator')

    _sigar = createSigar()

    _pid = getAgentPid()
    if(_pid)
    {
      _agentProperties["${prefix}.agent.pid".toString()] = _pid
    }

    log.info("Starting the agent with config: ${_agentProperties}")

    _config = properties
  }

  String getPrefix()
  {
    return 'glu'
  }

  /**
   * Make sure that the paths are canonical (no .. or links)
   * @param config
   * @param name
   */
  protected void toCanonicalPath(config, name)
  {
    def path = Config.getOptionalString(config, name, null)
    if(path)
    {
      path = new File(path).canonicalPath
      config[name] = path
    }
  }

  /**
   * Computes the fabric
   */
  protected String computeFabric(def config)
  {
    def fabric = Config.getOptionalString(config, "${prefix}.agent.fabric", null)

    if(!fabric)
    {
      def fabricFile = Config.getOptionalString(config, 'fabricFile', null)
      if(fabricFile)
        fabricFile = new File(fabricFile)

      def mgr = new FabricManager(_zkClient,
                                  computeZKAgentFabricPath(),
                                  fabricFile)

      fabric = mgr.getFabric()
    }

    if(!fabric)
      throw new IllegalStateException("cannot determine the fabric for the agent")

    return fabric
  }

  protected String computeZKAgentFabricPath()
  {
    return "${_zooKeeperRoot}/agents/names/${_agentName}"
  }

  protected def getRemoteConfigCodec()
  {
    return ONE_WAY_CODEC_2
  }

  protected def getTwoWayCodec()
  {
    TWO_WAY_CODEC
  }

  protected def getOneWayCodec()
  {
    ONE_WAY_CODEC
  }

  protected IZKClient createZooKeeperClient(def config)
  {
    def factory = new IZKClientFactory(config: config, codec: remoteConfigCodec, prefix: prefix)
    IZKClient zkClient = factory.create()

    if(zkClient)
    {
      _agentProperties[IZKClientFactory.ZK_CONNECT_STRING] = factory.zkConnectString
      zkClient.start()
      zkClient.waitForStart(null)
    }
    
    return zkClient
  }

  def startAndWait()
  {
    start()
    awaitTermination()
  }

  def start()
  {
    _shutdown = new Shutdown()
    _agent = new AgentImpl()
    _agentTempDir = GroovyIOUtils.toFile(Config.getRequiredString(_config, "${prefix}.agent.tempDir"))

    def rootShell = createRootShell()

    def agentArgs =
    [
      rootShell: rootShell,
      shellForScripts: createShell(rootShell, "${prefix}.agent.scriptRootDir"),
      agentLogDir: rootShell.toResource(Config.getRequiredString(_config, "${prefix}.agent.logDir")),
      storage: createStorage(),
      sigar: _sigar,
      sync: _zkSync
    ]

    _agent.boot(agentArgs)

    _proxiedAgent = ObjectProxyBuilder.createProxy(new ShutdownProxy(_agent, _shutdown),
                                                   Agent.class)

    startRestServer()

    registerTerminationHandler()

    log.info 'Agent started.'
  }

  def registerTerminationHandler()
  {
    addShutdownHook {
      log.info 'Shutting down...'

      synchronized(_lock) {
        _receivedShutdown = true
        _lock.notify()
      }

      // first we make sure that no calls can come in and that all pending calls have
      // gone through
      _shutdown.shutdown()
      _shutdown.waitForShutdown()

      if(_restServer)
      {
        log.info 'Stopping REST service...'
        _restServer.stop()
        log.info 'REST service stopped.'
      }

      if(_agent)
      {
        log.info 'Shutting down the agent...'
        _agent.shutdown()
        _agent.waitForShutdown()
        log.info 'Agent shut down...'
      }

      if(_zkClient)
      {
        log.info 'Stopping ZooKeeper client...'
        _zkClient.destroy()
        _zkClient = null
        log.info 'ZooKeeper client stopped.'
      }


      log.info 'Shutdown sequence complete.'
    }
  }

  def startRestServer()
  {
    def port = Config.getOptionalInt(_config, "${prefix}.agent.port", 12906)

    _restServer = new Component();
    def context = _restServer.getContext().createChildContext()
    def router = new Router(context)
    def attributes = context.getAttributes()

    attributes.put('agent', _proxiedAgent)

    [
      agent: [clazz: AgentResource, matchingMode: Template.MODE_STARTS_WITH],
      host: [clazz: HostResource],
      process: [clazz: ProcessResource, matchingMode: Template.MODE_STARTS_WITH],
      mountPoint: [clazz: MountPointResource, matchingMode: Template.MODE_STARTS_WITH],
      log: [clazz: LogResource, matchingMode: Template.MODE_STARTS_WITH],
      file: [clazz: FileResource, matchingMode: Template.MODE_STARTS_WITH]
    ].each { name, map ->
      def path = "/${name}".toString()
      Class clazz = map.clazz
      def route = router.attach(path, clazz)
      if(map.matchingMode)
        route.matchingMode = map.matchingMode
      attributes.put(clazz.name, path)
    }
    
    _restServer.getDefaultHost().attach(router);

    def secure = ''

    if(Config.getOptionalBoolean(_config, "${prefix}.agent.sslEnabled", true))
    {
      def serverContext = context.createChildContext()

      Series params = serverContext.getParameters();
      // keystore
      def keystore = fetchFile(Config.getRequiredString(_config, "${prefix}.agent.keystorePath"),
                               Config.getRequiredString(_config, "${prefix}.agent.keystoreChecksum"))
      params.add('keystorePath', keystore.path)
      params.add('keystorePassword', getPassword(_config, "${prefix}.agent.keystorePassword"))
      params.add('keyPassword', getPassword(_config, "${prefix}.agent.keyPassword"))

      // truststore
      def trustore = fetchFile(Config.getRequiredString(_config, "${prefix}.agent.truststorePath"),
                               Config.getRequiredString(_config, "${prefix}.agent.truststoreChecksum"))
      params.add('truststorePath', trustore.path)
      params.add('truststorePassword', getPassword(_config, "${prefix}.agent.truststorePassword"))

      params.add('sslContextFactory', 'org.restlet.engine.security.DefaultSslContextFactory')

      params.add('needClientAuthentication', 'true')

      params.add('defaultThreads',
                 Config.getOptionalString(_config, "${prefix}.agent.rest.server.defaultThreads", '3'))
      
      def server = _restServer.getServers().add(Protocol.HTTPS, port);
      server.setContext(serverContext)
      new HttpsServerHelper(server)

      secure = '(secure)'
    }
    else
    {
      def server = _restServer.getServers().add(Protocol.HTTP, port);
      new HttpServerHelper(server)
    }

    _restServer.start()
    log.info "Started REST service on ${secure} port: ${port}"
  }

  def awaitTermination()
  {
    synchronized (_lock) {
      while(!_receivedShutdown)
      {
        _lock.wait()
      }
    }
  }

  protected Shell createShell(ShellImpl rootShell, String root)
  {
    def fs =
      rootShell.fileSystem.newFileSystem(GroovyIOUtils.toFile(Config.getRequiredString(_config,
                                                                                       root)))
    return rootShell.newShell(fs)
  }

  protected ShellImpl createRootShell()
  {
    // registering ivy url handler
    def ivySettings =
      GroovyNetUtils.toURI(Config.getOptionalString(_config, "${prefix}.agent.ivySettings", null))
    if(ivySettings)
    {
      _urlFactory.registerHandler('ivy') {
        return new IvyURLHandler(ivySettings)
      }
    }

    def fileSystem = new FileSystemImpl(new File('/'), _agentTempDir)
    return new ShellImpl(fileSystem: fileSystem,
                         env: Collections.unmodifiableMap(_agentProperties))
  }

  protected Storage createStorage()
  {
    def fileSystem =
      new FileSystemImpl(GroovyIOUtils.toFile(Config.getRequiredString(_config,
                                                                       "${prefix}.agent.scriptStateDir")),
                         _agentTempDir)
    
    Storage storage = new FileSystemStorage(fileSystem)

    Storage zkStorage = createZooKeeperStorage()

    if(zkStorage)
    {
      _dwStorage = new DualWriteStorage(storage, zkStorage)
      storage = _dwStorage

      _zkClient.registerListener(this)
    }

    return storage
  }


  /**
   * Create Sigar.
   */
  protected Sigar createSigar()
  {
    try
    {
      Sigar.load()
      return new Sigar()
    }
    catch (Throwable th)
    {
      log.warn("Cannot load the Sigar library... ignoring", th)
      return null
    }
  }

  protected long getAgentPid()
  {
    if(_sigar)
    {
      try
      {
        return _sigar.pid
      }
      catch(SigarException e)
      {
        log.warn("Could not determine the pid for this agent [ignored]", e)
      }
    }

    return 0
  }

  public void onConnected()
  {
    _zkSync()
  }

  private _zkSync = {
    if(_zkClient)
    {
      synchronized(_zkClient)
      {
        // register an (ephemeral) entry in zookeeper: when the agent dies, it will automatically
        // be removed
        def enode = computeAgentEphemeralPath()
        log.info("Registering zk ephemeral node ${enode}")
        if(!_zkClient.exists(enode))
        {
          // we filter out the properties
          def props = _agentProperties.findAll { k, v ->
            k.startsWith(prefix) || k.startsWith('java.vm')
          }
          _zkClient.createWithParents(enode,
                                      JsonUtils.toJSON(props).toString(),
                                      Ids.OPEN_ACL_UNSAFE,
                                      CreateMode.EPHEMERAL)
        }

        log.info("Syncing filesystem <=> ZooKeeper")
        _dwStorage.sync()
      }
    }
  }

  protected String computeAgentEphemeralPath()
  {
    return "${_zooKeeperRoot}/agents/fabrics/${_fabric}/instances/${_agentName}"
  }

  public void onDisconnected()
  {
    if(_zkClient)
      log.warn("Detected ZooKeeper failure.")
  }

  protected ZooKeeperStorage createZooKeeperStorage()
  {
    if(_zkClient)
    {
      return new ZooKeeperStorage(_zkClient.chroot(computeZooKeeperStoragePath()))
    }

    return null
  }

  protected String computeZooKeeperStoragePath()
  {
    return "${_zooKeeperRoot}/agents/fabrics/${_fabric}/state/${_agentName}"
  }

  String getZookeeperRoot()
  {
    return _zooKeeperRoot
  }

  String getFabric()
  {
    return _fabric
  }

  String getAgentName()
  {
    return _agentName
  }

  static void main(args)
  {
    AgentMain agentMain = new AgentMain()
    agentMain.init(args)
    agentMain.startAndWait()
  }

  protected def readConfig(url, Properties properties)
  {
    if(url)
    {
      // using ant to read the properties which will automatically do ${} replacement
      def project = AntUtils.withBuilder { ant ->
        properties.each { k,v ->
          ant.property(name:k, value: v)
        }
        ant.loadproperties() {
          ant.url(url: url)
        }
      }.project

      project.properties.keySet().each { name ->
        properties[name] = project.replaceProperties(project.getProperty(name))
      }
    }
  }

  protected String getPassword(def config, String name)
  {
    def password = Config.getRequiredString(config, name)

    // to encrypt the password use the password.sh cli (provided with agent-cli)
    if(Config.getOptionalBoolean(config, "${name}Encrypted", true))
    {
      password = CodecUtils.decodeString(twoWayCodec, password)
    }

    return password
  }

  protected File fetchFile(fileLocation, checksum)
  {
    File file = GroovyIOUtils.toFile(fileLocation)
    def computedChecksum = oneWayCodec.encode(file.readBytes())
    if(computedChecksum != checksum)
      throw new IllegalArgumentException("wrong checksum for ${fileLocation}")
    return file
  }
}
