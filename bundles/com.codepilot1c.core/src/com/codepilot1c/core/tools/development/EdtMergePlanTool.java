// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.GsonBuilder;
import com.codepilot1c.core.tools.*;
@ToolMeta(name="edt_merge_plan",category="workspace",mutating=true,tags={"workspace","edt","compare"})
public final class EdtMergePlanTool extends AbstractTool {
    @Override public String getDescription(){return "Read a native merge plan or set an exact node rule. keep_main=left/no merge, take_other=right, prefer_main/prefer_other=three-way priority. Rules/settings only: no project writes. Before setting a rule read edt_compare details; expected_node_sha256 is mandatory. Recursive selection must be explicit. Returns plan_sha256 to pass as expected_plan_sha256 to edt_merge_apply. Native restrictions are never forced.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"session_id":{"type":"string"},"node_id":{"type":"string"},"rule":{"type":"string","enum":["keep_main","take_other","prefer_main","prefer_other"]},"expected_node_sha256":{"type":"string"},"recursive":{"type":"boolean","default":false}},"required":["session_id"]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{
        try{return ToolResult.success(new GsonBuilder().serializeNulls().create().toJson(com.codepilot1c.core.edt.compare.EdtComparisonService.getInstance().execute("plan",p.getRaw())));}
        catch(Exception e){return ToolResult.failure("EDT_COMPARE: "+e.getClass().getSimpleName()+": "+e.getMessage());}
    });}
}
