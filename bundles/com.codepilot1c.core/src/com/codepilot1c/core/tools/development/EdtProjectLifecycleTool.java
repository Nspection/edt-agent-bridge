// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
import com.codepilot1c.core.edt.workspace.ProjectLifecycleService;
@ToolMeta(name="edt_project_lifecycle",category="workspace",mutating=true,tags={"workspace","edt"})
public final class EdtProjectLifecycleTool extends AbstractTool {
 @Override public String getDescription(){return "Status/preview/apply native open, close, reopen or Project Clean for ONE exact registered project. Clean uses CLEAN_BUILD and by default scoped FULL_BUILD; never deletes source/cache directories, updates a base, or builds all projects. Preview returns plan_sha256 and blockers; apply needs that fresh expected_plan_sha256 and stable idempotency_key. Dirty editors in all windows, native comparisons, active runtime/debug/build jobs block maintenance; never saves/discards/stops them. Returns job_id; poll edt_operation_status. Same apply key reads its durable result; failed/unknown operations are never automatically repeated. Completed build is not proof of EDT model readiness, export freshness or infobase EQUAL. Use current readiness/diagnostics and read-only refresh separately.";}
 @Override public String getParameterSchema(){return """
 {"type":"object","additionalProperties":false,"properties":{"project":{"type":"string"},"action":{"type":"string","enum":["status","preview","apply"],"default":"status"},"operation":{"type":"string","enum":["open","close","reopen","clean"]},"rebuild":{"type":"boolean","default":true},"expected_plan_sha256":{"type":"string"},"idempotency_key":{"type":"string"}},"required":["project"]}
 """;}
 @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){try{var a=Map.copyOf(p.getRaw());String id=BackgroundJobRegistry.getInstance().startJob("project-lifecycle",()->new Gson().toJson(ProjectLifecycleService.workspace().execute(a)));return CompletableFuture.completedFuture(ToolResult.success(new Gson().toJson(Map.of("job_id",id,"state","queued","next_tool","edt_operation_status"))));}catch(Exception e){return CompletableFuture.completedFuture(ToolResult.failure(e.getMessage()));}}
}
