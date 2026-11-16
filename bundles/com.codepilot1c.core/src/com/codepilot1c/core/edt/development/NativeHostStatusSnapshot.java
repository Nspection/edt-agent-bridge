// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
import com.google.gson.*;
/** A busy host is observed from its atomic ownership receipt, never from BM, source hashes or admission. */
final class NativeHostStatusSnapshot {
    static Map<String,Object> observe(NativeHostLock lock,java.util.concurrent.Callable<JsonObject> record,java.util.concurrent.Callable<Map<String,Object>> fresh)throws Exception {
        try(var held=lock.tryHold()){return held==null?busy(record.call()):fresh.call();}
    }
    static Map<String,Object> busy(JsonObject state){
        var out=new LinkedHashMap<String,Object>();
        for(String key:List.of("host_id","project","mode","presentation","name","state","launch_strategy","loaded_into_base","source_removed","database_verified_absent","cleanup_in_progress","cleaned","database_removal_synchronized","database_update_skipped"))
            if(state.has(key))out.put(key,AgentResponses.JSON.fromJson(state.get(key),Object.class));
        out.put("status_scope","atomic_lifecycle_snapshot");out.put("lifecycle_busy",true);out.put("readiness_verified",false);out.put("execution_allowed",false);
        out.put("execution_blockers",List.of("host_lifecycle_busy"));out.put("foreground_fallback_allowed",false);
        return out;
    }
}
