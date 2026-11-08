package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
@ToolMeta(name="edt_infobase_state",category="diagnostics",mutating=false,tags={"workspace","edt"})
public final class EdtInfobaseStateTool extends AbstractTool {
    @Override public String getDescription(){return "Read current EDT project/infobase synchronization before launching/debugging. configuration_synchronized=true requires native equality=EQUAL; LOADING/NOT_EQUAL are not readiness. Reports connection and application_migrations_verified=false: schema equality does not certify application/BSP update handlers. Does not update the base or open a client.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"project":{"type":"string"}},"required":["project"]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{return ToolResult.success(new Gson().toJson(new EdtRuntimeService().synchronizationState((String)p.getRaw().get("project"))));}catch(Exception e){return ToolResult.failure("edt_infobase_state: "+e.getMessage());}});}
}
