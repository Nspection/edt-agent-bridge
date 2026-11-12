package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.ProcessWindowCapture;
@ToolMeta(name="edt_process_snapshot",category="qa",mutating=false,tags={"workspace","edt"})
public final class EdtProcessSnapshotTool extends AbstractTool {
    @Override public String getDescription(){return "Observe 1C/EDT process windows directly through Windows PrintWindow, even before Vanessa MCP/TestClient is ready. action=list returns verified process ids/start times and window ids including modal dialogs. action=snapshot requires those exact identity fields and saves PNG without activating the window or sending input. Only 1cv8.exe, 1cv8c.exe and 1cedt.exe are supported; it never captures the entire desktop. Capture is bounded to 5s/two native workers; blank and low_information images are flagged; quality and hung_hint are advisory and do not prove readiness.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"action":{"type":"string","enum":["list","snapshot"],"default":"list"},"process_id":{"type":"integer","minimum":1},"process_started_at":{"type":"string"},"window_id":{"type":"string"}},"required":[]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{var a=p.getRaw();Long pid=a.get("process_id") instanceof Number number?number.longValue():null;return ToolResult.success(new Gson().toJson(ProcessWindowCapture.execute((String)a.getOrDefault("action","list"),pid,(String)a.get("process_started_at"),(String)a.get("window_id"))));}catch(Exception e){return ToolResult.failure("edt_process_snapshot: "+e.getMessage());}});}
}
