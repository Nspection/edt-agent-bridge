package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.VanessaMcpBridge;
@ToolMeta(name="edt_runtime_action",category="qa",mutating=true,tags={"workspace","edt"})
public final class EdtRuntimeActionTool extends AbstractTool {
    @Override public String getDescription(){return "Queue a semantic TestClient action or action batch through Vanessa MCP; returns a background job_id. Inspect edt_runtime_catalog for tool schema. idempotency_key prevents duplicate clicks on retry. Use edt_operation_status to read the result, and EDT debug tools independently while an action is stopped at a BSL breakpoint. OS UIAutomation, coordinate clicks and clipboard tools are excluded.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"endpoint":{"type":"string","default":"http://127.0.0.1:9874/mcp"},"server_id":{"type":"string"},"tool":{"type":"string"},"arguments":{"type":"object"},"idempotency_key":{"type":"string"}},"required":["tool","arguments","idempotency_key"]}
        """;}
    @SuppressWarnings("unchecked") @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{var a=p.getRaw();var result=VanessaMcpBridge.getInstance().action((String)a.get("endpoint"),(String)a.get("server_id"),(String)a.get("tool"),(java.util.Map<String,Object>)a.get("arguments"),(String)a.get("idempotency_key"));String text=new Gson().toJson(result);return Boolean.FALSE.equals(result.get("success"))?ToolResult.failure(text):ToolResult.success(text);}catch(Exception e){return ToolResult.failure("edt_runtime_action: "+e.getMessage());}});}
}
