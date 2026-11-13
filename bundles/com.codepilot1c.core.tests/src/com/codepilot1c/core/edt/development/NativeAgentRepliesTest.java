// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;
public class NativeAgentRepliesTest {
 private Map<String,Object> raw(String state,Object... rows){var r=new LinkedHashMap<String,Object>();r.put("request_id","req");r.put("state",state);r.put("terminal",Set.of("completed","failed","outcome_unknown").contains(state));r.put("outcome_unknown",state.equals("outcome_unknown"));r.put("progress",Map.of("phase","waiting","sequence",1,"value",List.of(rows)));return r;}
 private Map<String,Object> step(int n,boolean success){return Map.of("step",n,"success",success,"value","large-secret".repeat(1000));}
 @SuppressWarnings("unchecked") private String action(Map<String,Object> call){return ((Map<String,Object>)((Map<String,Object>)call.get("arguments")).get("args")).get("action").toString();}
 @Test public void pagesNeverRepeatAndFullValuesRemainOutOfSummary()throws Exception{
  var raw=raw("delivered",step(1,true),step(2,true),step(3,true));var a=NativeAgentReplies.view("host",raw,List.of(),null,2);assertEquals(2,((List<?>)a.get("new_steps")).size());assertEquals(true,a.get("has_more"));assertEquals("result",action((Map)a.get("next_call")));assertFalse(AgentResponses.JSON.toJson(a).contains("large-secret"));
  var b=NativeAgentReplies.view("host",raw,List.of(),a.get("cursor").toString(),2);assertEquals(1,((List<?>)b.get("new_steps")).size());var c=NativeAgentReplies.view("host",raw,List.of(),b.get("cursor").toString(),2);assertEquals(false,c.get("changed"));assertTrue(((List<?>)c.get("new_steps")).isEmpty());assertEquals("wait",action((Map)c.get("next_call")));
 }
 @Test public void cursorRejectsOtherHostRequestAndChangedAcknowledgedPrefix()throws Exception{
  var r=raw("delivered",step(1,true));String c=NativeAgentReplies.view("a",r,List.of(),null,8).get("cursor").toString();
  for(var candidate:List.of(raw("delivered",step(1,false)),new LinkedHashMap<>(r))) {if(candidate.get("progress").equals(r.get("progress")))candidate.put("request_id","other");try{NativeAgentReplies.view("a",candidate,List.of(),c,8);fail();}catch(IllegalArgumentException expected){}}
  try{NativeAgentReplies.view("b",r,List.of(),c,8);fail();}catch(IllegalArgumentException expected){}
 }
 @Test public void waitingAttemptsAndSequenceDoNotCreateBusyPollingEvents()throws Exception{
  var a=raw("delivered",step(1,true));String c=NativeAgentReplies.view("h",a,List.of(),null,8).get("cursor").toString();a.put("progress",Map.of("phase","waiting","sequence",99,"attempts",99,"elapsed_ms",99000,"value",List.of(step(1,true))));assertEquals(false,NativeAgentReplies.view("h",a,List.of(),c,8).get("changed"));
 }
 @Test public void terminalFailureAndUnknownPreserveOutcomeAndRouteOnlyToDetails()throws Exception{
  for(String state:List.of("failed","outcome_unknown")){var r=raw(state,step(1,false));var a=NativeAgentReplies.view("h",r,List.of(),null,8);assertEquals(state,a.get("state"));assertEquals(r.get("outcome_unknown"),a.get("outcome_unknown"));assertEquals("details",action((Map)a.get("next_call")));assertNull(a.get("execution_success"));}
 }
 @Test public void artifactPagesAreReceiptsAndDetectChangedAcknowledgedHash()throws Exception{
  var a=Map.<String,Object>of("snapshot_id","a","sha256","1");var b=Map.<String,Object>of("snapshot_id","b","sha256","2");var r=raw("delivered");var first=NativeAgentReplies.view("h",r,List.of(a,b),null,1);String c=first.get("cursor").toString();var second=NativeAgentReplies.view("h",r,List.of(b,a),c,1);assertEquals(1,((List<?>)second.get("new_artifacts")).size());try{NativeAgentReplies.view("h",r,List.of(Map.of("snapshot_id","a","sha256","changed"),b),c,1);fail();}catch(IllegalArgumentException expected){}
 }
 @Test public void executionQueryArraysAreNotMisrepresentedAsBatchSteps()throws Exception{
  var r=new LinkedHashMap<String,Object>();r.put("request_id","r");r.put("state","completed");r.put("terminal",true);r.put("outcome_unknown",false);r.put("execution",Map.of("success",true,"value",List.of(Map.of("step","query column"))));var out=NativeAgentReplies.view("h",r,List.of(),null,8);assertEquals(0,out.get("reported_steps"));assertEquals(true,out.get("execution_success"));assertFalse(out.containsKey("next_call"));
  r.put("_batch",true);assertEquals(1,NativeAgentReplies.view("h",r,List.of(),null,8).get("reported_steps"));
 }
 @Test public void invalidCursorAndBoundsNeverSilentlyBecomeEmptyHistory()throws Exception{
  for(String c:List.of("garbage","e30")){try{NativeAgentReplies.view("h",raw("delivered"),List.of(),c,8);fail();}catch(IllegalArgumentException expected){}}
  try{NativeAgentReplies.view("h",raw("delivered"),List.of(),null,0);fail();}catch(IllegalArgumentException expected){}
 }

 @Test public void malformedViewArgumentsAreRejectedBeforeAnyDispatch(){for(var args:List.of(Map.of("page_size",0),Map.of("page_size",1.5),Map.of("timeout_ms",25001),Map.of("compact","false"),Map.of("cursor",42),Map.of("action","execute","cursor","garbage"),Map.of("compact",false,"cursor","x"))){try{NativeHostService.validateViewArguments(new HashMap<String,Object>(args));fail();}catch(IllegalArgumentException expected){}}}

 @Test public void refreshedCustomPackageMustKeepOriginalTrustedDigest(){String expected="a".repeat(64);NativeHostService.requireTrustedCustomHash(expected,expected);try{NativeHostService.requireTrustedCustomHash("b".repeat(64),expected);fail();}catch(IllegalStateException correct){}}

 @Test public void unifiedPreparationRequiresCompatibilityAndCompletedRefresh(){var status=new HashMap<String,Object>(Map.of("state","running","channel",Map.of("connected",true),"compatibility",Map.of("compatible",true)));assertEquals(true,RuntimePreparationBackend.nativeReadiness("host",status).get("ready"));status.put("compatibility",Map.of("compatible",false));var old=RuntimePreparationBackend.nativeReadiness("host",status);assertEquals(false,old.get("ready"));assertEquals("native_refresh_required",old.get("state"));assertEquals("ensure_current",action((Map)old.get("next_call")));status.put("compatibility",Map.of("compatible",true));status.put("execution_blocked_by_refresh",true);assertEquals(false,RuntimePreparationBackend.nativeReadiness("host",status).get("ready"));}

 @Test public void metadataRefreshScopeTracksUnrelatedSavedProjectChanges()throws Exception{var root=java.nio.file.Files.createTempDirectory("native-refresh-scope");var file=root.resolve("Unrelated.mdo");try{java.nio.file.Files.writeString(file,"before");String before=NativeHostService.refreshProjectFingerprint(root);java.nio.file.Files.writeString(file,"after");assertNotEquals(before,NativeHostService.refreshProjectFingerprint(root));}finally{java.nio.file.Files.deleteIfExists(file);java.nio.file.Files.deleteIfExists(root);}}

 @Test public void smallPlainResultsInlineNullButLargeResultsStayInDetails()throws Exception{var raw=new LinkedHashMap<String,Object>(Map.of("request_id","r","state","completed","terminal",true,"outcome_unknown",false));var execution=new LinkedHashMap<String,Object>();execution.put("success",true);execution.put("value",null);raw.put("execution",execution);var small=NativeAgentReplies.view("h",raw,List.of(),null,8);assertTrue(small.containsKey("value"));assertNull(small.get("value"));assertEquals(true,small.get("value_inlined"));execution.put("value","x".repeat(1025));var large=NativeAgentReplies.view("h",raw,List.of(),null,8);assertFalse(large.containsKey("value"));assertEquals(true,large.get("value_in_details"));}
 @Test public void omittedSingleValueRemainsExplicitInCompactReceipt()throws Exception{var raw=Map.<String,Object>of("request_id","r","state","completed","terminal",true,"outcome_unknown",false,"execution",Map.of("success",true,"value_omitted",true,"value_limit_bytes",1048576,"result_omission_reason","result_utf8_limit"));var compact=NativeAgentReplies.view("h",raw,List.of(),null,8);assertEquals(true,compact.get("execution_success"));assertEquals(true,compact.get("value_omitted"));assertEquals("result_utf8_limit",compact.get("result_omission_reason"));assertFalse(compact.containsKey("value"));}

}
