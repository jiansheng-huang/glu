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

package org.linkedin.glu.provisioner.impl.agent

/**
 * Constants for the action descriptor factory and the touchpoint
 */
public interface AgentConstants
{
  
  public static final String ID = "agent"

  public static final String INSTALLATION_NAME = 'installationName'
  public static final String MOUNT_POINT = 'mountPoint'
  public static final String SCRIPT_LOCATION = 'scriptLocation'
  public static final String PARENT = 'parent'
  public static final String AGENT_NAME = 'agentName'
  public static final String AGENT_URI = 'agentUri'

  public static final String ENCRYPTION_KEYS = 'encryptionKeys'
}