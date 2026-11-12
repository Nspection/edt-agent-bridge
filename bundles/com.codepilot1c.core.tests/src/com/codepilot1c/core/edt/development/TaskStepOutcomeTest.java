// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;
public class TaskStepOutcomeTest {
 @Test public void nativeJobDoneIsNotDomainCompletion(){
  for(String state:List.of("blocked","awaiting_consent","awaiting_runtime")){var out=TaskStepOutcome.classify("edt_runtime_host","prepare",Map.of("state",state,"execution_state","not_started"),true,true);assertFalse(out.success());assertEquals("blocked",out.state());}
  var queued=TaskStepOutcome.classify("edt_runtime_host","execute",Map.of("state","queued","terminal",false),true,true);assertFalse(queued.done());assertFalse(queued.success());
 }
 @Test public void nativeExecuteNeedsTerminalPositiveExecution(){
  assertTrue(TaskStepOutcome.classify("edt_runtime_host","execute",Map.of("state","completed","terminal",true,"execution",Map.of("success",true)),true,true).success());
  assertFalse(TaskStepOutcome.classify("edt_runtime_host","execute",Map.of("state","completed","terminal",true,"execution_success",false),true,true).success());
  assertFalse(TaskStepOutcome.classify("edt_runtime_host","execute",Map.of("state","completed"),true,true).success());
 }
 @Test public void unknownOverridesAllOtherSuccessEvidence(){var out=TaskStepOutcome.classify("edt_runtime_host","execute",Map.of("state","completed","terminal",true,"execution_success",true,"outcome_unknown",true),true,true);assertEquals("outcome_unknown",out.state());assertFalse(out.success());}
 @Test public void startWaitsForChannelReadinessAndHonoursRevocation(){assertEquals("running",TaskStepOutcome.classify("edt_runtime_host","start",Map.of("state","starting"),true,true).state());assertTrue(TaskStepOutcome.classify("edt_runtime_host","start",Map.of("state","running","execution_allowed",true),true,true).success());assertEquals("blocked",TaskStepOutcome.classify("edt_runtime_host","start",Map.of("state","running","consent_valid",false),true,true).state());}
 @Test public void dataCreateRequiresConfirmedReceiptAndSetsNeverInventAdvancement(){assertFalse(TaskStepOutcome.classify("edt_test_data","create",Map.of("state","completed","creation_id","c","confirmed",false),true,true).success());assertTrue(TaskStepOutcome.classify("edt_test_data","create",Map.of("state","completed","creation_id","c","confirmed",true),true,true).success());for(String state:List.of("ready","running","paused","completed_with_skips"))assertFalse(TaskStepOutcome.classify("edt_test_data","create_set",Map.of("state",state,"set_id","s"),true,true).success());}
 @Test public void everyAsyncReceiptBlocksCleanupUntilCollected(){for(String field:List.of("job_id","request_id","assertion_id","creation_id","cleanup_id","set_id","run_id"))assertTrue(TaskStepOutcome.requiresCollection(Map.of(field,"id")));assertFalse(TaskStepOutcome.requiresCollection(Map.of("window","Fixture")));}
}
