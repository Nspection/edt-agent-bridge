// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
/** Transport health and operation outcome are independent; never infer delivery from a timeout. */
public final class DirectClientTransport {
 private DirectClientTransport(){}
 public static final class WorkerFailure extends IllegalStateException {
  final boolean mayHaveReachedWorker;final Map<String,Object> diagnostics;
  WorkerFailure(boolean sent,Map<String,Object> diagnostics,Throwable cause){super("Owned TestClient worker unavailable; inspect original operation",cause);this.mayHaveReachedWorker=sent;this.diagnostics=Map.copyOf(diagnostics);}
 }
 static void validatePayload(Map<String,Object> request){if(AgentResponses.JSON.toJson(request).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>128*1024)throw new IllegalArgumentException("Direct request exceeds limit; no worker request sent");}
 static boolean shouldClose(Map<String,Object> result){Object health=result.get("transport_state");if(health!=null)return "broken".equals(health);return Set.of("outcome_unknown","transport_failed").contains(result.getOrDefault("state",""));}
 static Map<String,Object> failure(Exception error,boolean mutation){
  boolean mayHaveReached=!(error instanceof WorkerFailure f)||f.mayHaveReachedWorker;boolean unknown=mutation&&mayHaveReached;var out=new LinkedHashMap<String,Object>();out.put("success",false);out.put("state",unknown?"outcome_unknown":"transport_failed");out.put("outcome_unknown",unknown);if(!unknown)out.put("action_dispatched",false);out.put("execution_state",unknown?"not_proven":"not_dispatched");out.put("action_dispatch_state",unknown?"not_proven":"not_dispatched");out.put("transport_state","broken");out.put("retry_safe",false);out.put("message","Inspect original operation and diagnostics; no action was automatically replayed");out.put("diagnostics",error instanceof WorkerFailure f?f.diagnostics:Map.of("failure_kind","worker_call_failed","error_type",error.getClass().getSimpleName()));return out;
 }
}
