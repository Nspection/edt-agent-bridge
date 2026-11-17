// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import static org.junit.Assert.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
public class DirectActionCompletionTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 @Test public void primaryReceiptIsDurableBeforeOptionalCaptureStarts()throws Exception{
  var journal=new DirectClientJournal(temp.newFolder().toPath());var claim=journal.claim("open",Map.of("action","menu_open"));String id=(String)claim.record().get("operation_id");
  var result=DirectActionCompletion.finish(journal,id,Map.of("success",true,"postcondition_verified",true),()->{
   assertEquals("completed",journal.read(id).get("state"));assertEquals(true,journal.result(id).get("success"));assertFalse(journal.blockingOperation().isPresent());
   var before=journal.result(id);assertEquals("pending",((Map<?,?>)before.get("native_capture")).get("state"));return Map.of("success",true,"observation_only",true);
  });assertEquals(true,result.get("success"));assertEquals(true,((Map<?,?>)journal.result(id).get("native_capture")).get("success"));
 }
 @Test public void captureFailureCannotLoseOrDowngradePrimaryOutcome()throws Exception{
  var journal=new DirectClientJournal(temp.newFolder().toPath());String id=(String)journal.claim("open",Map.of()).record().get("operation_id");
  DirectActionCompletion.finish(journal,id,Map.of("success",true,"postcondition_verified",true),()->{throw new java.io.IOException("Renderer stopped");});assertEquals("completed",journal.read(id).get("state"));assertEquals(true,journal.result(id).get("success"));assertEquals(false,((Map<?,?>)journal.result(id).get("native_capture")).get("success"));assertFalse(journal.blockingOperation().isPresent());
 }
 @Test public void unknownOutcomeNeverStartsOptionalObservation()throws Exception{
  var journal=new DirectClientJournal(temp.newFolder().toPath());String id=(String)journal.claim("open",Map.of()).record().get("operation_id");DirectActionCompletion.finish(journal,id,Map.of("success",false,"outcome_unknown",true),()->{fail("No optional observer after unknown action");return Map.of();});assertEquals("outcome_unknown",journal.read(id).get("state"));assertEquals(id,journal.blockingOperation().orElseThrow());
 }
}
