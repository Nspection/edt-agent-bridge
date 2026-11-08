package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.DevelopmentRunService;
@ToolMeta(name="edt_development_run_cancel",category="qa",mutating=true,tags={"workspace","edt"})
public final class EdtDevelopmentRunCancelTool extends AbstractTool {
    @Override public String getDescription(){return "Request cancellation of this development run. Stops only correlated managed Vanessa clients. EDT update cancellation is cooperative: cancel_requested is not a completed cancellation. Existing debugger sessions and unrelated 1C clients are preserved.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"run_id":{"type":"string"}},"required":["run_id"]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{return ToolResult.success(new Gson().toJson(DevelopmentRunService.getInstance().cancel((String)p.getRaw().get("run_id"))));}catch(Exception e){return ToolResult.failure("edt_development_run_cancel: "+e.getMessage());}});}
}
