package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.debug.EdtDebugService;
@ToolMeta(name="edt_exception_breakpoint",category="diagnostics",mutating=true,tags={"workspace","edt"})
public final class EdtExceptionBreakpointTool extends AbstractTool {
    @Override public String getDescription(){return "Set or remove a native EDT exception breakpoint. Native exception breakpoints have WORKSPACE scope and can affect other attached applications; scope=workspace must be explicit. message uses the platform's exception-message matching semantics. Remove using the returned breakpoint_id. This is distinct from a conditional line breakpoint.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"scope":{"type":"string","enum":["workspace"]},"action":{"type":"string","enum":["set","remove"]},"breakpoint_id":{"type":"string"},"message":{"type":"string"},"catch_all":{"type":"boolean"},"enabled":{"type":"boolean","default":true}},"required":["scope","action"]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{var a=p.getRaw();if(!"workspace".equals(a.get("scope"))){throw new IllegalArgumentException("scope=workspace is required");}return ToolResult.success(new Gson().toJson(EdtDebugService.getInstance().exceptionBreakpoint((String)a.get("action"),(String)a.get("breakpoint_id"),(String)a.get("message"),(Boolean)a.get("catch_all"),!Boolean.FALSE.equals(a.get("enabled")))));}catch(Exception e){return ToolResult.failure("EDT_EXCEPTION_BREAKPOINT: "+e.getMessage());}});}
}
