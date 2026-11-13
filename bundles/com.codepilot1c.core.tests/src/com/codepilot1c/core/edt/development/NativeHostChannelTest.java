// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import static org.junit.Assert.*;
import org.junit.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.google.gson.*;
public class NativeHostChannelTest {
    private NativeHostChannel channel;private Path root;
    @Before public void setup()throws Exception{root=Files.createTempDirectory("native-host-test").resolve("requests");channel=new NativeHostChannel(root);}
    @After public void clean()throws Exception{channel.close();for(int attempt=0;attempt<10;attempt++)try{List<Path> paths;try(var files=Files.walk(root.getParent())){paths=files.sorted(Comparator.reverseOrder()).toList();}for(Path p:paths)Files.deleteIfExists(p);return;}catch(DirectoryNotEmptyException | AccessDeniedException e){if(attempt==9)throw e;Thread.sleep(50);}}
    private String post(String path,String value,boolean auth,int expected)throws Exception{
        var connection=(HttpURLConnection)new URL("http://127.0.0.1:"+channel.port()+path).openConnection();connection.setRequestMethod("POST");connection.setDoOutput(true);if(auth)connection.setRequestProperty("Authorization","Bearer "+channel.token());connection.setConnectTimeout(2000);connection.setReadTimeout(2000);
        try(var out=connection.getOutputStream()){out.write(value.getBytes(StandardCharsets.UTF_8));}assertEquals(expected,connection.getResponseCode());
        try(var in=expected==200?connection.getInputStream():connection.getErrorStream()){return in==null?"":new String(in.readAllBytes(),StandardCharsets.UTF_8);}finally{connection.disconnect();}
    }
    private void hello()throws Exception{post("/hello","{\"configuration\":\"Fixture\",\"user\":\"Audit\",\"platform\":\"8.3.27\"}",true,200);}
    @Test public void externalAndEmbeddedProcessorsUseActualDefaultFormSlot(){
        var strategy=com.codepilot1c.core.edt.forms.FormOwnerStrategy.defaultStrategy();
        for(String owner:List.of("DataProcessor","Report","ExternalDataProcessor","ExternalReport"))assertEquals("setDefaultForm",strategy.resolveDefaultSetter(owner,com.codepilot1c.core.edt.forms.FormUsage.OBJECT));
        assertEquals("setDefaultObjectForm",strategy.resolveDefaultSetter("Catalog",com.codepilot1c.core.edt.forms.FormUsage.OBJECT));
    }
    @Test public void nativeTemplateRequiresFunctionAcknowledgementsBeforeReadiness()throws Exception {
        String source;
        try(var input=NativeHostChannel.class.getResourceAsStream("/resources/agent-host/HostForm.bsl")) {
            assertNotNull("Packaged host template is required",input);
            source=new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
        for(String method:List.of("Configure","Hello","Complete","Stop")) {
            assertTrue("Native function return must be checked: "+method,
                source.contains("HostRequireNativeReply(КомпонентаАгента."+method+"("));
            assertFalse("Native function must not be called as a procedure: "+method,
                java.util.regex.Pattern.compile("(?m)^\\s*КомпонентаАгента\\."+method+"\\(").matcher(source).find());
        }
        assertTrue(source.contains("ТипЗнч(Ответ) <> Тип(\"Строка\") Или Ответ <> ОжидаемыйОтвет"));
        assertTrue(source.indexOf("HostRequireNativeReply(КомпонентаАгента.Hello(")<source.indexOf("HostStage(\"ready\")"));
    }
    @Test public void rejectsUnauthenticatedRequests()throws Exception{post("/hello","{}",false,403);assertEquals(false,channel.status().get("connected"));}
    @Test public void claimIsNeverRedeliveredAfterLostPollResponse()throws Exception{
        hello();var v=channel.submit("one","server","Результат=42;",Map.of());String id=v.get("request_id").toString();
        assertTrue(post("/poll","{}",true,200).contains(id));assertTrue(post("/poll","{}",true,200).contains("stop"));
        assertEquals("outcome_unknown",channel.result(id).get("state"));assertEquals(id,channel.submit("one","server","Результат=42;",Map.of()).get("request_id"));
    }
    @Test public void returnsStructuredUnicodeResultAndRejectsConflictingAck()throws Exception{
        hello();String id=channel.submit("one","client","x",Map.of()).get("request_id").toString();post("/poll","{}",true,200);
        String value="{\"request_id\":\""+id+"\",\"success\":true,\"value\":\"Русский 😀\"}";post("/result",value,true,200);post("/result",value,true,200);
        assertEquals("completed",channel.result(id).get("state"));assertEquals("Русский 😀",((Map<?,?>)channel.result(id).get("execution")).get("value"));post("/result",value.replace("true","false"),true,400);
    }
    @Test public void refusesDifferentPayloadForExistingKey()throws Exception{hello();channel.submit("one","server","x",Map.of());try{channel.submit("one","server","y",Map.of());fail();}catch(IllegalArgumentException expected){}}
    @Test public void doesNotReplayPersistedClaimOnNewEndpoint()throws Exception{
        hello();String id=channel.submit("one","server","x",Map.of()).get("request_id").toString();
        try(var recovered=new NativeHostChannel(root)){assertEquals("outcome_unknown",recovered.result(id).get("state"));}
    }
    @Test public void stopCancelsOnlyUnclaimedWork()throws Exception{hello();String id=channel.submit("one","server","x",Map.of()).get("request_id").toString();channel.requestStop();assertEquals("cancelled",channel.result(id).get("state"));assertTrue(post("/poll","{}",true,200).contains("stop"));}
    @Test public void rejectsResultBeforeClaimAndWrongSuccessType()throws Exception{hello();String id=channel.submit("one","server","x",Map.of()).get("request_id").toString();post("/result","{\"request_id\":\""+id+"\",\"success\":true}",true,400);post("/poll","{}",true,200);post("/result","{\"request_id\":\""+id+"\",\"success\":\"true\"}",true,400);}
    @Test public void closeResolvesDeliveredWorkWithoutClaimingSuccess()throws Exception{
        hello();String delivered=channel.submit("delivered","server","x",Map.of()).get("request_id").toString();
        post("/poll","{}",true,200);
        String queued=channel.submit("queued","server","y",Map.of()).get("request_id").toString();
        channel.close();
        assertEquals("outcome_unknown",channel.result(delivered).get("state"));
        assertEquals(true,channel.result(delivered).get("terminal"));
        assertEquals("cancelled",channel.result(queued).get("state"));
        assertEquals("outcome_unknown",NativeHostChannel.persistedResult(root,delivered).get("state"));
    }
    @Test public void persistedCancellationRemainsKnownAfterRestart()throws Exception{
        hello();String id=channel.submit("one","server","x",Map.of()).get("request_id").toString();channel.requestStop();
        var result=NativeHostChannel.persistedResult(root,id);
        assertEquals("cancelled",result.get("state"));assertEquals(false,result.get("outcome_unknown"));assertEquals(true,result.get("terminal"));
    }
    @Test public void persistedCompletedResultRemainsAvailable()throws Exception{
        hello();String id=channel.submit("one","server","x",Map.of()).get("request_id").toString();post("/poll","{}",true,200);
        post("/result","{\"request_id\":\""+id+"\",\"success\":true,\"value\":42}",true,200);
        var result=NativeHostChannel.persistedResult(root,id);
        assertEquals("completed",result.get("state"));assertEquals(false,result.get("outcome_unknown"));assertEquals(true,((Map<?,?>)result.get("execution")).get("success"));
    }
    @Test public void requestLimitSurvivesNewEndpoint()throws Exception{
        hello();for(int i=0;i<128;i++)channel.submit("key"+i,"server","x",Map.of());channel.requestStop();
        try(var recovered=new NativeHostChannel(root)){
            var c=(java.net.HttpURLConnection)new URL("http://127.0.0.1:"+recovered.port()+"/hello").openConnection();
            c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(2000);c.setReadTimeout(2000);c.setRequestProperty("Authorization","Bearer "+recovered.token());
            try(var out=c.getOutputStream()){out.write("{\"configuration\":\"Fixture\",\"user\":\"Audit\",\"platform\":\"8.3.27\"}".getBytes(StandardCharsets.UTF_8));}
            try{assertEquals(200,c.getResponseCode());}finally{c.disconnect();}
            assertEquals("cancelled",recovered.submit("key0","server","x",Map.of()).get("state"));
            try{recovered.submit("extra","server","x",Map.of());fail("Limit must include persisted claims");}catch(IllegalStateException expected){}
        }
    }
    @Test public void liveAndRecoveredResultNumbersRemainExact()throws Exception{hello();String id=channel.submit("number","server","Результат=1;",Map.of()).get("request_id").toString();post("/poll","{}",true,200);post("/result","{\"request_id\":\""+id+"\",\"success\":true,\"value\":12345678901234567890123456789012345678}",true,200);var expected=new java.math.BigDecimal("12345678901234567890123456789012345678");assertEquals(expected,((Map<?,?>)channel.result(id).get("execution")).get("value"));assertEquals(expected,((Map<?,?>)NativeHostChannel.persistedResult(root,id).get("execution")).get("value"));}
 @Test public void nativePollRetainsExplicitNullParameter()throws Exception{hello();var parameters=new LinkedHashMap<String,Object>();parameters.put("P",null);channel.submit("null-parameter","server","x",parameters);var request=JsonParser.parseString(post("/poll","{}",true,200)).getAsJsonObject();assertTrue(request.getAsJsonObject("parameters").has("P"));assertTrue(request.getAsJsonObject("parameters").get("P").isJsonNull());}
    @Test public void batchPollChecksStopWithoutClaimingAnotherRequest()throws Exception{
        hello();String id=channel.submit("batch-one","batch","{}",Map.of()).get("request_id").toString();String later=channel.submit("after-batch","client","x",Map.of()).get("request_id").toString();assertTrue(post("/poll","{}",true,200).contains(id));assertEquals("{}",post("/poll","{}",true,200));assertEquals("queued",channel.result(later).get("state"));channel.requestStop();assertTrue(post("/poll","{}",true,200).contains("stop"));assertEquals("cancelled",channel.result(later).get("state"));
    }
    @Test public void explicitUnknownStepIsDurableAndNeverReplayed()throws Exception{
        hello();String id=channel.submit("unknown-rpc","batch","{}",Map.of()).get("request_id").toString();post("/poll","{}",true,200);
        String value="{\"request_id\":\""+id+"\",\"success\":false,\"outcome_unknown\":true,\"value\":[]}";
        post("/result",value,true,200);post("/result",value,true,200);
        for(var result:List.of(channel.result(id),NativeHostChannel.persistedResult(root,id),channel.submit("unknown-rpc","batch","{}",Map.of()))){assertEquals("outcome_unknown",result.get("state"));assertEquals(true,result.get("terminal"));assertEquals(true,result.get("outcome_unknown"));}
        assertEquals("{}",post("/poll","{}",true,200));
    }
    @Test public void unknownOutcomeCannotBeSuccessfulOrStringTyped()throws Exception{
        hello();String id=channel.submit("invalid-unknown","batch","{}",Map.of()).get("request_id").toString();post("/poll","{}",true,200);
        post("/result","{\"request_id\":\""+id+"\",\"success\":true,\"outcome_unknown\":true}",true,400);
        post("/result","{\"request_id\":\""+id+"\",\"success\":false,\"outcome_unknown\":\"true\"}",true,400);
        assertEquals("delivered",channel.result(id).get("state"));
    }

    private String progress(String id,int sequence,String value){return "{\"request_id\":\""+id+"\",\"progress\":true,\"sequence\":"+sequence+",\"phase\":\"waiting\",\"value\":"+value+"}";}
    @Test public void progressIsDurableNonterminalAndNeverRedeliversBatch()throws Exception{
        hello();String id=channel.submit("progress","batch","{}",Map.of()).get("request_id").toString();post("/poll","{}",true,200);
        String first=progress(id,1,"[{\"step\":1,\"value\":42}]");post("/result",first,true,200);post("/result",first,true,200);
        var live=channel.result(id);assertEquals(false,live.get("terminal"));assertEquals("delivered",live.get("state"));assertTrue(live.containsKey("progress"));assertEquals("{}",post("/poll","{}",true,200));
        var recovered=NativeHostChannel.persistedResult(root,id);assertEquals("outcome_unknown",recovered.get("state"));assertEquals(live.get("progress"),recovered.get("progress"));
        post("/result",progress(id,2,"[{\"step\":1,\"value\":42},{\"step\":2,\"value\":true}]"),true,200);
        post("/result","{\"request_id\":\""+id+"\",\"success\":true,\"value\":[{\"step\":1,\"value\":42},{\"step\":2,\"value\":true}]}",true,200);
        assertEquals("completed",channel.result(id).get("state"));assertFalse(((Map<?,?>)channel.result(id).get("progress")).containsKey("value"));
        post("/result",progress(id,3,"[]"),true,400);
    }
    @Test public void progressRejectsRegressionConflictsSkippedSequenceAndForgedTerminal()throws Exception{
        hello();String id=channel.submit("proof","batch","{}",Map.of()).get("request_id").toString();post("/result",progress(id,1,"[]"),true,400);post("/poll","{}",true,200);
        post("/result",progress(id,2,"[]"),true,400);post("/result",progress(id,1,"[1]"),true,200);
        post("/result",progress(id,1,"[2]"),true,400);post("/result",progress(id,3,"[1]"),true,400);post("/result",progress(id,2,"[]"),true,400);post("/result",progress(id,2,"[2]"),true,400);
        post("/result","{\"request_id\":\""+id+"\",\"success\":true,\"value\":[]}",true,400);
        assertEquals("delivered",channel.result(id).get("state"));
        post("/result","{\"request_id\":\""+id+"\",\"success\":false,\"value\":[1],\"outcome_unknown\":true}",true,200);
        assertEquals("outcome_unknown",channel.result(id).get("state"));
    }
    @Test public void progressDoesNotApplyToLegacySingleRequests()throws Exception{hello();String id=channel.submit("single","server","x",Map.of()).get("request_id").toString();post("/poll","{}",true,200);post("/result",progress(id,1,"[]"),true,400);assertFalse(channel.result(id).containsKey("progress"));}
    @Test public void malformedProgressIsRejectedBeforeChangingReceipt()throws Exception{
        hello();String id=channel.submit("shape","batch","{}",Map.of()).get("request_id").toString();post("/poll","{}",true,200);
        for(String data:List.of(progress(id,1,"[]").replace("\"progress\":true","\"progress\":false"),progress(id,1,"[]").replace("\"sequence\":1","\"sequence\":1.5"),progress(id,1,"[]").replace("waiting","mystery"),progress(id,1,"null")))post("/result",data,true,400);
        assertFalse(channel.result(id).containsKey("progress"));
    }

    @Test public void failedPersistenceCannotBeAcknowledgedOnRetryWithoutSaving()throws Exception{
        hello();String id=channel.submit("io-fault","batch","{}",Map.of()).get("request_id").toString();post("/poll","{}",true,200);
        Path collision=root.resolve(NativeHostChannel.hash("io-fault")+".json.tmp");Files.createDirectory(collision);Files.writeString(collision.resolve("block"),"owned fault");
        String reply="{\"request_id\":\""+id+"\",\"success\":true,\"value\":[42]}";
        post("/result",reply,true,400);assertEquals("delivered",channel.result(id).get("state"));assertFalse(channel.result(id).containsKey("execution"));
        Files.delete(collision.resolve("block"));Files.delete(collision);post("/result",reply,true,200);
        assertEquals("completed",NativeHostChannel.persistedResult(root,id).get("state"));
    }

    @Test public void nativeToolWirePreservesSelectedNullInsteadOfMissingValue()throws Exception{
        hello();String id=channel.submit("null-wire","batch","{}",Map.of()).get("request_id").toString();post("/poll","{}",true,200);
        post("/result","{\"request_id\":\""+id+"\",\"success\":true,\"value\":[{\"path\":\"0.nullable\",\"value\":null}]}",true,200);
        var field=com.codepilot1c.core.tools.development.EdtRuntimeHostTool.class.getDeclaredField("RESULT_JSON");field.setAccessible(true);Gson writer=(Gson)field.get(null);
        var value=JsonParser.parseString(writer.toJson(channel.result(id))).getAsJsonObject().getAsJsonObject("execution").getAsJsonArray("value").get(0).getAsJsonObject();assertTrue(value.has("value"));assertTrue(value.get("value").isJsonNull());
    }

    @Test public void waitReleasesMonitorAndSeesPersistedCompletion()throws Exception{
        hello();String id=channel.submit("wait","client","x",Map.of()).get("request_id").toString();post("/poll","{}",true,200);long before=channel.revision();
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();try{var waiting=pool.submit(()->{channel.awaitRevision(before,2000);return channel.result(id);});Thread.sleep(50);post("/result","{\"request_id\":\""+id+"\",\"success\":true,\"value\":42}",true,200);assertEquals("completed",waiting.get(2,java.util.concurrent.TimeUnit.SECONDS).get("state"));}finally{pool.shutdownNow();}
    }
    @Test public void refreshFreezeRefusesUnknownAndNeverCancelsPendingWork()throws Exception{
        hello();String id=channel.submit("pending","client","x",Map.of()).get("request_id").toString();try{channel.freezeForRefresh();fail();}catch(IllegalStateException expected){}assertEquals("queued",channel.result(id).get("state"));assertEquals(false,channel.status().get("stopping"));
        post("/poll","{}",true,200);post("/result","{\"request_id\":\""+id+"\",\"success\":true}",true,200);channel.freezeForRefresh();try{channel.submit("new","client","x",Map.of());fail();}catch(IllegalStateException expected){}
    }
    @Test public void stoppedWaitIsBoundedAndPersistedBatchContextIsAvailable()throws Exception{
        hello();String id=channel.submit("batch","batch","{}",Map.of()).get("request_id").toString();assertTrue(channel.batch(id));assertTrue(NativeHostChannel.persistedBatch(root,id));long before=channel.revision();channel.requestStop();long started=System.nanoTime();channel.awaitRevision(before,2000);assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<500);
    }
    @Test public void repeatedKeyKeepsOriginalHistoryAfterStopBeforeAnyPreflightRejection()throws Exception {
        hello();var plan=NativeExecutionPlan.prepare(Map.of("idempotency_key","original","execution_mode","info"));String id=channel.submit(plan).get("request_id").toString();post("/poll","{}",true,200);
        post("/result","{\"request_id\":\""+id+"\",\"success\":true,\"value\":42}",true,200);channel.requestStop();
        var preflight=new java.util.concurrent.atomic.AtomicBoolean();var result=NativeExecutionGate.execute(()->Optional.of(channel.result(NativeHostChannel.findRequest(root,plan).orElseThrow())),()->{preflight.set(true);throw new IllegalStateException("Host stopped / consent revoked");});
        assertEquals(id,result.get("request_id"));assertEquals("completed",result.get("state"));assertFalse(NativeExecutionGate.notDispatched(result));assertFalse(preflight.get());assertEquals(id,channel.submit(plan).get("request_id"));
        var different=NativeExecutionPlan.prepare(Map.of("idempotency_key","original","execution_mode","execute","code","Результат=7;"));assertThrows(IllegalArgumentException.class,()->NativeHostChannel.findRequest(root,different));
    }
    @Test public void unreadableOriginalHistoryCannotBecomeKnownNotDispatched()throws Exception {
        var plan=NativeExecutionPlan.prepare(Map.of("idempotency_key","original","execution_mode","info"));Files.writeString(root.resolve(NativeHostChannel.hash("original")+".json"),"{broken");
        var preflight=new java.util.concurrent.atomic.AtomicBoolean();assertThrows(Exception.class,()->NativeExecutionGate.execute(()->{NativeHostChannel.findRequest(root,plan);return Optional.empty();},()->{preflight.set(true);throw new IllegalStateException("Host stopped");}));assertFalse(preflight.get());
    }
    @Test public void repeatedKeyCanReadValidDurableResultLargerThanEndpointLimit()throws Exception {
        hello();var plan=NativeExecutionPlan.wire("large","server","x".repeat(100*1024),Map.of());String id=channel.submit(plan).get("request_id").toString();post("/poll","{}",true,200);
        post("/result","{\"request_id\":\""+id+"\",\"success\":true,\"value\":\""+"x".repeat(NativeHostChannel.MAX_BYTES-256)+"\"}",true,200);
        assertTrue(Files.size(root.resolve(NativeHostChannel.hash("large")+".json"))>NativeHostChannel.MAX_BYTES);channel.requestStop();assertEquals(id,channel.submit(plan).get("request_id"));assertEquals("completed",channel.submit(plan).get("state"));
    }
    @Test public void corruptedCrossKeyRequestIdentityCannotReturnAnotherReceipt()throws Exception {
        hello();var a=NativeExecutionPlan.wire("a","server","a",Map.of());var b=NativeExecutionPlan.wire("b","server","b",Map.of());channel.submit(a);String bId=channel.submit(b).get("request_id").toString();
        Path file=root.resolve(NativeHostChannel.hash("a")+".json");var row=JsonParser.parseString(Files.readString(file)).getAsJsonObject();row.addProperty("request_id",bId);Files.writeString(file,row.toString());assertThrows(IllegalStateException.class,()->channel.submit(a));
        row.remove("claim_identity_sha256");Files.writeString(file,row.toString());assertThrows(IllegalStateException.class,()->NativeHostChannel.previous(root,a));assertEquals(2,channel.status().get("requests"));
        assertThrows(IllegalStateException.class,()->channel.close()); // Edited receipt is preserved; transport shutdown still completes.
    }
    @Test public void missingKnownClaimNeverDispatchesAgainAndUpdatesDoNotScanUnrelatedReceipts()throws Exception {
        hello();var plan=NativeExecutionPlan.wire("own","server","x",Map.of());String id=channel.submit(plan).get("request_id").toString();
        assertTrue(post("/poll","{}",true,200).contains(id));post("/result","{\"request_id\":\""+id+"\",\"success\":true,\"value\":42}",true,200);
        Files.delete(root.resolve(NativeHostChannel.hash("own")+".json"));assertThrows(IllegalStateException.class,()->channel.submit(plan));assertEquals(1,channel.status().get("requests"));
    }
    @Test public void missingReceiptAcrossEndpointReplacementNeverAllowsReplay()throws Exception {
        hello();var plan=NativeExecutionPlan.wire("lost","server","write",Map.of());
        String id=channel.submit(plan).get("request_id").toString();post("/poll","{}",true,200);
        post("/result","{\"request_id\":\""+id+"\",\"success\":true,\"value\":1}",true,200);
        channel.close();Files.delete(root.resolve(NativeHostChannel.hash("lost")+".json"));
        assertThrows(IllegalStateException.class,()->NativeHostChannel.previous(root,plan));
        assertThrows(IllegalStateException.class,()->NativeHostChannel.persistedResult(root,id));
        assertThrows(IllegalStateException.class,()->NativeHostChannel.requireRefreshSafe(root));
        try(var recovered=new NativeHostChannel(root)){
            assertThrows(IllegalStateException.class,()->recovered.submit(plan));
            assertEquals(0,recovered.status().get("requests"));
        }
    }
    @Test public void missingLedgerOrHistoryCannotBecomeEmptyNewHost()throws Exception {
        hello();var plan=NativeExecutionPlan.wire("known","server","write",Map.of());channel.submit(plan);channel.close();
        Files.delete(root.resolve(".claims.json"));
        assertThrows(IllegalStateException.class,()->NativeHostChannel.previous(root,plan));
        try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
        assertThrows(IllegalStateException.class,()->new NativeHostChannel(root,false));
        Files.delete(NativeRequestStore.anchor(root));assertThrows(IllegalStateException.class,()->new NativeHostChannel(root,false));
        Files.createDirectory(root); // fixture cleanup only
    }
    @Test public void bothReceiptIndexesRejectChangedResultAndDuplicateIdentity()throws Exception {
        hello();var plan=NativeExecutionPlan.wire("own-id","server","x",Map.of());String id=channel.submit(plan).get("request_id").toString();post("/poll","{}",true,200);
        post("/result","{\"request_id\":\""+id+"\",\"success\":true,\"value\":1}",true,200);channel.close();
        Path path=root.resolve(NativeHostChannel.hash("own-id")+".json");String bytes=Files.readString(path);
        Files.writeString(path,bytes.replace("\"value\":1","\"value\":999"));
        assertThrows(IllegalStateException.class,()->NativeHostChannel.previous(root,plan));assertThrows(IllegalStateException.class,()->NativeHostChannel.persistedResult(root,id));
        Files.writeString(path,bytes);Path other=root.resolve(NativeHostChannel.hash("other-id")+".json");Files.writeString(other,bytes);
        assertThrows(IllegalStateException.class,()->NativeHostChannel.persistedResult(root,id));
    }
    @Test public void legacyReceiptsAreReadableButNeverAuthorizeNewDispatch()throws Exception {
        hello();var plan=NativeExecutionPlan.wire("legacy","server","x",Map.of());String id=channel.submit(plan).get("request_id").toString();channel.close();
        Files.delete(root.resolve(".claims.json"));Files.delete(NativeRequestStore.anchor(root));
        assertEquals(id,NativeHostChannel.persistedResult(root,id).get("request_id"));
        assertThrows(IllegalStateException.class,()->new NativeHostChannel(root,false,true));
        try(var legacy=new NativeHostChannel(root)){
            assertThrows(IllegalStateException.class,()->new NativeRequestStore(root,false).claim(NativeHostChannel.hash("new"),new JsonObject()));
            assertEquals(id,legacy.submit(plan).get("request_id"));
        }
    }

    private java.util.concurrent.atomic.AtomicLong fakeClock()throws Exception{channel.close();var clock=new java.util.concurrent.atomic.AtomicLong();channel=new NativeHostChannel(root,false,true,clock::get);return clock;}
    @Test public void queuedDeadlinePreventsLatePollAndPreservesSameKey()throws Exception{var clock=fakeClock();hello();String id=channel.submit("deadline","server","x",Map.of()).get("request_id").toString();clock.set(NativeHostChannel.QUEUE_TIMEOUT_NANOS);assertEquals("{}",post("/poll","{}",true,200));var ended=channel.result(id);assertEquals("cancelled",ended.get("state"));assertEquals("not_dispatched",ended.get("execution_state"));assertEquals(id,channel.submit("deadline","server","x",Map.of()).get("request_id"));assertEquals("cancelled",NativeHostChannel.persistedResult(root,id).get("state"));}
    @Test public void exitSealsUndeliveredButNeverClaimsDeliveredOutcomeKnown()throws Exception{hello();String delivered=channel.submit("delivered","server","x",Map.of()).get("request_id").toString();post("/poll","{}",true,200);String queued=channel.submit("queued","server","x",Map.of()).get("request_id").toString();channel.clientExited();assertEquals(false,channel.status().get("connected"));assertEquals("outcome_unknown",channel.result(delivered).get("state"));assertEquals("cancelled",channel.result(queued).get("state"));assertThrows(IllegalStateException.class,()->channel.submit("new-after-exit","server","x",Map.of()));}
    @Test public void staleContactBlocksNewDispatchWithoutCancellingLongExecution()throws Exception{var clock=fakeClock();hello();String id=channel.submit("slow","server","x",Map.of()).get("request_id").toString();post("/poll","{}",true,200);clock.set(NativeHostChannel.CONTACT_TIMEOUT_NANOS+1);assertEquals(false,channel.status().get("connected"));assertEquals("delivered",channel.result(id).get("state"));assertThrows(IllegalStateException.class,()->channel.submit("new-stale","server","x",Map.of()));post("/result","{\"request_id\":\""+id+"\",\"success\":true,\"value\":42}",true,200);assertEquals("completed",channel.result(id).get("state"));assertEquals(true,channel.status().get("connected"));}
    @Test public void executionGateRequiresOwnedProcessAndFreshChannel(){var state=new HashMap<String,Object>(Map.of("state","running","compatibility",Map.of("compatible",true),"owned_process_alive",false,"channel",Map.of("connected",false)));var blockers=NativeExecutionGate.readiness(state,true,true,false,false);assertTrue(blockers.contains("owned_process_unverified"));assertTrue(blockers.contains("channel_not_fresh"));}
    @Test public void resultRetriesAnExitTransitionThatFailedBeforeWritingReceipt()throws Exception{hello();String id=channel.submit("exit-write","server","x",Map.of()).get("request_id").toString();post("/poll","{}",true,200);Path blocked=root.resolve(NativeHostChannel.hash("exit-write")+".json.tmp");Files.createDirectory(blocked);assertThrows(Exception.class,()->channel.clientExited());Files.delete(blocked);assertEquals("outcome_unknown",channel.result(id).get("state"));assertEquals("outcome_unknown",NativeHostChannel.persistedResult(root,id).get("state"));}
    @Test public void completedHandshakeNeverReopensStartupAfterContactExpires()throws Exception{var clock=fakeClock();hello();clock.set(NativeHostChannel.CONTACT_TIMEOUT_NANOS+1);assertEquals(false,channel.status().get("connected"));assertEquals(true,channel.status().get("handshake_completed"));assertThrows(NativeStartupInspection.PhaseEnded.class,()->NativeStartupInspection.requirePending("starting",true,(Boolean)channel.status().get("handshake_completed")));}
    @Test public void compactCancelledReceiptExplainsNonDispatch()throws Exception{var clock=fakeClock();hello();String id=channel.submit("compact-deadline","server","x",Map.of()).get("request_id").toString();clock.set(NativeHostChannel.QUEUE_TIMEOUT_NANOS);var view=NativeAgentReplies.view("owned",channel.result(id),List.of(),null,8);assertEquals("cancelled",view.get("state"));assertEquals("not_dispatched",view.get("execution_state"));assertEquals("queue_deadline_before_dispatch",view.get("terminal_reason"));}
    @Test public void lostSingleDeliveryStopsBeforeNextQueuedMutation()throws Exception{
        hello();String first=channel.submit("lost","client","mutate",Map.of()).get("request_id").toString();String second=channel.submit("later","client","mutate2",Map.of()).get("request_id").toString();assertTrue(post("/poll","{}",true,200).contains(first));assertTrue(post("/poll","{}",true,200).contains("stop"));assertEquals("outcome_unknown",channel.result(first).get("state"));assertEquals("cancelled",channel.result(second).get("state"));assertEquals("not_dispatched",channel.result(second).get("execution_state"));
    }
    @Test public void failedClientTransportSealsPendingWithoutClaimingExit()throws Exception{
        hello();String first=channel.submit("failed","server","mutate",Map.of()).get("request_id").toString();post("/poll","{}",true,200);String second=channel.submit("later","client","mutate2",Map.of()).get("request_id").toString();channel.transportFailed();assertEquals("outcome_unknown",channel.result(first).get("state"));assertEquals("cancelled",channel.result(second).get("state"));assertEquals(false,channel.status().get("client_exited"));assertEquals(false,channel.status().get("connected"));channel.transportFailed();assertEquals("client_transport_failed",channel.result(first).get("terminal_reason"));
    }

}
