// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.*;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
@ToolMeta(name="edt_test_client",category="qa",mutating=true,tags={"workspace","edt","sensitive"})
public final class EdtTestClientTool extends AbstractTool {
    public String getDescription(){return "Own local direct TestClient session, without Vanessa manager, VAExtension or Testpilot MCP server. Stage1 Windows only; explicit isolated python.exe needs pywin32/cryptography. start requires project bound to permitted native EQUAL base; never updates it, installs dependencies or changes trust. Launches own 1C process via SDK credentials, checks PID/start/base and TCP listener ownership. Returns job_id, then client_id; wait edt_operation_status. Observe/act using existing edt_form_state/edt_form_action with transport=testclient and client_id. status freshly verifies process/base; result reads durable operation_id without replay. stop/cleanup affect only own processes and files; stop never rolls back data. After restart a private pipe is not silently recreated; inspect unknown operations. Cleanup removes saved form results. No attach to arbitrary endpoints, server process or user client.";}
    public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"required":["action"],"properties":{"action":{"enum":["start","status","stop","cleanup","result"]},"project":{"type":"string"},"python_path":{"type":"string"},"client_id":{"type":"string"},"operation_id":{"type":"string"}}}
        """;}
    protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{
        var args=p.getRaw();String action=RuntimeExecutionPlan.required(args,"action");var service=new DirectTestClientService();
        if(action.equals("start")||action.equals("cleanup")){String job=BackgroundJobRegistry.getInstance().startJob("test-client-"+action,()->new com.google.gson.GsonBuilder().serializeNulls().create().toJson(action.equals("start")?service.start(args):service.cleanup(RuntimeExecutionPlan.required(args,"client_id"))));return ToolResult.success(new com.google.gson.GsonBuilder().serializeNulls().create().toJson(java.util.Map.of("job_id",job,"next_tool","edt_operation_status")));}
        String id=RuntimeExecutionPlan.required(args,"client_id");var result=switch(action){case "status"->service.status(id);case "stop"->service.stop(id);case "result"->service.result(id,RuntimeExecutionPlan.required(args,"operation_id"));default->throw new IllegalArgumentException("Unsupported action");};return ToolResult.success(new com.google.gson.GsonBuilder().serializeNulls().create().toJson(result));
    }catch(Exception error){return ToolResult.failure("edt_test_client: "+error.getMessage());}});}
}
