#!/bin/bash

#
# Copyright 2010-2010 LinkedIn, Inc
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License. You may obtain a copy of
# the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations under
# the License.
#

if [ -z "$GLU_CONFIG_PREFIX" ]; then
  GLU_CONFIG_PREFIX="glu"
fi

# handle GLU_ZOOKEEPER
if [ -z "$GLU_ZOOKEEPER" ]; then
  D_GLU_ZOOKEEPER=""
else
  D_GLU_ZOOKEEPER="-D$GLU_CONFIG_PREFIX.agent.zkConnectString=$GLU_ZOOKEEPER"
fi

# handle GLU_AGENT_NAME
if [ -z "$GLU_AGENT_NAME" ]; then
  D_GLU_AGENT_NAME=""
else
  D_GLU_AGENT_NAME="-D$GLU_CONFIG_PREFIX.agent.name=$GLU_AGENT_NAME"
fi

# handle GLU_AGENT_FABRIC
if [ -z "$GLU_AGENT_FABRIC" ]; then
  D_GLU_AGENT_FABRIC=""
else
  D_GLU_AGENT_FABRIC="-D$GLU_CONFIG_PREFIX.agent.fabric=$GLU_AGENT_FABRIC"
fi

if [ -z "$GLU_AGENT_APPS" ]; then
  GLU_AGENT_APPS=$GLU_AGENT_HOME/../apps
fi

# make sure that the apps folder exist
mkdir -p $GLU_AGENT_APPS
GLU_AGENT_APPS=`cd $GLU_AGENT_APPS; pwd`

if [ -z "$GLU_AGENT_ZOOKEEPER_ROOT" ]; then
  GLU_AGENT_ZOOKEEPER_ROOT=/org/glu
fi

# Application name (for the container/cmdline app) - build-time expansion
if [ -z "$APP_NAME" ]; then
  APP_NAME="@agent.name@"
fi

# Application version (for the container/cmdline app) - build-time expansion
if [ -z "$APP_VERSION" ]; then
  APP_VERSION="@agent.version@"
fi

# Java Classpath (leave empty - set by the ctl script)
if [ -z "$JVM_CLASSPATH" ]; then
  JVM_CLASSPATH=
fi

# Min, max, total JVM size (-Xms -Xmx)
if [ -z "$JVM_SIZE" ]; then
  JVM_SIZE=-Xmx256m
fi

# New Generation Sizes (-XX:NewSize -XX:MaxNewSize)
if [ -z "$JVM_SIZE_NEW" ]; then
  JVM_SIZE_NEW=
fi

# Perm Generation Sizes (-XX:PermSize -XX:MaxPermSize)
if [ -z "$JVM_SIZE_PERM" ]; then
  JVM_SIZE_PERM=
fi

# Type of Garbage Collector to use
if [ -z "$JVM_GC_TYPE" ]; then
  JVM_GC_TYPE=
fi

# Tuning options for the above garbage collector
if [ -z "$JVM_GC_OPTS" ]; then
  JVM_GC_OPTS=
fi

# JVM GC activity logging settings ($LOG_DIR set in the ctl script)
if [ -z "$JVM_GC_LOG" ]; then
  JVM_GC_LOG="-XX:+PrintGCDetails -XX:+PrintGCTimeStamps -XX:+PrintTenuringDistribution -Xloggc:$GC_LOG"
fi

# Log4J configuration
if [ -z "$JVM_LOG4J" ]; then
  JVM_LOG4J="-Dlog4j.configuration=file:$CONF_DIR/log4j.xml"
fi

# Java I/O tmp dir
if [ -z "$JVM_TMP_DIR" ]; then
  JVM_TMP_DIR="-Djava.io.tmpdir=$TMP_DIR"
fi

# Any additional JVM arguments
if [ -z "$JVM_XTRA_ARGS" ]; then
  JVM_XTRA_ARGS="-Dsun.security.pkcs11.enable-solaris=false $D_GLU_ZOOKEEPER $D_GLU_AGENT_NAME $D_GLU_AGENT_FABRIC -D$GLU_CONFIG_PREFIX.agent.homeDir=$GLU_AGENT_HOME -D$GLU_CONFIG_PREFIX.agent.apps=$GLU_AGENT_APPS -D$GLU_CONFIG_PREFIX.agent.zookeeper.root=$GLU_AGENT_ZOOKEEPER_ROOT"
fi

# Debug arguments to pass to the JVM (when starting with '-d' flag)
if [ -z "$JVM_DEBUG" ]; then
  JVM_DEBUG="-Xdebug -Xnoagent -Djava.compiler=NONE -Xrunjdwp:transport=dt_socket,address=8887,server=y,suspend=n"
fi

# Application Info - name, version, instance, etc.
if [ -z "$JVM_APP_INFO" ]; then
  JVM_APP_INFO="-Dorg.linkedin.app.name=$APP_NAME -Dorg.linkedin.app.version=$APP_VERSION"
fi

# Main Java Class to run
if [ -z "$MAIN_CLASS" ]; then
  MAIN_CLASS=org.linkedin.glu.agent.server.AgentMain
fi

# Main Java Class arguments
if [ -z "$MAIN_CLASS_ARGS" ]; then
  MAIN_CLASS_ARGS="file:$CONF_DIR/agentConfig.properties"
fi

# set JAVA_HOME (JDK6) & JAVA_CMD
if [ -z "$JAVA_HOME" ]; then
  echo "Please set JAVA_HOME manually."
  exit 1
fi

if [ -z "$JAVA_CMD" ]; then
  JAVA_CMD=$JAVA_HOME/bin/java
fi


