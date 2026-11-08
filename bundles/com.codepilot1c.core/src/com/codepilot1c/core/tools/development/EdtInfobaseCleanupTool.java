// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
import com.codepilot1c.core.edt.infobase.OwnedFileBaseService;
@ToolMeta(name="edt_infobase_cleanup",category="workspace",mutating=true,tags={"workspace","edt"})
public final class EdtInfobaseCleanupTool extends AbstractTool {
 public String getDescription(){return "Preview/apply removal of a NEW FILE database created with explicit disposable_test_base=true. Requires owned_base_id, project, base_uuid and fresh preview hash. Checks file identity witness, other owners, file lock and remaining manifest; never terminates user clients. Native EDT removes association/launcher entry, Eclipse preferences removes only owned UUID settings, then disposable files are deleted. Existing/server databases have no lease and cannot be removed. Idempotent cleanup can remove residual owned UUID settings. apply returns job_id.";}
 public String getParameterSchema(){return """
 {"type":"object","additionalProperties":false,"required":["action","owned_base_id","project","base_uuid"],"properties":{"action":{"enum":["preview","apply"]},"owned_base_id":{"type":"string"},"project":{"type":"string"},"base_uuid":{"type":"string"},"expected_plan_sha256":{"type":"string","pattern":"^[a-f0-9]{64}$"}}}
 """;}
 protected CompletableFuture<ToolResult> doExecute(ToolParameters p){try{
  var service=new OwnedFileBaseService();String id=p.requireString("owned_base_id"),project=p.requireString("project"),uuid=p.requireString("base_uuid"),action=p.requireString("action");
  if(action.equals("preview"))return CompletableFuture.completedFuture(ToolResult.success(new Gson().toJson(service.preview(id,project,uuid))));
  if(!action.equals("apply"))throw new IllegalArgumentException("Unknown action");String hash=p.requireString("expected_plan_sha256");String job=BackgroundJobRegistry.getInstance().startJob("owned-file-base-cleanup",()->new Gson().toJson(service.cleanup(id,project,uuid,hash)));
  return CompletableFuture.completedFuture(ToolResult.success(new Gson().toJson(Map.of("job_id",job,"next_tool","edt_operation_status"))));
 }catch(Exception e){return CompletableFuture.completedFuture(ToolResult.failure("edt_infobase_cleanup: "+e.getMessage()));}}
}
