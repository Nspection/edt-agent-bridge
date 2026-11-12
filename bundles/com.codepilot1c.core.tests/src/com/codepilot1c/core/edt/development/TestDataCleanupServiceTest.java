// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import static org.junit.Assert.*;
import java.util.*;
import java.io.IOException;
import org.junit.Test;
import com.google.gson.*;
public class TestDataCleanupServiceTest {
    static class Backend implements TestDataCreateService.Backend {
        TestDataLedger.Target actual=TestDataCreateServiceTest.TARGET;int submits;boolean rejected,loseAck,wrongNonce,failed,wrongRequest,unavailable;String outcome="marked_for_deletion",request;Map<String,Object> params;
        public TestDataLedger.Target target(String id,boolean running){if(unavailable)throw new IllegalStateException("Host stopped");return actual;}
        public Map<String,Object> submit(String id,String key,String code,Map<String,Object> parameters)throws Exception {submits++;params=parameters;if(rejected)return Map.of("success",false,"terminal",true,"outcome_unknown",false,"execution_state","not_dispatched","state","failed");request=UUID.randomUUID().toString();if(loseAck)throw new IOException("lost");return Map.of("request_id",request);}
        public Map<String,Object> result(String id,String request){Map<String,Object> receipt=new HashMap<>(params);receipt.put("protocol","edt-test-data-cleanup-v1");receipt.put("outcome",outcome);if(wrongNonce)receipt.put("nonce",UUID.randomUUID().toString());return Map.of("request_id",wrongRequest?UUID.randomUUID().toString():request,"state",failed?"failed":"completed","terminal",true,"execution",Map.of("success",!failed,"value",receipt));}
    }
    static class Fixture implements AutoCloseable {
        final TestDataCreateServiceTest.Fixture create=new TestDataCreateServiceTest.Fixture();final Backend backend=new Backend();final TestDataCleanupService service=new TestDataCleanupService(create.files.ledger,backend);final String record;
        Fixture()throws Exception{var op=create.create();record=create.poll(op).getAsJsonObject("record").get("record_id").getAsString();}
        String plan()throws Exception{return create.files.ledger.preview(create.files.id,TestDataCreateServiceTest.TARGET,0,50).get("plan_sha256").getAsString();}
        JsonObject start()throws Exception{return service.start(create.files.id,TestDataCreateServiceTest.TARGET,"cleanup-one",TestDataCreateServiceTest.HOST,record,plan());}
        JsonObject poll(JsonObject op)throws Exception{return service.status(create.files.id,TestDataCreateServiceTest.TARGET,op.get("cleanup_id").getAsString());}
        public void close()throws Exception{create.close();}
    }
    @Test public void markReceiptIsDurableButNeverClaimsPhysicalDeletion()throws Exception {
        try(Fixture f=new Fixture()){var op=f.start();var result=f.poll(op);assertEquals("completed",result.get("state").getAsString());assertTrue(result.get("marked_for_deletion").getAsBoolean());assertFalse(result.get("database_cleanup_verified").getAsBoolean());assertFalse(result.get("physical_delete_performed").getAsBoolean());assertTrue(result.get("data_may_remain").getAsBoolean());assertEquals(0,result.get("pending_cleanups").getAsInt());
            assertEquals(result,new TestDataCleanupService(new TestDataLedger(f.create.files.path),f.backend).status(f.create.files.id,TestDataCreateServiceTest.TARGET,op.get("cleanup_id").getAsString()));f.start();assertEquals(1,f.backend.submits);
        }
    }
    @Test public void lostAckCannotReplayAndBlocksJournalClose()throws Exception {
        try(Fixture f=new Fixture()){f.backend.loseAck=true;var result=f.start();assertEquals("outcome_unknown",result.get("state").getAsString());assertEquals("outcome_unknown",f.start().get("state").getAsString());assertEquals(1,f.backend.submits);assertThrows(IllegalStateException.class,()->f.create.files.ledger.close(f.create.files.id,TestDataCreateServiceTest.TARGET,f.plan()));}
    }
    @Test public void changedVersionPostedPredefinedAndAlreadyMarkedAreSkipped()throws Exception {
        for(String outcome:List.of("version_changed","posted","predefined","already_marked"))try(Fixture f=new Fixture()){f.backend.outcome=outcome;var result=f.poll(f.start());assertEquals("skipped",result.get("state").getAsString());assertEquals(outcome,result.get("outcome").getAsString());assertFalse(result.get("marked_for_deletion").getAsBoolean());}
    }
    @Test public void rollbackIsFailureNotCleaned()throws Exception{try(Fixture f=new Fixture()){f.backend.outcome="rolled_back";var result=f.poll(f.start());assertEquals("failed",result.get("state").getAsString());assertFalse(result.get("marked_for_deletion").getAsBoolean());}}
    @Test public void missingObjectIsObservationNotDeletion()throws Exception{try(Fixture f=new Fixture()){f.backend.outcome="already_absent";var result=f.poll(f.start());assertEquals("already_absent",result.get("outcome").getAsString());assertFalse(result.get("physical_delete_performed").getAsBoolean());}}
    @Test public void wrongReceiptRequestOrExecutionFailureCannotConfirmCleanup()throws Exception {
        for(int i=0;i<3;i++)try(Fixture f=new Fixture()){var op=f.start();f.backend.wrongNonce=i==0;f.backend.wrongRequest=i==1;f.backend.failed=i==2;assertEquals("outcome_unknown",f.poll(op).get("state").getAsString());}
    }
    @Test public void wrongHostBaseAndStalePlanCannotDispatch()throws Exception {
        try(Fixture f=new Fixture()){f.backend.actual=new TestDataLedger.Target("Other",TestDataCreateServiceTest.TARGET.baseUuid());assertThrows(IllegalStateException.class,()->f.start());assertEquals(0,f.backend.submits);}
        try(Fixture f=new Fixture()){assertThrows(IllegalStateException.class,()->f.service.start(f.create.files.id,TestDataCreateServiceTest.TARGET,"cleanup",TestDataCreateServiceTest.HOST,f.record,"bad"));assertEquals(0,f.backend.submits);}
    }
    @Test public void manualDeclarationsCannotBeCleaned()throws Exception {
        try(TestDataLedgerTest.Fixture f=new TestDataLedgerTest.Fixture()){f.ledger.register(f.id,TestDataCreateServiceTest.TARGET,0,List.of(TestDataLedgerTest.entry()));var p=f.ledger.preview(f.id,TestDataCreateServiceTest.TARGET,0,50);String record=p.getAsJsonArray("records").get(0).getAsJsonObject().get("record_id").getAsString();Backend b=new Backend();assertThrows(IllegalArgumentException.class,()->new TestDataCleanupService(f.ledger,b).start(f.id,TestDataCreateServiceTest.TARGET,"cleanup",TestDataCreateServiceTest.HOST,record,p.get("plan_sha256").getAsString()));assertEquals(0,b.submits);}
    }
    @Test public void concurrentClaimsOnlyDispatchOnce()throws Exception {
        try(Fixture f=new Fixture()){var ledger=f.create.files.ledger;var op=ledger.reserveCleanup(f.create.files.id,TestDataCreateServiceTest.TARGET,"cleanup-one",TestDataCreateServiceTest.HOST,f.record,f.plan());String id=op.get("cleanup_id").getAsString();assertTrue(ledger.claimCleanup(f.create.files.id,TestDataCreateServiceTest.TARGET,id).get("claim_acquired").getAsBoolean());assertFalse(ledger.claimCleanup(f.create.files.id,TestDataCreateServiceTest.TARGET,id).get("claim_acquired").getAsBoolean());assertEquals("outcome_unknown",f.start().get("state").getAsString());assertEquals(0,f.backend.submits);}
    }
    @Test public void recipesNeverPhysicallyDeleteOrDisableBusinessHandlers()throws Exception {
        for(String type:List.of("Catalog.TestItems","Document.TestDocument")){String code=TestDataCleanupService.code(type);assertTrue(code.contains(type.startsWith("Catalog.")?"УстановитьПометкуУдаления(Истина, Ложь)":"УстановитьПометкуУдаления(Истина)"));assertTrue(code.contains("expected_version"));assertTrue(code.contains("БлокировкаУборки.Заблокировать()"));assertFalse(code.contains(".Удалить("));assertFalse(code.contains("ПривилегированныйРежим"));assertFalse(code.contains("__"));}
    }
    @Test public void completedRetryDoesNotNeedRunningHost()throws Exception{try(Fixture f=new Fixture()){var done=f.poll(f.start());f.backend.unavailable=true;assertEquals(done.get("cleanup_id"),f.start().get("cleanup_id"));assertEquals(1,f.backend.submits);}}

    @Test public void preparedIntentResumesOnceWithoutReplayingUnknown()throws Exception{try(Fixture f=new Fixture()){
        var op=f.create.files.ledger.reserveCleanup(f.create.files.id,TestDataCreateServiceTest.TARGET,"before-crash",TestDataCreateServiceTest.HOST,f.record,f.plan());String id=op.get("cleanup_id").getAsString();
        var submitted=f.service.resumePrepared(f.create.files.id,TestDataCreateServiceTest.TARGET,id);assertEquals(1,f.backend.submits);assertEquals("completed",f.service.resumePrepared(f.create.files.id,TestDataCreateServiceTest.TARGET,id).get("state").getAsString());assertEquals(1,f.backend.submits);
    }}

    @Test public void knownCleanupRejectionPreservesRecordAndCannotReplay()throws Exception {try(Fixture f=new Fixture()){
        f.backend.rejected=true;var result=f.start();assertEquals("failed",result.get("state").getAsString());assertEquals("not_dispatched",result.get("execution_state").getAsString());assertFalse(result.get("marked_for_deletion").getAsBoolean());assertEquals(0,result.get("pending_cleanups").getAsInt());
        var restarted=new TestDataCleanupService(new TestDataLedger(f.create.files.path),f.backend);assertEquals("failed",restarted.status(f.create.files.id,TestDataCreateServiceTest.TARGET,result.get("cleanup_id").getAsString()).get("state").getAsString());assertEquals("failed",f.start().get("state").getAsString());assertEquals(1,f.backend.submits);assertEquals(1,f.create.files.ledger.list(f.create.files.id,TestDataCreateServiceTest.TARGET,0,10).get("total").getAsInt());
    }}
}
