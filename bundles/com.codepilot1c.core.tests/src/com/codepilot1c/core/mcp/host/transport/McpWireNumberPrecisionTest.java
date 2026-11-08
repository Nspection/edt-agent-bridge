package com.codepilot1c.core.mcp.host.transport;
import static org.junit.Assert.*;
import java.net.URI;import java.net.http.*;import java.time.Duration;import java.util.*;import java.math.BigDecimal;
import org.junit.Test;
import com.google.gson.*;
import com.codepilot1c.core.mcp.host.*;import com.codepilot1c.core.mcp.host.session.McpHostSession;import com.codepilot1c.core.mcp.model.McpMessage;
public class McpWireNumberPrecisionTest {
 @Test public void realHttpIngressKeepsNestedNumbersAndRpcIdExact()throws Exception{
  final Object[] captured=new Object[1];var router=new McpHostRequestRouter(null,List.of(),null,McpHostConfig.MutationPolicy.ALLOW){@Override public McpMessage route(McpMessage request,McpHostSession session){if(request.getMethod().equals("tools/call"))captured[0]=request.getParams();McpMessage response=new McpMessage();response.setRawId(request.getRawId());response.setResult(request.getParams());return response;}};
  var transport=new McpHostHttpTransport("127.0.0.1",0,new McpBearerAuthorization("127.0.0.1",0,"precision-test-token"),router,McpHostConfig.AuthMode.BEARER_ONLY);transport.start();
  try{HttpClient client=HttpClient.newHttpClient();URI uri=URI.create("http://127.0.0.1:"+transport.getBoundPort()+"/mcp");var init=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer precision-test-token").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}" )).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,init.statusCode());String session=init.headers().firstValue("Mcp-Session-Id").orElseThrow();
   String json="{\"jsonrpc\":\"2.0\",\"id\":9007199254740993,\"method\":\"tools/call\",\"params\":{\"name\":\"edt_test_data\",\"arguments\":{\"fields\":{\"Amount\":12345678901234567890123456789012345678,\"Small\":0.1234567890123456789012345678},\"items\":[{\"fields\":{\"Count\":9007199254740993}}]}}}";
   var response=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer precision-test-token").header("Mcp-Session-Id",session).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());JsonObject parsed=JsonParser.parseString(response.body()).getAsJsonObject();assertEquals("9007199254740993",parsed.get("id").getAsString());Map<?,?> arguments=(Map<?,?>)((Map<?,?>)captured[0]).get("arguments"),fields=(Map<?,?>)arguments.get("fields");assertEquals(new BigDecimal("12345678901234567890123456789012345678"),fields.get("Amount"));assertEquals(new BigDecimal("0.1234567890123456789012345678"),fields.get("Small"));Map<?,?> nested=(Map<?,?>)((Map<?,?>)((List<?>)arguments.get("items")).get(0)).get("fields");assertEquals(new BigDecimal("9007199254740993"),nested.get("Count"));assertEquals(new BigDecimal("12345678901234567890123456789012345678"),parsed.getAsJsonObject("result").getAsJsonObject("arguments").getAsJsonObject("fields").get("Amount").getAsBigDecimal());
  }finally{transport.stop();}
 }
}
