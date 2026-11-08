package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.VanessaMcpBridge;
@ToolMeta(name="edt_runtime_observe",category="qa",mutating=false,tags={"workspace","edt"})
public final class EdtRuntimeObserveTool extends AbstractTool {
    @Override public String getDescription(){return "Read native TestClient form/window/element/table state or capture a screenshot through Vanessa MCP. Inspect edt_runtime_catalog for exact tool arguments. Screenshots are saved to local image_path artifacts for visual analysis. No UI input is performed.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"endpoint":{"type":"string","default":"http://127.0.0.1:9874/mcp"},"server_id":{"type":"string"},"tool":{"type":"string"},"arguments":{"type":"object"}},"required":["tool","arguments"]}
        """;}
    @SuppressWarnings("unchecked") @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{var a=p.getRaw();var result=VanessaMcpBridge.getInstance().observe((String)a.get("endpoint"),(String)a.get("server_id"),(String)a.get("tool"),(java.util.Map<String,Object>)a.get("arguments"));String text=new Gson().toJson(result);return Boolean.FALSE.equals(result.get("success"))?ToolResult.failure(text):ToolResult.success(text);}catch(Exception e){return ToolResult.failure("edt_runtime_observe: "+e.getMessage());}});}
}
