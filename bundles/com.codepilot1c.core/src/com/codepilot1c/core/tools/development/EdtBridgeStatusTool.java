// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.eclipse.core.runtime.Platform;
import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.mcp.host.McpHostManager;
import com.codepilot1c.core.tools.*;
@ToolMeta(name="edt_bridge_status",category="workspace",tags={"read-only","edt","workspace"})
public final class EdtBridgeStatusTool extends AbstractTool {
 public String getDescription(){return "Inspect installed EDT Agent Bridge identity, native tool count, owned preference pages and absence of embedded chat/provider components. Contains no tokens or account data.";}
 public String getParameterSchema(){return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}";}
 protected CompletableFuture<ToolResult> doExecute(ToolParameters p){
  var plugin=VibeCorePlugin.getDefault();var bundle=plugin.getBundle();var rows=new ArrayList<Map<String,Object>>();
  var registry=Platform.getExtensionRegistry();
  if(registry!=null)for(var item:registry.getConfigurationElementsFor("org.eclipse.ui.preferencePages"))if("com.codepilot1c.ui".equals(item.getContributor().getName()))rows.add(Map.of("id",item.getAttribute("id"),"name",item.getAttribute("name")));
  var absent=new LinkedHashMap<String,Boolean>();
  for(String name:List.of("com/codepilot1c/core/provider/LlmProviderRegistry.class","com/codepilot1c/core/backend/BackendService.class","com/codepilot1c/core/mcp/McpServerManager.class","com/codepilot1c/core/memory/MemoryService.class"))absent.put(name,bundle.getEntry(name)==null);
  return CompletableFuture.completedFuture(ToolResult.success(new com.google.gson.Gson().toJson(Map.of("product","EDT Agent Bridge","version",bundle.getVersion().toString(),"mcp_running",McpHostManager.getInstance().isRunning(),"registered_tools",ToolRegistry.getInstance().getAllTools().size(),"preference_pages",rows,"excluded_components_absent",absent))));
 }
}
