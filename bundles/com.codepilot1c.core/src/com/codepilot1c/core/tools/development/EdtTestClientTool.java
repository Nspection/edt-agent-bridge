// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.*;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
@ToolMeta(name="edt_test_client",category="qa",mutating=true,tags={"workspace","edt","sensitive"})
public final class EdtTestClientTool extends AbstractTool {
    public String getDescription(){return "Own local direct TestClient session, without Vanessa manager, VAExtension or Testpilot MCP server. Windows only. prepare_environment optionally takes a source python_path, auto-discovers standard CPython3.11-3.14 x64 in PATH/Windows registration, creates a workspace-owned venv and downloads only pinned/hash-verified PyPI wheels; never installs base Python or global packages. Reuses a verified ready venv. Poll returned job_id. environment_status inspects readiness without installation. cleanup_environment requires environment_id and refuses live clients/changed files. start omits python_path to use the prepared active environment (or supplies environment_id); legacy explicit isolated python_path remains supported. start requires project bound to permitted native EQUAL base; never updates it, automatically installs dependencies or changes trust. Launches own 1C process via SDK credentials, checks PID/start/base and TCP listener ownership. Returns job_id, then client_id; wait edt_operation_status. Observe/act using existing edt_form_state/edt_form_action with transport=testclient and client_id. status freshly verifies process/base; result reads durable operation_id without replay. stop/cleanup affect only own processes and files; stop never rolls back data. After restart a private pipe is not silently recreated; inspect unknown operations. Cleanup removes saved form results. No attach to arbitrary endpoints, server process or user client.";}
    public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"required":["action"],"properties":{"action":{"enum":["start","status","stop","cleanup","result","prepare_environment","environment_status","cleanup_environment"]},"project":{"type":"string"},"python_path":{"type":"string"},"environment_id":{"type":"string","pattern":"^[a-f0-9]{24}$"},"client_id":{"type":"string"},"operation_id":{"type":"string"}}}
        """;}
    protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{
        var args=p.getRaw();String action=RuntimeExecutionPlan.required(args,"action");
        if(java.util.Set.of("prepare_environment","environment_status","cleanup_environment").contains(action)){
            var allowed=action.equals("prepare_environment")?java.util.Set.of("action","python_path"):java.util.Set.of("action","environment_id");if(!allowed.containsAll(args.keySet()))throw new IllegalArgumentException("Field does not apply to environment action");
            var environment=PythonEnvironmentService.workspace();if(action.equals("prepare_environment"))return ToolResult.success(AgentResponses.JSON.toJson(environment.prepareJob(args)));
            if(action.equals("environment_status"))return ToolResult.success(AgentResponses.JSON.toJson(environment.inspect((String)args.get("environment_id"))));
            String environmentId=RuntimeExecutionPlan.required(args,"environment_id");String job=BackgroundJobRegistry.getInstance().startJob("python-environment-cleanup",()->AgentResponses.JSON.toJson(environment.cleanup(environmentId)));return ToolResult.success(AgentResponses.JSON.toJson(java.util.Map.of("job_id",job,"next_tool","edt_operation_status")));
        }
        var service=new DirectTestClientService();
        if(action.equals("start")||action.equals("cleanup")){String job=BackgroundJobRegistry.getInstance().startJob("test-client-"+action,()->new com.google.gson.GsonBuilder().serializeNulls().create().toJson(action.equals("start")?service.start(args):service.cleanup(RuntimeExecutionPlan.required(args,"client_id"))));return ToolResult.success(new com.google.gson.GsonBuilder().serializeNulls().create().toJson(java.util.Map.of("job_id",job,"next_tool","edt_operation_status")));}
        String id=RuntimeExecutionPlan.required(args,"client_id");var result=switch(action){case "status"->service.status(id);case "stop"->service.stop(id);case "result"->service.result(id,RuntimeExecutionPlan.required(args,"operation_id"));default->throw new IllegalArgumentException("Unsupported action");};return ToolResult.success(new com.google.gson.GsonBuilder().serializeNulls().create().toJson(result));
    }catch(Exception error){return ToolResult.failure("edt_test_client: "+error.getMessage());}});}
}
