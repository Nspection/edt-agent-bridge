// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;import java.time.Instant;
/** Bounded process counters only: no payloads, credentials or token estimates retained. */
public final class AgentMetrics {
 private static final Map<String,long[]> COUNTERS=new TreeMap<>();private static final String SINCE=Instant.now().toString();private AgentMetrics(){}
 public static synchronized void record(String tool,long requestBytes,long responseBytes,long ms,boolean failed){if(tool==null)return;if(!COUNTERS.containsKey(tool)&&COUNTERS.size()>=256)tool="other";var c=COUNTERS.computeIfAbsent(tool,k->new long[5]);c[0]++;c[1]+=requestBytes;c[2]+=responseBytes;c[3]+=ms;if(failed)c[4]++;}
 public static synchronized Map<String,Object> snapshot(){List<Map<String,Object>>rows=new ArrayList<>();long calls=0,request=0,response=0;for(var e:COUNTERS.entrySet()){long[]c=e.getValue();rows.add(Map.of("tool",e.getKey(),"calls",c[0],"request_bytes",c[1],"response_bytes",c[2],"duration_ms",c[3],"failures",c[4]));calls+=c[0];request+=c[1];response+=c[2];}return Map.of("since",SINCE,"scope","this_edt_process","calls",calls,"request_bytes",request,"response_bytes",response,"tools",rows,"token_usage_measured",false);}
}
