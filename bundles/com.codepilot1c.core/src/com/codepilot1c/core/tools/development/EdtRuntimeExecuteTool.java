package com.codepilot1c.core.tools.development;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.edt.development.*;
import com.codepilot1c.core.tools.*;

@ToolMeta(name="edt_runtime_execute",category="qa",mutating=true,tags={"workspace","edt","sensitive"})
public final class EdtRuntimeExecuteTool extends AbstractTool {
    @Override public String getDescription() {return "Execute BSL, a query, runtime info or an exported EPF/ERF function in the target through VAExtension and the managed Vanessa runner. Requires a saved verified edt_runner_profile, dedicated manager and VAExtension in target. Does not update the base or install extensions. parameters becomes ПараметрыВызова; assign a JSON-compatible value to Результат. External method must be an exported function accepting one parameter. Returns run_id; action=status returns structured execution result only after scenario completion. Debugger tools remain independent. Cancellation is not a transaction rollback; an interrupted write may have completed. action=cleanup removes payload/result files and retains deduplication tombstones.";}
    @Override public String getParameterSchema() {return """
        {"type":"object","additionalProperties":false,"properties":{
          "action":{"type":"string","enum":["start","status","cancel","cleanup"],"default":"start"},
          "project":{"type":"string"},"profile_id":{"type":"string"},"idempotency_key":{"type":"string"},
          "mode":{"type":"string","enum":["info","execute","query","external"]},
          "context":{"type":"string","enum":["server","client"],"default":"server"},
          "code":{"type":"string","maxLength":131072},"query":{"type":"string","maxLength":131072},
          "parameters":{"type":"object"},"limit":{"type":"integer","minimum":1,"maximum":1000,"default":100},
          "artifact_path":{"type":"string"},"expected_artifact_sha256":{"type":"string"},"method":{"type":"string"},
          "timeout_s":{"type":"integer","minimum":30,"maximum":7200,"default":300},"run_id":{"type":"string"}
        }}
        """;}
    @SuppressWarnings("unchecked") @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p) {
        return CompletableFuture.supplyAsync(()->{try {
            Map<String,Object> a=p.getRaw();RuntimeExecutionService service=RuntimeExecutionService.workspace();
            String action=(String)a.getOrDefault("action","start");Map<String,Object> result;
            if(action.equals("start")) {
                String project=RuntimeExecutionPlan.required(a,"project");
                var document=RunnerProfileStore.workspace().get(project,(String)a.get("profile_id"));
                if(!Boolean.TRUE.equals(document.get("configured"))) throw new IllegalStateException("needs_setup: save edt_runner_profile first");
                result=service.start(a,(Map<String,Object>)document.get("profile"));
            } else {
                String id=RuntimeExecutionPlan.required(a,"run_id");
                result=switch(action) {case "status" -> service.status(id);case "cancel" -> service.cancel(id);case "cleanup" -> service.cleanup(id);default -> throw new IllegalArgumentException("Unknown action");};
            }
            return ToolResult.success(new Gson().toJson(result));
        } catch(Exception e) {return ToolResult.failure("edt_runtime_execute: "+e.getMessage());}});
    }
}
