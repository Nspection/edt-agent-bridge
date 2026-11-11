// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.util.*;
public class DirectClientJournalTest {
 @Rule public TemporaryFolder temporary=new TemporaryFolder();
 @Test public void claimSurvivesMissingResultAndNestedOrder()throws Exception{
  var journal=new DirectClientJournal(temporary.newFolder().toPath());var a=Map.<String,Object>of("action","input","expect",Map.of("b","2","a","1"));var first=journal.claim("fill-one",a);assertTrue(first.created());String id=first.record().get("operation_id").toString();
  var again=journal.claim("fill-one",Map.of("expect",new TreeMap<>(Map.of("a","1","b","2")),"action","input"));assertFalse(again.created());assertEquals(true,journal.result(id).get("outcome_unknown"));
 }
 @Test public void unknownAndCompletedEffectsNeverReceiveANewClaim()throws Exception{
  var journal=new DirectClientJournal(temporary.newFolder().toPath());var request=Map.<String,Object>of("action","command","element","Save");var first=journal.claim("save",request);var record=first.record();String id=record.get("operation_id").toString();record.put("result",Map.of("success",false,"outcome_unknown",true,"state","outcome_unknown"));record.put("state","outcome_unknown");journal.write(id,record);
  assertFalse(journal.claim("save",request).created());assertEquals("outcome_unknown",journal.result(id).get("state"));
  try{journal.claim("save",Map.of("action","command","element","Delete"));fail();}catch(IllegalArgumentException expected){}
 }
 @Test public void unsupportedOrMalformedBatchFailsBeforeClaim()throws Exception{
  var base=new LinkedHashMap<String,Object>(Map.of("action","batch","expected_window","Items","snapshot_id","tc-form-owned"));
  for(Object steps:List.of(List.of(),Collections.nCopies(17,Map.of()),List.of(Map.of()),List.of(Map.of("action","keyboard","expected_window","Items")),List.of(Map.of("action","input","expected_window","Items","element","Description","value",true)))){
   base.put("steps",steps);try{DirectTestClientService.validateAction(base);fail();}catch(IllegalArgumentException expected){}
  }
 }
 @Test public void directNavigationCannotOpenForeignUrlsOrInjectOptions(){
  var args=new LinkedHashMap<String,Object>(Map.of("action","open","expected_window","","snapshot_id","tc-form-owned"));
  for(String link:List.of("https://example.invalid/", "e1cib/list/Items\n/Execute evil.epf", "e1c://other-base/")){
   args.put("navigation_link",link);try{DirectTestClientService.validateAction(args);fail();}catch(IllegalArgumentException expected){}
  }
  args.put("navigation_link","e1cib/list/Справочник.AuditItems");DirectTestClientService.validateAction(args);
 }
 @Test public void concurrentStartsCannotExceedFourReservations()throws Exception{
  var root=temporary.newFolder().toPath();for(int i=0;i<3;i++)DirectTestClientService.reserveDirectory(root);
  var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
  try{
   java.util.concurrent.Callable<Boolean> reserve=()->{ready.countDown();go.await();try{DirectTestClientService.reserveDirectory(root);return true;}catch(IllegalStateException limit){return false;}};
   var first=pool.submit(reserve);var second=pool.submit(reserve);assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));go.countDown();
   assertNotEquals(first.get(5,java.util.concurrent.TimeUnit.SECONDS),second.get(5,java.util.concurrent.TimeUnit.SECONDS));
   try(var dirs=java.nio.file.Files.list(root)){assertEquals(4,dirs.count());}
  }finally{go.countDown();pool.shutdownNow();}
 }

 @Test public void tableActionsAndBatchValidateBeforeClaim(){
  var read=new LinkedHashMap<String,Object>(Map.of("action","table_read","expected_window","Card","snapshot_id","owned","table","Lines","columns",List.of("LinesKey"),"max_rows",2));
  DirectTestClientService.validateAction(read);
  for(Object limit:List.of(0,51,1.5,"2")){read.put("max_rows",limit);try{DirectTestClientService.validateAction(read);fail();}catch(IllegalArgumentException expected){}}
  read.put("max_rows",2);read.put("cells",Map.of("LinesKey","A"));try{DirectTestClientService.validateAction(read);fail();}catch(IllegalArgumentException expected){}
  var step=Map.<String,Object>of("action","table_add","expected_window","Card","table","Lines","cells",Map.of("LinesKey","A"));
  DirectTestClientService.validateAction(Map.of("action","batch","expected_window","Card","snapshot_id","owned","steps",List.of(step)));
 }
 @Test public void tableSetRequiresExactCriteriaAndBoundedNamedCells(){
  var set=new LinkedHashMap<String,Object>(Map.of("action","table_set","expected_window","Card","snapshot_id","owned","table","Lines","cells",Map.of("LinesValue","new")));
  try{DirectTestClientService.validateAction(set);fail();}catch(IllegalArgumentException expected){}
  set.put("row_match",Map.of("LinesKey","A"));DirectTestClientService.validateAction(set);
  set.put("cells",Map.of("LinesValue","x".repeat(4097)));try{DirectTestClientService.validateAction(set);fail();}catch(IllegalArgumentException expected){}
  set.put("cells",Map.of("LinesValue","new"));set.put("offset",1);try{DirectTestClientService.validateAction(set);fail();}catch(IllegalArgumentException expected){}
 }

 @Test public void unavailableCellRemainsExplicitNullInDurableResults()throws Exception{
  var journal=new DirectClientJournal(temporary.newFolder().toPath());var record=journal.claim("read-null",Map.of("action","table_read")).record();String id=record.get("operation_id").toString();
  var row=new LinkedHashMap<String,Object>();row.put("Cell",null);record.put("result",Map.of("success",true,"rows",List.of(row)));record.put("state","completed");journal.write(id,record);
  var result=journal.result(id);var rows=(List<?>)result.get("rows");var cell=(Map<?,?>)rows.get(0);assertTrue(cell.containsKey("Cell"));assertNull(cell.get("Cell"));
 }


 @Test public void waitValidatesTypedBoundedConditionsBeforeClaim(){
  var wait=new LinkedHashMap<String,Object>(Map.of("action","wait","snapshot_id","owned","expected_window","Card","wait_timeout_ms",500,"expect",Map.of("elements",Map.of("Name",Map.of("enabled",true,"readonly",false)))));
  DirectTestClientService.validateAction(wait);
  for(Object timeout:List.of(-1,61000,300001,1.5,true,"500")){wait.put("wait_timeout_ms",timeout);try{DirectTestClientService.validateAction(wait);fail();}catch(IllegalArgumentException expected){}}
  wait.put("wait_timeout_ms",0);wait.put("expect",Map.of("elements",Map.of("Name",Map.of("enabled","true"))));try{DirectTestClientService.validateAction(wait);fail();}catch(IllegalArgumentException expected){}
  wait.put("expect",Map.of("window","Other"));try{DirectTestClientService.validateAction(wait);fail();}catch(IllegalArgumentException expected){}
 }
 @Test public void batchWaitAndSummaryValidateButStepResponseModeDoesNot(){
  var step=Map.<String,Object>of("action","wait","expected_window","Card","wait_timeout_ms",500);
  var batch=new LinkedHashMap<String,Object>(Map.of("action","batch","snapshot_id","owned","expected_window","List","steps",List.of(step),"response_mode","summary"));DirectTestClientService.validateAction(batch);
  var bad=new LinkedHashMap<>(step);bad.put("response_mode","full");batch.put("steps",List.of(bad));try{DirectTestClientService.validateAction(batch);fail();}catch(IllegalArgumentException expected){}
 }
 @Test public void summaryOmitsLargeValuesButRetainsFailureAndSnapshotEvidence(){
  var step=Map.<String,Object>of("step_index",0,"action","input","success",false,"action_dispatched",true,"postcondition_verified",false,"snapshot_id","s","values",Map.of("Secret","large data"));
  var full=Map.<String,Object>of("success",false,"outcome_unknown",false,"results",List.of(step));var summary=DirectTestClientService.present(full,Map.of("response_mode","summary","action","batch","steps",List.of(Map.of())),"op");assertFalse(summary.toString().contains("large data"));assertTrue(summary.toString().contains("snapshot_id=s"));assertEquals(1,summary.get("finished_steps"));assertEquals(false,summary.get("success"));assertSame(full,DirectTestClientService.present(full,Map.of(),"op"));
 }

 @Test public void singleSummaryHasOneVerifiedStepAndNoLargePayload(){
  var full=Map.<String,Object>of("action","wait","success",true,"postcondition_verified",true,"snapshot_id","single","values",Map.of("Name","Large"));
  var result=DirectTestClientService.present(full,Map.of("response_mode","summary","action","wait"),"op");assertEquals(1,result.get("finished_steps"));assertEquals(1,result.get("total_steps"));assertEquals(1L,result.get("passed_steps"));assertEquals(1L,result.get("verified_steps"));assertFalse(result.toString().contains("Large"));
 }
 @Test public void failedSingleSummaryHasFailedIndexZero(){
  var full=new LinkedHashMap<String,Object>(Map.of("success",false,"state","rejected","results",List.of()));full.put("failed_step_index",null);
  var result=DirectTestClientService.present(full,Map.of("response_mode","summary","action","command"),"op");assertEquals(0,result.get("failed_step_index"));assertEquals(1,result.get("finished_steps"));assertEquals(0L,result.get("passed_steps"));
 }
 @Test public void fullBackgroundResultCorrelatesTheOriginalOperationWithoutMutatingWorkerPayload(){var raw=Map.<String,Object>of("success",false,"outcome_unknown",false);var result=DirectTestClientService.correlatedResult(raw,Map.of("response_mode","full"),"original");assertEquals("original",result.get("operation_id"));assertEquals(false,result.get("success"));assertFalse(raw.containsKey("operation_id"));}

 @Test public void unknownOutcomeBlocksNewKeyButOriginalResultRemainsReadable()throws Exception {var j=new DirectClientJournal(temporary.newFolder().toPath());var a=j.claim("one",Map.of("action","close"));String id=(String)a.record().get("operation_id");var row=a.record();row.put("state","outcome_unknown");row.put("result",Map.of("success",false,"outcome_unknown",true,"transport_state","usable"));j.write(id,row);assertFalse(j.claim("one",Map.of("action","close")).created());assertThrows(IllegalStateException.class,()->j.claim("two",Map.of("action","close")));assertEquals(id,j.blockingOperation().orElseThrow());}
 @Test public void activeClaimBlocksConcurrentMutationReservation()throws Exception {var j=new DirectClientJournal(temporary.newFolder().toPath());j.claim("one",Map.of("action","close"));assertThrows(IllegalStateException.class,()->j.claim("two",Map.of("action","close")));}
 @Test public void provenCompletedOperationAllowsNextIndependentMutation()throws Exception {var j=new DirectClientJournal(temporary.newFolder().toPath());var a=j.claim("one",Map.of("action","close"));String id=(String)a.record().get("operation_id");var row=a.record();row.put("state","completed");row.put("result",Map.of("success",true,"outcome_unknown",false));j.write(id,row);assertTrue(j.claim("two",Map.of("action","open")).created());}
 @Test public void failedProvenNotSentDoesNotBecomeStickyUnknown()throws Exception {var j=new DirectClientJournal(temporary.newFolder().toPath());var a=j.claim("one",Map.of("action","close"));String id=(String)a.record().get("operation_id");var row=a.record();row.put("state","failed");row.put("result",Map.of("success",false,"outcome_unknown",false,"action_dispatched",false));j.write(id,row);assertFalse(j.blockingOperation().isPresent());assertTrue(j.claim("two",Map.of("action","open")).created());}
 @Test public void restartCannotEraseUnknownMutationBarrier()throws Exception {var root=temporary.newFolder().toPath();var j=new DirectClientJournal(root);j.claim("one",Map.of("action","close"));var restarted=new DirectClientJournal(root);assertThrows(IllegalStateException.class,()->restarted.claim("two",Map.of("action","close")));}

 @Test public void lookupWithoutClaimReturnsOriginalUnknownReceiptAndNeverCreatesNewOne()throws Exception {var root=temporary.newFolder().toPath();var j=new DirectClientJournal(root);var payload=Map.<String,Object>of("action","close");assertFalse(j.existing("fresh",payload).isPresent());try(var files=java.nio.file.Files.list(root)){assertEquals(0,files.count());}var claim=j.claim("one",payload);var row=claim.record();row.put("state","outcome_unknown");row.put("result",Map.of("success",false,"outcome_unknown",true));j.write((String)row.get("operation_id"),row);assertEquals("outcome_unknown",j.existing("one",payload).orElseThrow().get("state"));assertThrows(IllegalArgumentException.class,()->j.existing("one",Map.of("action","open")));}
}
