package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.DevelopmentRunService;
import com.codepilot1c.core.edt.development.RunnerProfileStore;
@ToolMeta(name="edt_development_run_start",category="qa",mutating=true,tags={"workspace","edt"})
public final class EdtDevelopmentRunStartTool extends AbstractTool {
    @Override public String getDescription(){return "Read edt_runner_profile first. Start an independent update/debug/Vanessa cycle using profile_id or explicit paths. feature_paths selects scenarios directly over the base parameters without editing JSON; invalid selections are rejected before debug launch. Returns run_id immediately; poll edt_development_run_status. idempotency_key prevents duplicate clients on retry. File infobase must match the connected EDT project. update_db updates schema; application migrations need scenario assertions. UI actions run through Vanessa; screenshots are for review. Native VA step timeouts are independent of the pause-aware watchdog.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"project":{"type":"string"},"idempotency_key":{"type":"string"},"profile_id":{"type":"string"},"expected_vanessa_sha256":{"type":"string"},"platform_bin":{"type":"string"},"infobase_path":{"type":"string"},"vanessa_epf":{"type":"string"},"params_template":{"type":"string"},"feature_paths":{"type":"array","items":{"type":"string"},"minItems":1,"maxItems":100},"user_name":{"type":"string"},"manager_infobase_path":{"type":"string","description":"Dedicated TestManager base with client_mcp. Defaults to target infobase."},"manager_user_name":{"type":"string"},"configuration_name":{"type":"string"},"update_db":{"type":"boolean"},"visual_checkpoint":{"type":"boolean","default":true},"timeout_s":{"type":"integer","minimum":30,"maximum":7200}},"required":["project","idempotency_key","update_db"]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{return ToolResult.success(new Gson().toJson(DevelopmentRunService.getInstance().start(RunnerProfileStore.workspace().resolve(p.getRaw()))));}catch(Exception e){return ToolResult.failure("edt_development_run_start: "+e.getMessage());}});}
}
