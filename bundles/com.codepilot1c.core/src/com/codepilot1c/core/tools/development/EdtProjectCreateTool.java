package com.codepilot1c.core.tools.development;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
import com.codepilot1c.core.edt.metadata.EdtNativeProjects;
@ToolMeta(name="edt_project_create",category="workspace",mutating=true,tags={"workspace","edt"})
public final class EdtProjectCreateTool extends AbstractTool {
    @Override public String getDescription(){return "Create a new standalone configuration project through the native EDT factory/project manager, with default language. Never overwrites a project or directory. Returns job_id immediately; poll edt_operation_status, then wait for project readiness. Does not connect an infobase or update existing projects.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"project":{"type":"string"},"project_path":{"type":"string"},"configuration_name":{"type":"string"},"version":{"type":"string"}},"required":["project","project_path","configuration_name","version"]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){
        try {
            Map<String,Object> a=Map.copyOf(p.getRaw());String id=BackgroundJobRegistry.getInstance().startJob("project-create",()->new Gson().toJson(EdtNativeProjects.create((String)a.get("project"),(String)a.get("project_path"),(String)a.get("version"),(String)a.get("configuration_name"))));
            return CompletableFuture.completedFuture(ToolResult.success(new Gson().toJson(Map.of("job_id",id,"state","queued","next_tool","edt_operation_status"))));
        } catch(Exception e){return CompletableFuture.completedFuture(ToolResult.failure(e.getMessage()));}
    }
}
