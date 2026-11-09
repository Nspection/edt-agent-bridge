// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.*;import java.util.concurrent.CompletableFuture;
import com.codepilot1c.core.tools.*;import com.codepilot1c.core.edt.development.*;import com.codepilot1c.core.edt.ast.*;import com.codepilot1c.core.edt.runtime.EdtRuntimeService;import com.codepilot1c.core.mcp.host.McpContractMetadataService;
@ToolMeta(name="edt_ready",category="workspace",tags={"read-only","workspace","edt"})
public final class EdtReadyTool extends AbstractTool {
 public String getDescription(){return "Bounded read-only readiness for services, required commands, one project model and optional database/runner requirements. No launch/connect/update. database defaults none; connected requires per-base proof and currently stays unverified with the SDK container-only flag. timeout_ms=0 checks once; unknown/timeout stays blocked. Before MCP is reachable use its existing health endpoint.";}
 public String getParameterSchema(){return AgentResponses.schema("""
 {"type":"object","additionalProperties":false,"properties":{"project":{"type":"string"},"profile_id":{"type":"string"},"required_tools":{"type":"array","maxItems":32,"items":{"type":"string"}},"database":{"enum":["none","bound","connected","equal"],"default":"none"},"runner_configured":{"type":"boolean","default":false},"timeout_ms":{"type":"integer","minimum":0,"maximum":25000,"default":20000}}}
 """);}
 public static com.codepilot1c.core.mcp.host.McpReadiness globalServices(com.codepilot1c.core.edt.metadata.EdtMetadataGateway gateway,java.util.function.BooleanSupplier importAvailable){return new com.codepilot1c.core.mcp.host.McpSemanticReadinessProbe(gateway,importAvailable,List::of).evaluate();}
 protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{
  Map<String,Object>a=p.getRaw();String project=(String)a.get("project"),database=(String)a.getOrDefault("database","none");List<String> required=new ArrayList<>();if(a.containsKey("required_tools")){if(!(a.get("required_tools") instanceof List<?> names)||names.size()>32||names.stream().anyMatch(n->!(n instanceof String)||((String)n).isBlank()))throw new IllegalArgumentException("Invalid required_tools");for(Object n:names)required.add((String)n);}
  var out=ReadyWaitService.await(()->{
   Map<String,Object> seen=new LinkedHashMap<>();seen.put("services",globalServices(new com.codepilot1c.core.edt.metadata.EdtMetadataGateway(),()->ToolRegistry.getInstance().resolveTool("workspace_import_project").tool()!=null).services());List<String> unknown=new ArrayList<>();
   if(project!=null){seen.put("project",project);try{var g=new EdtServiceGateway();seen.put("model",Map.of("state",new ProjectReadinessChecker(g).check(g.resolveProject(project)).getState().name()));}catch(Exception|LinkageError e){unknown.add("model");seen.put("model",Map.of("state","unknown"));}
    if(!database.equals("none"))try{seen.put("database",AgentBrief.select(new EdtRuntimeService().synchronizationState(project),"uuid","connected","equality","configuration_synchronized"));}catch(Exception|LinkageError e){unknown.add("database");}
    if(seen.get("database") instanceof Map<?,?> raw){Map<String,Object> db=new LinkedHashMap<>();raw.forEach((k,v)->db.put(String.valueOf(k),v));Object container=db.remove("connected");db.put("synchronization_container_present",Boolean.TRUE.equals(container));db.put("connected_verified",false);db.put("connection_state","unverified_sdk_container_only");seen.put("database",db);}
    if(Boolean.TRUE.equals(a.get("runner_configured")))try{seen.put("runner",AgentBrief.select(RunnerProfileStore.workspace().get(project,(String)a.get("profile_id")),"configured","state"));}catch(Exception|LinkageError e){unknown.add("runner");}
   }
   seen.put("unknown_sections",unknown);return ReadyWaitService.evaluate(seen,ToolRegistry.getInstance().getAllTools().stream().filter(tool->ToolRegistry.getInstance().isToolModelFacing(tool.getName())).map(ITool::getName).toList(),required,database,Boolean.TRUE.equals(a.get("runner_configured")));
  },AgentResponses.integer(a,"timeout_ms",20000,0,25000));return AgentResponses.success(out,a);
 }catch(InterruptedException e){Thread.currentThread().interrupt();return AgentErrors.failure(getName(),"readiness",e,true);}catch(Exception e){return AgentErrors.failure(getName(),"readiness",e,true);}});}
}
