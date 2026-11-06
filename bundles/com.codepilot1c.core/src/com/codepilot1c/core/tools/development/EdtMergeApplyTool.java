// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.GsonBuilder;
import com.codepilot1c.core.tools.*;
@ToolMeta(name="edt_merge_apply",category="workspace",mutating=true,tags={"workspace","edt","compare"})
public final class EdtMergeApplyTool extends AbstractTool {
    @Override public String getDescription(){return "Apply a prepared native EDT merge plan into MAIN/left project. Requires fresh expected_plan_sha256 from edt_merge_plan and unchanged source-project hashes. Unsaved editors and unresolved markers block applying. Uses native startMerge, never startMergeIgnoringProblems. Afterwards inspect diagnostics, repeat comparison and perform required runtime checks. Changes to infobases/Git are not performed.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"session_id":{"type":"string"},"expected_plan_sha256":{"type":"string"}},"required":["session_id","expected_plan_sha256"]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{
        try{return ToolResult.success(new GsonBuilder().serializeNulls().create().toJson(com.codepilot1c.core.edt.compare.EdtComparisonService.getInstance().execute("apply",p.getRaw())));}
        catch(Exception e){return ToolResult.failure("EDT_COMPARE: "+e.getClass().getSimpleName()+": "+e.getMessage());}
    });}
}
