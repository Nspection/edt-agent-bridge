// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;import java.time.Instant;
/** Bounded process counters only: no payloads, credentials or token estimates retained. */
public final class AgentMetrics {
 private static final Map<String,long[]> COUNTERS=new TreeMap<>();private static final String SINCE=Instant.now().toString();private AgentMetrics(){}
 /** Classify compact calls by registered canonical name; never retain arbitrary op/args text. */
 public static String operationName(String name,Map<String,Object> arguments,java.util.function.Predicate<String> registered){
  if("edt.help".equals(name)){Object action=arguments.get("action");return action instanceof String s&&Set.of("contract","operations","catalog").contains(s)?"edt.help."+s:"edt.help";}
  if(com.codepilot1c.core.mcp.host.McpCompactSurface.isFacade(name)){
   Object raw=arguments.get("op");if(raw==null){if("edt.status".equals(name)&&registered.test("edt_bridge_status"))return "edt_bridge_status";return name+".operations";}if("edt.run".equals(name)&&"route".equals(raw))return "edt.run.route";
   if("edt.run".equals(name)&&"ui".equals(raw))raw="edt_ui_run";else if("edt.run".equals(name)&&"prepare".equals(raw))raw="edt_runtime_prepare";
   return raw instanceof String op&&registered.test(op)?op:"unresolved";
  }
  return name!=null&&registered.test(name)?name:"unresolved";
 }
 public static synchronized void record(String tool,long requestBytes,long responseBytes,long ms,boolean failed){if(tool==null)return;if(!COUNTERS.containsKey(tool)&&COUNTERS.size()>=256)tool="other";var c=COUNTERS.computeIfAbsent(tool,k->new long[5]);c[0]++;c[1]+=requestBytes;c[2]+=responseBytes;c[3]+=ms;if(failed)c[4]++;}
 private record Baseline(Map<String,Object> counters,Instant at){}
 private static final Map<String,Baseline> BASELINES=new LinkedHashMap<>();
 private static void pruneBaselines(){Instant cutoff=Instant.now().minusSeconds(3600);BASELINES.entrySet().removeIf(e->e.getValue().at().isBefore(cutoff));}
 public static synchronized Map<String,Object> baseline(){
  pruneBaselines();while(BASELINES.size()>=16)BASELINES.remove(BASELINES.keySet().iterator().next());var counters=snapshot();String id="metrics-"+UUID.randomUUID();BASELINES.put(id,new Baseline(counters,Instant.now()));
  var out=AgentBrief.select(counters,"since","scope","calls","request_bytes","response_bytes","token_usage_measured");out.put("baseline_id",id);out.put("observation_only",true);return out;
 }
 public static synchronized Map<String,Object> changes(String id){
  pruneBaselines();var baseline=BASELINES.get(id);if(baseline==null)throw new IllegalArgumentException("Metrics baseline expired or unavailable after restart; take a new baseline");
  var before=new HashMap<String,Map<?,?>>();for(Object item:(List<?>)baseline.counters().get("tools")){var row=(Map<?,?>)item;before.put(row.get("tool").toString(),row);}
  var rows=new ArrayList<Map<String,Object>>();long calls=0,request=0,response=0,time=0,failures=0;
  for(Object item:(List<?>)snapshot().get("tools")){
   var row=(Map<?,?>)item;var old=before.getOrDefault(row.get("tool"),Map.of());var diff=new LinkedHashMap<String,Object>();diff.put("tool",row.get("tool"));boolean changed=false;
   for(String key:List.of("calls","request_bytes","response_bytes","duration_ms","failures")){long value=((Number)row.get(key)).longValue()-(old.get(key) instanceof Number n?n.longValue():0);if(value<0)throw new IllegalStateException("Metrics counters changed; take a new baseline");diff.put(key,value);changed|=value!=0;}
   if(changed){rows.add(diff);calls+=((Number)diff.get("calls")).longValue();request+=((Number)diff.get("request_bytes")).longValue();response+=((Number)diff.get("response_bytes")).longValue();time+=((Number)diff.get("duration_ms")).longValue();failures+=((Number)diff.get("failures")).longValue();}
  }
  var out=new LinkedHashMap<String,Object>();out.put("baseline_id",id);out.put("since",SINCE);out.put("scope","this_edt_process_since_baseline");out.put("calls",calls);out.put("request_bytes",request);out.put("response_bytes",response);out.put("tool_duration_ms",time);out.put("failures",failures);out.put("tools",rows);out.put("token_usage_measured",false);out.put("observation_only",true);return out;
 }
 public static synchronized Map<String,Object> snapshot(){List<Map<String,Object>>rows=new ArrayList<>();long calls=0,request=0,response=0;for(var e:COUNTERS.entrySet()){long[]c=e.getValue();rows.add(Map.of("tool",e.getKey(),"calls",c[0],"request_bytes",c[1],"response_bytes",c[2],"duration_ms",c[3],"failures",c[4]));calls+=c[0];request+=c[1];response+=c[2];}return Map.of("since",SINCE,"scope","this_edt_process","calls",calls,"request_bytes",request,"response_bytes",response,"tools",rows,"token_usage_measured",false);}
}
