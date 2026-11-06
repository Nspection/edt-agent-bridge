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
}
