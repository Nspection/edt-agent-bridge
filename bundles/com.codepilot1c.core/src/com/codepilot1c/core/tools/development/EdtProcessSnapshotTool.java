package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.ProcessWindowCapture;
@ToolMeta(name="edt_process_snapshot",category="qa",mutating=false,tags={"workspace","edt"})
public final class EdtProcessSnapshotTool extends AbstractTool {
    @FunctionalInterface interface Observer {java.util.Map<String,Object> execute(String action,Long pid,String started,String window,boolean popups,boolean expect)throws Exception;}
    static java.util.Map<String,Object> observe(java.util.Map<String,Object> a,Observer observer)throws Exception {
        if(!java.util.Set.of("action","process_id","process_started_at","window_id","include_popups","expect_popup").containsAll(a.keySet()))
            throw new IllegalArgumentException("Unknown process snapshot field; filter list with process_id, not client_id");
        for(String key:java.util.List.of("include_popups","expect_popup"))if(a.containsKey(key)&&!(a.get(key) instanceof Boolean))throw new IllegalArgumentException(key+" must be boolean");
        Long pid=null;if(a.containsKey("process_id")){if(!(a.get("process_id") instanceof Number n)||n.doubleValue()!=n.longValue()||n.longValue()<1)throw new IllegalArgumentException("process_id must be a positive integer");pid=n.longValue();}
        return observer.execute((String)a.getOrDefault("action","list"),pid,(String)a.get("process_started_at"),(String)a.get("window_id"),!Boolean.FALSE.equals(a.get("include_popups")),Boolean.TRUE.equals(a.get("expect_popup")));
    }
    @Override public String getDescription(){return "Observe 1C/EDT process windows directly through Windows PrintWindow, even before Vanessa MCP/TestClient is ready. action=list returns verified process ids/start times and window ids including modal dialogs. action=snapshot requires those exact identity fields and saves PNG without activating the window or sending input. Only 1cv8.exe, 1cv8c.exe and 1cedt.exe are supported; it never captures the entire desktop. include_popups=true (default) returns root image_path plus separate popup_images PNGs for visible WS_POPUP windows of the SAME process/UI thread, including small/ownerless menus. composed=false: inspect EVERY popup image; root PNG alone does not contain them. expect_popup=true fails unless at least one nonuniform popup image was captured and the bounded inventory stayed complete. Transparent/uniform shadow images remain visible in the response but do not count as informative popups. An informative WS_POPUP does not prove the intended semantic menu identity; inspect all images. Only windows present during snapshot are observed; this does not arm an event hook or retain menus that closed earlier. Each window identity/geometry and process start is checked; two matte passes reconstruct per-window transparency. No desktop pixels. Capture is bounded to 5s/two native workers, with a process gate held until the actual worker finishes after timeout; blank and low_information images are flagged; quality and hung_hint are advisory and do not prove readiness.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"action":{"type":"string","enum":["list","snapshot"],"default":"list"},"process_id":{"type":"integer","minimum":1},"process_started_at":{"type":"string"},"window_id":{"type":"string"},"include_popups":{"type":"boolean","default":true},"expect_popup":{"type":"boolean","default":false}},"required":[]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{return ToolResult.success(new Gson().toJson(observe(p.getRaw(),ProcessWindowCapture::execute)));}catch(Exception e){return ToolResult.failure("edt_process_snapshot: "+e.getMessage());}});}
}
