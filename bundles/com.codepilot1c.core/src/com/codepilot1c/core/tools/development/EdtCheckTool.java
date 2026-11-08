// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.*;

@ToolMeta(name="edt_check",category="qa",mutating=true,tags={"workspace","edt"})
public final class EdtCheckTool extends AbstractTool {
    public String getDescription(){return "Short entry to the existing update/debug/Vanessa cycle. start needs project, idempotency_key and explicit update_db; uses a saved runner profile. No base provisioning or automatic data deletion. status needs run_id, returns compact report/checkpoint and <=20 new events; reuse event_cursor. cancel stops owned run only. Full details: edt_development_run_status. Continue visual review via edt_development_run_continue using fresh checkpoint/hash. Interrupted runs are never replayed.";}
    public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{
          "action":{"type":"string","enum":["start","status","cancel"]},
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
