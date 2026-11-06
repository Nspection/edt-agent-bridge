package com.codepilot1c.core.tools.editor;
import java.util.concurrent.CompletableFuture;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.editor.EdtEditorService;
import com.google.gson.Gson;
public abstract class AbstractEdtEditorTool extends AbstractTool {
    private final String action, description, schema;
    protected AbstractEdtEditorTool(String action,String description,String schema) {
        this.action=action;this.description=description;this.schema=schema;
    }
    @Override public String getDescription(){return description;}
    @Override public String getParameterSchema(){return schema;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters params){
        return CompletableFuture.supplyAsync(() -> {
            try{return ToolResult.success(new Gson().toJson(EdtEditorService.getInstance().execute(action,params.getRaw())));}
            catch(Exception e){return ToolResult.failure("EDT_EDITOR_ERROR: "+e.getClass().getSimpleName()+": "+e.getMessage());}
        });
    }
}
