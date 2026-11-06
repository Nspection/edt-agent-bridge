package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.debug.EdtDebugService;
@ToolMeta(name="edt_run_to_line",category="diagnostics",mutating=true,tags={"workspace","edt"})
public final class EdtRunToLineTool extends AbstractTool {
    @Override public String getDescription(){return "Resume a suspended EDT thread with a native temporary run-to-line breakpoint. Returns immediately; use wait_for_break and edt_debug_inspect to verify the actual stop. Another breakpoint or exception may stop execution first. Existing ordinary breakpoints are preserved; remove_breakpoint can cancel the returned temporary breakpoint.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"project":{"type":"string"},"thread_id":{"type":"string"},"file_path":{"type":"string"},"line":{"type":"integer","minimum":1}},"required":["project","file_path","line"]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{var a=p.getRaw();return ToolResult.success(new Gson().toJson(EdtDebugService.getInstance().runToLine((String)a.get("project"),(String)a.get("thread_id"),(String)a.get("file_path"),((Number)a.get("line")).intValue())));}catch(Exception e){return ToolResult.failure("EDT_RUN_TO_LINE: "+e.getMessage());}});}
}
