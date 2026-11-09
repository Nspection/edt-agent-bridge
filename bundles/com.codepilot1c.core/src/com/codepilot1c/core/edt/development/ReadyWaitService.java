// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;import java.util.concurrent.TimeUnit;
/** Read-only bounded readiness. A timeout or unknown section never implies readiness. */
public final class ReadyWaitService {
 @FunctionalInterface public interface Probe {Map<String,Object> read()throws Exception;}
 private ReadyWaitService(){}
 public static Map<String,Object> await(Probe probe,int timeout)throws Exception {
  if(timeout<0||timeout>25000)throw new IllegalArgumentException("timeout_ms must be 0..25000");long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);Map<String,Object> out;
  do {out=new LinkedHashMap<>(probe.read());if(Boolean.TRUE.equals(out.get("ready")))break;long left=deadline-System.nanoTime();if(left<=0)break;TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(250),left));}while(true);
  out.put("timed_out",!Boolean.TRUE.equals(out.get("ready")));out.put("observation_only",true);return out;
 }
 public static Map<String,Object> evaluate(Map<String,Object> observed,Collection<String> available,List<String> required,String database,boolean runner){
  List<String> blockers=new ArrayList<>();Map<String,Object> out=new LinkedHashMap<>();out.putAll(observed);
  if(!"ready".equals(observed.get("services")))blockers.add("services_not_ready");
  var missing=required.stream().filter(n->!available.contains(n)).toList();if(!missing.isEmpty()){blockers.add("required_tools_missing");out.put("missing_tools",missing);}
  if(observed.containsKey("project")){
   if(!"READY".equals(TaskSessionService.object(observed.get("model")).get("state")))blockers.add("model_not_ready");
   var db=TaskSessionService.object(observed.get("database"));boolean ok=switch(database){case "none"->true;case "bound"->db.get("uuid") instanceof String;case "connected"->Boolean.TRUE.equals(db.get("connected_verified"));case "equal"->Boolean.TRUE.equals(db.get("configuration_synchronized"))&&"EQUAL".equals(db.get("equality"));default->throw new IllegalArgumentException("Invalid database readiness requirement");};if(!ok)blockers.add("database_"+database+"_not_verified");
   if(runner&&!Boolean.TRUE.equals(TaskSessionService.object(observed.get("runner")).get("configured")))blockers.add("runner_not_configured");
  }else if(!"none".equals(database)||runner)throw new IllegalArgumentException("project required for database/runner readiness");
  out.put("ready",blockers.isEmpty());out.put("state",blockers.isEmpty()?"ready":"blocked");out.put("blockers",blockers);return out;
 }
}
