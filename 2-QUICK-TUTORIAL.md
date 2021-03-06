Quick Tutorial
==============
This is a quick tutorial and assumes you have followed steps 1,2 and 5 with the exact same parameters described in [1-QUICK-DEV-SETUP.md](https://github.com/linkedin/glu/blob/master/1-QUICK-DEV-SETUP.md)

Note: for the sake of this tutorial and development ease, the agent(s) and the console are all running on the same host. This is not the production case where there is 1 agent per host with a central console to command them.

1. Login to the console
-----------------------
Point your browser to `http://localhost:8080/console`

and login

    username: glua
    password: password

You will be prompted to select your fabric. Pick `glu-dev-1`.

The console (in dev mode) has 3 users:

* `glua` (administrator priviledge)
* `glur` (release priviledge)
* `glu`  (user priviledge)

Pick `glua` because as an admin you can do everything.

2. View the agent
-----------------
Click on the `'Dashboard'` tab

Click on `agent:1` (in the `'Group By'` section) (don't click on the checkbox, but on `'agent:1'`)

You should see a row in the table where the status says 

    'nothing deployed'

Click on `'agent-1'` (in the first column of the table) which brings you to the agent view.

Click on `'View Details'` which show/hide the details about the agent: this is information coming straight from agent-1 which was registered in ZooKeeper when the agent started.

You should see the properties `glu.agent.port` (13906) and `glu.agent.pid` representing the pid of the agent.

3. View log files
-----------------
Click on `'main'` (next to Logs:) which shows the last 500 lines of the main log file of the agent (if you scroll to the bottom you should see the same message that the tail command (started in 2. is showing)).

Note how the agent logs a message that you are looking at its log file!

Go back to the agent view page and click `'more...'` (next to `Logs:`). This will show you the content of the logs folder and you can navigate to look at any file you want!

All those operations are executed on the agent(s) and the console merely displays the result (as can be seen in the log file of the agent).

4. View processes (ps)
----------------------
Go back to the agent view page and click `'All Processes'`. This essentialy runs the `'ps'` command on the agent and returns the result. In the `org.linkedin.app.name` column you should be able to identify the 2 agents that are running. By clicking on the pid you can view details about the process as well as sending a signal to the process!

All those operations are executed on the agent(s) and the console merely displays the result (as can be seen in the log file of the agent).

5. Loading the model
--------------------
Click on the `'Model'` tab

Load a json model (using Upload) that was generated for you when you started the application (check the output of the startup command) which will be (if you have not modified your settings):

    glu/out/build/console/org.linkedin.glu.console-webapp/systems/hello-world-system.json

6. 'Fixing' the issues
----------------------
After loading the model you should be back on the Dashboard view with 4 red rows in the table. The status of all those rows read: `'NOT deployed'`. 

What has just happened ? 

We have just loaded a model which represents a system where 4 'entries' need to be running on agent-1. Since nothing is running, there is a delta (represented by the red rows) that the console tells you to fix. 'Fixing' it means deploying the 4 'entries'.

From there, there are several ways to do it (partially or all at once). Let's do it all for now.

Click on the `'System'` tab.

Click on the `'Current'` subtab. You should see a drop down below `"Deploy: Fabric [glu-dev-1]"` which says `'Choose Plan'`. Select the one that has `SEQUENTIAL` in the name. It should immediately shows you the list of actions (and their ordering) that are going to be accomplished to 'fix' the delta.

Click `'Select this plan'`.

The next page allows you to _customize_ the plan. Simply click `'Execute'` and confirm the action.

The next page will show you the plan again and will change as the plan gets executed. Since you selected `SEQUENTIAL` all the actions will take place one after another. The plan should conclude successfully.

At this stage you can check the log file of the agent and see all the activity.

Go back to the `Dashboard` and everything should be green.

Note: the terminology 'entry' may sound a little vague right now, but it is associated to a unique mountPoint (or unique key) like `/m1/i001` on an agent with a script (called glu script) which represents the set of instructions necessary to start an application. In the course of this tutorial we use the [`HelloWorldScript`](https://github.com/linkedin/glu/blob/master/scripts/glu.script-hello-world/src/main/groovy/HelloWorldScript.groovy) which displays the name of the 'phase' and the message. So we do not really start anything, but the scripts can do whatever you want them to do.

7. Viewing entry details
------------------------------
Click on `'agent-1'` on any of the 4 rows to go back to the agent page (same step as 2.).

The page shows you now the 4 entries that were installed.

Under `/m1/i001` click the `'View Details'` link to show/hide details about the entry.

You should see the message (`Hello World`) and the location of the `HelloWorldScript`

8. Changing the system
----------------------
Now click the `'System'` tab again.

You should see a table with 2 entries which shows you all the systems that were loaded. The one at the bottom is the empty one created at bootstrap. The one at the top is the one you loaded in step 5.

Click on the first id. You should now see the json document that you loaded in step 5. We are going to edit it in place.

The format is a json document which contains essentially an array of entries representing each entry in the system (as explained in section 6.).

Change the message in the very first entry to `"Hello World 2"` and click `"Save Changes"`

Go back to the `Dashboard`.

Note that the first row is now red and the status says `'version MISMATCH'`. If you click on the status it will say:

    initParameters.message:Hello World 2 != initParameters.message:Hello World

Similarly to 6., there is a delta: the system in the console is not matching with what is currently deployed. Hence it is red.

Click on `'/m1/i001'` and you land on a filtered view containing only the mountPoint you clicked on.

Similarly to step 6., choose a plan under `'Deploy: mountPoint [/m1/i001]'`. Note that since there is only 1 entry, choosing `SEQUENTIAL` or `PARALLEL` will have the same effect.

Select the plan and execute it: it firsts uninstall the first one entirely and reinstall and restart the new one.

When the plan finishes executing, click on `/m1/i001` which is a shortcut to the agent view page.

If you click on `'View Details'` you should see the new message.

Now the system (also known as desired state) and the current state match. There is no delta anymore so the console is happy: everything is green.

9. Viewing the audit log
------------------------
Click the `'Admin'` tab and then select `'View Audit Logs'`.

You should be able to see all the actions that you have done in the system (usually all actions involving talking to the agent are logged).

10. The end
-----------
That is it for this quick tutorial. You can now check the [documentation](https://github.com/linkedin/glu/wiki).