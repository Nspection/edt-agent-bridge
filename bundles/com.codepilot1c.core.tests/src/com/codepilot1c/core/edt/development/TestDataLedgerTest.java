// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
public class TestDataLedgerTest {
    static final TestDataLedger.Target TARGET=new TestDataLedger.Target("Fixture","12345678-1234-1234-1234-123456789abc");
    static Map<String,Object> entry(){return Map.of("metadata_fqn","Catalog.TestItems","uuid",UUID.randomUUID().toString(),"expected_version","v1");}
    static class Fixture implements AutoCloseable {
        final Path path;final TestDataLedger ledger;final String id;
        Fixture()throws Exception {path=Files.createTempDirectory("bridge-ledger-");ledger=new TestDataLedger(path);id=ledger.begin(TARGET,"request-1","task-123").get("data_run_id").getAsString();}
        public void close()throws Exception {for(int attempt=0;attempt<10;attempt++)try{Files.walkFileTree(path,new java.nio.file.SimpleFileVisitor<Path>(){
            public java.nio.file.FileVisitResult visitFile(Path p,java.nio.file.attribute.BasicFileAttributes a)throws java.io.IOException{Files.delete(p);return java.nio.file.FileVisitResult.CONTINUE;}
            public java.nio.file.FileVisitResult postVisitDirectory(Path p,java.io.IOException e)throws java.io.IOException{if(e!=null)throw e;Files.delete(p);return java.nio.file.FileVisitResult.CONTINUE;}
        });return;}catch(java.nio.file.DirectoryNotEmptyException | java.nio.file.AccessDeniedException e){if(attempt==9)throw e;Thread.sleep(50);}}
    }
    @Test public void beginSurvivesRestartAndChangedLabelIsRejected()throws Exception{
        try(Fixture f=new Fixture()){
            var other=new TestDataLedger(f.path);assertEquals(f.id,other.begin(TARGET,"request-1","task-123").get("data_run_id").getAsString());
            assertThrows(IllegalArgumentException.class,()->other.begin(TARGET,"request-1","another"));
        }
    }
    @Test public void baseAndProjectChangesCannotReuseLedger()throws Exception{
        try(Fixture f=new Fixture()){
            assertThrows(IllegalStateException.class,()->f.ledger.list(f.id,new TestDataLedger.Target("Fixture","12345678-1234-1234-1234-123456789abd"),0,10));
            assertThrows(IllegalStateException.class,()->f.ledger.list(f.id,new TestDataLedger.Target("Other",TARGET.baseUuid()),0,10));
            assertEquals(0,f.ledger.list(f.id,TARGET,0,10).get("total").getAsInt());
        }
    }
    @Test public void duplicateRetryIsNoopAndCannotReplaceOriginalVersion()throws Exception{
        try(Fixture f=new Fixture()){
            var item=entry();assertEquals(1,f.ledger.register(f.id,TARGET,0,List.of(item)).get("added").getAsInt());
            assertEquals(0,f.ledger.register(f.id,TARGET,0,List.of(item)).get("added").getAsInt());
            var changed=new HashMap<>(item);changed.put("expected_version","v2");assertThrows(IllegalArgumentException.class,()->f.ledger.register(f.id,TARGET,1,List.of(changed)));
            assertEquals("v1",f.ledger.list(f.id,TARGET,0,10).getAsJsonArray("records").get(0).getAsJsonObject().get("expected_version").getAsString());
        }
    }
    @Test public void invalidOrStaleBatchDoesNotPartiallyWrite()throws Exception{
        try(Fixture f=new Fixture()){
            assertThrows(IllegalArgumentException.class,()->f.ledger.register(f.id,TARGET,0,List.of(entry(),Map.of("metadata_fqn","Catalog.X; delete","uuid",UUID.randomUUID().toString()))));
            assertEquals(0,f.ledger.list(f.id,TARGET,0,10).get("total").getAsInt());
            f.ledger.register(f.id,TARGET,0,List.of(entry()));
            assertThrows(IllegalStateException.class,()->f.ledger.register(f.id,TARGET,0,List.of(entry())));
            assertEquals(1,f.ledger.list(f.id,TARGET,0,10).get("total").getAsInt());
        }
    }
    @Test public void pagedPlanHasOneHashAndNeverAuthorizesDeletion()throws Exception{
        try(Fixture f=new Fixture()){
            f.ledger.register(f.id,TARGET,0,List.of(entry(),entry()));var a=f.ledger.preview(f.id,TARGET,0,1);var b=f.ledger.preview(f.id,TARGET,1,1);
            assertEquals(a.get("plan_sha256"),b.get("plan_sha256"));assertTrue(a.get("has_more").getAsBoolean());assertFalse(b.get("has_more").getAsBoolean());
            assertFalse(a.get("physical_delete_allowed").getAsBoolean());assertFalse(a.get("writes_database").getAsBoolean());assertTrue(a.get("requires_runtime_verification").getAsBoolean());
            assertEquals("reported_by_agent",a.getAsJsonArray("records").get(0).getAsJsonObject().get("ownership").getAsString());
        }
    }
    @Test public void closingJournalRetainsDataAndDoesNotClaimCleanup()throws Exception{
        try(Fixture f=new Fixture()){
            f.ledger.register(f.id,TARGET,0,List.of(entry()));var plan=f.ledger.preview(f.id,TARGET,0,10);var result=f.ledger.close(f.id,TARGET,plan.get("plan_sha256").getAsString());
            assertEquals("closed",result.get("state").getAsString());assertTrue(result.get("data_may_remain").getAsBoolean());assertFalse(result.get("database_cleanup_verified").getAsBoolean());
            assertEquals(1,f.ledger.list(f.id,TARGET,0,10).get("total").getAsInt());assertThrows(IllegalStateException.class,()->f.ledger.register(f.id,TARGET,2,List.of(entry())));
        }
    }
    @Test public void changedRecordsInvalidatePreviewHash()throws Exception{
        try(Fixture f=new Fixture()){
            var hash=f.ledger.preview(f.id,TARGET,0,10).get("plan_sha256").getAsString();f.ledger.register(f.id,TARGET,0,List.of(entry()));
            assertThrows(IllegalStateException.class,()->f.ledger.close(f.id,TARGET,hash));assertEquals("open",f.ledger.list(f.id,TARGET,0,10).get("state").getAsString());
        }
    }
    @Test public void malformedIdentifiersAndUnknownFieldsAreRejected()throws Exception{
        try(Fixture f=new Fixture()){
            assertThrows(IllegalArgumentException.class,()->f.ledger.list("../outside",TARGET,0,10));
            assertThrows(IllegalArgumentException.class,()->f.ledger.register(f.id,TARGET,0,List.of(Map.of("metadata_fqn","Catalog.X","uuid","00000000-0000-0000-0000-000000000000"))));
            var value=new HashMap<>(entry());value.put("password","secret");assertThrows(IllegalArgumentException.class,()->f.ledger.register(f.id,TARGET,0,List.of(value)));
            assertThrows(IllegalArgumentException.class,()->f.ledger.list(f.id,TARGET,0,101));
        }
    }
    @Test public void concurrentWritersCannotLoseEachOthersRecords()throws Exception{
        try(Fixture f=new Fixture()){
            var second=new TestDataLedger(f.path);var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
            try {
                Callable<Boolean> first=()->{gate.await();try{f.ledger.register(f.id,TARGET,0,List.of(entry()));return true;}catch(IllegalStateException stale){return false;}};
                Callable<Boolean> other=()->{gate.await();try{second.register(f.id,TARGET,0,List.of(entry()));return true;}catch(IllegalStateException stale){return false;}};
                var a=pool.submit(first);var b=pool.submit(other);gate.countDown();assertNotEquals(a.get(),b.get());assertEquals(1,f.ledger.list(f.id,TARGET,0,10).get("total").getAsInt());
            }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        }
    }
    @Test public void encodedSizeLimitCannotProduceUnreadableLedger()throws Exception{
        try(Fixture f=new Fixture()){
            for(int batch=0;batch<3;batch++){
                List<Map<String,Object>> items=new ArrayList<>();for(int i=0;i<100;i++)items.add(Map.of("metadata_fqn","Catalog.TestItems","uuid",UUID.randomUUID().toString(),"expected_version",String.valueOf((char)1).repeat(4096)));
                f.ledger.register(f.id,TARGET,batch,items);
            }
            byte[] before=Files.readAllBytes(f.path.resolve(f.id+".json"));List<Map<String,Object>> items=new ArrayList<>();for(int i=0;i<100;i++)items.add(Map.of("metadata_fqn","Catalog.TestItems","uuid",UUID.randomUUID().toString(),"expected_version",String.valueOf((char)1).repeat(4096)));
            assertThrows(IllegalArgumentException.class,()->f.ledger.register(f.id,TARGET,3,items));assertArrayEquals(before,Files.readAllBytes(f.path.resolve(f.id+".json")));assertEquals(300,f.ledger.list(f.id,TARGET,0,1).get("total").getAsInt());
        }
    }
    @Test public void corruptedSchemaIsNotSilentlyOverwritten()throws Exception{
        try(Fixture f=new Fixture()){
            Path file=f.path.resolve(f.id+".json");String corrupt=Files.readString(file).replace("\"schema\":1","\"schema\":1.5");Files.writeString(file,corrupt);
            assertThrows(ArithmeticException.class,()->f.ledger.begin(TARGET,"request-1","task-123"));assertEquals(corrupt,Files.readString(file));
        }
    }
}
