// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import static org.junit.Assert.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import com.google.gson.*;
public class TestDataCreateServiceTest {
    static final String HOST="0123456789abcdef0123456789abcdef";
    static final TestDataLedger.Target TARGET=TestDataLedgerTest.TARGET;
    static class Backend implements TestDataCreateService.Backend {
        TestDataLedger.Target actual=TARGET;int submits,targetChecks;boolean rejected,loseAck,unavailable,executionFailure,rolledBack,wrongNonce,wrongRequest,missingReceipt;
        String request=UUID.randomUUID().toString(),code;Map<String,Object> parameters=Map.of();
        public TestDataLedger.Target target(String host,boolean running){targetChecks++;if(unavailable)throw new IllegalStateException("Host unavailable");return actual;}
        public Map<String,Object> submit(String host,String key,String code,Map<String,Object> parameters){submits++;this.code=code;this.parameters=parameters;if(rejected)return Map.of("success",false,"terminal",true,"outcome_unknown",false,"execution_state","not_dispatched","state","failed");if(loseAck)throw new IllegalStateException("Reply lost after queueing");return Map.of("request_id",request);}
        public Map<String,Object> result(String host,String requestId){
            Map<String,Object> receipt=new LinkedHashMap<>();receipt.put("protocol","edt-test-data-create-v1");
            for(String name:List.of("creation_id","nonce","metadata_fqn","uuid"))receipt.put(name,parameters.get(name));
            if(wrongNonce)receipt.put("nonce",UUID.randomUUID().toString());
            receipt.put("created",!rolledBack);if(rolledBack)receipt.put("rolled_back",true);else receipt.put("expected_version","version-at-write");
            return Map.of("request_id",wrongRequest?UUID.randomUUID().toString():request,"terminal",true,"state",executionFailure?"failed":"completed","execution",Map.of("success",!executionFailure,"value",missingReceipt?Map.of():receipt));
        }
    }
    static class Fixture implements AutoCloseable {
        final TestDataLedgerTest.Fixture files=new TestDataLedgerTest.Fixture();final Backend backend=new Backend();final TestDataCreateService service=new TestDataCreateService(files.ledger,backend);
        Fixture()throws Exception{}
        JsonObject create()throws Exception{return service.create(files.id,TARGET,0,"create-one",HOST,"Catalog.TestItems",Map.of("Наименование","Sample"));}
        JsonObject poll(JsonObject op)throws Exception{return service.status(files.id,TARGET,op.get("creation_id").getAsString());}
        public void close()throws Exception{files.close();}
    }
    @Test public void nativeReceiptCreatesConfirmedRecordAndSurvivesRestart()throws Exception {
        try(Fixture f=new Fixture()){
            var op=f.create();assertFalse(op.get("confirmed").getAsBoolean());assertEquals(0,f.files.ledger.list(f.files.id,TARGET,0,10).get("total").getAsInt());
            var done=f.poll(op);assertEquals("completed",done.get("state").getAsString());assertTrue(done.get("confirmed").getAsBoolean());
            var ledger=new TestDataLedger(f.files.path);var row=ledger.list(f.files.id,TARGET,0,10).getAsJsonArray("records").get(0).getAsJsonObject();
            assertEquals("native_host_confirmed",row.get("ownership").getAsString());assertEquals(f.backend.request,row.get("source_request_id").getAsString());assertEquals("version-at-write",row.get("expected_version").getAsString());
            f.backend.unavailable=true;var after=new TestDataCreateService(ledger,f.backend).create(f.files.id,TARGET,0,"create-one",HOST,"Catalog.TestItems",Map.of("Наименование","Sample"));assertTrue(after.get("confirmed").getAsBoolean());assertEquals(1,f.backend.submits);
        }
    }
    @Test public void nonceMismatchNeverPromotesDeclarationToOwnership()throws Exception {
        try(Fixture f=new Fixture()){
            var op=f.create();f.backend.wrongNonce=true;var result=f.poll(op);assertEquals("outcome_unknown",result.get("state").getAsString());assertFalse(result.get("confirmed").getAsBoolean());
            var preview=f.files.ledger.preview(f.files.id,TARGET,0,10);assertEquals(0,preview.get("total").getAsInt());assertTrue(preview.get("data_may_remain").getAsBoolean());assertFalse(preview.get("physical_delete_allowed").getAsBoolean());
            assertThrows(IllegalStateException.class,()->f.files.ledger.close(f.files.id,TARGET,preview.get("plan_sha256").getAsString()));
        }
    }
    @Test public void lostSubmissionAcknowledgementIsNotResubmitted()throws Exception {
        try(Fixture f=new Fixture()){
            f.backend.loseAck=true;var result=f.create();assertEquals("outcome_unknown",result.get("state").getAsString());
            var restarted=new TestDataCreateService(new TestDataLedger(f.files.path),f.backend);var retry=restarted.create(f.files.id,TARGET,0,"create-one",HOST,"Catalog.TestItems",Map.of("Наименование","Sample"));assertEquals("outcome_unknown",retry.get("state").getAsString());assertEquals(1,f.backend.submits);
        }
    }
    @Test public void runtimeFailureOrMissingReceiptIsNotProofOfRollback()throws Exception {
        try(Fixture f=new Fixture()){var op=f.create();f.backend.executionFailure=true;assertEquals("outcome_unknown",f.poll(op).get("state").getAsString());assertEquals(0,f.files.ledger.list(f.files.id,TARGET,0,10).get("total").getAsInt());}
        try(Fixture f=new Fixture()){var op=f.create();f.backend.missingReceipt=true;assertEquals("outcome_unknown",f.poll(op).get("state").getAsString());}
    }
    @Test public void explicitRollbackRemainsFailureWithNoOwnedRecord()throws Exception {
        try(Fixture f=new Fixture()){
            var op=f.create();f.backend.rolledBack=true;var result=f.poll(op);assertEquals("failed",result.get("state").getAsString());assertFalse(result.get("confirmed").getAsBoolean());assertEquals(0,result.get("total").getAsInt());assertEquals(0,result.get("pending_creations").getAsInt());
            assertEquals("failed",f.create().get("state").getAsString());assertEquals(1,f.backend.submits);
        }
    }
    @Test public void nativeHostMustMatchProjectAndDatabaseBeforeDispatch()throws Exception {
        try(Fixture f=new Fixture()){
            f.backend.actual=new TestDataLedger.Target("Other",TARGET.baseUuid());assertThrows(IllegalStateException.class,()->f.create());assertEquals(0,f.backend.submits);assertEquals(0,f.files.ledger.list(f.files.id,TARGET,0,10).get("pending_creations").getAsInt());
        }
        try(Fixture f=new Fixture()){
            var op=f.create();f.backend.actual=new TestDataLedger.Target(TARGET.project(),"12345678-1234-1234-1234-123456789abd");assertThrows(IllegalStateException.class,()->f.poll(op));assertEquals(0,f.files.ledger.list(f.files.id,TARGET,0,10).get("total").getAsInt());
        }
    }
    @Test public void aDifferentNativeRequestCannotConfirmCreation()throws Exception {
        try(Fixture f=new Fixture()){var op=f.create();f.backend.wrongRequest=true;assertEquals("outcome_unknown",f.poll(op).get("state").getAsString());assertEquals(0,f.files.ledger.list(f.files.id,TARGET,0,10).get("total").getAsInt());}
    }
    @Test public void changedArgumentsAndStaleRevisionDoNotWrite()throws Exception {
        try(Fixture f=new Fixture()){
            f.create();assertThrows(IllegalArgumentException.class,()->f.service.create(f.files.id,TARGET,0,"create-one",HOST,"Catalog.TestItems",Map.of("Наименование","Changed")));assertEquals(1,f.backend.submits);
            assertThrows(IllegalStateException.class,()->f.files.ledger.register(f.files.id,TARGET,1,List.of(TestDataLedgerTest.entry())));
        }
        try(Fixture f=new Fixture()){
            f.files.ledger.register(f.files.id,TARGET,0,List.of(TestDataLedgerTest.entry()));assertThrows(IllegalStateException.class,()->f.create());assertEquals(0,f.backend.submits);
        }
    }
    @Test public void submissionClaimHasSingleOwnerAndSurvivesProcessLoss()throws Exception {
        try(Fixture f=new Fixture()){
            var spec=TestDataCreatePlan.normalize("Catalog.TestItems",Map.of("Наименование","Sample"));var op=f.files.ledger.reserveCreation(f.files.id,TARGET,0,"create-one",HOST,spec);String id=op.get("creation_id").getAsString();
            assertTrue(f.files.ledger.claimCreation(f.files.id,TARGET,id).get("claim_acquired").getAsBoolean());assertFalse(f.files.ledger.claimCreation(f.files.id,TARGET,id).get("claim_acquired").getAsBoolean());
            var result=new TestDataCreateService(new TestDataLedger(f.files.path),f.backend).create(f.files.id,TARGET,0,"create-one",HOST,"Catalog.TestItems",Map.of("Наименование","Sample"));assertEquals("outcome_unknown",result.get("state").getAsString());assertEquals(0,f.backend.submits);
        }
    }
    @Test public void valuesAreParametersAndDecimalPrecisionSurvivesJournal()throws Exception {
        try(Fixture f=new Fixture()){
            String attack="\");Выполнить(\"unexpected\");";BigDecimal number=new BigDecimal("12345678901234567890123456789012345678");
            var op=f.service.create(f.files.id,TARGET,0,"precision",HOST,"Catalog.TestItems",Map.of("Наименование",attack,"Amount",number,"Дата",Map.of("type","date","value","20261108120000")));
            assertFalse(f.backend.code.contains(attack));assertTrue(f.backend.code.contains("СоздатьЭлемент"));assertFalse(f.backend.code.contains("УстановитьПривилегированныйРежим"));
            var fields=(Map<?,?>)f.backend.parameters.get("fields");assertEquals(number,fields.get("Amount"));assertEquals(attack,fields.get("Наименование"));assertTrue(f.poll(op).get("confirmed").getAsBoolean());
        }
    }
    @Test public void protectedFieldsReferencesAndInvalidDatesAreRejectedBeforeDispatch()throws Exception {
        try(Fixture f=new Fixture()){
            for(Map<String,Object> fields:List.<Map<String,Object>>of(Map.of("Ссылка","x"),Map.of("ПометкаУдаления",true),Map.of("ОбменДанными",true),Map.of("Parent",Map.of("type","reference","value","x")),Map.of("Дата",Map.of("type","date","value","20260230120000"))))assertThrows(IllegalArgumentException.class,()->f.service.create(f.files.id,TARGET,0,"invalid",HOST,"Catalog.TestItems",fields));
            assertThrows(IllegalArgumentException.class,()->f.service.create(f.files.id,TARGET,0,"invalid",HOST,"Catalog.X; Выполнить",Map.of()));assertEquals(0,f.backend.submits);
        }
    }
    @Test public void manualRegistrationCannotForgeConfirmedOwnership()throws Exception {
        try(Fixture f=new Fixture()){
            var record=new HashMap<String,Object>(TestDataLedgerTest.entry());record.put("ownership","native_host_confirmed");assertThrows(IllegalArgumentException.class,()->f.files.ledger.register(f.files.id,TARGET,0,List.of(record)));
        }
    }
    @Test public void documentCreationUsesExplicitWriteModeAndKeepsTypedReceipt()throws Exception {
        try(Fixture f=new Fixture()){
            var op=f.service.create(f.files.id,TARGET,0,"document",HOST,"Document.TestDocument",Map.of("Дата",Map.of("type","date","value","20261108120000")));
            assertTrue(f.backend.code.contains("Документы.TestDocument"));assertTrue(f.backend.code.contains("РежимЗаписиДокумента.Запись"));
            var done=f.poll(op);assertTrue(done.get("confirmed").getAsBoolean());assertEquals("Document.TestDocument",done.getAsJsonObject("record").get("metadata_fqn").getAsString());
        }
    }
    @Test public void confirmedProofCannotBeDetachedFromLedgerReference()throws Exception {
        try(Fixture f=new Fixture()){
            var op=f.create();f.poll(op);Path file=f.files.path.resolve(f.files.id+".json");JsonObject corrupt=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            corrupt.add("records",new JsonArray());Files.writeString(file,corrupt.toString());
            assertThrows(IllegalStateException.class,()->f.service.status(f.files.id,TARGET,op.get("creation_id").getAsString()));assertEquals(1,f.backend.submits);
        }
    }
    @Test public void knownPreDispatchRejectionIsDurableFailureWithoutOwnershipOrReplay()throws Exception {try(Fixture f=new Fixture()){
        f.backend.rejected=true;var result=f.create();assertEquals("failed",result.get("state").getAsString());assertEquals("not_dispatched",result.get("execution_state").getAsString());assertFalse(result.get("confirmed").getAsBoolean());assertEquals(0,result.get("total").getAsInt());assertEquals(0,result.get("pending_creations").getAsInt());
        var restarted=new TestDataCreateService(new TestDataLedger(f.files.path),f.backend);var again=restarted.create(f.files.id,TARGET,0,"create-one",HOST,"Catalog.TestItems",Map.of("Наименование","Sample"));assertEquals("failed",again.get("state").getAsString());assertEquals(1,f.backend.submits);
        var preview=f.files.ledger.preview(f.files.id,TARGET,0,10);f.files.ledger.close(f.files.id,TARGET,preview.get("plan_sha256").getAsString());
    }}
}
