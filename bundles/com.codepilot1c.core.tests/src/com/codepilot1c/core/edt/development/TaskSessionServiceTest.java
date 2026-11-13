// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.nio.file.*;

public class TaskSessionServiceTest {
    static class Backend implements TaskSessionService.Backend {
        String base="base-a",window="Fixture";int actions,cleanups;boolean fail,changed,done,condition=true;
        List<String> cleanupOrder=new ArrayList<>();
        public Map<String,Object> binding(String p,String profile){return Map.of("project",p,"base_uuid",base,"project_location","fixture","runner_sha256","runner");}
        public Map<String,Object> call(String t,Map<String,Object>a) {
            if(t.equals("edt_operation_status"))return Map.of("state",done?"DONE":"RUNNING","result",Map.of("success",true));
            if(t.equals("edt_form_action")){actions++;if(fail)throw new IllegalStateException("transport lost");return Map.of("job_id","job-own");}
            if(t.equals("edt_form_state"))return Map.of("snapshot_id","form-own","window",window,"items",List.of(condition?"Value: changed":"Value: old"),"has_more",false);
            return Map.of("run_id","dev-own");
        }
        public Map<String,Object> resource(Map<String,Object>b,String k,String id){if(id.equals("foreign"))throw new IllegalStateException("Foreign resource");return Map.of("id",id,"hash",changed?"new":"old");}
        public Map<String,Object> cleanup(Map<String,Object>b,String k,String id,Map<String,Object>e){cleanups++;cleanupOrder.add(k);return Map.of("kind",k,"id",id,"cleanup_verified",done);}
    }
    static Map<String,Object> action(){return Map.of("action","input","element","Name","value","changed","expected_window","Fixture","idempotency_key","input-1");}
    static Map<String,Object> expect(){return Map.of("kind","element_contains","element","Name","text","Value: changed","window","Fixture");}
    @Test public void bindingDriftAndForeignResourcesFailBeforeDispatch()throws Exception{try(var f=new TestDataLedgerTest.Fixture()){
        var b=new Backend();var s=new TaskSessionService(f.path,b);String id=(String)s.open("P",null,null,"task").get("session_id");
        assertThrows(IllegalStateException.class,()->s.attach(id,"host","foreign"));b.base="other";
        assertEquals(false,s.status(id).get("binding_current"));assertThrows(IllegalStateException.class,()->s.call(id,"a","edt_form_action",action(),expect()));assertEquals(0,b.actions);
    }}
    @Test public void durableStepDoesNotResendAndVerifiesOnlyAfterSuccessfulJob()throws Exception{try(var f=new TestDataLedgerTest.Fixture()){
        var b=new Backend();var s=new TaskSessionService(f.path,b);String id=(String)s.open("P",null,null,"task").get("session_id");
        s.call(id,"input","edt_form_action",action(),expect());assertEquals(false,s.stepStatus(id,"input").get("postcondition_verified"));assertEquals(1,b.actions);
        var restarted=new TaskSessionService(f.path,b);restarted.call(id,"input","edt_form_action",action(),expect());assertEquals(1,b.actions);
        assertThrows(IllegalStateException.class,()->restarted.close(id));b.done=true;assertEquals(true,restarted.stepStatus(id,"input").get("postcondition_verified"));
        b.condition=false;assertEquals(false,restarted.stepStatus(id,"input").get("postcondition_verified"));assertEquals("closed",restarted.close(id).get("state"));
    }}
    @Test public void unknownClaimSurvivesRestartAndNeverReplays()throws Exception{try(var f=new TestDataLedgerTest.Fixture()){
        var b=new Backend();var s=new TaskSessionService(f.path,b);String id=(String)s.open("P",null,null,"task").get("session_id");b.fail=true;
        assertThrows(IllegalStateException.class,()->s.call(id,"input","edt_form_action",action(),expect()));b.fail=false;var resumed=new TaskSessionService(f.path,b);
        assertThrows(IllegalStateException.class,()->resumed.call(id,"input","edt_form_action",action(),expect()));assertEquals(1,b.actions);assertThrows(IllegalStateException.class,()->resumed.preview(id));
    }}
    @Test public void staleCleanupPlanAndPendingDataBlockHostTeardown()throws Exception{try(var f=new TestDataLedgerTest.Fixture()){
        var b=new Backend();var s=new TaskSessionService(f.path,b);String id=(String)s.open("P",null,null,"task").get("session_id");s.attach(id,"host","h");s.attach(id,"data_set","s");
        String hash=(String)s.preview(id).get("plan_sha256");b.changed=true;assertThrows(IllegalStateException.class,()->s.cleanup(id,hash));assertEquals(0,b.cleanups);
        var result=s.cleanup(id,(String)s.preview(id).get("plan_sha256"));assertEquals(false,result.get("all_tracked_resources_cleaned"));assertEquals(List.of("data_set"),b.cleanupOrder);assertThrows(IllegalStateException.class,()->s.close(id));
        b.done=true;result=s.cleanup(id,(String)s.preview(id).get("plan_sha256"));assertEquals(true,result.get("all_tracked_resources_cleaned"));assertEquals(List.of("data_set","data_set","host"),b.cleanupOrder);assertEquals("closed",s.close(id).get("state"));
    }}
    @Test public void invalidPredicateAndEndpointOverridesNeverDispatch()throws Exception{try(var f=new TestDataLedgerTest.Fixture()){
        var b=new Backend();var s=new TaskSessionService(f.path,b);String id=(String)s.open("P",null,null,"task").get("session_id");
        assertThrows(IllegalArgumentException.class,()->s.call(id,"a","edt_form_action",action(),Map.of("kind","arbitrary")));assertEquals(0,b.actions);
        var a=new HashMap<>(action());a.put("endpoint","http://127.0.0.1:9999/mcp");assertThrows(IllegalArgumentException.class,()->s.call(id,"b","edt_form_action",a,expect()));assertEquals(0,b.actions);
    }}

    @Test public void queuedScenarioIsNotACompletedStep()throws Exception{try(var f=new TestDataLedgerTest.Fixture()){
        class RunBackend extends Backend {boolean terminal;
            public Map<String,Object> call(String tool,Map<String,Object>a){if(tool.equals("edt_check")&&"status".equals(a.get("action")))return Map.of("run_id","dev-own","terminal",terminal,"phase",terminal?"completed":"scenario_running");return super.call(tool,a);}}
        var backend=new RunBackend();var s=new TaskSessionService(f.path,backend);String id=(String)s.open("P",null,null,"run").get("session_id");s.call(id,"start","edt_check",Map.of("action","start","idempotency_key","run","update_db",false),Map.of());
        assertEquals("running",s.stepStatus(id,"start").get("state"));backend.terminal=true;assertEquals("completed",s.stepStatus(id,"start").get("state"));
    }}
    @Test public void auditProjectBindingMustMatchTaskTarget(){assertThrows(IllegalStateException.class,()->TaskSessionBackend.requireAuditProject(Map.of("project","Own"),"Foreign"));TaskSessionBackend.requireAuditProject(Map.of("project","Own"),"Own");}
    @Test public void segmentedTextCannotProveNegativePostcondition()throws Exception{try(var f=new TestDataLedgerTest.Fixture()){
        var backend=new Backend(){public Map<String,Object> call(String t,Map<String,Object>a){return Map.of("snapshot_id","form-own","window","Fixture","items",List.of("TA","RGET"),"has_more",false,"text_segmented",true);}};
        var s=new TaskSessionService(f.path,backend);String id=(String)s.open("P",null,null,"negative").get("session_id");var out=s.verify(id,Map.of("kind","element_not_contains","element","Name","text","TARGET"));assertEquals(false,out.get("postcondition_verified"));assertEquals(false,out.get("observation_complete"));
    }}
    @Test public void missingObservationItemsNeverProveNoMessages()throws Exception{try(var f=new TestDataLedgerTest.Fixture()){
        var backend=new Backend(){public Map<String,Object> call(String t,Map<String,Object>a){return Map.of("snapshot_id","form-own");}};var s=new TaskSessionService(f.path,backend);String id=(String)s.open("P",null,null,"no-messages").get("session_id");assertThrows(IllegalStateException.class,()->s.verify(id,Map.of("kind","messages_empty")));
    }}

    @Test public void upgradeAcceptsOnlyLegacyFileRunsOfTheCurrentBoundBase()throws Exception {
        Path root=Files.createTempDirectory("legacy-run-binding-");try {
            String connection="File=\""+root+"\";",canonical=com.codepilot1c.core.edt.infobase.InfobasePaths.connectionIdentity(connection);
            var binding=Map.<String,Object>of("project","Fixture","base_uuid","owned","base_identity_sha256",com.codepilot1c.core.edt.infobase.InfobasePaths.hash(canonical));
            var run=new HashMap<String,Object>(Map.of("project","Fixture","base_identity_sha256",com.codepilot1c.core.edt.infobase.InfobasePaths.hash(root.toRealPath().toString().toLowerCase(Locale.ROOT))));
            assertTrue(TaskSessionBackend.legacyFileRunMatches(binding,run,connection,"owned"));
            assertFalse(TaskSessionBackend.legacyFileRunMatches(binding,run,connection,"other-uuid"));assertFalse(TaskSessionBackend.legacyFileRunMatches(binding,run,"Srvr=\"localhost\";Ref=\"Fixture\";","owned"));
            run.put("project","Other");assertFalse(TaskSessionBackend.legacyFileRunMatches(binding,run,connection,"owned"));run.put("project","Fixture");run.put("base_identity_format","connection-v1");assertFalse(TaskSessionBackend.legacyFileRunMatches(binding,run,connection,"owned"));
        }finally{Files.delete(root);}
    }

    @Test public void verifiedClientSurvivesStateSerializationAndPidDriftBlocksDispatch()throws Exception{try(var f=new TestDataLedgerTest.Fixture()){
        class Bound extends Backend {long pid=42;boolean available=true;public Map<String,Object> verifyClient(String project,String endpoint){return available?Map.of("live_client_binding_verified",true,"process_id",pid,"process_started_at","2026-11-09T00:00:00Z","profile","Client","base_identity_sha256","hash","proof_kind","local_process_correlation"):ClientBindingVerifier.failed("unavailable");}}
        var b=new Bound();var s=new TaskSessionService(f.path,b);String id=(String)s.open("P",null,null,"bound",true).get("session_id");
        assertThrows(IllegalStateException.class,()->s.call(id,"before","edt_form_action",action(),expect()));assertEquals(0,b.actions);
        assertEquals(true,s.verifyClient(id).get("live_client_binding_verified"));assertEquals(true,new TaskSessionService(f.path,b).status(id).get("live_client_binding_verified"));s.call(id,"after","edt_form_action",action(),expect());assertEquals(1,b.actions);
        b.pid=43;assertThrows(IllegalStateException.class,()->s.call(id,"drift","edt_form_action",action(),expect()));assertEquals(1,b.actions);assertEquals(false,s.status(id).get("live_client_binding_verified"));
        b.available=false;assertEquals(false,s.verifyClient(id).get("live_client_binding_verified"));assertThrows(IllegalStateException.class,()->s.call(id,"unavailable","edt_form_action",action(),expect()));
    }}
 @Test public void taskWirePreservesExplicitNullExpectations(){var check=new LinkedHashMap<String,Object>(Map.of("id","null","kind","scalar_equals","query","Р’Р«Р‘Р РђРўР¬ NULL РљРђРљ Value"));check.put("expected",null);var raw=new com.google.gson.Gson().fromJson(TaskSessionBackend.argumentsJson(Map.of("checks",List.of(check))),LinkedHashMap.class);var checked=DataAssertionService.validate(raw.get("checks"));assertTrue(checked.get(0).containsKey("expected"));assertNull(checked.get(0).get("expected"));}
 @Test public void unknownAssertionOutcomeRemainsUnknownInTask()throws Exception{try(var f=new TestDataLedgerTest.Fixture()){var b=new Backend(){public Map<String,Object> call(String t,Map<String,Object>a){if(t.equals("edt_data_assert"))return "start".equals(a.get("action"))?Map.of("assertion_id","assert-own"):Map.of("state","outcome_unknown","business_result_verified",false);return super.call(t,a);}};var s=new TaskSessionService(f.path,b);String id=(String)s.open("P",null,null,"task-assert-unknown").get("session_id");s.call(id,"check","edt_data_assert",Map.of("action","start","host_id","h","idempotency_key","one"),Map.of());var result=s.stepStatus(id,"check");assertEquals("outcome_unknown",result.get("state"));assertFalse(Boolean.TRUE.equals(result.get("business_result_verified")));}}
 @Test public void fingerprintDistinguishesNullFromMissing(){var explicit=new LinkedHashMap<String,Object>();explicit.put("P",null);assertNotEquals(TaskSessionService.fingerprint(Map.of()),TaskSessionService.fingerprint(explicit));}
 @Test public void knownFailedOwnedDirectResultCanBeCollectedWithoutPassingButUnknownOrMismatchedCannot()throws Exception{
     for(String variant:List.of("known","unknown","mismatch","other_adapter"))try(var f=new TestDataLedgerTest.Fixture()){
         var b=new Backend(){
             public Map<String,Object> verifyClient(String p,String e){return Map.of("live_client_binding_verified",true,"process_id",42,"process_started_at","2026-11-09T00:00:00Z","profile","Client","base_identity_sha256","hash","proof_kind",variant.equals("other_adapter")?"local_process_correlation":"owned_direct_testclient");}
             public Map<String,Object> call(String t,Map<String,Object>a){if(t.equals("edt_form_action"))return Map.of("job_id","job-own","operation_id","original");if(t.equals("edt_operation_status"))return Map.of("state","DONE","result",DirectTestClientService.correlatedResult(Map.of("success",false,"outcome_unknown",variant.equals("unknown")),Map.of("response_mode","full"),variant.equals("mismatch")?"other":"original"));return super.call(t,a);}
         };
         var service=new TaskSessionService(f.path,b);String id=(String)service.open("P",null,null,"task",true).get("session_id");service.verifyClient(id);service.call(id,"batch","edt_form_action",action(),Map.of());var result=service.stepStatus(id,"batch");assertEquals(variant.equals("unknown")?"outcome_unknown":"failed",result.get("state"));assertEquals(false,result.get("postcondition_verified"));if(variant.equals("known"))assertEquals("closed",service.closeAfterCollectingStep(id,"batch").get("state"));else assertThrows(IllegalStateException.class,()->service.closeAfterCollectingStep(id,"batch"));
     }
 }
 @Test public void cleanupCanRecollectOriginalKnownResultAfterBindingRestoredWithoutReplay()throws Exception{try(var f=new TestDataLedgerTest.Fixture()){
     var b=new Backend(){public Map<String,Object> verifyClient(String p,String e){return Map.of("live_client_binding_verified",true,"process_id",42,"process_started_at","2026-11-09T00:00:00Z","profile","Client","base_identity_sha256","hash","proof_kind","owned_direct_testclient");}public Map<String,Object> call(String t,Map<String,Object>a){if(t.equals("edt_form_action")){actions++;return Map.of("job_id","job-own","operation_id","original");}if(t.equals("edt_operation_status"))return Map.of("state","DONE","result",DirectTestClientService.correlatedResult(Map.of("success",false,"outcome_unknown",false),Map.of("response_mode","full"),"original"));return super.call(t,a);}};
     var service=new TaskSessionService(f.path,b);String id=(String)service.open("P",null,null,"task",true).get("session_id");service.verifyClient(id);service.call(id,"batch","edt_form_action",action(),Map.of());b.base="other";assertThrows(IllegalStateException.class,()->service.closeAfterCollectingStep(id,"batch"));b.base="base-a";assertEquals("closed",service.closeAfterCollectingStep(id,"batch").get("state"));assertEquals(1,b.actions);
 }}
    @Test public void queuedNativeRequestIsPolledWithoutRedispatchAndFailureCannotCompleteStep()throws Exception{try(var f=new TestDataLedgerTest.Fixture()){
        var sends=new java.util.concurrent.atomic.AtomicInteger();var polls=new java.util.concurrent.atomic.AtomicInteger();var b=new Backend(){@Override public Map<String,Object> call(String tool,Map<String,Object> a){if(tool.equals("edt_runtime_host")){if("execute".equals(a.get("action"))){sends.incrementAndGet();return Map.of("host_id","host","request_id","request","state","queued","terminal",false);}assertEquals("result",a.get("action"));assertEquals("request",a.get("request_id"));return polls.incrementAndGet()==1?Map.of("request_id","request","state","queued","terminal",false):Map.of("request_id","request","state","completed","terminal",true,"execution",Map.of("success",false));}return super.call(tool,a);}};
        var service=new TaskSessionService(f.path,b);String id=(String)service.open("P",null,null,"native-request").get("session_id");service.call(id,"execute","edt_runtime_host",Map.of("action","execute","host_id","host","execution_mode","info","idempotency_key","once"),Map.of());assertEquals("running",service.stepStatus(id,"execute").get("state"));assertThrows(IllegalStateException.class,()->service.close(id));assertThrows(IllegalStateException.class,()->service.preview(id));assertEquals("failed",service.stepStatus(id,"execute").get("state"));var plan=service.preview(id);b.done=true;assertEquals(true,service.cleanup(id,(String)plan.get("plan_sha256")).get("all_tracked_resources_cleaned"));assertEquals("closed",service.close(id).get("state"));assertEquals(1,sends.get());assertEquals(2,polls.get());
    }}
    @Test public void nativeBlockedJobPreservesProofAndConsentDecision()throws Exception{try(var f=new TestDataLedgerTest.Fixture()){
        var b=new Backend(){@Override public Map<String,Object> call(String tool,Map<String,Object>a){if(tool.equals("edt_runtime_host"))return Map.of("job_id","prepare-job");if(tool.equals("edt_operation_status"))return Map.of("state","DONE","result",Map.of("state","blocked","execution_state","not_started","outcome_unknown",false,"retry_safe",true,"decision",Map.of("kind","runtime_launch_and_use")));return super.call(tool,a);}};
        var service=new TaskSessionService(f.path,b);String id=(String)service.open("P",null,null,"native-blocked").get("session_id");service.call(id,"prepare","edt_runtime_host",Map.of("action","prepare","mode","external"),Map.of());var result=service.stepStatus(id,"prepare");assertEquals("blocked",result.get("state"));assertEquals("not_started",result.get("execution_state"));assertEquals(false,result.get("outcome_unknown"));assertTrue(result.containsKey("decision"));
    }}
    @Test public void knownConsentPauseCanBeCollectedForCleanupWithoutRelaunch()throws Exception{try(var f=new TestDataLedgerTest.Fixture()){
        var sends=new java.util.concurrent.atomic.AtomicInteger();var b=new Backend(){@Override public Map<String,Object> call(String tool,Map<String,Object>a){if(tool.equals("edt_runtime_host")){sends.incrementAndGet();return Map.of("job_id","start-job");}if(tool.equals("edt_operation_status"))return Map.of("state","DONE","result",Map.of("state","awaiting_consent","process_started",false,"execution_state","launch_not_dispatched","outcome_unknown",false,"decision",Map.of("kind","runtime_launch_and_use")));return super.call(tool,a);}};
        var service=new TaskSessionService(f.path,b);String id=(String)service.open("P",null,null,"consent-pause").get("session_id");service.call(id,"start","edt_runtime_host",Map.of("action","start","host_id","host"),Map.of());assertEquals("blocked",service.stepStatus(id,"start").get("state"));assertEquals("closed",service.close(id).get("state"));assertEquals(1,sends.get());
    }}
    @Test public void terminalFailedCheckIsCollectedWithoutBecomingSuccessful()throws Exception{try(var f=new TestDataLedgerTest.Fixture()){
        var b=new Backend(){@Override public Map<String,Object> call(String t,Map<String,Object>a){if(t.equals("edt_check"))return a.get("action").equals("start")?Map.of("run_id","own-run"):Map.of("run_id","own-run","terminal",true,"phase","failed");return super.call(t,a);}};var service=new TaskSessionService(f.path,b);String id=(String)service.open("P",null,null,"failed-check").get("session_id");service.call(id,"check","edt_check",Map.of("action","start"),Map.of());assertThrows(IllegalStateException.class,()->service.close(id));assertEquals("failed",service.stepStatus(id,"check").get("state"));var preview=service.preview(id);b.done=true;assertEquals(true,service.cleanup(id,(String)preview.get("plan_sha256")).get("all_tracked_resources_cleaned"));assertEquals("closed",service.close(id).get("state"));
    }}
    @Test public void terminalAssertionMismatchIsCollectedButUnknownNeverIs()throws Exception{for(boolean unknown:List.of(false,true))try(var f=new TestDataLedgerTest.Fixture()){
        var b=new Backend(){@Override public Map<String,Object> call(String t,Map<String,Object>a){if(t.equals("edt_data_assert"))return a.get("action").equals("start")?Map.of("assertion_id","assert-own"):Map.of("assertion_id","assert-own","terminal",true,"outcome_unknown",unknown,"business_result_verified",false,"state",unknown?"outcome_unknown":"failed");return super.call(t,a);}};var service=new TaskSessionService(f.path,b);String id=(String)service.open("P",null,null,"assert-failed").get("session_id");service.call(id,"assert","edt_data_assert",Map.of("action","start","host_id","host","idempotency_key","a","checks",List.of()),Map.of());var result=service.stepStatus(id,"assert");assertEquals(unknown?"outcome_unknown":"failed",result.get("state"));if(unknown)assertThrows(IllegalStateException.class,()->service.preview(id));else assertNotNull(service.preview(id).get("plan_sha256"));
    }}
    @Test public void invalidNativeExecutionKeyDoesNotClaimTaskOrDispatch()throws Exception{try(var f=new TestDataLedgerTest.Fixture()){
        var sends=new java.util.concurrent.atomic.AtomicInteger();var b=new Backend(){public Map<String,Object> call(String t,Map<String,Object>a){sends.incrementAndGet();throw new AssertionError("No native execution may dispatch");}};
        var service=new TaskSessionService(f.path,b);String id=(String)service.open("P",null,null,"invalid-native-key").get("session_id");
        for(var arguments:List.of(Map.<String,Object>of("action","execute","host_id","host"),Map.<String,Object>of("action","execute","host_id","host","idempotency_key","invalid/key")))assertThrows(IllegalArgumentException.class,()->service.call(id,"invalid","edt_runtime_host",arguments,Map.of()));
        assertEquals(0,sends.get());assertEquals("closed",service.close(id).get("state"));
    }}
    @Test public void invalidNativePayloadNeverClaimsTaskOrDispatches()throws Exception {
        var invalid=new ArrayList<Map<String,Object>>();
        invalid.add(Map.of("execution_mode","query","context","client","query","SELECT 1"));
        invalid.add(Map.of("execution_mode","execute"));
        invalid.add(Map.of("execution_mode","unknown"));
        invalid.add(Map.of("execution_mode","batch","steps",List.of()));
        invalid.add(Map.of("execution_mode","info","context","invalid"));
        invalid.add(Map.of("execution_mode","info","parameters",List.of("invalid")));
        invalid.add(Map.of("execution_mode","info","parameters",Map.of("large","x".repeat(128*1024))));
        invalid.add(Map.of("execution_mode","execute","code","\"".repeat(70000)));
        invalid.add(Map.of("execution_mode","info","compact","invalid"));
        invalid.add(Map.of("execution_mode","info","cursor","invalid"));
        invalid.add(Map.of("execution_mode","info","timeout_ms",1000));
        invalid.add(Map.of("execution_mode","info","expected_plan_sha256","invalid"));
        invalid.add(Map.of("execution_mode","info","grid",true));
        invalid.add(Map.of("execution_mode","query","query","SELECT 1","limit",1.5));
        for(var payload:invalid)try(var f=new TestDataLedgerTest.Fixture()){
            var sends=new java.util.concurrent.atomic.AtomicInteger();
            var backend=new Backend(){public Map<String,Object> call(String t,Map<String,Object>a){sends.incrementAndGet();throw new AssertionError("Invalid Native payload dispatched");}};
            var service=new TaskSessionService(f.path,backend);String id=(String)service.open("P",null,null,"invalid-payload").get("session_id");
            var args=new HashMap<String,Object>(payload);args.putAll(Map.of("action","execute","host_id","host","idempotency_key","once"));
            assertThrows(IllegalArgumentException.class,()->service.call(id,"invalid","edt_runtime_host",args,Map.of()));
            assertEquals(0,sends.get());assertEquals("closed",service.close(id).get("state"));
        }
    }
    @Test public void nativePlanSnapshotsNestedParametersBeforeClaim()throws Exception {
        var nested=new HashMap<String,Object>();nested.put("value","before");nested.put("nullable",null);
        var plan=NativeExecutionPlan.prepare(Map.of("execution_mode","info","idempotency_key","once","parameters",Map.of("nested",nested)));
        nested.put("value","after");var body=plan.body();assertEquals("before",body.getAsJsonObject("parameters").getAsJsonObject("nested").get("value").getAsString());
        body.addProperty("code","tampered");assertNotEquals("tampered",plan.body().get("code").getAsString());
    }
    @Test public void nativeReadOnlyPreflightRejectionIsKnownAndCollectable()throws Exception {try(var f=new TestDataLedgerTest.Fixture()){
        var backend=new Backend(){public Map<String,Object> call(String t,Map<String,Object>a){try{return NativeExecutionGate.execute(()->{throw new IllegalStateException("Host is stopped");});}catch(Exception failure){throw new AssertionError(failure);}}};
        var service=new TaskSessionService(f.path,backend);String id=(String)service.open("P",null,null,"stopped-native").get("session_id");
        var result=service.call(id,"execute","edt_runtime_host",Map.of("action","execute","host_id","host","execution_mode","info","idempotency_key","once"),Map.of());
        assertEquals("not_dispatched",result.get("execution_state"));assertEquals(false,result.get("outcome_unknown"));
        assertEquals("failed",service.stepStatus(id,"execute").get("state"));assertEquals("closed",service.close(id).get("state"));
    }}
    @Test public void nativeDispatchFailureNeverBecomesPreflightRejection()throws Exception {
        var failure=new java.io.IOException("Receipt lost after persistence");
        var actual=assertThrows(java.io.IOException.class,()->NativeExecutionGate.execute(()->()->{throw failure;}));assertSame(failure,actual);
    }
    @Test public void nativeCompatibilityRejectionKeepsItsRecoveryContract()throws Exception {
        var result=NativeExecutionGate.execute(()->{throw new NativeHostService.CompatibilityRequired("host",Map.of("compatible",false));});
        assertEquals("not_dispatched",result.get("execution_state"));assertEquals(false,result.get("outcome_unknown"));assertEquals("blocked",result.get("state"));assertTrue(result.containsKey("next_call"));
    }
    @Test public void incompleteOrContradictoryNativeRejectionNeverProvesNoDispatch(){
        var proof=new HashMap<String,Object>(Map.of("success",false,"terminal",true,"outcome_unknown",false,"execution_state","not_dispatched","state","failed"));assertTrue(NativeExecutionGate.notDispatched(proof));
        for(String field:List.of("success","terminal","outcome_unknown","execution_state","state")){var incomplete=new HashMap<>(proof);incomplete.remove(field);assertFalse(NativeExecutionGate.notDispatched(incomplete));}
        var request=new HashMap<>(proof);request.put("request_id","submitted");assertFalse(NativeExecutionGate.notDispatched(request));var unknown=new HashMap<>(proof);unknown.put("outcome_unknown",true);assertFalse(NativeExecutionGate.notDispatched(unknown));
    }
    @Test public void batchWaitReleasesSessionMonitorAndConcurrentObserversDoNotDuplicateWork()throws Exception {try(var f=new TestDataLedgerTest.Fixture()){
        var observed=new java.util.concurrent.CountDownLatch(1);var complete=new java.util.concurrent.atomic.AtomicBoolean();
        var backend=new Backend(){public Map<String,Object> call(String tool,Map<String,Object>a){if(tool.equals("edt_operation_status")){observed.countDown();return Map.of("state",complete.get()?"DONE":"RUNNING","result",Map.of("success",true));}return super.call(tool,a);}};
        var service=new TaskSessionService(f.path,backend);String id=(String)service.open("P",null,null,"batch-concurrency").get("session_id");var plan=List.of(Map.<String,Object>of("step_key","one","tool","edt_form_action","arguments",action(),"expect",expect()));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(3);try{
            var first=pool.submit(()->service.batchAdvance(id,"batch",plan,3000));assertTrue(observed.await(1,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("open",pool.submit(()->service.status(id)).get(1,java.util.concurrent.TimeUnit.SECONDS).get("state"));
            assertEquals("running",pool.submit(()->service.batchStatus(id,"batch")).get(1,java.util.concurrent.TimeUnit.SECONDS).get("state"));
            var second=pool.submit(()->service.batchAdvance(id,"batch",plan,3000));complete.set(true);
            assertEquals("completed",first.get(2,java.util.concurrent.TimeUnit.SECONDS).get("state"));assertEquals("completed",second.get(2,java.util.concurrent.TimeUnit.SECONDS).get("state"));assertEquals(1,backend.actions);
            assertEquals(1,((List<?>)service.batchStatus(id,"batch").get("results")).size());
        }finally{complete.set(true);pool.shutdownNow();pool.awaitTermination(2,java.util.concurrent.TimeUnit.SECONDS);}
    }}
    @Test public void nativeReadinessExplainsEveryExecutionBarrier(){
        var healthy=Map.<String,Object>of("state","running","compatibility",Map.of("compatible",true));assertTrue(NativeExecutionGate.readiness(healthy,true,true,false,false).isEmpty());
        assertTrue(NativeExecutionGate.readiness(healthy,false,true,false,false).contains("consent_or_startup_question"));assertTrue(NativeExecutionGate.readiness(healthy,true,false,false,false).contains("base_binding_changed"));assertTrue(NativeExecutionGate.readiness(healthy,true,true,true,false).contains("refresh_in_progress"));assertTrue(NativeExecutionGate.readiness(healthy,true,true,false,true).contains("cleanup_in_progress"));assertTrue(NativeExecutionGate.readiness(Map.of("state","running","compatibility",Map.of("compatible",false)),true,true,false,false).contains("native_refresh_required"));
    }
    @Test public void deniedLeafDoesNotClaimKeyAndCanBeAllowedLater()throws Exception{try(var f=new TestDataLedgerTest.Fixture()){
        class Authorized extends Backend {boolean denied=true;public void authorize(String tool){if(denied)throw new IllegalStateException("leaf denied");}}
        var b=new Authorized();var service=new TaskSessionService(f.path,b);String id=(String)service.open("P",null,null,"auth").get("session_id");
        assertThrows(IllegalStateException.class,()->service.call(id,"same","edt_form_action",action(),Map.of()));assertEquals(0,b.actions);
        b.denied=false;service.call(id,"same","edt_form_action",action(),Map.of());assertEquals(1,b.actions);
        b.denied=true;assertThrows(IllegalStateException.class,()->service.stepStatus(id,"same"));assertEquals(1,b.actions);
    }}

}
