package com.codepilot1c.core.mcp.host.transport;
import static org.junit.Assert.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import com.codepilot1c.core.mcp.host.McpHostConfig;
import org.junit.Test;
public class McpHostSseAuthorizationTest {
    @Test public void sseRejectsAnonymousAndInvalidBearerButAcceptsValidBearer() throws Exception {
        McpHostHttpTransport transport=new McpHostHttpTransport("127.0.0.1",0,
                new McpBearerAuthorization("127.0.0.1",0,"review-fixture-token"),null,McpHostConfig.AuthMode.BEARER_ONLY);
        transport.start();
        try {
            HttpClient client=HttpClient.newHttpClient();
            URI uri=URI.create("http://127.0.0.1:"+transport.getBoundPort()+"/mcp");
            for(String token:new String[]{"","Bearer wrong"}) {
                HttpRequest.Builder b=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("Accept","text/event-stream");
                if(!token.isEmpty()){b.header("Authorization",token);}
                assertEquals(401,client.send(b.GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            HttpRequest authorized=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                    .header("Accept","text/event-stream").header("Authorization","Bearer review-fixture-token").GET().build();
            HttpResponse<InputStream> response=client.send(authorized,HttpResponse.BodyHandlers.ofInputStream());
            try(InputStream body=response.body()) {
                assertEquals(200,response.statusCode());
                assertEquals("event: ready",new BufferedReader(new InputStreamReader(body)).readLine());
            }
        } finally {transport.stop();}
    }
    private McpHostHttpTransport fixture(){return new McpHostHttpTransport("127.0.0.1",0,new McpBearerAuthorization("127.0.0.1",0,"review-fixture-token"),null,McpHostConfig.AuthMode.BEARER_ONLY);}
    @Test public void oversizedDeclaredBodyIsRejectedBeforeReadingOrRouting()throws Exception{
        var transport=fixture();transport.start();
        try(var socket=new java.net.Socket("127.0.0.1",transport.getBoundPort())){
            socket.setSoTimeout(3000);
            String request="POST /mcp HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer review-fixture-token\r\nContent-Length: "+(McpHostHttpTransport.MAX_REQUEST_BYTES+1)+"\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));socket.getOutputStream().flush();
            assertTrue(new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine().contains("413"));
        }finally{transport.stop();}
    }
    @Test public void chunkedBodyCannotBypassRequestLimit()throws Exception{
        var transport=fixture();transport.start();
        try{
            var c=(java.net.HttpURLConnection)URI.create("http://127.0.0.1:"+transport.getBoundPort()+"/mcp").toURL().openConnection();
            c.setRequestMethod("POST");c.setDoOutput(true);c.setChunkedStreamingMode(8192);c.setConnectTimeout(3000);c.setReadTimeout(5000);c.setRequestProperty("Authorization","Bearer review-fixture-token");
            try{
                try(var out=c.getOutputStream()){
                    byte[] block=new byte[8192];int remaining=McpHostHttpTransport.MAX_REQUEST_BYTES+1;
                    while(remaining>0){int count=Math.min(remaining,block.length);out.write(block,0,count);remaining-=count;}
                }
                assertEquals(413,c.getResponseCode());
            }finally{c.disconnect();}
        }finally{transport.stop();}
    }
    @Test public void stopReleasesExecutorAndHeartbeatAndAllowsRestart()throws Exception{
        var transport=fixture();transport.start();
        java.lang.reflect.Field executorField=McpHostHttpTransport.class.getDeclaredField("requestExecutor");executorField.setAccessible(true);
        var executor=(java.util.concurrent.ExecutorService)executorField.get(transport);
        java.lang.reflect.Field streamsField=McpHostHttpTransport.class.getDeclaredField("sseStreams");streamsField.setAccessible(true);
        try{
            var client=HttpClient.newHttpClient();
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+transport.getBoundPort()+"/mcp")).timeout(Duration.ofSeconds(5)).header("Accept","text/event-stream").header("Authorization","Bearer review-fixture-token").GET().build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var body=response.body()){
                assertEquals("event: ready",new BufferedReader(new InputStreamReader(body)).readLine());
                @SuppressWarnings("unchecked") var streams=(java.util.Map<Thread,?>)streamsField.get(transport);
                var threads=java.util.List.copyOf(streams.keySet());assertEquals(1,threads.size());
                transport.stop();assertTrue(executor.isShutdown());assertTrue(executor.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS));
                for(Thread t:threads){t.join(3000);assertFalse("Heartbeat must stop promptly",t.isAlive());}
                assertTrue(streams.isEmpty());
            }
            transport.start();
            var health=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+transport.getBoundPort()+"/health")).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,health.statusCode());
        }finally{transport.stop();}
    }
}
