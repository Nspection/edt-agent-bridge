// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;import java.util.function.LongSupplier;import java.util.concurrent.TimeUnit;
/** Read-only UI startup observations in the SAME process; no alternate-client fallback. */
public final class DirectClientStartup {
 private DirectClientStartup(){}
 public interface Probe{Map<String,Object> read(int timeoutMs)throws Exception;}
 public interface Verify{void check()throws Exception;}
 public static int timeout(Map<String,Object>a){if(!a.containsKey("readiness_timeout_s"))return 90;try{int n=new java.math.BigDecimal(a.get("readiness_timeout_s").toString()).intValueExact();if(n<10||n>180)throw new ArithmeticException();return n;}catch(Exception e){throw new IllegalArgumentException("readiness_timeout_s must be integer10..180");}}
 public static String kind(Map<String,Object>a){Object k=a.getOrDefault("client_kind","thin");if(!Set.of("thin","thick").contains(k))throw new IllegalArgumentException("client_kind must be thin/thick");return (String)k;}
 public static Map<String,Object> await(Probe probe,Verify verify,int seconds,LongSupplier clock)throws Exception{
  long begin=clock.getAsLong(),deadline=begin+TimeUnit.SECONDS.toNanos(seconds);int attempts=0;String last="not_observed";
  while(clock.getAsLong()<deadline){verify.check();long left=TimeUnit.NANOSECONDS.toMillis(deadline-clock.getAsLong());if(left<=0)break;
   var result=probe.read((int)Math.min(180000,Math.max(1,left)));attempts++;last=Objects.toString(result.get("state"),"unknown");
   if(Boolean.TRUE.equals(result.get("success"))){verify.check();return Map.of("ready",true,"state","ready","observations",attempts,"elapsed_ms",TimeUnit.NANOSECONDS.toMillis(clock.getAsLong()-begin));}
   // Only a known read-only condition timeout preserves the pipe and may be observed again.
   if(!"condition_timeout".equals(last)||!Boolean.FALSE.equals(result.get("outcome_unknown"))||!Boolean.FALSE.equals(result.get("action_dispatched")))return Map.of("ready",false,"state","startup_transport_unavailable","observations",attempts,"last_state",last,"reason",Objects.toString(result.get("reason"),"unavailable").replaceAll("[^A-Za-z0-9_]",""),"retry_safe",false);
   Thread.sleep(50);
  }
  return Map.of("ready",false,"state","startup_ui_timeout","observations",attempts,"last_state",last,"readiness_timeout_s",seconds,"retry_safe",false,"execution_state","not_proven","ui_mutation_dispatched",false);
 }
}
