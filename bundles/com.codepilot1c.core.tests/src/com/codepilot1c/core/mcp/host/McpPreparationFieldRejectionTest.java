// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.mcp.host;
import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.mcp.model.*;
import com.codepilot1c.core.mcp.host.session.McpHostSession;
public class McpPreparationFieldRejectionTest {
 private void rejected(boolean compact)throws Exception{
  var registry=ToolRegistry.createDetached();registry.register(new com.codepilot1c.core.tools.development.EdtRuntimePrepareTool());
  try(var lease=ToolRegistry.installScopedForTesting(registry)){
   var exposure=new McpToolExposurePolicy(){public boolean isExposed(String name){return true;}public boolean requiresConfirmation(String name,Map<String,Object> args){return false;}public boolean isDestructive(String name){return false;}};
   var prompts=new com.codepilot1c.core.mcp.host.prompt.IMcpPromptProvider(){public List<McpPrompt> listPrompts(){return List.of();}public Optional<McpPromptResult> getPrompt(String name,Map<String,Object> args){return Optional.empty();}};
   var router=new McpHostRequestRouter(exposure,List.of(),prompts,McpHostConfig.MutationPolicy.ALLOW,"");var session=new McpHostSession("prepare-contract-test");session.setClientName("test");
   var request=new McpMessage();request.setMethod("tools/call");request.setId("invalid-field");var payload=Map.<String,Object>of("action","prepare","timeout_ms",1000);request.setParams(Map.of("name",compact?"edt.run":"edt_runtime_prepare","arguments",compact?Map.of("op","prepare","args",payload):payload));
   var response=router.route(request,session);var result=new com.google.gson.Gson().toJsonTree(response.getResult()).getAsJsonObject();assertTrue(result.get("isError").getAsBoolean());assertEquals("timeout_ms",result.getAsJsonObject("structuredContent").get("rejected_field").getAsString());assertTrue(result.getAsJsonArray("content").toString().contains("wait-only"));
  }
 }
 @Test public void canonicalEntryRejectsBeforeWorkspaceDependencyInspection()throws Exception{rejected(false);}
 @Test public void compactEntryPreservesTheSameStructuredFieldError()throws Exception{rejected(true);}
}
