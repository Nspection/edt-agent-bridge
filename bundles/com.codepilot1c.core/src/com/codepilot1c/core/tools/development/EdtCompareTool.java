// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.GsonBuilder;
import com.codepilot1c.core.tools.*;
@ToolMeta(name="edt_compare",category="workspace",mutating=false,tags={"workspace","edt","compare"})
public final class EdtCompareTool extends AbstractTool {
    @Override public String getDescription(){return "Start a native EDT comparison or attach to existing workspace sessions. MAIN is the destination/left, OTHER the source/right. No project writes. New MCP sessions start with nothing selected. Borrowed UI sessions preserve choices and cannot be closed by MCP. Structured BSL comparison defaults to true. Use status until ready, then paged tree/details. No Git merge. handoff/open_ui opens the SAME prepared native session for user review, optionally highlights node_id and shows reason. User may edit and click Merge directly or ask the agent to resume; no automatic apply is scheduled.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"action":{"type":"string","enum":["start","sessions","status","tree","details","close","open_ui","handoff"],"default":"sessions"},"reason":{"type":"string"},"main_project":{"type":"string"},"other_project":{"type":"string"},"ancestor_project":{"type":"string"},"scope":{"type":"array","items":{"type":"string"}},"matching":{"type":"string","enum":["UUID","NAME","UUID_THEN_NAME"],"default":"UUID_THEN_NAME"},"parse_bsl_structure":{"type":"boolean","default":true},"allow_deletions":{"type":"boolean","default":false},"session_id":{"type":"string"},"node_id":{"type":"string"},"offset":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":500},"changes_only":{"type":"boolean","default":true},"name_contains":{"type":"string"}},"required":[]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{
        try{return ToolResult.success(new GsonBuilder().serializeNulls().create().toJson(com.codepilot1c.core.edt.compare.EdtComparisonService.getInstance().execute("compare",p.getRaw())));}
        catch(Exception e){return ToolResult.failure("EDT_COMPARE: "+e.getClass().getSimpleName()+": "+e.getMessage());}
    });}
}
