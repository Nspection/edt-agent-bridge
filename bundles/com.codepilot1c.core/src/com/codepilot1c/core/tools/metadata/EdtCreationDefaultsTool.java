package com.codepilot1c.core.tools.metadata;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.metadata.EdtMetadataService;
@ToolMeta(name="edt_creation_defaults",category="metadata",tags={"read-only","workspace","edt"})
public final class EdtCreationDefaultsTool extends AbstractTool {
    @Override public String getDescription(){return "Inspect project-aware EDT creation defaults and optionally actual metadata properties. Reports effective values, model presence and EMF defaults separately. Does not attach, save or repair objects. Specify target_fqn OR concrete mdclass class_name; fields limits output. Missing XML properties are not automatically errors.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"project":{"type":"string"},"target_fqn":{"type":"string"},"class_name":{"type":"string"},"fields":{"type":"array","items":{"type":"string"}}},"required":["project"],"oneOf":[{"required":["target_fqn"]},{"required":["class_name"]}]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters params){return CompletableFuture.supplyAsync(()->{
        try {
            Map<String,Object> args=params.getRaw();Set<String> fields=new LinkedHashSet<>();
            if(args.get("fields") instanceof List<?> list){for(Object value:list){fields.add((String)value);}}
            return ToolResult.success(new com.google.gson.GsonBuilder().serializeNulls().create().toJson(new EdtMetadataService().inspectCreationDefaults((String)args.get("project"),(String)args.get("target_fqn"),(String)args.get("class_name"),fields)));
        } catch(Exception error){return ToolResult.failure("EDT_CREATION_DEFAULTS: "+error.getMessage());}
    });}
}
