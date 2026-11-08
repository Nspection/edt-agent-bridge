package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
import com.codepilot1c.core.edt.external.EdtExternalObjectService;
import com.codepilot1c.core.edt.development.RuntimeExecutionPlan;
@ToolMeta(name="edt_external_build",category="workspace",mutating=true,tags={"workspace","edt"})
public final class EdtExternalBuildTool extends AbstractTool {
    @Override public String getDescription(){return "Build EPF/ERF through native EDT IExternalObjectDumper. Use external_manage create_processing/create_report to create a new external-object project bound to a base project first. Pass full ExternalDataProcessor.Name/ExternalReport.Name FQN. Output stays in workspace metadata outside source/Git. action=cleanup removes one owned artifact by exact path and SHA256. Returns job_id; poll edt_operation_status for artifact_path and SHA256. Uses the base application's EDT runtime/access settings; fails on native configuration/lock errors.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"action":{"type":"string","enum":["build","cleanup"],"default":"build"},"artifact_path":{"type":"string"},"expected_artifact_sha256":{"type":"string"},"project":{"type":"string"},"external_project":{"type":"string"},"object_fqn":{"type":"string"}}}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try {
        var a=p.getRaw();String action=(String)a.getOrDefault("action","build");
        if(action.equals("cleanup")) return ToolResult.success(new Gson().toJson(new EdtExternalObjectService().cleanupArtifact(RuntimeExecutionPlan.required(a,"artifact_path"),RuntimeExecutionPlan.required(a,"expected_artifact_sha256"))));
        if(!action.equals("build")) throw new IllegalArgumentException("Unknown action");
        String project=RuntimeExecutionPlan.required(a,"project"),external=RuntimeExecutionPlan.required(a,"external_project"),fqn=RuntimeExecutionPlan.required(a,"object_fqn");
        String id=BackgroundJobRegistry.getInstance().startJob("external-build",()->new Gson().toJson(new EdtExternalObjectService().build(project,external,fqn)));
        return ToolResult.success(new Gson().toJson(java.util.Map.of("job_id",id,"next_tool","edt_operation_status")));
    }catch(Exception e){return ToolResult.failure("edt_external_build: "+e.getMessage());}});}
}
