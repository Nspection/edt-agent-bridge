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
  String account="42";int posts,httpStatus;boolean drop,authFails;Optional<BugReportGitHub.Receipt> found=Optional.empty();CountDownLatch entered,release,postEntered,postRelease;
  public BugReportGitHub.Account account()throws Exception{if(entered!=null){entered.countDown();if(!release.await(3,TimeUnit.SECONDS))throw new TimeoutException();}return new BugReportGitHub.Account(account,"tester");}
  public BugReportGitHub.Receipt post(String account,String title,String body)throws Exception{posts++;if(postEntered!=null){postEntered.countDown();if(!postRelease.await(3,TimeUnit.SECONDS))throw new TimeoutException();}if(httpStatus>0)throw new BugReportGitHub.HttpFailure(httpStatus);if(drop)throw new java.io.IOException("SECRET-SHOULD-NEVER-APPEAR");return new BugReportGitHub.Receipt(123,"https://github.com/Nspection/edt-agent-bridge/issues/123");}
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
 @Test public void liveVerificationIsExplicitAndReconnectPreservesExistingChoice()throws Exception{
  var f=new Fixture();var connected=f.service.connectFromUserInterface();assertEquals(true,connected.get("account_live_verified"));var cached=f.service.status();assertEquals(false,cached.get("account_live_verified"));assertEquals(true,cached.get("account_previously_verified"));assertEquals("cached_local_status",cached.get("verification_source"));f.service.policyFromUserInterface("auto_sanitized");f.service.connectFromUserInterface();assertEquals("auto_sanitized",f.service.status().get("policy"));assertEquals(true,f.service.verifyAccount().get("account_live_verified"));assertEquals(0,f.backend.posts);
 }
 @Test public void customDescriptionNeedsExactApprovalEvenInAutomaticTechnicalMode()throws Exception{
  var f=new Fixture();f.connect("auto_sanitized");var draft=f.service.prepareDescription("editor",Map.of("failure_kind","unknown"),false,"Button not found","A command-bar button cannot be found by its name.");assertTrue(String.valueOf(draft.get("body")).contains("command-bar"));assertEquals(true,draft.get("requires_user_review"));assertThrows(IllegalStateException.class,()->f.submit(draft));f.service.approveFromUserInterface((String)draft.get("report_id"),(String)draft.get("payload_sha256"));assertEquals("submitted",f.submit(draft).get("state"));f.submit(draft);assertEquals(1,f.backend.posts);
 }
 @Test public void customTextIdentityAndSecretRedactionSurviveRestart()throws Exception{
  var f=new Fixture();f.connect("ask");String secret="gh"+"p_"+"A".repeat(36);var a=f.service.prepareDescription("mcp",Map.of(),true,"Any bug","Observed behaviour; token "+secret);var b=f.service.prepareDescription("mcp",Map.of(),true,"Any bug","Different behaviour");assertNotEquals(a.get("report_id"),b.get("report_id"));assertFalse(String.valueOf(a.get("body")).contains(secret));assertTrue(String.valueOf(a.get("body")).contains("[REDACTED]"));assertTrue(String.valueOf(a.get("body")).contains("description_source"));var restarted=new BugReportService(f.root,f.backend,()->{},"1.4.0.test");assertEquals(a.get("payload_sha256"),restarted.result((String)a.get("report_id")).get("payload_sha256"));assertThrows(IllegalArgumentException.class,()->f.service.prepareDescription("mcp",Map.of(),false,"bad\nheading","body"));assertEquals(0,f.backend.posts);
 }
 @Test public void cancelledConnectionCannotChangePolicyOrBindAccount()throws Exception{
  var f=new Fixture();assertThrows(CancellationException.class,()->f.service.connectFromUserInterface("",()->false));assertEquals("off",f.service.status().get("policy"));assertEquals("",f.service.status().get("configured_account"));
 }
 @Test public void failedTokenInputIsClearedAndNeverEnablesSharing()throws Exception{
  var f=new Fixture();char[] token="owned-test-input".toCharArray();assertThrows(IllegalStateException.class,()->f.service.tokenFromUserInterface(token,()->true));for(char c:token)assertEquals(0,c);assertEquals("off",f.service.status().get("policy"));assertEquals(0,f.backend.posts);
 }
 @Test public void accountSwitchCannotApplyOldAutomaticPermission()throws Exception{
  var f=new Fixture();f.connect("auto_sanitized");var before=f.service.status();f.backend.account="43";f.service.connectFromUserInterface();assertEquals("ask",f.service.status().get("policy"));assertThrows(IllegalStateException.class,()->f.service.policyFromUserInterface("auto_sanitized",(String)before.get("account_id"),(String)before.get("policy_revision")));assertEquals("ask",f.service.status().get("policy"));
 }
 @Test public void fullDescriptionLimitRoundTripsEscapedXmlWithoutGenericFailure()throws Exception{
  var f=new Fixture();f.connect("ask");String description="<".repeat(8000);var row=f.service.prepareDescription("editor",Map.of(),false,"Large XML text",description);assertTrue(String.valueOf(row.get("body")).contains(description));assertEquals(row.get("payload_sha256"),f.service.result((String)row.get("report_id")).get("payload_sha256"));
 }
 @Test public void reportFileLockHasABoundedDeadlineAndNoPost()throws Exception{
  var f=new Fixture();try(var channel=java.nio.channels.FileChannel.open(f.root.resolve("outbox/.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()){assertThrows(TimeoutException.class,f::prepare);}assertEquals(0,f.backend.posts);
 }
 @Test public void releaseAfterFailureReturnsActualReceiptAndAtomicallyRevokesPreparedApproval()throws Exception {
  var f=new Fixture();f.connect("ask");var draft=f.prepare();String id=(String)draft.get("report_id"),hash=(String)draft.get("payload_sha256");f.service.approveFromUserInterface(id,hash);assertEquals("not_submitted",f.service.releaseApprovalAfterFailure(id,hash,"daily_quota").get("state"));assertThrows(IllegalStateException.class,()->f.submit(draft));f.service.approveFromUserInterface(id,hash);assertEquals("submitted",f.submit(draft).get("state"));assertEquals("submitted",f.service.releaseApprovalAfterFailure(id,hash,"not_dispatched").get("state"));assertEquals(1,f.backend.posts);
 }
 @Test public void proposalsAreDistinctAndRequireExactHumanApproval()throws Exception {
  var f=new Fixture();f.connect("auto_sanitized");var bug=f.service.prepareDescription("mcp",Map.of(),false,"Improve report UI","Allow agents to describe improvements.","bug");var proposal=f.service.prepareDescription("mcp",Map.of(),false,"Improve report UI","Allow agents to describe improvements.","proposal");assertTrue(proposal.get("title").toString().startsWith("[Proposal] "));assertTrue(bug.get("title").toString().startsWith("[Bug report] "));assertNotEquals(bug.get("report_id"),proposal.get("report_id"));assertThrows(IllegalStateException.class,()->f.submit(proposal));assertThrows(IllegalArgumentException.class,()->f.service.prepareDescription("mcp",Map.of(),false,"Title","Body","other"));String id=(String)proposal.get("report_id"),hash=(String)proposal.get("payload_sha256");f.service.approveFromUserInterface(id,hash);assertEquals("submitted",f.submit(proposal).get("state"));assertEquals(1,f.backend.posts);
 }
 @Test public void definitivePostRejectionPreservesDraftRefundsQuotaAndRequiresNewApproval()throws Exception{
  for(int status:List.of(401,403,404,422)){var f=new Fixture();f.connect("auto_sanitized");var draft=f.prepare();f.backend.httpStatus=status;var failed=f.submit(draft);assertEquals("prepared",failed.get("state"));assertEquals(status,failed.get("rejected_http_status"));assertEquals(true,failed.get("requires_user_review"));assertEquals(true,failed.get("quota_refund_applied"));assertEquals(0,com.google.gson.JsonParser.parseString(Files.readString(f.root.resolve("daily-budget.json"))).getAsJsonObject().get("count").getAsInt());assertThrows(IllegalStateException.class,()->f.submit(draft));assertEquals(1,f.backend.posts);f.backend.httpStatus=0;f.service.approveFromUserInterface((String)draft.get("report_id"),(String)draft.get("payload_sha256"));assertEquals("submitted",f.submit(draft).get("state"));assertEquals(2,f.backend.posts);}
 }
 @Test public void rateLimitServerFailureAndIoRemainUnknownWithoutReplay()throws Exception{
  for(int status:List.of(429,500,503)){var f=new Fixture();f.connect("auto_sanitized");var draft=f.prepare();f.backend.httpStatus=status;assertEquals("delivery_unknown",f.submit(draft).get("state"));assertEquals("delivery_unknown",f.submit(draft).get("state"));assertEquals(1,f.backend.posts);}
 }
 @Test public void explicitUserSendDoesNotUseMcpExposureButStillRequiresExactApproval()throws Exception{
  var f=new Fixture();var service=new BugReportService(f.root,f.backend,()->{throw new IllegalStateException("MCP hidden");},"1.4.0.test");service.connectFromUserInterface();service.policyFromUserInterface("auto_sanitized");var row=service.prepare("mcp",Map.of(),false);String id=(String)row.get("report_id"),hash=(String)row.get("payload_sha256");assertThrows(IllegalStateException.class,()->service.submitFromUserInterface(id,hash));service.approveFromUserInterface(id,hash);assertThrows(IllegalStateException.class,()->service.submit(id,hash));assertEquals("submitted",service.submitFromUserInterface(id,hash).get("state"));assertEquals(1,f.backend.posts);
 }
 @Test public void corruptDraftIsPreservedAndBlockedWithoutBreakingOtherReports()throws Exception{
  var f=new Fixture();f.connect("auto_sanitized");var draft=f.prepare();String id=(String)draft.get("report_id");Path bad=f.root.resolve("outbox/"+id+".json");Files.writeString(bad,"BROKEN-PRIVATE-TEXT");assertEquals("unavailable",f.service.result(id).get("state"));assertThrows(IllegalStateException.class,()->f.submit(draft));assertThrows(IllegalStateException.class,()->f.prepare());var other=f.service.prepareDescription("mcp",Map.of(),false,"Different bug","Independent report");assertEquals("prepared",other.get("state"));assertEquals(0,f.backend.posts);assertFalse(Files.exists(bad));try(var paths=Files.list(f.root.resolve("outbox/quarantine"))){assertTrue(paths.anyMatch(p->{try{return Files.readString(p).equals("BROKEN-PRIVATE-TEXT");}catch(Exception e){return false;}}));}assertFalse(AgentResponses.JSON.toJson(f.service.result(id)).contains("PRIVATE"));
 }
 @Test public void submittedHistoryRemainsDeduplicatedOutsidePendingScan()throws Exception{
  var f=new Fixture();f.connect("auto_sanitized");var draft=f.prepare();assertEquals("submitted",f.submit(draft).get("state"));assertEquals("submitted",f.prepare().get("state"));assertEquals("submitted",f.submit(draft).get("state"));assertEquals(1,f.backend.posts);try(var paths=Files.list(f.root.resolve("outbox"))){assertEquals(0,paths.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).count());}
 }
 @Test public void rejectionSurvivesPolicyRevisionAndReprepare()throws Exception{
  var f=new Fixture();f.connect("auto_sanitized");var draft=f.prepare();f.backend.httpStatus=403;assertEquals("prepared",f.submit(draft).get("state"));f.service.policyFromUserInterface("ask");f.service.policyFromUserInterface("auto_sanitized");var fresh=f.prepare();assertNotEquals(draft.get("payload_sha256"),fresh.get("payload_sha256"));assertThrows(IllegalStateException.class,()->f.service.approveFromUserInterface((String)draft.get("report_id"),(String)draft.get("payload_sha256")));assertEquals(true,fresh.get("review_required_after_rejection"));f.backend.httpStatus=0;assertThrows(IllegalStateException.class,()->f.submit(fresh));assertEquals(1,f.backend.posts);
 }
 @Test public void damagedSubmittedHistoryNeverReposts()throws Exception{
  var f=new Fixture();f.connect("auto_sanitized");var draft=f.prepare();assertEquals("submitted",f.submit(draft).get("state"));String id=(String)draft.get("report_id");Path file=f.root.resolve("outbox/history/"+id+".json");var row=com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();row.addProperty("state","prepared");Files.writeString(file,row.toString());assertEquals("unavailable",f.service.result(id).get("state"));assertThrows(IllegalStateException.class,()->f.prepare());assertThrows(IllegalStateException.class,()->f.submit(draft));assertEquals(1,f.backend.posts);
 }
 static final class GenerationBackend implements BugReportService.Backend {
  final Backend delegate=new Backend();final Set<String> candidates=new HashSet<>();String latest;Runnable afterStore=()->{};
  public void login(String login){} public BugReportGitHub.Session authenticate(String login)throws Exception{return delegate.authenticate(login);}
  public void login(String login,String generation){latest=generation;candidates.add(generation);afterStore.run();}
  public BugReportGitHub.Session token(char[] value,String generation)throws Exception{login("",generation);return authenticate("");}
  public BugReportGitHub.Session authenticate(String login,String generation)throws Exception{assertTrue(candidates.contains(generation));return authenticate(login);}
  public void discard(String generation){candidates.remove(generation);}
 }
 @Test public void cancelledCandidateNeverReplacesSavedCredential()throws Exception{
  Path root=temporary.newFolder().toPath();var backend=new GenerationBackend();var service=new BugReportService(root,backend,()->{},"1.4.0.test");service.connectFromUserInterface();String old=backend.latest;String policy=Files.readString(root.resolve("policy.json"));var active=new java.util.concurrent.atomic.AtomicBoolean(true);backend.afterStore=()->active.set(false);char[] token="test-input".toCharArray();assertThrows(CancellationException.class,()->service.tokenFromUserInterface(token,active::get));assertEquals(policy,Files.readString(root.resolve("policy.json")));assertEquals(Set.of(old),backend.candidates);for(char c:token)assertEquals(0,c);assertEquals(true,service.verifyAccount().get("account_live_verified"));
 }
 @Test public void failedPolicyPublicationPreservesPreviousCredential()throws Exception{
  Path root=temporary.newFolder().toPath();var backend=new GenerationBackend();var service=new BugReportService(root,backend,()->{},"1.4.0.test");service.connectFromUserInterface();String old=backend.latest,policy=Files.readString(root.resolve("policy.json"));Files.createDirectory(root.resolve("policy.tmp"));assertThrows(java.io.IOException.class,()->service.connectFromUserInterface());assertEquals(policy,Files.readString(root.resolve("policy.json")));assertEquals(Set.of(old),backend.candidates);Files.delete(root.resolve("policy.tmp"));service.connectFromUserInterface();assertNotEquals(old,backend.latest);assertEquals(2,backend.candidates.size());assertEquals(true,service.verifyAccount().get("account_live_verified"));
 }
 @Test public void historicalFailureCannotOverrideCurrentOperationOrInsertionOrder(){
  var history=Map.<String,Object>of("outcome_unknown",true,"action_dispatched",true,"transport_state","broken");var first=new LinkedHashMap<String,Object>();first.put("outcome_unknown",false);first.put("action_dispatched",false);first.put("last_failure",history);var second=new LinkedHashMap<String,Object>();second.put("last_failure",history);second.put("action_dispatched",false);second.put("outcome_unknown",false);var a=BugReportEnvelope.collect("mcp","1.4.0.test",first,false);var b=BugReportEnvelope.collect("mcp","1.4.0.test",second,false);assertEquals(a,b);assertEquals(false,a.get("outcome_unknown"));assertEquals(false,a.get("action_dispatched"));assertFalse(a.containsKey("transport_state"));var failure=BugReportEnvelope.collect("testclient","1.4.0.test",Map.of("diagnostics",Map.of("last_rpc_method","GET_ACTIVE_WINDOW","rpc_count",1,"bytes_received",0,"first_rpc_timeout_s",10)),true);assertEquals("GET_ACTIVE_WINDOW",failure.get("last_rpc_method"));assertEquals(1L,failure.get("rpc_count"));assertFalse(BugReportEnvelope.collect("mcp","1.4.0.test",Map.of("last_rpc_method","SECRET_PATH"),false).containsKey("last_rpc_method"));
 }
}
