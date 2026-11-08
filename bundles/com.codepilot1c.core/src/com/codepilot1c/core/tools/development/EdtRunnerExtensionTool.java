package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
import com.codepilot1c.core.edt.development.RunnerExtensionService;
@ToolMeta(name="edt_runner_extension",category="qa",mutating=true,tags={"workspace","edt"})
public final class EdtRunnerExtensionTool extends AbstractTool {
    @Override public String getDescription(){return "Inspect/prepare client_mcp only in the dedicated manager base from the saved runner profile, using a native EDT Designer session. Preview by default; returns current flags and state_sha256. apply=true requires expected_state_sha256 from that preview and sets active=true, safe_mode=false, unsafe_action_protection=false. Does not install/replace the extension or touch the target base. The initial OS-auth manager profile is supported; an unavailable credential profile returns needs_setup instead of prompting. Returns job_id; poll edt_operation_status.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"project":{"type":"string"},"profile_id":{"type":"string"},"apply":{"type":"boolean","default":false},"expected_state_sha256":{"type":"string"}},"required":["project"]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){try{String id=BackgroundJobRegistry.getInstance().startJob("runner-extension-prepare",()->new Gson().toJson(new RunnerExtensionService().prepare((String)p.getRaw().get("project"),(String)p.getRaw().get("profile_id"),Boolean.TRUE.equals(p.getRaw().get("apply")),(String)p.getRaw().get("expected_state_sha256"))));return CompletableFuture.completedFuture(ToolResult.success(new Gson().toJson(java.util.Map.of("job_id",id,"next_tool","edt_operation_status"))));}catch(Exception e){return CompletableFuture.completedFuture(ToolResult.failure("edt_runner_extension: "+e.getMessage()));}}
}
