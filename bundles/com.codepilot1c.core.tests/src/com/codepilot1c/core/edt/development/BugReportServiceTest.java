// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
public class BugReportServiceTest {
 @org.junit.Rule public org.junit.rules.TemporaryFolder temporary=new org.junit.rules.TemporaryFolder();
 static final class Backend implements BugReportService.Backend {
  String account="42";int posts;boolean drop,authFails;Optional<BugReportGitHub.Receipt> found=Optional.empty();CountDownLatch entered,release,postEntered,postRelease;
  public BugReportGitHub.Account account()throws Exception{if(entered!=null){entered.countDown();if(!release.await(3,TimeUnit.SECONDS))throw new TimeoutException();}return new BugReportGitHub.Account(account,"tester");}
  public BugReportGitHub.Receipt post(String account,String title,String body)throws Exception{posts++;if(postEntered!=null){postEntered.countDown();if(!postRelease.await(3,TimeUnit.SECONDS))throw new TimeoutException();}if(drop)throw new java.io.IOException("SECRET-SHOULD-NEVER-APPEAR");return new BugReportGitHub.Receipt(123,"https://github.com/Nspection/edt-agent-bridge/issues/123");}
  public Optional<BugReportGitHub.Receipt> find(String marker,String body,String account){return found;}
  public void login(String login){}
  public BugReportGitHub.Session authenticate(String login)throws Exception{if(authFails)throw new java.io.IOException("AUTH_SENTINEL");var bound=account();return new BugReportGitHub.Session(){
   public BugReportGitHub.Account account(){return bound;}
   public BugReportGitHub.Receipt post(String title,String body)throws Exception{return Backend.this.post(bound.id(),title,body);}
   public Optional<BugReportGitHub.Receipt> find(String marker,String body){return Backend.this.find(marker,body,bound.id());}
  };}
 }
 final class Fixture {
  final Path root=temporary.newFolder().toPath();final Backend backend=new Backend();final AtomicInteger checks=new AtomicInteger();final BugReportService service=new BugReportService(root,backend,checks::incrementAndGet,"1.4.0.test");
  Fixture()throws Exception{}
  Map<String,Object> prepare()throws Exception{return service.prepare("testclient",Map.of("failure_kind","wire_timeout","pending_replies",1,"outcome_unknown",true),false);}
  void connect(String policy)throws Exception{service.connectFromUserInterface();service.policyFromUserInterface(policy);}
  Map<String,Object> submit(Map<String,Object> draft)throws Exception{return service.submit((String)draft.get("report_id"),(String)draft.get("payload_sha256"));}
 }
 @Test public void rawSecretsPathsStacksAndUnknownStringsNeverEnterPublicText()throws Exception{
  var value=BugReportEnvelope.collect("testclient","1.4.0.test",Map.of("message","SECRET C:/private/base","stack","PASSWORD","failure_kind","SECRET","diagnostics",Map.of("last_rpc_phase","reply_rejected","last_rpc_kind","action","pending_replies",1,"url","https://secret")),false);
  String json=BugReportOutbox.JSON.toJson(value);assertFalse(json.contains("SECRET"));assertFalse(json.contains("PASSWORD"));assertFalse(json.contains("private"));assertFalse(json.contains("https"));assertEquals("reply_rejected",value.get("last_rpc_phase"));assertEquals("agent_reported",value.get("source"));
  assertFalse(BugReportEnvelope.collect("mcp","bad version with path",Map.of("elapsed_ms",Double.NaN,"pending_replies",-1,"status_code",1.5),false).containsKey("status_code"));
 }
 @Test public void statusPrepareAndOffNeverPost()throws Exception{
  var f=new Fixture();assertEquals("off",f.service.status().get("policy"));var draft=f.prepare();assertThrows(IllegalStateException.class,()->f.submit(draft));assertEquals(0,f.backend.posts);
 }
 @Test public void askRequiresUserApprovalOfExactFreshBytes()throws Exception{
  var f=new Fixture();f.connect("ask");var draft=f.prepare();assertThrows(IllegalStateException.class,()->f.submit(draft));f.service.approveFromUserInterface((String)draft.get("report_id"),(String)draft.get("payload_sha256"));assertEquals("submitted",f.submit(draft).get("state"));assertEquals(1,f.backend.posts);assertEquals(2,f.checks.get()-1);f.submit(draft);assertEquals(1,f.backend.posts);
 }
 @Test public void changedAccountAndPolicyInvalidatePreviewBeforePost()throws Exception{
  var f=new Fixture();f.connect("auto_sanitized");var draft=f.prepare();f.backend.account="43";assertThrows(IllegalStateException.class,()->f.submit(draft));assertEquals(0,f.backend.posts);f.backend.account="42";f.service.policyFromUserInterface("off");assertThrows(IllegalStateException.class,()->f.submit(draft));assertEquals(0,f.backend.posts);
 }
 @Test public void dropAfterAcceptedPostStaysUnknownAndNeverRetriesEvenAfterRestart()throws Exception{
  var f=new Fixture();f.connect("auto_sanitized");var draft=f.prepare();f.backend.drop=true;assertEquals("delivery_unknown",f.submit(draft).get("state"));assertEquals(1,f.backend.posts);assertFalse(BugReportOutbox.JSON.toJson(f.submit(draft)).contains("SECRET"));assertEquals(1,f.backend.posts);
  var restarted=new BugReportService(f.root,f.backend,()->{},"1.4.0.test");assertEquals("delivery_unknown",restarted.submit((String)draft.get("report_id"),(String)draft.get("payload_sha256")).get("state"));assertEquals(1,f.backend.posts);
  assertEquals("delivery_unknown",f.service.reconcile((String)draft.get("report_id")).get("state"));f.backend.found=Optional.of(new BugReportGitHub.Receipt(123,"https://github.com/Nspection/edt-agent-bridge/issues/123"));assertEquals("submitted",f.service.reconcile((String)draft.get("report_id")).get("state"));assertEquals(1,f.backend.posts);
 }
 @Test public void concurrentSubmitHasOnlyOneDurableDispatch()throws Exception{
  var f=new Fixture();f.connect("auto_sanitized");var draft=f.prepare();var workers=Executors.newFixedThreadPool(2);try{var a=workers.submit(()->f.submit(draft));var b=workers.submit(()->f.submit(draft));a.get(3,TimeUnit.SECONDS);b.get(3,TimeUnit.SECONDS);assertEquals(1,f.backend.posts);}finally{workers.shutdownNow();}
 }
 @Test public void userCanRevokeDuringCredentialReadAndNoPostFollows()throws Exception{
  var f=new Fixture();f.connect("auto_sanitized");var draft=f.prepare();f.backend.entered=new CountDownLatch(1);f.backend.release=new CountDownLatch(1);var worker=Executors.newSingleThreadExecutor();try{
   var pending=worker.submit(()->f.submit(draft));assertTrue(f.backend.entered.await(1,TimeUnit.SECONDS));f.service.policyFromUserInterface("off");f.backend.release.countDown();assertThrows(ExecutionException.class,()->pending.get(2,TimeUnit.SECONDS));assertEquals(0,f.backend.posts);
  }finally{f.backend.release.countDown();worker.shutdownNow();}
 }
 @Test public void persistedBodyTamperCannotBypassApprovalHash()throws Exception{
  var f=new Fixture();f.connect("ask");var draft=f.prepare();String id=(String)draft.get("report_id"),hash=(String)draft.get("payload_sha256");f.service.approveFromUserInterface(id,hash);Path file=f.root.resolve("outbox").resolve(id+".json");var json=com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();json.addProperty("body","SECRET");Files.writeString(file,json.toString());assertThrows(IllegalStateException.class,()->f.submit(draft));assertEquals(0,f.backend.posts);
 }
 @Test public void invalidPolicyAndDeniedExposureFailClosedBeforeDispatch()throws Exception{
  var f=new Fixture();f.connect("auto_sanitized");var draft=f.prepare();var denied=new BugReportService(f.root,f.backend,()->{throw new IllegalStateException("denied");},"1.4.0.test");assertThrows(IllegalStateException.class,()->denied.submit((String)draft.get("report_id"),(String)draft.get("payload_sha256")));assertEquals(0,f.backend.posts);Files.writeString(f.root.resolve("policy.json"),"{}");assertThrows(IllegalStateException.class,()->f.submit(draft));assertEquals(0,f.backend.posts);
 }
 @Test public void dedupIgnoresFreeTextAndDifferentNumbersMustNotLeakValues()throws Exception{
  var f=new Fixture();f.connect("auto_sanitized");var a=f.service.prepare("editor",Map.of("message","SECRET-A"),false);var b=f.service.prepare("editor",Map.of("message","SECRET-B"),false);assertEquals(a.get("report_id"),b.get("report_id"));assertFalse(String.valueOf(a.get("body")).contains("SECRET"));
 }
 @Test public void fiveDispatchQuotaPreservesAllSixthDraftsWithoutPost()throws Exception{
  var f=new Fixture();f.connect("auto_sanitized");for(int i=0;i<5;i++){var draft=f.service.prepare("mcp",Map.of("status_code",i),false);f.submit(draft);}var sixth=f.service.prepare("mcp",Map.of("status_code",6),false);assertThrows(IllegalStateException.class,()->f.submit(sixth));assertEquals(5,f.backend.posts);assertEquals("prepared",f.service.result((String)sixth.get("report_id")).get("state"));
 }
 @Test public void changingLatencyAndStepCountersDoNotCreateDuplicateIssues()throws Exception{
  var f=new Fixture();f.connect("auto_sanitized");var a=f.service.prepare("testclient",Map.of("failure_kind","wire_timeout","elapsed_ms",100,"pending_replies",1),false);f.submit(a);var b=f.service.prepare("testclient",Map.of("failure_kind","wire_timeout","elapsed_ms",60000,"pending_replies",2),false);assertEquals(a.get("report_id"),b.get("report_id"));assertEquals("submitted",b.get("state"));f.submit(b);assertEquals(1,f.backend.posts);
 }
 @Test public void delayedPostTimeoutCannotDowngradeConcurrentReconciliation()throws Exception{
  var f=new Fixture();f.connect("auto_sanitized");var draft=f.prepare();f.backend.drop=true;f.backend.postEntered=new CountDownLatch(1);f.backend.postRelease=new CountDownLatch(1);var worker=Executors.newSingleThreadExecutor();
  try{var pending=worker.submit(()->f.submit(draft));assertTrue(f.backend.postEntered.await(1,TimeUnit.SECONDS));f.backend.found=Optional.of(new BugReportGitHub.Receipt(123,"https://github.com/Nspection/edt-agent-bridge/issues/123"));assertEquals("submitted",f.service.reconcile((String)draft.get("report_id")).get("state"));f.backend.postRelease.countDown();assertEquals("submitted",pending.get(2,TimeUnit.SECONDS).get("state"));assertEquals("submitted",f.service.result((String)draft.get("report_id")).get("state"));assertEquals(1,f.backend.posts);}finally{f.backend.postRelease.countDown();worker.shutdownNow();}
 }
 @Test public void unrelatedLocalFieldsAreNeverReturnedToAgent()throws Exception{
  var f=new Fixture();var draft=f.prepare();Path file=f.root.resolve("outbox").resolve(draft.get("report_id")+".json");var json=com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();json.addProperty("private_note","SECRET-LOCAL");Files.writeString(file,json.toString());assertFalse(BugReportOutbox.JSON.toJson(f.service.result((String)draft.get("report_id"))).contains("SECRET-LOCAL"));
 }
 @Test public void authenticationFailureBeforeClaimLeavesDraftAndBudgetUntouched()throws Exception{
  var f=new Fixture();f.connect("auto_sanitized");var draft=f.prepare();f.backend.authFails=true;assertThrows(java.io.IOException.class,()->f.submit(draft));assertEquals("prepared",f.service.result((String)draft.get("report_id")).get("state"));assertFalse(Files.exists(f.root.resolve("daily-budget.json")));assertEquals(0,f.backend.posts);f.backend.authFails=false;assertEquals("submitted",f.submit(draft).get("state"));assertEquals(1,f.backend.posts);
 }
 @Test public void workerProtocolRejectionCodesSurviveWithoutItsRawMessage(){var diagnostic=BugReportEnvelope.collect("testclient","1.4.0.test",Map.of("execution_state","not_dispatched","diagnostics",Map.of("failure_kind","protocol_rejected","last_rpc_kind","read818","last_rpc_phase","reply_complete","protocol_rejection",Map.of("status_code",5,"message","PRIVATE_SENTINEL"))),true);assertEquals(5L,diagnostic.get("status_code"));assertEquals("read818",diagnostic.get("last_rpc_kind"));assertEquals("reply_complete",diagnostic.get("last_rpc_phase"));assertFalse(BugReportOutbox.JSON.toJson(diagnostic).contains("PRIVATE_SENTINEL"));}
 @Test public void explicitAcceptanceIssueHasAFixedTestLabel()throws Exception{var f=new Fixture();var draft=f.service.prepare("mcp",Map.of("report_kind","acceptance_test"),false);assertTrue(String.valueOf(draft.get("title")).startsWith("[Acceptance test]"));assertTrue(String.valueOf(draft.get("body")).contains("not a product defect"));}
 @Test public void invalidIssueAcknowledgementIsRejected(){var json=new com.google.gson.JsonObject();json.addProperty("number",12);json.addProperty("html_url","https://attacker.invalid/issues/12");assertThrows(IllegalStateException.class,()->BugReportGitHub.receipt(json));}
 @Test public void allWorkerFailureKindsRemainDistinctInPublicIdentity()throws Exception{var f=new Fixture();f.connect("auto_sanitized");var ids=new HashSet<Object>();for(String kind:List.of("local_budget_expired","validation_rejected","connection_or_protocol_failure","wire_timeout","protocol_rejected")){var diagnostic=BugReportEnvelope.collect("testclient","1.4.0.test",Map.of("failure_kind",kind,"execution_state","completed_or_failed"),true);assertEquals(kind,diagnostic.get("failure_kind"));assertEquals("completed_or_failed",diagnostic.get("execution_state"));var report=f.service.prepare("testclient",diagnostic,true);assertTrue(ids.add(report.get("report_id")));}}
}
