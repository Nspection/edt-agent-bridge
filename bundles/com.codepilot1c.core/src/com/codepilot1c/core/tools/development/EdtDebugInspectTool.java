package com.codepilot1c.core.tools.development;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.debug.EdtDebugService;
@ToolMeta(name="edt_debug_inspect",category="diagnostics",tags={"read-only","workspace","edt"})
public final class EdtDebugInspectTool extends AbstractTool {
    @Override public String getDescription(){return "Inspect EDT launches, targets, threads, stopped breakpoint ids and stack frames. Select thread_id/frame_id to read variables; variable_path expands children by exact names with pagination. Source contains the EDT module URI. No expressions are executed. Multiple suspended threads require explicit selection for variables.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"project":{"type":"string"},"thread_id":{"type":"string"},"frame_id":{"type":"string"},"include_variables":{"type":"boolean","default":false},"variable_path":{"type":"array","items":{"type":"string"}},"offset":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":200}},"required":["project"]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{
        var a=p.getRaw();List<String> path=new ArrayList<>();if(a.get("variable_path") instanceof List<?> list){for(Object v:list){path.add((String)v);}}
        return ToolResult.success(new Gson().toJson(EdtDebugService.getInstance().inspectDebug((String)a.get("project"),(String)a.get("thread_id"),(String)a.get("frame_id"),Boolean.TRUE.equals(a.get("include_variables")),path,((Number)a.getOrDefault("offset",0)).intValue(),((Number)a.getOrDefault("limit",50)).intValue())));
    }catch(Exception e){return ToolResult.failure("EDT_DEBUG_INSPECT: "+e.getMessage());}});}
}
