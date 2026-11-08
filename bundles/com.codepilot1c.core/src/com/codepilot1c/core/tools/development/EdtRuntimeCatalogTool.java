package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.VanessaMcpBridge;
@ToolMeta(name="edt_runtime_catalog",category="qa",mutating=false,tags={"workspace","edt"})
public final class EdtRuntimeCatalogTool extends AbstractTool {
    @Override public String getDescription(){return "Discover the connected Vanessa MCP semantic UI capabilities and actual parameter schemas. Requires client_mcp in the TestManager base. endpoint is loopback only; use an already configured CodePilot server_id for authenticated connections. Does not install extensions or start clients.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"endpoint":{"type":"string","default":"http://127.0.0.1:9874/mcp"},"server_id":{"type":"string"}}}
        """;}
    @SuppressWarnings("unchecked") @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{var a=p.getRaw();var result=VanessaMcpBridge.getInstance().catalog((String)a.get("endpoint"),(String)a.get("server_id"));String text=new Gson().toJson(result);return Boolean.FALSE.equals(result.get("success"))?ToolResult.failure(text):ToolResult.success(text);}catch(Exception e){return ToolResult.failure("edt_runtime_catalog: "+e.getMessage());}});}
}
