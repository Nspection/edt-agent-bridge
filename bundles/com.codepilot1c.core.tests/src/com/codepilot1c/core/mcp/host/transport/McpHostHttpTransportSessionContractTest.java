package com.codepilot1c.core.mcp.host.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.codepilot1c.core.mcp.host.McpHostConfig;
import com.codepilot1c.core.mcp.host.McpHostRequestRouter;
import com.codepilot1c.core.mcp.host.McpToolExposurePolicy;
import com.codepilot1c.core.mcp.host.prompt.IMcpPromptProvider;
import com.codepilot1c.core.mcp.model.McpPrompt;
import com.codepilot1c.core.mcp.model.McpPromptResult;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * HTTP-level compatibility contracts for MCP session lifecycle behavior.
 */
public class McpHostHttpTransportSessionContractTest {

    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
        + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"clientInfo\":{\"name\":\"contract\",\"version\":\"1\"}}}"; //$NON-NLS-1$
    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}"; //$NON-NLS-1$
    private static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}"; //$NON-NLS-1$

    @Test
    public void initializeWithoutSessionIdCreatesSessionAndReturnsHeader() throws Exception {
        try (TransportFixture fixture = new TransportFixture(Duration.ofMinutes(1), Clock.systemUTC())) {
            HttpResponse<String> response = fixture.post(INITIALIZE, Map.of());

            assertEquals(200, response.statusCode());
            String sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null); //$NON-NLS-1$
            assertNotNull(sessionId);
            assertFalse(sessionId.isBlank());
            assertEquals(1, fixture.transport.getSessionsSnapshot().size());
        }
    }

    @Test
    public void loopbackOriginCanInitializeWithBearer() throws Exception {
        try (TransportFixture fixture = new TransportFixture(Duration.ofMinutes(1), Clock.systemUTC())) {
            HttpResponse<String> response = fixture.post(INITIALIZE,
                    Map.of("Origin", "http://localhost:3000")); //$NON-NLS-1$ //$NON-NLS-2$

            assertEquals(200, response.statusCode());
            assertEquals(1, fixture.transport.getSessionsSnapshot().size());
        }
    }

    @Test
    public void remoteBrowserOriginIsRejectedBeforeSessionHandling() throws Exception {
        try (TransportFixture fixture = new TransportFixture(Duration.ofMinutes(1), Clock.systemUTC())) {
            HttpResponse<String> rejected = fixture.post(INITIALIZE,
                    Map.of("Origin", "http://attacker.example:3000")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(403, rejected.statusCode());
            assertEquals(Map.of("error", "invalid_origin"), json(rejected.body())); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(fixture.transport.getSessionsSnapshot().isEmpty());

            HttpResponse<String> initialized = fixture.post(INITIALIZE, Map.of());
            String sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow(); //$NON-NLS-1$
            assertEquals(403, fixture.delete(Map.of("Mcp-Session-Id", sessionId, //$NON-NLS-1$
                    "Origin", "http://attacker.example")).statusCode()); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(1, fixture.transport.getSessionsSnapshot().size());
        }
    }

    @Test
    public void unknownClientSessionIdReturns404WithoutCreatingSession() throws Exception {
        try (TransportFixture fixture = new TransportFixture(Duration.ofMinutes(1), Clock.systemUTC())) {
            HttpResponse<String> initialized = fixture.post(INITIALIZE, Map.of());
            assertEquals(200, initialized.statusCode());

            HttpResponse<String> response = fixture.post(PING, Map.of("Mcp-Session-Id", "unknown-session")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(404, response.statusCode());
            assertEquals(Map.of("error", "session_not_found"), json(response.body())); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(1, fixture.transport.getSessionsSnapshot().size());
        }
    }

    @Test
    public void postWithoutSessionIdRequiresInitializeAndDoesNotCreateSession() throws Exception {
        try (TransportFixture fixture = new TransportFixture(Duration.ofMinutes(1), Clock.systemUTC())) {
            assertEquals(200, fixture.post(INITIALIZE, Map.of()).statusCode());
            int existingSessionCount = fixture.transport.getSessionsSnapshot().size();

            HttpResponse<String> response = fixture.post(TOOLS_LIST, Map.of());

            assertEquals(400, response.statusCode());
            assertEquals(Map.of("error", "session_required"), json(response.body())); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(existingSessionCount, fixture.transport.getSessionsSnapshot().size());
        }
    }

    @Test
    public void deleteTerminatesKnownSession() throws Exception {
        try (TransportFixture fixture = new TransportFixture(Duration.ofMinutes(1), Clock.systemUTC())) {
            HttpResponse<String> initialized = fixture.post(INITIALIZE, Map.of());
            String sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow(); //$NON-NLS-1$

            HttpResponse<String> deleted = fixture.delete(Map.of("Mcp-Session-Id", sessionId)); //$NON-NLS-1$
            assertEquals(204, deleted.statusCode());
            assertTrue(fixture.transport.getSessionsSnapshot().isEmpty());

            assertEquals(404, fixture.post(PING, Map.of("Mcp-Session-Id", sessionId)).statusCode()); //$NON-NLS-1$
        }
    }

    @Test
    public void expiresSessionAtExactIdleTtlWithoutWallClockWaiting() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z")); //$NON-NLS-1$
        try (TransportFixture fixture = new TransportFixture(Duration.ofSeconds(60), clock)) {
            HttpResponse<String> initialized = fixture.post(INITIALIZE, Map.of());
            String sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow(); //$NON-NLS-1$

            clock.advance(Duration.ofSeconds(60));
            fixture.transport.cleanupInactiveSessions();

            assertTrue(fixture.transport.getSessionsSnapshot().isEmpty());
            assertEquals(404, fixture.post(PING, Map.of("Mcp-Session-Id", sessionId)).statusCode()); //$NON-NLS-1$
        }
    }

    @Test
    public void acceptsMissingAndSupportedProtocolHeaderAndRejectsUnsupportedVersion() throws Exception {
        try (TransportFixture fixture = new TransportFixture(Duration.ofMinutes(1), Clock.systemUTC())) {
            assertEquals(200, fixture.post(INITIALIZE, Map.of()).statusCode());
            assertEquals(200, fixture.post(INITIALIZE,
                    Map.of("MCP-Protocol-Version", "2025-06-18")).statusCode()); //$NON-NLS-1$ //$NON-NLS-2$

            HttpResponse<String> rejected = fixture.post(INITIALIZE,
                    Map.of("MCP-Protocol-Version", "1999-01-01")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(400, rejected.statusCode());
            assertEquals(Map.of(
                    "error", "unsupported_protocol_version", //$NON-NLS-1$ //$NON-NLS-2$
                    "message", "Unsupported MCP-Protocol-Version", //$NON-NLS-1$ //$NON-NLS-2$
                    "supported", List.of("2025-11-25", "2025-06-18", "2024-11-05")), json(rejected.body())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertEquals(2, fixture.transport.getSessionsSnapshot().size());
        }
    }

    @Test public void slowRequestDoesNotBlockStatusDeleteOrExpireWhileActive()throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var clock=new MutableClock(Instant.parse("2026-11-13T00:00:00Z"));
        var router=new McpHostRequestRouter(new AllowAllExposurePolicy(),List.of(),new EmptyPromptProvider(),McpHostConfig.MutationPolicy.ALLOW){
            @Override public com.codepilot1c.core.mcp.model.McpMessage route(com.codepilot1c.core.mcp.model.McpMessage request,com.codepilot1c.core.mcp.host.session.McpHostSession session){
                if("slow".equals(request.getMethod())){entered.countDown();try{if(!release.await(4,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("test release missing");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
                return super.route(request,session);
            }
        };
        try(var fixture=new TransportFixture(Duration.ofMinutes(1),clock,router)){
            String id=fixture.post(INITIALIZE,Map.of()).headers().firstValue("Mcp-Session-Id").orElseThrow();var headers=Map.of("Mcp-Session-Id",id);
            var slow=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return fixture.post("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"slow\"}",headers);}catch(Exception e){throw new RuntimeException(e);}});
            try{
                assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));clock.advance(Duration.ofMinutes(2));fixture.transport.cleanupInactiveSessions();assertEquals(1,fixture.transport.getSessionsSnapshot().size());
                assertEquals(200,fixture.post(PING,headers).statusCode());assertEquals(204,fixture.delete(headers).statusCode());assertEquals(404,fixture.post(PING,headers).statusCode());
            }finally{release.countDown();}
            assertEquals(200,slow.get(3,java.util.concurrent.TimeUnit.SECONDS).statusCode());assertTrue(fixture.transport.getSessionsSnapshot().isEmpty());
        }finally{release.countDown();}
    }

    @Test
    public void postStillSupportsSseFraming() throws Exception {
        try (var fixture = new TransportFixture(Duration.ofMinutes(1), Clock.systemUTC())) {
            String session = fixture.post(INITIALIZE, Map.of()).headers().firstValue("Mcp-Session-Id").orElseThrow();
            var reply = fixture.post(PING, Map.of("Mcp-Session-Id",session,"Accept","text/event-stream"));
            assertEquals(200, reply.statusCode());
            assertEquals("text/event-stream",reply.headers().firstValue("Content-Type").orElseThrow());
            assertTrue(reply.body().startsWith("event: message\ndata: "));
        }
    }

    @Test
    public void parallelToolBurstRejectsBeforeDispatchAndPreservesControlTraffic() throws Exception {
        verifyBurst(false);
    }

    @Test
    public void blockedResponseWritersCannotStarveSameSessionControlTraffic() throws Exception {
        verifyBurst(true);
    }

    private static void verifyBurst(boolean blockDelivery) throws Exception {
        int slots = McpHostHttpTransport.WORK_LIMIT;
        var occupied = new java.util.concurrent.CountDownLatch(slots);
        var release = new java.util.concurrent.CountDownLatch(1);
        var dispatched = new java.util.concurrent.atomic.AtomicInteger();
        var clock = new MutableClock(Instant.parse("2026-11-16T00:00:00Z"));
        Runnable block = () -> {
            occupied.countDown();
            try {
                if (!release.await(20, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("test release missing");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };
        var router = new McpHostRequestRouter(new AllowAllExposurePolicy(), List.of(), new EmptyPromptProvider(), McpHostConfig.MutationPolicy.ALLOW) {
            @Override public com.codepilot1c.core.mcp.model.McpMessage route(
                    com.codepilot1c.core.mcp.model.McpMessage request, com.codepilot1c.core.mcp.host.session.McpHostSession session) {
                if (!"tools/call".equals(request.getMethod())) return super.route(request, session);
                if ("inspect_form_layout".equals(((Map<?, ?>)request.getParams()).get("name"))) {
                    dispatched.incrementAndGet();
                    if (!blockDelivery) block.run();
                }
                var response = new com.codepilot1c.core.mcp.model.McpMessage();
                response.setRawId(request.getRawId());
                response.setResult(Map.of("test_result", true));
                return response;
            }
        };
        var transport = new McpHostHttpTransport("127.0.0.1", 0,
                new McpBearerAuthorization("127.0.0.1",0,"session-contract-token"), router,
                McpHostConfig.AuthMode.BEARER_ONLY, Duration.ofMinutes(1), clock) {
            @Override void writeResponseBody(com.sun.net.httpserver.HttpExchange exchange, byte[] bytes) throws java.io.IOException {
                if (blockDelivery && exchange.getRequestHeaders().containsKey("X-Test-Block")) block.run();
                super.writeResponseBody(exchange, bytes);
            }
        };
        try (var fixture = new TransportFixture(transport)) {
            String session = fixture.post(INITIALIZE, Map.of()).headers().firstValue("Mcp-Session-Id").orElseThrow();
            var headers = Map.of("Mcp-Session-Id", session);
            var heavy = new java.util.ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
            for (int i=0; i<slots; i++) {
                heavy.add(fixture.postAsync(toolCall("inspect_form_layout", i+100), Map.of("Mcp-Session-Id",session,"X-Test-Block","true")));
            }
            assertTrue(occupied.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var overflow = new java.util.ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
            for (int i=slots; i<53; i++) overflow.add(fixture.postAsync(toolCall("inspect_form_layout", i+100), headers));
            assertEquals(200, fixture.post(PING, headers).statusCode());
            assertTrue(fixture.post(toolCall("edt.status", 17), headers).body().contains("test_result"));
            var health = fixture.client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+fixture.port+"/health"))
                    .timeout(Duration.ofSeconds(5)).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            int expectedId = slots+100;
            for (var pending : overflow) {
                var body = com.google.gson.JsonParser.parseString(pending.get(8, java.util.concurrent.TimeUnit.SECONDS).body()).getAsJsonObject();
                assertEquals(expectedId++, body.get("id").getAsInt());
                assertEquals(-32001, body.getAsJsonObject("error").get("code").getAsInt());
                assertEquals("not_started", body.getAsJsonObject("error").getAsJsonObject("data").get("execution_state").getAsString());
            }
            assertEquals(slots, dispatched.get());
            assertEquals(53-slots, transport.transportMetrics().get("overload_before_dispatch").longValue());
            assertEquals(blockDelivery ? slots+3 : 3, transport.transportMetrics().get("route_completed").longValue());
            clock.advance(Duration.ofMinutes(2));
            transport.cleanupInactiveSessions();
            assertEquals(1, transport.getSessionsSnapshot().size());
            assertEquals(204, fixture.delete(headers).statusCode());
            assertEquals(404, fixture.post(PING, headers).statusCode());
            release.countDown();
            for (var pending : heavy) assertTrue(pending.get(5, java.util.concurrent.TimeUnit.SECONDS).body().contains("test_result"));
        } finally { release.countDown(); }
    }

    private static String toolCall(String name, int id) {
        return new Gson().toJson(Map.of("jsonrpc","2.0","method","tools/call","id",id,
                "params",Map.of("name",name,"arguments",Map.of())));
    }

    private static Map<String, Object> json(String value) {
        return new Gson().fromJson(value, new TypeToken<Map<String, Object>>() { }.getType());
    }

    private static final class TransportFixture implements AutoCloseable {
        private final McpHostHttpTransport transport;
        private final HttpClient client = HttpClient.newHttpClient();
        private final int port;

        TransportFixture(Duration ttl, Clock clock) {
            this(ttl,clock,new McpHostRequestRouter(new AllowAllExposurePolicy(), List.of(), new EmptyPromptProvider(),McpHostConfig.MutationPolicy.ALLOW));
        }
        TransportFixture(Duration ttl,Clock clock,McpHostRequestRouter router) {
            this(new McpHostHttpTransport("127.0.0.1", 0, //$NON-NLS-1$
                    new McpBearerAuthorization("127.0.0.1", 0, "session-contract-token"), router, //$NON-NLS-1$ //$NON-NLS-2$
                    McpHostConfig.AuthMode.BEARER_ONLY, ttl, clock));
        }

        TransportFixture(McpHostHttpTransport transport) {
            this.transport=transport;
            transport.start();
            port = transport.getBoundPort();
        }

        HttpResponse<String> post(String body, Map<String, String> headers) throws Exception {
            HttpRequest.Builder request = request(headers)
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        java.util.concurrent.CompletableFuture<HttpResponse<String>> postAsync(String body, Map<String,String> headers) {
            return client.sendAsync(request(headers).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> delete(Map<String, String> headers) throws Exception {
            return client.send(request(headers).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        }

        private HttpRequest.Builder request(Map<String, String> headers) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp")); //$NON-NLS-1$ //$NON-NLS-2$
            request.timeout(Duration.ofSeconds(5)).header("Authorization","Bearer session-contract-token");
            headers.forEach(request::header);
            return request;
        }

        @Override
        public void close() {
            transport.stop();
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        MutableClock(Instant initial) {
            instant = new AtomicReference<>(initial);
        }

        void advance(Duration duration) {
            instant.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }

    private static final class AllowAllExposurePolicy implements McpToolExposurePolicy {
        @Override
        public boolean isExposed(String toolName) {
            return true;
        }

        @Override
        public boolean requiresConfirmation(String toolName, Map<String, Object> args) {
            return false;
        }

        @Override
        public boolean isDestructive(String toolName) {
            return false;
        }
    }

    private static final class EmptyPromptProvider implements IMcpPromptProvider {
        @Override
        public List<McpPrompt> listPrompts() {
            return List.of();
        }

        @Override
        public java.util.Optional<McpPromptResult> getPrompt(String name, Map<String, Object> arguments) {
            return java.util.Optional.empty();
        }
    }
}
