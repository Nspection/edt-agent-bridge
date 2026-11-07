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
    @After public void clean()throws Exception{channel.close();try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
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
}
