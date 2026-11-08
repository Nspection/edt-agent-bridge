package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.codepilot1c.core.tools.*;
import com.google.gson.Gson;
@ToolMeta(name="edt_change_set",category="workspace",mutating=true,tags={"workspace","edt"})
public final class EdtChangeSetTool extends AbstractTool {
    @Override public String getDescription(){return "Capture explicit existing project files and current diagnostics (begin), inspect hash/diagnostic deltas (status), make owned UTF-8 text edits with expected_sha256 (write_text), or roll back only this command's recorded writes while hashes still match (rollback). Baseline alone never authorizes restoring other edits. Metadata XML is snapshot/read-only here; use native metadata tools. No Git reset. Paths are project-relative, max 50 files / 8 MB total / 1 MB per file. Unsaved editor changes block writes. Return current marker deltas does not await builders.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"project":{"type":"string"},"action":{"type":"string","enum":["begin","status","write_text","rollback"]},"files":{"type":"array","items":{"type":"string"},"maxItems":50},"change_set_id":{"type":"string"},"file_path":{"type":"string"},"content":{"type":"string"},"expected_sha256":{"type":"string"}},"required":["project","action"]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{return ToolResult.success(new Gson().toJson(new com.codepilot1c.core.edt.development.EdtChangeSetService().execute(p.getRaw())));}catch(Exception e){return ToolResult.failure("EDT_CHANGE_SET: "+e.getMessage());}});}
}
