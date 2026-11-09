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
    @Before public void setup()throws Exception{root=Files.createTempDirectory("native-host-test");channel=new NativeHostChannel(root);}
    @After public void clean()throws Exception{channel.close();for(int attempt=0;attempt<10;attempt++)try{List<Path> paths;try(var files=Files.walk(root)){paths=files.sorted(Comparator.reverseOrder()).toList();}for(Path p:paths)Files.deleteIfExists(p);return;}catch(DirectoryNotEmptyException | AccessDeniedException e){if(attempt==9)throw e;Thread.sleep(50);}}
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
        assertTrue(post("/poll","{}",true,200).contains(id));assertEquals("{}",post("/poll","{}",true,200));
        assertEquals("delivered",channel.result(id).get("state"));assertEquals(id,channel.submit("one","server","Результат=42;",Map.of()).get("request_id"));
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
}
