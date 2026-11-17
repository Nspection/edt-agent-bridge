// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
import java.util.concurrent.Callable;

/** Commit the proven primary outcome before optional observation, never downgrade it for a screenshot. */
public final class DirectActionCompletion {
 private DirectActionCompletion() {}
 public static Map<String,Object> finish(DirectClientJournal journal,String operation,Map<String,Object> primary,Callable<Map<String,Object>> observation)throws Exception {
  var result=new LinkedHashMap<>(primary);
  boolean capture=observation!=null&&Boolean.TRUE.equals(primary.get("success"))&&!Boolean.TRUE.equals(primary.get("outcome_unknown"));
  if(capture)result.put("native_capture",Map.of("success",false,"state","pending","observation_only",true));
  var record=journal.read(operation);record.put("result",result);record.put("state",Boolean.TRUE.equals(primary.get("outcome_unknown"))?"outcome_unknown":Boolean.TRUE.equals(primary.get("success"))?"completed":"failed");journal.write(operation,record);
  if(!capture)return result;
  Map<String,Object> proof;
  try{proof=observation.call();}catch(Exception error){if(error instanceof InterruptedException)Thread.currentThread().interrupt();proof=Map.of("success",false,"reason","optional_observation_failed","error_type",error.getClass().getSimpleName(),"observation_only",true);}
  result.put("native_capture",proof);
  try {
   // Read/append only: cleanup may already have intentionally removed the journal.
   var latest=journal.read(operation);
   if("completed".equals(latest.get("state"))&&latest.get("result") instanceof Map<?,?> stored){
    var appended=new LinkedHashMap<String,Object>();stored.forEach((k,v)->appended.put(String.valueOf(k),v));appended.put("native_capture",proof);latest.put("result",appended);journal.write(operation,latest);
   }
  }catch(Exception noLongerAvailable){/* Primary receipt was committed first; do not recreate a removed journal. */}
  return result;
 }
}
