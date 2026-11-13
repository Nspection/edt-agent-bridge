// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
import java.util.concurrent.Callable;
/** Inspection may observe a completed startup; answering remains strictly startup-only. */
final class NativeStartupInspection {
 static final class PhaseEnded extends IllegalStateException{PhaseEnded(){super("Bootstrap is startup-only");}}
 static Map<String,Object> inspect(String host,Callable<Map<String,Object>> status,Callable<Map<String,Object>> inspect)throws Exception{
  var current=status.call();if(!"starting".equals(current.get("state")))return observed(host,current);
  try{return inspect.call();}catch(PhaseEnded changed){return observed(host,status.call());}
 }
 private static Map<String,Object> observed(String host,Map<String,Object> status){var out=new LinkedHashMap<>(status);out.put("startup_inspection","not_required");out.put("startup_inspection_performed",false);out.put("startup_input_dispatched",false);
  if("starting".equals(status.get("state"))){out.put("startup_inspection","phase_changed");out.put("next_call",RuntimeConsentPolicy.call("edt_runtime_host",Map.of("action","status","host_id",host)));}return out;
 }
}
