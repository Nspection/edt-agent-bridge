// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.*;

@ToolMeta(name="edt_check",category="qa",mutating=true,tags={"workspace","edt"})
public final class EdtCheckTool extends AbstractTool {
    public String getDescription(){return "Inspect changes without database writes: baseline captures current markers; changes returns changed Git files (or explicit files), objects, current errors and diagnostic delta if baseline_id supplied, optionally a correlated existing run_id. details pages a saved check_id. Missing Git/baseline and running builders remain explicit unknowns. These snapshots do not run tests. Individual tools remain available. Short entry to the existing update/debug/Vanessa cycle. start needs project, idempotency_key and explicit update_db; uses a saved runner profile. No base provisioning or automatic data deletion. status needs run_id, returns compact report/checkpoint and <=20 new events; reuse event_cursor. cancel stops owned run only. Full details: edt_development_run_status. Continue visual review via edt_development_run_continue using fresh checkpoint/hash. Interrupted runs are never replayed.";}
    public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{
          "action":{"type":"string","enum":["start","status","cancel","baseline","changes","details"]},
          "files":{"type":"array","maxItems":2000,"items":{"type":"string"}},"baseline_id":{"type":"string"},"check_id":{"type":"string"},"section":{"type":"string","enum":["files","diagnostics","diagnostics_added","diagnostics_removed"]},"offset":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":100},
          "project":{"type":"string"},"profile_id":{"type":"string"},
          "idempotency_key":{"type":"string"},"update_db":{"type":"boolean"},
          "feature_paths":{"type":"array","items":{"type":"string"},"minItems":1,"maxItems":100},
          "visual_checkpoint":{"type":"boolean","default":true},
          "timeout_s":{"type":"integer","minimum":30,"maximum":7200},
          "run_id":{"type":"string"},"after_cursor":{"type":"integer","minimum":0}},
          "required":["action"]}
        """;}
    protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{
        try {
            var a=new LinkedHashMap<>(p.getRaw());String action=p.requireString("action");
            Map<String,Object> result;
            if(Set.of("baseline","changes","details").contains(action)) {
                Set<String> allowed=new HashSet<>(Set.of("action","project"));allowed.addAll(action.equals("baseline")?Set.of("files"):action.equals("changes")?Set.of("files","baseline_id","run_id"):Set.of("check_id","section","offset","limit"));for(String k:a.keySet())if(!allowed.contains(k))throw new IllegalArgumentException(action+" does not accept "+k);
                var service=ChangeCheckService.workspace();String project=p.requireString("project");List<String> files=null;if(a.containsKey("files")){if(!(a.get("files") instanceof List<?> list))throw new IllegalArgumentException("files must be an array");files=new ArrayList<>();for(Object x:list){if(!(x instanceof String text))throw new IllegalArgumentException("file paths must be strings");files.add(text);}}
                result=switch(action){case "baseline"->service.baseline(project,files);case "changes"->service.changes(project,files,(String)a.get("baseline_id"),(String)a.get("run_id"));default->service.details(project,p.requireString("check_id"),(String)a.getOrDefault("section","diagnostics"),a.containsKey("offset")?Math.toIntExact(p.requireLong("offset")):0,a.containsKey("limit")?Math.toIntExact(p.requireLong("limit")):50);};return ToolResult.success(new Gson().toJson(result));
            }
            for(String key:a.keySet())if(!Set.of("action","project","profile_id","idempotency_key","update_db","feature_paths","visual_checkpoint","timeout_s","run_id","after_cursor").contains(key))throw new IllegalArgumentException(action+" does not accept "+key);
            switch(action) {
                case "start":
                    p.requireString("project");p.requireString("idempotency_key");
                    if(!(a.get("update_db") instanceof Boolean))throw new IllegalArgumentException("start requires explicit update_db");
                    if(a.containsKey("run_id") || a.containsKey("after_cursor"))throw new IllegalArgumentException("start does not accept run_id/after_cursor");
                    a.remove("action");a.putIfAbsent("profile_id","default");
                    result=DevelopmentRunService.getInstance().start(RunnerProfileStore.workspace().resolve(a));break;
                case "status": case "cancel":
                    for(String key:a.keySet())if(!Set.of("action","run_id","after_cursor").contains(key))throw new IllegalArgumentException(action+" does not accept "+key);
                    String id=p.requireString("run_id");
                    long cursor=a.containsKey("after_cursor")?p.requireLong("after_cursor"):0;
                    if(cursor<0)throw new IllegalArgumentException("after_cursor must be nonnegative");
                    if(action.equals("cancel")) {
                        if(a.containsKey("after_cursor"))throw new IllegalArgumentException("cancel does not accept after_cursor");
                        result=DevelopmentRunService.getInstance().cancel(id);
                    } else result=DevelopmentRunService.getInstance().status(id,cursor);
                    return ToolResult.success(new Gson().toJson(AgentBrief.run(result,cursor)));
                default: throw new IllegalArgumentException("Unknown action");
            }
            return ToolResult.success(new Gson().toJson(AgentBrief.run(result,0)));
        } catch(Exception e){return ToolResult.failure("edt_check: "+e.getMessage());}
    });}
}
