package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.metadata.EdtMetadataService;
@ToolMeta(name="edt_rename_metadata",category="metadata",mutating=true,tags={"workspace","edt"})
public final class EdtRenameMetadataTool extends AbstractTool {
    @Override public String getDescription(){return "Preview a native EDT metadata rename, including reference changes, then apply with the fresh expected_preview_sha256. Preserves UUID and uses IMdRefactoringService; no XML/text replacement. Native prohibitions are never forced. Handles metadata objects/children; owned form and visual-item names require form-aware tools. Review all returned refactorings, which may include extension projects.";}
    @Override public String getParameterSchema(){return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"project\":{\"type\":\"string\"},\"target_fqn\":{\"type\":\"string\"},\"new_name\":{\"type\":\"string\"},\"apply\":{\"type\":\"boolean\",\"default\":false},\"expected_preview_sha256\":{\"type\":\"string\"}},\"required\":[\"project\",\"target_fqn\",\"new_name\"]}";}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{var a=p.getRaw();return ToolResult.success(new Gson().toJson(new EdtMetadataService().renameMetadata((String)a.get("project"),(String)a.get("target_fqn"),(String)a.get("new_name"),Boolean.TRUE.equals(a.get("apply")),(String)a.get("expected_preview_sha256"))));}catch(Exception e){return ToolResult.failure("EDT_RENAME: "+e.getMessage());}});}
}
