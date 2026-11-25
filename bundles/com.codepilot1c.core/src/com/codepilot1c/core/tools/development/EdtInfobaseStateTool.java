package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
@ToolMeta(name="edt_infobase_state",category="diagnostics",mutating=false,tags={"workspace","edt"})
public final class EdtInfobaseStateTool extends AbstractTool {
    @Override public String getDescription(){return "Read current EDT project/infobase synchronization before launching/debugging. configuration_synchronized=true requires native equality=EQUAL; LOADING/NOT_EQUAL are not readiness. Reports connection and application_migrations_verified=false: schema equality does not certify application/BSP update handlers. Accepts optional expected_base_uuid to verify the current binding. A matching UUID and an open connection do not establish configuration equality. A TestClient start requires native EQUAL. This reads existing EDT state without refresh, update, or client launch.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"project":{"type":"string"},"expected_base_uuid":{"type":"string","format":"uuid"}},"required":["project"]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{var state=new EdtRuntimeService().synchronizationState(p.requireString("project"));
        if(p.getRaw().containsKey("expected_base_uuid")){
            var expected=java.util.UUID.fromString(p.requireString("expected_base_uuid")).toString();
            var checked=new java.util.LinkedHashMap<String,Object>(state);
            checked.put("binding_matches_expected",expected.equalsIgnoreCase((String)state.get("uuid")));
            return ToolResult.success(new Gson().toJson(checked));
        }
        return ToolResult.success(new Gson().toJson(state));}catch(Exception e){return ToolResult.failure("edt_infobase_state: "+e.getMessage());}});}
}
