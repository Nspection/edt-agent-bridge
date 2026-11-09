// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.edt.development.*;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
@ToolMeta(name="edt_runtime_host",category="qa",mutating=true,tags={"workspace","edt","sensitive"})
public final class EdtRuntimeHostTool extends AbstractTool {
    @Override public String getDescription(){return "Native API BSL host without VAExtension. prepare mode=external creates an owned EDT external EPF project. mode=metadata creates only an internal temporary DataProcessor; no external project or EPF. Requires associated permitted test base, native ZIP and SHA256. First metadata start FULL-updates the whole project into the base; an unchanged previously loaded host with current native EQUAL skips updating. external mode never updates it. Own Enterprise client loads EPF/native component; platform trust may require local preparation. execute returns request_id; poll result. context client/server; execution_mode info/execute/query/external; parameters becomes ПараметрыВызова, assign Результат. Claims never automatically replay. stop affects only owned client. cleanup checks ownership/source hashes, native-deletes metadata, FULL-updates base and independently proves absence by a generation-checked CF/XML snapshot compared with DBConfiguration using native Designer. Legacy hosts retain EPF verification. cleanup_external_project removes only the owned external project/files, without FULL update or claiming database absence; verifier EPF is preserved for pending metadata cleanup. cleanup may reuse verification_host_id: an already running native host on the exact same synchronized base, without FULL or a new verifier client. Edited sources are preserved. No business data rollback. prepare/start/cleanup return job_id; poll edt_operation_status. Artifacts stay in workspace metadata. execution_mode=batch accepts up to 16 explicit steps with code/context/parameters/caption/screenshot; runs in 1C over timer ticks, yields to paint before each capture, stops on the first failure and returns completed step results. No automatic replay after unknown outcome. Client BSL can call КомпонентаАгента.Screenshot() and parse its JSON manifest, collecting images inside one batch. action=snapshots returns verified paths/hashes associated with request_id (optional request_id filters one batch); images stay in owned host storage until cleanup, no base64 JSON. Native component Windows x64 only.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"required":["action"],"properties":{
          "action":{"type":"string","enum":["prepare","start","status","execute","result","stop","cleanup","cleanup_external_project","snapshots"]},
          "project":{"type":"string"},"mode":{"type":"string","enum":["external","metadata"]},
          "native_path":{"type":"string"},"expected_native_sha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},
          "host_id":{"type":"string"},"verification_host_id":{"type":"string"},"request_id":{"type":"string"},"idempotency_key":{"type":"string"},
          "execution_mode":{"type":"string","enum":["info","execute","query","external","batch"],"default":"execute"},
          "context":{"type":"string","enum":["server","client"],"default":"server"},
          "code":{"type":"string","maxLength":131072},"query":{"type":"string","maxLength":131072},
          "steps":{"type":"array","minItems":1,"maxItems":16,"items":{"type":"object","additionalProperties":false,"required":["code"],"properties":{"code":{"type":"string","maxLength":8192},"context":{"type":"string","enum":["client","server"],"default":"client"},"parameters":{"type":"object"},"caption":{"type":"string","maxLength":200},"screenshot":{"type":"boolean","default":false}}}},
          "parameters":{"type":"object"},"limit":{"type":"integer","minimum":1,"maximum":1000,"default":100},
          "artifact_path":{"type":"string"},"expected_artifact_sha256":{"type":"string"},"method":{"type":"string"}
        }}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{
        Map<String,Object> a=p.getRaw();String action=RuntimeExecutionPlan.required(a,"action");NativeHostService service=new NativeHostService();
        if(Set.of("prepare","start","cleanup","cleanup_external_project").contains(action)){
            String id=action.equals("prepare")?null:RuntimeExecutionPlan.required(a,"host_id");
            String job=BackgroundJobRegistry.getInstance().startJob("native-host-"+action,()->new Gson().toJson(switch(action){case "prepare"->service.prepare(a);case "start"->service.start(id);case "cleanup_external_project"->service.cleanupExternal(id);default->service.cleanup(id,(String)a.get("verification_host_id"));}));
            return ToolResult.success(new Gson().toJson(Map.of("job_id",job,"next_tool","edt_operation_status")));
        }
        String id=RuntimeExecutionPlan.required(a,"host_id");Map<String,Object> result=switch(action){
        case "snapshots"->service.snapshots(id,(String)a.get("request_id"));case "status"->service.status(id);case "execute"->service.execute(id,a);case "result"->service.result(id,RuntimeExecutionPlan.required(a,"request_id"));case "stop"->service.stop(id);default->throw new IllegalArgumentException("Unknown action");};
        return ToolResult.success(new Gson().toJson(result));
    }catch(Exception e){return ToolResult.failure("edt_runtime_host: "+e.getMessage());}});}
}
