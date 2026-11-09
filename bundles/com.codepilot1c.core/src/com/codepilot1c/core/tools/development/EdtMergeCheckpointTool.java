package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;import com.google.gson.Gson;import com.codepilot1c.core.tools.*;
@ToolMeta(name="edt_merge_checkpoint",category="workspace",mutating=true,tags={"workspace","edt","compare"})
public final class EdtMergeCheckpointTool extends AbstractTool {
 public String getDescription(){return "Save/read/restore a native comparison plan across EDT restarts. Save includes native rules, selections, saved BSL/spreadsheet custom results, spreadsheet decisions and AI review notes. Unsaved editors block save/restore. Restore into a fresh ready comparison with identical source projects/files and semantic tree; requires fresh expected_plan_sha256, expected_checkpoint_sha256 and replace_plan=true. No source apply or project creation. Unresolved technical/ambiguous/custom non-text nodes fail closed. Check restored/partial_restore and inspect native plan/UI before applying. Checkpoints stay in workspace metadata outside Git.";}
 public String getParameterSchema(){return """
 {"type":"object","additionalProperties":false,"required":["action"],"properties":{"action":{"enum":["save","read","restore"]},"session_id":{"type":"string"},"checkpoint_id":{"type":"string"},"expected_plan_sha256":{"type":"string"},"expected_checkpoint_sha256":{"type":"string"},"replace_plan":{"type":"boolean"}}}
 """;}
 protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{return ToolResult.success(new Gson().toJson(com.codepilot1c.core.edt.compare.EdtComparisonService.getInstance().execute("checkpoint",p.getRaw())));}catch(Exception e){return ToolResult.failure("edt_merge_checkpoint: "+e.getMessage());}});}
}
