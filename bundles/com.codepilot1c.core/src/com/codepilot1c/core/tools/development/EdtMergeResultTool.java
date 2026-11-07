// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.GsonBuilder;
import com.codepilot1c.core.tools.*;
@ToolMeta(name="edt_merge_result",category="workspace",mutating=true,tags={"workspace","edt","compare"})
public final class EdtMergeResultTool extends AbstractTool {
    @Override public String getDescription(){return "Read native BSL or spreadsheet sides/result. For spreadsheets: open_ui opens native visual editors; close_ui closes only a saved spreadsheet editor without cancelling comparison; patch_cells saves a native CUSTOM_MERGE plan using optional base_side (MAIN/OTHER/COMMON_ANCESTOR) and sheet_patch (1-based cells/areas/formatting as edt_spreadsheet). Unsaved visual drafts block writes. For BSL: write or patch native CUSTOM_MERGE text, without writing source files. Read sides/result first. Writes require expected_node_sha256; patch old_text must occur exactly once. Native custom settings are returned; a still-uncommitted live editor buffer is NOT claimed to be captured. Dirty editors/modal dialogs block writes. Syntax is checked separately from semantic correctness.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"session_id":{"type":"string"},"node_id":{"type":"string"},"action":{"type":"string","enum":["read","write","patch","open_ui","close_ui","patch_cells"],"default":"read"},"expected_node_sha256":{"type":"string"},"base_side":{"type":"string","enum":["MAIN","OTHER","COMMON_ANCESTOR"]},"sheet_patch":{"type":"object"},"content":{"type":"string"},"old_text":{"type":"string"},"new_text":{"type":"string"}},"required":["session_id","node_id"]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{
        try{return ToolResult.success(new GsonBuilder().serializeNulls().create().toJson(com.codepilot1c.core.edt.compare.EdtComparisonService.getInstance().execute("result",p.getRaw())));}
        catch(Exception e){return ToolResult.failure("EDT_COMPARE: "+e.getClass().getSimpleName()+": "+e.getMessage());}
    });}
}
