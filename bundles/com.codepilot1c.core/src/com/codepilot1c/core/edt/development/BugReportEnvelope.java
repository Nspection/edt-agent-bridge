// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
/** Public diagnostics are a typed allowlist. No arbitrary text is ever copied. */
public final class BugReportEnvelope {
 private static final Map<String,Set<String>> CODES=Map.of(
  "report_kind",Set.of("bug","acceptance_test"),
  "failure_kind",Set.of("local_budget_expired","validation_rejected","connection_or_protocol_failure","wire_timeout","protocol_rejected","wire_disconnected","transport_error","operation_error","readiness_timeout","unknown"),
  "transport_state",Set.of("usable","broken","disconnected","unknown"),
  "execution_state",Set.of("completed_or_failed","not_sent","not_dispatched","not_proven","partial_completed","completed","failed","unknown","dispatched"),
  "last_rpc_kind",Set.of("action","commit","read","read818","observe","handshake","query","unknown"),
  "last_rpc_phase",Set.of("incomplete_exchange","reply_rejected","not_sent","reply_complete","request_not_sent","reply_received","complete","unknown"));
 private static final Set<String> BOOL=Set.of("outcome_unknown","mutation_blocked","action_dispatched","protocol_connected");
 private static final Set<String> NUMBERS=Set.of("pending_replies","failed_step_index","status_code","elapsed_ms","operation_timeout_s");
 private static final Set<String> CONTAINERS=Set.of("diagnostics","last_failure","result","transport_diagnostics","data","protocol_rejection");
 private BugReportEnvelope(){}
 public static Map<String,Object> collect(String component,String version,Map<String,Object> input,boolean verified){
  if(!Set.of("testclient","native","editor","mcp").contains(component))throw new IllegalArgumentException("Unsupported report component");
  var out=new TreeMap<String,Object>();out.put("schema",1);out.put("component",component);
  out.put("bridge_version",version!=null&&version.matches("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}(?:\\.[A-Za-z0-9]{1,40})?")?version:"unknown");
  out.put("source",verified?"verified_local_operation_journal":"agent_reported");collectInto(input,out,0);return Collections.unmodifiableMap(out);
 }
 private static void collectInto(Map<?,?> input,Map<String,Object> out,int depth){
  if(depth>4)return;
  for(var entry:input.entrySet()){
   if(!(entry.getKey() instanceof String key))continue;Object value=entry.getValue();
   if(CODES.containsKey(key)&&value instanceof String code&&CODES.get(key).contains(code))out.put(key,code);
   else if(BOOL.contains(key)&&value instanceof Boolean)out.put(key,value);
   else if(NUMBERS.contains(key)&&value instanceof Number number){try{var n=new java.math.BigDecimal(number.toString());long v=n.longValueExact();if(v>=0&&v<=86400000)out.put(key,v);}catch(ArithmeticException|NumberFormatException ignored){}}
   else if(CONTAINERS.contains(key)&&value instanceof Map<?,?> nested)collectInto(nested,out,depth+1);
  }
 }
}
