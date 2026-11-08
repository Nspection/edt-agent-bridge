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

}
