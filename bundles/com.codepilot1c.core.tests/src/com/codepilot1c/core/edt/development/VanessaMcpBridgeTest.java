package com.codepilot1c.core.edt.development;
import org.junit.Test;
import static org.junit.Assert.*;
public class VanessaMcpBridgeTest {
    @Test public void acceptsOnlyExplicitLoopbackMcpEndpoints() {
        assertEquals("http://127.0.0.1:9874/mcp",VanessaMcpBridge.localEndpoint("http://127.0.0.1:9874/mcp"));
        assertEquals("http://localhost:1234/mcp/",VanessaMcpBridge.localEndpoint("http://localhost:1234/mcp/"));
        assertEquals("http://[::1]:9874/mcp",VanessaMcpBridge.localEndpoint("http://[::1]:9874/mcp"));
    }
    @Test public void rejectsExternalAddressesAndEmbeddedCredentials() {
        for(String endpoint:new String[]{"http://example.com:9874/mcp","http://127.0.0.1.example.com:9874/mcp","http://user:password@localhost:9874/mcp","http://0.0.0.0:9874/mcp"}){reject(endpoint);}
    }
    @Test public void rejectsAmbiguousPortsPathsAndRedirectParameters() {
        for(String endpoint:new String[]{"http://localhost/mcp","http://localhost:0/mcp","http://localhost:65536/mcp","http://localhost:9874/other","http://localhost:9874/mcp?target=external","http://localhost:9874/mcp#fragment","file:///mcp"}){reject(endpoint);}
    }
    @Test public void observationAndActionsDoNotExposeCoordinateInputOrCodeExecution() {
        assertTrue(VanessaMcpBridge.READ.contains("get_window_screenshot_os"));
        assertTrue(VanessaMcpBridge.WRITE.contains("execute_form_actions"));
        assertTrue(java.util.Collections.disjoint(VanessaMcpBridge.READ,VanessaMcpBridge.WRITE));
        for(String forbidden:new String[]{"execute_bsl","click","press_key","set_clipboard","ui_automation"}){assertFalse(VanessaMcpBridge.READ.contains(forbidden));assertFalse(VanessaMcpBridge.WRITE.contains(forbidden));}
    }
    @Test public void distinguishesExpiredSessionsFromActionValidationFailures() {
        assertTrue(VanessaMcpBridge.isExpiredSession(new java.util.concurrent.ExecutionException(new com.codepilot1c.core.mcp.transport.McpHttpException("expired",404))));
        assertTrue(VanessaMcpBridge.isExpiredSession(new com.codepilot1c.core.mcp.transport.McpHttpException("server restarted",422)));
        assertFalse(VanessaMcpBridge.isExpiredSession(new com.codepilot1c.core.mcp.transport.McpHttpException("bad input",400)));
        assertFalse(VanessaMcpBridge.isExpiredSession(new IllegalArgumentException("missing field")));
    }
    @Test public void structuredFailureIsNotReportedAsSuccessEvenWithIsErrorFalse(){
        assertFalse(VanessaMcpBridge.isSuccessful(com.codepilot1c.core.mcp.model.McpToolResult.text("{\"ok\":false,\"errors\":[\"failed\"]}")));
        assertTrue(VanessaMcpBridge.isSuccessful(com.codepilot1c.core.mcp.model.McpToolResult.text("{\"ok\":true}")));
    }
    @Test public void clientConnectionRequiresObservablePostcondition(){
        assertEquals(Boolean.FALSE,VanessaMcpBridge.connected(com.codepilot1c.core.mcp.model.McpToolResult.text("{\"ok\":true,\"testClientConnected\":false}")));
        assertEquals(Boolean.TRUE,VanessaMcpBridge.connected(com.codepilot1c.core.mcp.model.McpToolResult.text("{\"ok\":true,\"testClientConnected\":true}")));
        assertNull(VanessaMcpBridge.connected(com.codepilot1c.core.mcp.model.McpToolResult.text("Connection was requested")));
    }
    private static void reject(String endpoint){try{VanessaMcpBridge.localEndpoint(endpoint);fail("Accepted unsafe endpoint: "+endpoint);}catch(IllegalArgumentException expected){}}
    @Test public void queuedWindowGuardRejectsWrongWindowWithoutSendingTheAction()throws Exception{
        var writes=new java.util.concurrent.atomic.AtomicInteger();var result=VanessaMcpBridge.guardedDispatch("Expected",()->java.util.Map.of("success",true,"content",java.util.List.of(java.util.Map.of("type","text","text","Other"))),()->{writes.incrementAndGet();return java.util.Map.of("success",true);});
        assertEquals(false,result.get("success"));assertEquals(false,result.get("action_dispatched"));assertEquals(0,writes.get());
    }
    @Test public void legacyActionsAndMatchingWindowStillDispatchOnce()throws Exception{
        var writes=new java.util.concurrent.atomic.AtomicInteger();java.util.concurrent.Callable<java.util.Map<String,Object>>write=()->{writes.incrementAndGet();return java.util.Map.of("success",true);};
        VanessaMcpBridge.guardedDispatch(null,()->{throw new AssertionError("Legacy action should not add a new read");},write);
        VanessaMcpBridge.guardedDispatch("Expected",()->java.util.Map.of("success",true,"content",java.util.List.of(java.util.Map.of("type","text","text","Expected"))),write);assertEquals(2,writes.get());
    }
}
