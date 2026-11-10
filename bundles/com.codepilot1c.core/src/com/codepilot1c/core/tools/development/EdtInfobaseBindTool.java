// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.infobase.InfobaseBindingService;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
import com.google.gson.Gson;
@ToolMeta(name="edt_infobase_bind",category="workspace",mutating=true,tags={"workspace","edt","sensitive"})
public final class EdtInfobaseBindTool extends AbstractTool {
 public String getDescription(){return "Bind an ALREADY REGISTERED EDT infobase by exact base_uuid to the current project/branch context, including file bases outside workspace/home. action=list reads registrations; preview captures context/target/current primary; apply requires its unchanged expected_preview_sha256 and returns a job. Preserves saved credentials/runtime. Never creates/restores/starts/updates a base, imports sources, registers a new path or bypasses connect_infobase path checks. Refuses other project owners; replacing a different primary requires replace_primary=true. Same preview receipt returns its durable result; unknown partial binding is never replayed. Binding success does not prove native EQUAL or business migrations; read edt_infobase_state separately.";}
 public String getParameterSchema(){return """
 {"type":"object","additionalProperties":false,"required":["action"],"properties":{"action":{"enum":["list","preview","apply"]},"project":{"type":"string"},"base_uuid":{"type":"string"},"set_primary":{"type":"boolean","default":true},"replace_primary":{"type":"boolean","default":false},"expected_preview_sha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},"filter":{"type":"string","maxLength":128},"offset":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":50,"default":20}}}
 """;}
 protected CompletableFuture<ToolResult> doExecute(ToolParameters p){try{
  var a=p.getRaw();String action=p.requireString("action");var service=new InfobaseBindingService();var json=new Gson();
  Set<String> allowed=action.equals("list")?Set.of("action","project","filter","offset","limit"):action.equals("preview")?Set.of("action","project","base_uuid","set_primary","replace_primary"):Set.of("action","project","base_uuid","set_primary","replace_primary","expected_preview_sha256");if(!allowed.containsAll(a.keySet()))throw new IllegalArgumentException("Field does not apply to binding action");
  if(action.equals("list"))return CompletableFuture.completedFuture(ToolResult.success(json.toJson(service.list((String)a.getOrDefault("filter",""),integer(a,"offset",0),integer(a,"limit",20)))));
  if(action.equals("preview"))return CompletableFuture.completedFuture(ToolResult.success(json.toJson(service.preview(a))));
  if(!action.equals("apply"))throw new IllegalArgumentException("Unknown binding action");
  String job=BackgroundJobRegistry.getInstance().startJob("infobase-bind",()->json.toJson(service.apply(a)));return CompletableFuture.completedFuture(ToolResult.success(json.toJson(Map.of("job_id",job,"next_tool","edt_operation_status"))));
 }catch(Exception e){return CompletableFuture.completedFuture(ToolResult.failure("edt_infobase_bind: "+e.getMessage()));}}
 private static int integer(Map<String,Object>a,String k,int fallback){if(!a.containsKey(k))return fallback;if(!(a.get(k) instanceof Number n))throw new IllegalArgumentException(k+" must be integer");return new java.math.BigDecimal(n.toString()).intValueExact();}
}
