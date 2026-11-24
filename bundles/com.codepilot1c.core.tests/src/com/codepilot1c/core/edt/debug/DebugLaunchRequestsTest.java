// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.debug;
import java.util.*;import org.junit.*;import org.junit.rules.TemporaryFolder;import static org.junit.Assert.*;
public class DebugLaunchRequestsTest {
 @Rule public TemporaryFolder tmp=new TemporaryFolder();
 static class Fake implements DebugLaunchRequests.Backend{boolean equal,fail,unbound;String base="base-one";String target="target-one";String branch="old";List<String> blockers=List.of();int updates,launches;public Map<String,Object> binding(String p){if(unbound)throw new IllegalStateException("Project unbound");return Map.of("project",p,"base_uuid",base,"target_identity_sha256",target,"project_path",p,"context",branch,"configuration_synchronized",equal);}public List<String> recoveryBlockers(){return blockers;}public void update(String p){updates++;equal=true;}public Map<String,Object> launch(String p,String c,boolean allowed){launches++;if(fail)throw new IllegalStateException("reply lost");return Map.of("status","debug_started","owned_debug_session_id","owned");}}
 Fake fake;DebugLaunchRequests service;@Before public void setup()throws Exception{fake=new Fake();service=new DebugLaunchRequests(tmp.newFolder().toPath(),fake);}
 Map<String,Object> done(String id)throws Exception{for(int i=0;i<400;i++){var r=service.status(id);if(Boolean.TRUE.equals(r.get("terminal")))return r;Thread.sleep(10);}throw new AssertionError("Launch not finished");}
 String hash(Map<String,Object>d){return (String)((Map<?,?>)d.get("decision")).get("decision_sha256");}
 @Test public void defaultAskReturnsTypedChoicesBeforeAnyLaunchOrDatabaseUpdate()throws Exception{var r=service.start("P","cfg","one","ask");assertEquals("awaiting_decision",r.get("state"));assertEquals(0,fake.updates);assertEquals(0,fake.launches);assertTrue(r.get("decision").toString().contains("launch_decide"));assertEquals(r.get("debug_launch_id"),service.start("P","cfg","one","ask").get("debug_launch_id"));}
 @Test public void selectedUpdateResumesOneRequestAndNeverReportsTestAcceptance()throws Exception{var r=service.start("P","cfg","one","ask");String id=(String)r.get("debug_launch_id");service.decide(id,hash(r),"update_and_launch");var result=done(id);assertEquals("launched",result.get("state"));assertEquals(false,result.get("test_acceptance_verified"));assertEquals(1,fake.updates);assertEquals(1,fake.launches);service.start("P","cfg","one","ask");assertEquals(1,fake.launches);assertThrows(IllegalStateException.class,()->service.decide(id,hash(r),"update_and_launch"));}
 @Test public void cancellingPendingDecisionHasNoSideEffects()throws Exception{var r=service.start("P","cfg","one","ask");assertEquals("cancelled",service.decide((String)r.get("debug_launch_id"),hash(r),"cancel").get("state"));assertEquals(0,fake.updates);assertEquals(0,fake.launches);}
 @Test public void taskScopedAuthorizationCanUpdateWithoutAnotherQuestion()throws Exception{var r=service.start("P","cfg","one","task_authorized_update");assertEquals("launched",done((String)r.get("debug_launch_id")).get("state"));assertEquals(1,fake.updates);assertEquals(1,fake.launches);}
 @Test public void staleOrChangedBaseDecisionCannotWrite()throws Exception{var r=service.start("P","cfg","one","ask");String id=(String)r.get("debug_launch_id");assertThrows(IllegalStateException.class,()->service.decide(id,"0".repeat(64),"update_and_launch"));fake.base="other";assertThrows(IllegalStateException.class,()->service.decide(id,hash(r),"update_and_launch"));assertEquals(0,fake.updates);}
 @Test public void unknownLaunchIsNeverRetriedWithSameOrNewKey()throws Exception{fake.equal=true;fake.fail=true;var r=service.start("P","cfg","one","ask");String id=(String)r.get("debug_launch_id");assertEquals("outcome_unknown",done(id).get("state"));service.start("P","cfg","one","ask");assertThrows(IllegalStateException.class,()->service.start("P","cfg","two","ask"));assertEquals(1,fake.launches);}
 @Test public void differentArgumentsCannotReuseLaunchKey()throws Exception{service.start("P","cfg","one","ask");assertThrows(IllegalStateException.class,()->service.start("P","other-cfg","one","ask"));}
 @Test public void cancellingAfterBaseChangeReleasesProjectWithoutUpdatingEitherBase()throws Exception{var r=service.start("P","cfg","one","ask");String id=(String)r.get("debug_launch_id");fake.base="base-two";assertThrows(IllegalStateException.class,()->service.decide(id,"0".repeat(64),"cancel"));assertThrows(IllegalStateException.class,()->service.decide(id,hash(r),"update_and_launch"));assertEquals("cancelled",service.decide(id,hash(r),"cancel").get("state"));assertEquals("awaiting_decision",service.start("P","cfg","two","ask").get("state"));assertEquals(0,fake.updates);assertEquals(0,fake.launches);}
 @Test public void cancellingAfterUnbindNeedsOnlyOriginalDecisionAndRejectsReplay()throws Exception{var r=service.start("P","cfg","one","ask");String id=(String)r.get("debug_launch_id");fake.unbound=true;assertEquals("cancelled",service.decide(id,hash(r),"cancel").get("state"));assertThrows(IllegalStateException.class,()->service.decide(id,hash(r),"cancel"));assertEquals(0,fake.updates);assertEquals(0,fake.launches);}

 @Test public void unboundProjectIsRejectedBeforeReservationAndCanBeBoundWithoutUnknownOutcome()throws Exception{fake.base="";assertThrows(IllegalStateException.class,()->service.start("P","cfg","one","ask"));assertThrows(IllegalStateException.class,()->service.start("P","cfg","one","task_authorized_update"));fake.base="base-one";assertEquals("awaiting_decision",service.start("P","cfg","one","ask").get("state"));assertEquals(0,fake.updates);assertEquals(0,fake.launches);}

 @Test public void recoveryPreservesOriginalAndAllowsOnlyAnExplicitNewRequest()throws Exception {
  fake.equal=true;
  fake.fail=true;
  String id=(String) service.start("P","cfg","original","ask").get("debug_launch_id");
  assertEquals("launch_dispatched",done(id).get("stage"));
  fake.branch="new";
  var plan=awaitRecovery(id);
  String expected=(String)plan.get("expected_plan_sha256");
  assertThrows(IllegalArgumentException.class,()->service.recover(id,expected,false));
  fake.blockers=List.of("live_edt_launch");
  assertFalse((boolean)service.recoveryPreview(id).get("recovery_allowed"));
  assertThrows(IllegalStateException.class,()->service.recover(id,expected,true));
  fake.blockers=List.of();
  fake.branch="newer";
  assertThrows(IllegalStateException.class,()->service.recover(id,expected,true));
  expectedRecovery(service,id);
  assertEquals(1,fake.launches);
  fake.fail=false;
  var next=service.start("P","new-cfg","new-key","ask");
  assertEquals("launched",done((String)next.get("debug_launch_id")).get("state"));
  assertEquals(2,fake.launches);
  assertEquals("outcome_unknown",service.start("P","cfg","original","ask").get("state"));
  assertEquals(0,fake.updates);
 }

 private void expectedRecovery(DebugLaunchRequests requests,String id)throws Exception {
  String expected=(String)awaitRecovery(id).get("expected_plan_sha256");
  var resolved=requests.recover(id,expected,true);
  assertEquals("outcome_unknown",resolved.get("state"));
  assertEquals(false,resolved.get("automatic_replay"));
  assertNotNull(resolved.get("resolution"));
  assertEquals(resolved,requests.recover(id,expected,true));
 }

 @Test public void interruptedReceiptCanBeResolvedAcrossServiceInstances()throws Exception {
  var path=tmp.newFolder().toPath();
  var first=new DebugLaunchRequests(path,fake);
  String id=(String)first.start("P","cfg","first","ask").get("debug_launch_id");
  var file=path.resolve(id+".json");
  java.nio.file.Files.writeString(file,java.nio.file.Files.readString(file).replace("awaiting_decision","running"));
  var restarted=new DebugLaunchRequests(path,fake);
  String plan=(String)restarted.recoveryPreview(id).get("expected_plan_sha256");
  assertEquals("outcome_unknown",restarted.recover(id,plan,true).get("state"));
  assertNotNull(new DebugLaunchRequests(path,fake).status(id).get("resolution"));
  assertEquals(0,fake.updates);
  assertEquals(0,fake.launches);
 }

 @Test public void sameTargetWithAnotherRegistrationBlocksButDifferentTargetCanProceed()throws Exception {
  service.start("P","cfg","first","ask");
  fake.base="base-two";
  assertThrows(IllegalStateException.class,()->service.start("Other","cfg","alias-key","ask"));
  fake.target="target-two";
  assertEquals("awaiting_decision",service.start("P","cfg","separate-key","ask").get("state"));
  assertEquals(0,fake.updates);
  assertEquals(0,fake.launches);
 }

 private Map<String,Object> awaitRecovery(String id)throws Exception {
  for(int i=0;i<400;i++) {
   var plan=service.recoveryPreview(id);
   if(Boolean.TRUE.equals(plan.get("recovery_allowed")))return plan;
   Thread.sleep(10);
  }
  throw new AssertionError(service.recoveryPreview(id).toString());
 }

}
