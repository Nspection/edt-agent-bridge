// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.codepilot1c.core.edt.development.*;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
@ToolMeta(name="edt_runtime_host",category="qa",mutating=true,tags={"workspace","edt","sensitive"})
public final class EdtRuntimeHostTool extends AbstractTool {
    private static final Gson RESULT_JSON=new GsonBuilder().serializeNulls().create();
    @Override public String getDescription(){return "Native API BSL host without VAExtension. prepare mode=external creates an owned EDT external EPF project. mode=metadata creates only an internal temporary DataProcessor; no external project or EPF. Requires associated permitted test base. Omit both native_path/expected_native_sha256 to use the pinned, automatically downloaded and SHA256-verified bundled Native package; explicit custom path/hash remains supported. package_status/prepare_package/configure_package manage only the shared package cache without a host_id, launching 1C, updating DB or changing trust; automatic_download defaults true. First metadata start FULL-updates the whole project into the base; an unchanged previously loaded host with current native EQUAL skips updating. external mode never updates it. Own Enterprise client loads EPF/native component; platform trust may require local preparation. execute returns request_id; poll result. context client/server; execution_mode info/execute/query/external; parameters becomes ПараметрыВызова, assign Результат. Claims never automatically replay. stop affects only owned client. cleanup checks ownership/source hashes, native-deletes metadata, FULL-updates base and independently proves absence by a generation-checked CF/XML snapshot compared with DBConfiguration using native Designer. Legacy hosts retain EPF verification. cleanup_external_project removes only the owned external project/files, without FULL update or claiming database absence; verifier EPF is preserved for pending metadata cleanup. cleanup may reuse verification_host_id: an already running native host on the exact same synchronized base, without FULL or a new verifier client. Edited sources are preserved. No business data rollback. prepare/start/cleanup return job_id; poll edt_operation_status. Artifacts stay in workspace metadata. execution_mode=batch accepts up to 16 explicit steps with code/context/parameters/caption/screenshot; runs in 1C over timer ticks, yields to paint before each capture, stops on the first failure and returns completed step results. No automatic replay after unknown outcome. Client BSL can call КомпонентаАгента.Screenshot() and parse its JSON manifest, collecting images inside one batch. action=snapshots returns verified paths/hashes associated with request_id (optional request_id filters one batch); images stay in owned host storage until cleanup, no base64 JSON. With action=snapshots, grid=true adds an alignment.image_path PNG with rulers and grid_step(default20px,8..500); image_path/sha256 remain original. Annotation does not recapture or execute 1C. Partial annotation returns original images with alignment_error and alignment_all_succeeded=false; never replay BSL for this. Coordinates are original-image pixels, origin32px in annotated PNG. Batch kind=wait repeats explicitly side-effect-free BSL returning Boolean until true, timeout_ms default60000 (1000..300000), interval_ms default1000 (1000..10000); this budget cannot preempt synchronous BSL/RPC. kind=assert evaluates Boolean once; false or non-Boolean stops dependent steps. kind=read executes caller-supplied read-only BSL and select projects up to32 dot paths from its JSON value (array indexes zero-based); it is not automatic discovery of arbitrary open forms. screenshot_on_failure captures on the next timer tick before stopping. result includes durable progress: phase, sequence, append-only step receipts, also after disconnect; progress is not terminal success. Completed values above16384 JSON characters are explicitly omitted; use a narrow read, never replay mutations. New controls require host_features batch_controls_v1; old hosts retain plain batches. Native binary/protocol unchanged. Native component Windows x64 only.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"required":["action"],"properties":{
          "action":{"type":"string","enum":["prepare","start","status","execute","result","stop","cleanup","cleanup_external_project","snapshots","package_status","prepare_package","configure_package"]},
          "project":{"type":"string"},"mode":{"type":"string","enum":["external","metadata"]},
          "native_path":{"type":"string"},"expected_native_sha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},
          "automatic_download":{"type":"boolean"},"host_id":{"type":"string"},"verification_host_id":{"type":"string"},"request_id":{"type":"string"},"idempotency_key":{"type":"string"},
          "execution_mode":{"type":"string","enum":["info","execute","query","external","batch"],"default":"execute"},
          "context":{"type":"string","enum":["server","client"],"default":"server"},
          "code":{"type":"string","maxLength":131072},"query":{"type":"string","maxLength":131072},
          "steps":{"type":"array","minItems":1,"maxItems":16,"items":{"type":"object","additionalProperties":false,"required":["code"],"properties":{"code":{"type":"string","maxLength":8192},"context":{"type":"string","enum":["client","server"],"default":"client"},"parameters":{"type":"object"},"caption":{"type":"string","maxLength":200},"screenshot":{"type":"boolean","default":false},"kind":{"type":"string","enum":["execute","wait","assert","read"],"default":"execute"},"timeout_ms":{"type":"integer","minimum":1000,"maximum":300000,"default":60000},"interval_ms":{"type":"integer","minimum":1000,"maximum":10000,"default":1000},"select":{"type":"array","maxItems":32,"uniqueItems":true,"items":{"type":"string","maxLength":200}},"screenshot_on_failure":{"type":"boolean","default":false}}}},
          "grid":{"type":"boolean","default":false},"grid_step":{"type":"integer","minimum":8,"maximum":500,"default":20},
          "parameters":{"type":"object"},"limit":{"type":"integer","minimum":1,"maximum":1000,"default":100},
          "artifact_path":{"type":"string"},"expected_artifact_sha256":{"type":"string"},"method":{"type":"string"}
        }}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{
        Map<String,Object> a=p.getRaw();String action=RuntimeExecutionPlan.required(a,"action");
        if(Set.of("package_status","prepare_package","configure_package").contains(action)){
            if(!(action.equals("configure_package")?Set.of("action","automatic_download"):Set.of("action")).containsAll(a.keySet()))throw new IllegalArgumentException("Field does not apply to Native package action");var packages=NativePackageManager.getInstance();Object answer;
            if(action.equals("configure_package")){if(!(a.get("automatic_download") instanceof Boolean enabled))throw new IllegalArgumentException("automatic_download must be boolean");answer=packages.configure(enabled);}else answer=action.equals("package_status")?packages.status():packages.prepare();
            return ToolResult.success(RESULT_JSON.toJson(answer));
        }
        if(a.containsKey("grid")||a.containsKey("grid_step")){com.codepilot1c.core.edt.editor.SnapshotAlignment.options(a);if(!action.equals("snapshots"))throw new IllegalArgumentException("grid/grid_step require action=snapshots");}NativeHostService service=new NativeHostService();
        if(Set.of("prepare","start","cleanup","cleanup_external_project").contains(action)){
            String id=action.equals("prepare")?null:RuntimeExecutionPlan.required(a,"host_id");
            String job=BackgroundJobRegistry.getInstance().startJob("native-host-"+action,()->RESULT_JSON.toJson(switch(action){case "prepare"->service.prepare(a);case "start"->service.start(id);case "cleanup_external_project"->service.cleanupExternal(id);default->service.cleanup(id,(String)a.get("verification_host_id"));}));
            return ToolResult.success(RESULT_JSON.toJson(Map.of("job_id",job,"next_tool","edt_operation_status")));
        }
        String id=RuntimeExecutionPlan.required(a,"host_id");Map<String,Object> result=switch(action){
        case "snapshots"->service.snapshots(id,(String)a.get("request_id"),a);case "status"->service.status(id);case "execute"->service.execute(id,a);case "result"->service.result(id,RuntimeExecutionPlan.required(a,"request_id"));case "stop"->service.stop(id);default->throw new IllegalArgumentException("Unknown action");};
        return ToolResult.success(RESULT_JSON.toJson(result));
    }catch(Exception e){return ToolResult.failure("edt_runtime_host: "+e.getMessage());}});}
}
