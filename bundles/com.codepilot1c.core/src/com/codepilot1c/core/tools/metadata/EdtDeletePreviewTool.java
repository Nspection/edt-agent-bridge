package com.codepilot1c.core.tools.metadata;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.metadata.EdtMetadataService;
@ToolMeta(name="edt_delete_preview",category="metadata",tags={"read-only","workspace","edt"})
public final class EdtDeletePreviewTool extends AbstractTool {
    @Override public String getDescription(){return "Prepare native EDT delete refactoring without performing it. Lists affected items, incoming references EDT cannot clean, and platform prohibitions. Preview does not authorize deletion: use edt_validate_request followed by delete_metadata only when removal is requested. force never overrides platform prohibitions.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"project":{"type":"string"},"target_fqn":{"type":"string"}},"required":["project","target_fqn"]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{
        try{return ToolResult.success(new Gson().toJson(new EdtMetadataService().previewDelete((String)p.getRaw().get("project"),(String)p.getRaw().get("target_fqn"))));}
        catch(Exception error){return ToolResult.failure("EDT_DELETE_PREVIEW: "+error.getMessage());}
    });}
}
