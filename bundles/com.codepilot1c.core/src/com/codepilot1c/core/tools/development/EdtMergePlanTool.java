// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.GsonBuilder;
import com.codepilot1c.core.tools.*;
@ToolMeta(name="edt_merge_plan",category="workspace",mutating=true,tags={"workspace","edt","compare"})
public final class EdtMergePlanTool extends AbstractTool {
    @Override public String getDescription(){return "Read a page of checked nodes and a full native plan hash, or set an exact checkbox with selected=true/false (preserves the native rule for ordinary nodes; use the native editor if the change might affect custom results). Use offset/limit to page and background=true to compute a large plan as a job. An edit returns the exact node receipt; read the plan separately for a fresh plan_sha256. keep_main=left/no merge, take_other=right, prefer_main/prefer_other=three-way priority. Rules/settings only: no project writes. Before setting a rule read edt_compare details; expected_node_sha256 is mandatory. Recursive selection must be explicit. Returns plan_sha256 to pass as expected_plan_sha256 to edt_merge_apply. Native restrictions are never forced.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"session_id":{"type":"string"},"node_id":{"type":"string"},"background":{"type":"boolean"},"selected":{"type":"boolean"},"offset":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":500},"rule":{"type":"string","enum":["keep_main","take_other","prefer_main","prefer_other"]},"expected_node_sha256":{"type":"string"},"recursive":{"type":"boolean","default":false}},"required":["session_id"]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){
        var args=p.getRaw();
        if(Boolean.TRUE.equals(args.get("background")) && !args.containsKey("selected") && !args.containsKey("rule")) {
            var request=java.util.Map.copyOf(args);
            String id=com.codepilot1c.core.tools.workspace.BackgroundJobRegistry.getInstance().startJob("edt-merge-plan",
                    ()->new GsonBuilder().serializeNulls().create().toJson(
                            com.codepilot1c.core.edt.compare.EdtComparisonService.getInstance().execute("plan",request)));
            return CompletableFuture.completedFuture(ToolResult.success(new GsonBuilder().create().toJson(
                    java.util.Map.of("job_id",id,"state","queued","next_tool","edt_operation_status"))));
        }
        return CompletableFuture.supplyAsync(()->{
        try{return ToolResult.success(new GsonBuilder().serializeNulls().create().toJson(com.codepilot1c.core.edt.compare.EdtComparisonService.getInstance().execute("plan",p.getRaw())));}
        catch(Exception e){return ToolResult.failure("EDT_COMPARE: "+e.getClass().getSimpleName()+": "+e.getMessage());}
    });}
}
