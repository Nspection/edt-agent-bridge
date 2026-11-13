// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.mcp.host;
import static org.junit.Assert.*;
import org.junit.Test;
public class McpOperationAuthorizationTest {
 @Test public void liveLeafDenyAndGlobalDenyAreBothEnforced(){
  var config=McpHostConfig.defaults();config.setMutationPolicy(McpHostConfig.MutationPolicy.ALLOW);config.setExposedToolsFilter("*,-edt_check");
  assertThrows(IllegalStateException.class,()->McpOperationAuthorization.requireAllowed("edt_check",config));
  McpOperationAuthorization.requireAllowed("edt_task_step",config);
  config.setExposedToolsFilter("*");McpOperationAuthorization.requireAllowed("edt_check",config);
  for(var decision:new McpHostConfig.MutationPolicy[]{McpHostConfig.MutationPolicy.DENY,McpHostConfig.MutationPolicy.ASK}){config.setMutationPolicy(decision);assertThrows(IllegalStateException.class,()->McpOperationAuthorization.requireAllowed("edt_check",config));}
 }
}
