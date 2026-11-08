// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
public class DevelopmentRunWaitTest {
 @Test public void waitWakesForEventAndTerminalAndRejectsInvalidBudget()throws Exception {
  try(var f=new DevelopmentRunServiceTest.Fixture()) {
   String id=f.start("wait");var start=f.waitPhase(id,"scenario_running");long cursor=((Number)start.get("event_cursor")).longValue();
   var pending=CompletableFuture.supplyAsync(()->{try{return f.service.awaitStatus(id,cursor,2000);}catch(Exception e){throw new CompletionException(e);}});
   f.backend.step.incrementAndGet();var result=pending.get(3,TimeUnit.SECONDS);assertTrue(((Number)result.get("event_cursor")).longValue()>cursor);
   f.backend.completed.set(true);f.waitPhase(id,"completed");long finalCursor=((Number)f.service.status(id,0).get("event_cursor")).longValue();assertEquals(true,f.service.awaitStatus(id,finalCursor,2000).get("terminal"));
   assertThrows(IllegalArgumentException.class,()->f.service.awaitStatus(id,0,25001));assertThrows(IllegalArgumentException.class,()->f.service.awaitStatus(id,finalCursor+1,1));
  }
 }
 @Test public void waitTimeoutDoesNotExecuteAnotherScenario()throws Exception {
  try(var f=new DevelopmentRunServiceTest.Fixture()) {String id=f.start("timeout");var s=f.waitPhase(id,"scenario_running");long cursor=((Number)s.get("event_cursor")).longValue();f.service.awaitStatus(id,cursor,20);assertEquals(1,f.backend.starts.get());}
 }
 @Test public void actualRunCapturesAndPersistsAllSourceCheckpoints()throws Exception {
  try(var f=new DevelopmentRunServiceTest.Fixture()) {
   f.backend.receipt=Map.of("complete",true,"sha256","hash","project_location","fixture");String id=f.start("source-receipt");f.waitPhase(id,"scenario_running");f.backend.completed.set(true);var done=f.waitPhase(id,"completed");
   var receipts=(Map<?,?>)done.get("source_receipts");assertEquals(f.backend.receipt,receipts.get("before"));assertEquals(f.backend.receipt,receipts.get("launch"));assertEquals(f.backend.receipt,receipts.get("finished"));
   var restarted=new DevelopmentRunService(f.folder,f.backend,f.workers);assertEquals(receipts,restarted.status(id,0).get("source_receipts"));
  }
 }

 @Test public void failureBeforeVanessaStillCertifiesOwnedTeardown()throws Exception{try(var f=new DevelopmentRunServiceTest.Fixture()){
  f.backend.linkageFailure=true;String id=f.start("early-failure");var failed=f.waitPhase(id,"failed");var managed=(Map<?,?>)failed.get("managed");assertEquals(0,f.backend.starts.get());assertEquals(true,managed.get("owned_processes_closed"));assertEquals(true,managed.get("teardown_completed"));
 }}

}
