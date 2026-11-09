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
 @Test public void taskWirePreservesExplicitNullExpectations(){var check=new LinkedHashMap<String,Object>(Map.of("id","null","kind","scalar_equals","query","ВЫБРАТЬ NULL КАК Value"));check.put("expected",null);var raw=new com.google.gson.Gson().fromJson(TaskSessionBackend.argumentsJson(Map.of("checks",List.of(check))),LinkedHashMap.class);var checked=DataAssertionService.validate(raw.get("checks"));assertTrue(checked.get(0).containsKey("expected"));assertNull(checked.get(0).get("expected"));}
 @Test public void unknownAssertionOutcomeRemainsUnknownInTask()throws Exception{try(var f=new TestDataLedgerTest.Fixture()){var b=new Backend(){public Map<String,Object> call(String t,Map<String,Object>a){if(t.equals("edt_data_assert"))return "start".equals(a.get("action"))?Map.of("assertion_id","assert-own"):Map.of("state","outcome_unknown","business_result_verified",false);return super.call(t,a);}};var s=new TaskSessionService(f.path,b);String id=(String)s.open("P",null,null,"task-assert-unknown").get("session_id");s.call(id,"check","edt_data_assert",Map.of("action","start","host_id","h","idempotency_key","one"),Map.of());var result=s.stepStatus(id,"check");assertEquals("outcome_unknown",result.get("state"));assertFalse(Boolean.TRUE.equals(result.get("business_result_verified")));}}
 @Test public void fingerprintDistinguishesNullFromMissing(){var explicit=new LinkedHashMap<String,Object>();explicit.put("P",null);assertNotEquals(TaskSessionService.fingerprint(Map.of()),TaskSessionService.fingerprint(explicit));}
}
