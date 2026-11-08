package com.codepilot1c.core.tools.development;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.metadata.EdtMetadataService;
@ToolMeta(name="edt_predefined",category="metadata",mutating=true,tags={"workspace","edt"})
public final class EdtPredefinedTool extends AbstractTool {
    @Override public String getDescription(){return "Read/create/update/delete predefined items through the EDT model. Supports Catalog and characteristic/account/calculation charts. read returns paginated items with UUIDs; mutations first preview (apply=false), then require the fresh expected_preview_sha256. Existing item IDs are preserved; create-preview IDs are provisional. Folder deletion includes descendants. Does not execute data migrations or update the infobase; inspect code references before deletion.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"project":{"type":"string"},"owner_fqn":{"type":"string"},"action":{"type":"string","enum":["read","create","update","delete"],"default":"read"},"name":{"type":"string"},"properties":{"type":"object","description":"Named properties: code, description, isFolder, parent (create only), valueType for characteristic charts, or supported chart-specific fields. Omitted values are retained on update."},"apply":{"type":"boolean","default":false},"expected_preview_sha256":{"type":"string"},"offset":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":500,"default":100}},"required":["project","owner_fqn"]}
        """;}
    @SuppressWarnings("unchecked") @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{var a=p.getRaw();Map<String,Object> properties=a.get("properties") instanceof Map<?,?> values?(Map<String,Object>)values:Map.of();return ToolResult.success(new Gson().toJson(new EdtMetadataService().predefinedContent((String)a.get("project"),(String)a.get("owner_fqn"),(String)a.getOrDefault("action","read"),(String)a.get("name"),properties,Boolean.TRUE.equals(a.get("apply")),(String)a.get("expected_preview_sha256"),((Number)a.getOrDefault("offset",0)).intValue(),((Number)a.getOrDefault("limit",100)).intValue())));}catch(Exception e){return ToolResult.failure("EDT_PREDEFINED: "+e.getMessage());}});}
}
