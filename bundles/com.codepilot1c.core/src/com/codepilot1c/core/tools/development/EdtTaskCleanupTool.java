// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.*;
@ToolMeta(name="edt_task_cleanup",category="qa",mutating=true,tags={"workspace","edt","sensitive"})
public final class EdtTaskCleanupTool extends AbstractTool {
 public String getDescription(){return "Preview/apply one dependency-ordered cleanup pass for explicit task resources. apply requires a fresh plan_sha256 and returns job_id. Pending/unknown data blocks host/project teardown. Delegates existing ownership/hash-guarded native cleanup; test records are only marked for deletion, never physically deleted. Untracked bases, launcher entries and side effects are NOT claimed clean. Repeat preview to continue; individual cleanup tools remain available.";}
 public String getParameterSchema(){return """
{"type": "object", "additionalProperties": false, "properties": {"action": {"enum": ["preview", "apply"]}, "session_id": {"type": "string"}, "expected_plan_sha256": {"type": "string", "pattern": "^[a-f0-9]{64}$"}}, "required": ["action", "session_id"]}
""";}
 protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{

 var s=TaskSessionService.workspace();String id=p.requireString("session_id");
 if("preview".equals(p.requireString("action")))return ToolResult.success(new Gson().toJson(s.preview(id)));
 if(!"apply".equals(p.requireString("action")))throw new IllegalArgumentException("Unknown action");String hash=p.requireString("expected_plan_sha256");
 String job=com.codepilot1c.core.tools.workspace.BackgroundJobRegistry.getInstance().startJob("task-cleanup",()->new Gson().toJson(s.cleanup(id,hash)));
 return ToolResult.success(new Gson().toJson(Map.of("job_id",job,"next_tool","edt_operation_status")));

 }catch(Exception e){return ToolResult.failure("edt_task_cleanup: "+e.getMessage());}});}
}
