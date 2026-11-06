package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.DevelopmentRunService;
@ToolMeta(name="edt_development_run_continue",category="qa",mutating=true,tags={"workspace","edt"})
public final class EdtDevelopmentRunContinueTool extends AbstractTool {
    @Override public String getDescription(){return "Continue a visual checkpoint only after reviewing its screenshot. All identifiers and screenshot_sha256 must come from the current run status. The controller rejects stale, changed, expired or already accepted checkpoints.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"run_id":{"type":"string"},"managed_run_id":{"type":"string"},"checkpoint_id":{"type":"string"},"screenshot_sha256":{"type":"string"}},"required":["run_id","managed_run_id","checkpoint_id","screenshot_sha256"]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{return ToolResult.success(new Gson().toJson(DevelopmentRunService.getInstance().continueRun((String)p.getRaw().get("run_id"),(String)p.getRaw().get("managed_run_id"),(String)p.getRaw().get("checkpoint_id"),(String)p.getRaw().get("screenshot_sha256"))));}catch(Exception e){return ToolResult.failure("edt_development_run_continue: "+e.getMessage());}});}
}
