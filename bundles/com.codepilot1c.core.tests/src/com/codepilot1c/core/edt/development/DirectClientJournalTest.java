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

}
