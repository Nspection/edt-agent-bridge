package com.codepilot1c.core.tools.editor;
import java.util.concurrent.CompletableFuture;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.editor.EdtWindowService;
import com.google.gson.Gson;
@ToolMeta(name="edt_window",category="workspace",mutating=true,tags={"workspace","edt"})
public final class EdtWindowTool extends AbstractTool {
    @Override public String getDescription(){return "Inspect, minimize or restore the EDT workbench window without an editor_id. On Windows minimize/restore avoid activating EDT over the user's current application. Returns before/after state, window_id, minimized/maximized/foreground and changed. Does not close windows or save/change metadata. Modal dialogs are reported rather than dismissed.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,
         "properties":{"action":{"type":"string","enum":["state","minimize","restore"],"default":"state"},"window_id":{"type":"string"}}}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters params){
        return CompletableFuture.supplyAsync(() -> {
            try{return ToolResult.success(new Gson().toJson(EdtWindowService.getInstance().execute(params.getRaw())));}
            catch(Exception e){return ToolResult.failure("EDT_WINDOW_ERROR: "+e.getClass().getSimpleName()+": "+e.getMessage());}
        });
    }
}
