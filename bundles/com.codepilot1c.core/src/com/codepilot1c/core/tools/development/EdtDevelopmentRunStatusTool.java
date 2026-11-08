package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.DevelopmentRunService;
@ToolMeta(name="edt_development_run_status",category="qa",mutating=false,tags={"workspace","edt"})
public final class EdtDevelopmentRunStatusTool extends AbstractTool {
    @Override public String getDescription(){return "Read development run state, current Vanessa step, debugger launch, visual checkpoint and events after a cursor. paused_by_debugger is explicit. Disk snapshots survive restart and report interrupted_by_restart rather than claiming the old worker is still running.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"run_id":{"type":"string"},"compact":{"type":"boolean","default":false},"after_cursor":{"type":"integer","minimum":0}},"required":["run_id"]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{long cursor=p.getRaw().containsKey("after_cursor")?p.requireLong("after_cursor"):0;var result=DevelopmentRunService.getInstance().status(p.requireString("run_id"),cursor);return ToolResult.success(new Gson().toJson(Boolean.TRUE.equals(p.getRaw().get("compact"))?com.codepilot1c.core.edt.development.AgentBrief.run(result,cursor):result));}catch(Exception e){return ToolResult.failure("edt_development_run_status: "+e.getMessage());}});}
}
