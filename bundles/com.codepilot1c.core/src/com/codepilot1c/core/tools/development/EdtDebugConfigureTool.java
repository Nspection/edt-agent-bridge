package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.debug.EdtDebugService;
@ToolMeta(name="edt_debug_configure",category="diagnostics",mutating=true,tags={"workspace","edt"})
public final class EdtDebugConfigureTool extends AbstractTool {
    @Override public String getDescription(){return "Preview or create a workspace-local native EDT RuntimeClient debug configuration using the project's real default application. dry_run defaults true; apply with false creates only a new configuration and never overwrites an existing name belonging to another project/type. Optional debug_server_port1..65535 configures LOCAL server port on this launch configuration only (including dry-run); no global debugger setting or server is changed/started. No client is launched. Development runs prepare their own configuration automatically.";}
    @Override public String getParameterSchema(){return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"project\":{\"type\":\"string\"},\"configuration_name\":{\"type\":\"string\"},\"debug_server_port\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":65535},\"dry_run\":{\"type\":\"boolean\",\"default\":true}},\"required\":[\"project\"]}";}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{var a=p.getRaw();return ToolResult.success(new Gson().toJson(EdtDebugService.getInstance().prepareDebugConfiguration((String)a.get("project"),(String)a.get("configuration_name"),!Boolean.FALSE.equals(a.get("dry_run")),a.get("debug_server_port")==null?null:new java.math.BigDecimal(a.get("debug_server_port").toString()).intValueExact())));}catch(Exception e){return ToolResult.failure("EDT_DEBUG_CONFIGURE: "+e.getMessage());}});}
}
