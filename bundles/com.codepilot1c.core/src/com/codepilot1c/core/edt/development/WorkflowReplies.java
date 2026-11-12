// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
/** Agent-facing projections and observation-only continuations. Never manufacture a mutation. */
public final class WorkflowReplies {
 private WorkflowReplies(){}
 public static Map<String,Object> call(String op,String action,String key,Object id){return Map.of("tool","edt.run","arguments",Map.of("op",op,"args",Map.of("action",action,key,id)));}
 public static Map<String,Object> job(String id){return Map.of("tool","edt.task","arguments",Map.of("op","edt_operation_status","args",Map.of("job_id",id,"timeout_ms",20000,"compact",true)));}
 private static Map<String,Object> waitCall(String op,String key,Object id){return Map.of("tool","edt.run","arguments",Map.of("op",op,"args",Map.of("action","wait",key,id,"timeout_ms",20000)));}
 private static Map<String,Object> pick(Map<String,Object> value,String...keys){return AgentBrief.select(value,keys);}
 @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return value instanceof Map<?,?>?(Map<String,Object>)value:Map.of();}
 public static Map<String,Object> ui(Map<String,Object> source){
  var out=pick(source,"ui_run_id","project","base_uuid","preparation_id","client_id","session_id","job_id","state","stage","terminal","test_passed","cleanup_verified","outcome_unknown","execution_state","failed_stage","reason","error_type","total_steps","reuse_from_ui_run_id","client_reused","client_retained","session_closed","capture_error_type","cleanup_error_type","artifacts","elapsed_ms","historical_result","automatic_replay","timed_out","live_progress_available","decision","resume_call","preparation_call");
  if(source.get("summary") instanceof Map<?,?>)out.put("summary",pick(object(source.get("summary")),"success","postcondition_verified","state","failed_step_index","finished_steps","passed_steps","verified_steps","total_steps","task_collection_verified","task_collection_error_type"));
  if(source.get("diagnostic") instanceof Map<?,?>)out.put("diagnostic",pick(object(source.get("diagnostic")),"code","stage","automatic_replay","executor_switch_allowed"));
  if(source.get("startup_progress") instanceof Map<?,?>)out.put("startup_progress",pick(object(source.get("startup_progress")),"startup_phase","startup_error_type"));
  if(source.get("preparation_progress") instanceof Map<?,?>)out.put("preparation_progress",pick(object(source.get("preparation_progress")),"stage","state","engine","ready","reason"));
  Object id=source.get("ui_run_id");if(id==null)return out;
  var details=call("ui","details","ui_run_id",id);out.put("details_call",details);
  if(Set.of("awaiting_consent","awaiting_runtime").contains(Objects.toString(source.get("state"),""))){if(source.get("preparation_call") instanceof Map<?,?>)out.put("next_call",source.get("preparation_call"));return out;}
  if(Boolean.TRUE.equals(source.get("outcome_unknown")))out.put("next_call",details);
  else if(!Boolean.TRUE.equals(source.get("terminal")))out.put("next_call",waitCall("ui","ui_run_id",id));
  else if(!Boolean.TRUE.equals(source.get("test_passed")))out.put("next_call",details);
  else if(!Boolean.TRUE.equals(source.get("cleanup_verified"))&&!Boolean.TRUE.equals(source.get("client_retained")))out.put("next_call",call("ui","cleanup_preview","ui_run_id",id));
  return out;
 }
 public static Map<String,Object> preparation(Map<String,Object> source){
  var out=pick(source,"preparation_id","project","base_uuid","engine","job_id","state","stage","reason","ready","terminal","resources","elapsed_ms","slow_preparation","slowest_stage","slow_threshold_ms","configuration_updated","trust_changed","timed_out","decision","resume_call");
  if(source.get("failure") instanceof Map<?,?>)out.put("failure",pick(object(source.get("failure")),"state","phase","reason","code","execution_state","retry_safe","outcome_unknown","host_allocated","host_resources_created","external_project_created","process_started","database_updated","automatic_retry"));
  out.put("automatic_replay",false);
  if("needs_runner_setup".equals(source.get("state"))&&!out.containsKey("reason")&&object(source.get("runtime")).get("reason") instanceof String reason)out.put("reason",reason);
  if(source.get("runtime") instanceof Map<?,?>&&Set.of("ready","awaiting_runtime").contains(source.getOrDefault("state","unknown")))out.put("runtime",pick(object(source.get("runtime")),"ready","state","client_id","host_id","reason","outcome_unknown","connected","transport","startup_phase","borrowed","next_call"));
  Object id=source.get("preparation_id");if(id==null)return out;
  var details=call("prepare","details","preparation_id",id);out.put("details_call",details);
  if("awaiting_consent".equals(source.get("state"))){out.remove("next_call");return out;}
  if("outcome_unknown".equals(source.get("state")))out.put("next_call",details);
  else if("awaiting_runtime".equals(source.get("state"))&&object(source.get("runtime")).containsKey("next_call"))out.put("next_call",object(source.get("runtime")).get("next_call"));
  else if(!Boolean.TRUE.equals(source.get("terminal")))out.put("next_call",waitCall("prepare","preparation_id",id));
  else if(!Boolean.TRUE.equals(source.get("ready"))&&!"cleaned".equals(source.get("state")))out.put("next_call",details);
  return out;
 }
 public static Map<String,Object> waitFor(String job,int timeout,BooleanSupplier active,Callable<Map<String,Object>> observe)throws Exception {
  AgentResponses.integer(Map.of("timeout_ms",timeout),"timeout_ms",20000,0,25000);long end=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeout);
  try {
   if(active.getAsBoolean()&&job!=null)BackgroundJobRegistry.getInstance().awaitStatus(job,timeout);
   Map<String,Object> out;
   while(true){out=observe.call();if(Boolean.TRUE.equals(out.get("terminal"))||System.nanoTime()>=end)break;long remaining=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(end-System.nanoTime());if(remaining>0)Thread.sleep(Math.min(1000,remaining));}
   out=new LinkedHashMap<>(out);out.put("timed_out",!Boolean.TRUE.equals(out.get("terminal")));return out;
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}
 }
}
