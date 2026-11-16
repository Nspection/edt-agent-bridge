package com.codepilot1c.core.mcp.host.transport;

import static org.junit.Assert.*;
import org.junit.Test;
import java.net.*;import java.net.http.*;import java.nio.charset.StandardCharsets;import java.time.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import com.codepilot1c.core.mcp.host.*;import com.codepilot1c.core.mcp.host.prompt.*;import com.codepilot1c.core.mcp.host.session.*;import com.codepilot1c.core.mcp.model.*;

/** Real SocketChannel faults, not a mocked OutputStream or a sleep in a tool. */
public class McpHostHttpIoDeadlineTest {
 static final String TOKEN="owned-io-contract",INIT="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}",PING="{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}";
 static String call(int id,String name){return "{\"jsonrpc\":\"2.0\",\"id\":"+id+",\"method\":\"tools/call\",\"params\":{\"name\":\""+name+"\"}}";}
 @Test public void incompleteBodiesExpireWithoutDispatchAndSameSessionRecovers()throws Exception {incompleteRequests(false);}
 @Test public void incompleteHeadersExpireBeforeHandlerAndSameSessionRecovers()throws Exception {incompleteRequests(true);}
 void incompleteRequests(boolean headersOnly)throws Exception {
  try(var f=new Fixture(Duration.ofMillis(600))) {
   var peers=new ArrayList<Socket>();
   try {
    for(int i=0;i<16;i++){Socket s=new Socket("127.0.0.1",f.port);s.setSoTimeout(3000);peers.add(s);String header="POST /mcp HTTP/1.1\r\nHost: localhost\r\n";
     if(!headersOnly)header+="Authorization: Bearer "+TOKEN+"\r\nMcp-Session-Id: "+f.session+"\r\nContent-Length: 10000\r\n\r\n{";
     s.getOutputStream().write(header.getBytes(StandardCharsets.UTF_8));s.getOutputStream().flush();}
    for(var s:peers)try{assertEquals(-1,s.getInputStream().read());}catch(SocketException expected){/* channel interrupted/closed */}
    assertEquals(0,f.heavyRoutes.get());assertTrue(f.transport.transportMetrics().get("request_io_timeout")>=16);
    assertEquals(200,f.post(PING).statusCode());assertEquals(1,f.transport.getSessionsSnapshot().size());
   }finally {for(var s:peers)s.close();}
  }
 }
 @Test public void nonReadingPeersReleaseHeavySlotsWithoutRepeatingCompletedRoutes()throws Exception {
  try(var f=new Fixture(Duration.ofMillis(900))) {
   var peers=new ArrayList<Socket>();
   try {
    for(int i=0;i<4;i++){Socket s=new Socket();s.setReceiveBufferSize(1024);s.connect(new InetSocketAddress("127.0.0.1",f.port));peers.add(s);String body=call(10+i,"large");s.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+TOKEN+"\r\nMcp-Session-Id: "+f.session+"\r\nContent-Length: "+body.length()+"\r\n\r\n"+body).getBytes(StandardCharsets.UTF_8));s.getOutputStream().flush();}
    assertTrue(f.largeRoutes.await(3,TimeUnit.SECONDS));assertEquals(200,f.post(PING).statusCode());
    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(f.transport.transportMetrics().get("response_io_timeout")<4&&System.nanoTime()<end)Thread.sleep(20);
    assertEquals(4,f.transport.transportMetrics().get("response_io_timeout").longValue());
    HttpResponse<String> result=null;do{result=f.post(call(30,"small"));if(!result.body().contains("Server busy"))break;Thread.sleep(20);}while(System.nanoTime()<end);
    assertNotNull(result);assertTrue(result.body(),result.body().contains("small"));assertEquals(5,f.heavyRoutes.get());
    assertEquals(7,f.transport.transportMetrics().get("route_completed").longValue()); // init, 4large, ping, small
    assertFalse(Thread.currentThread().isInterrupted());
   }finally{for(var s:peers)s.close();}
  }
 }
 @Test public void businessExecutionOutlivesIoBudgetWithoutInterruption()throws Exception {
  try(var f=new Fixture(Duration.ofMillis(600))) {
   var response=f.post(call(40,"slow"));assertEquals(200,response.statusCode());assertTrue(response.body(),response.body().contains("small"));assertFalse(f.businessInterrupted.get());assertEquals(1,f.heavyRoutes.get());assertEquals(0,f.transport.transportMetrics().get("request_io_timeout").longValue());assertEquals(0,f.transport.transportMetrics().get("response_io_timeout").longValue());
  }
 }
 @Test public void cancelledDeadlineCannotInterruptReusedPoolWorker()throws Exception {
  var scheduler=new ScheduledThreadPoolExecutor(1);scheduler.setRemoveOnCancelPolicy(true);var pool=Executors.newSingleThreadExecutor();var expired=new AtomicInteger();
  try {for(int i=0;i<50;i++)pool.submit(()->{try(var d=new McpHttpIoDeadline(scheduler,Duration.ofMillis(80),expired::incrementAndGet)){}return true;}).get(2,TimeUnit.SECONDS);
   assertTrue(pool.submit(()->{Thread.sleep(150);return !Thread.currentThread().isInterrupted();}).get(2,TimeUnit.SECONDS));assertEquals(0,expired.get());assertTrue(scheduler.getQueue().isEmpty());
  }finally {pool.shutdownNow();scheduler.shutdownNow();}
 }
 @Test public void expiryClearsItsInterruptBeforeNextIoPhase()throws Exception {
  var scheduler=new ScheduledThreadPoolExecutor(1);var expired=new AtomicInteger();
  try {try(var d=new McpHttpIoDeadline(scheduler,Duration.ofMillis(30),expired::incrementAndGet)){try{Thread.sleep(2000);fail("Deadline did not interrupt");}catch(InterruptedException expected){}assertTrue(d.expired());}assertFalse(Thread.currentThread().isInterrupted());assertEquals(1,expired.get());}
  finally {scheduler.shutdownNow();Thread.interrupted();}
 }
 static class Fixture implements AutoCloseable {
  final AtomicBoolean businessInterrupted=new AtomicBoolean();
  final AtomicInteger heavyRoutes=new AtomicInteger();final CountDownLatch largeRoutes=new CountDownLatch(4);final McpHostHttpTransport transport;final int port;final HttpClient client=HttpClient.newHttpClient();final String session;
  Fixture(Duration timeout)throws Exception {
   String large="x".repeat(8*1024*1024);var router=new McpHostRequestRouter(new McpToolExposurePolicy(){public boolean isExposed(String s){return true;}public boolean requiresConfirmation(String s,Map<String,Object> m){return false;}public boolean isDestructive(String s){return false;}},List.of(),new IMcpPromptProvider(){public List<McpPrompt> listPrompts(){return List.of();}public Optional<McpPromptResult> getPrompt(String s,Map<String,Object> m){return Optional.empty();}},McpHostConfig.MutationPolicy.ALLOW){
    @Override public McpMessage route(McpMessage r,McpHostSession s){if(!"tools/call".equals(r.getMethod()))return super.route(r,s);heavyRoutes.incrementAndGet();if("slow".equals(((Map<?,?>)r.getParams()).get("name")))try{Thread.sleep(1200);}catch(InterruptedException interrupted){businessInterrupted.set(true);Thread.currentThread().interrupt();}boolean big="large".equals(((Map<?,?>)r.getParams()).get("name"));if(big)largeRoutes.countDown();var response=new McpMessage();response.setRawId(r.getRawId());response.setResult(Map.of("value",big?large:"small"));return response;}
   };
   transport=new McpHostHttpTransport("127.0.0.1",0,new McpBearerAuthorization("127.0.0.1",0,TOKEN),router,McpHostConfig.AuthMode.BEARER_ONLY,Duration.ofMinutes(1),Clock.systemUTC(),timeout);transport.start();port=transport.getBoundPort();session=post(INIT).headers().firstValue("Mcp-Session-Id").orElseThrow();
  }
  HttpResponse<String> post(String body)throws Exception {var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/mcp")).timeout(Duration.ofSeconds(4)).header("Authorization","Bearer "+TOKEN);if(session!=null)builder.header("Mcp-Session-Id",session);return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
  public void close(){transport.stop();}
 }
}
