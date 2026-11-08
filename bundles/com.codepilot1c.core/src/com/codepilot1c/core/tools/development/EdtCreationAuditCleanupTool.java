package com.codepilot1c.core.tools.development;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
import com.codepilot1c.core.edt.metadata.EdtNativeProjects;
@ToolMeta(name="edt_creation_audit_cleanup",category="workspace",mutating=true,tags={"workspace","edt"})
public final class EdtCreationAuditCleanupTool extends AbstractTool {
    @Override public String getDescription(){return "Preview or clean a disposable BridgeCreation audit project through Eclipse workspace APIs. Only permits marked .runs/creation-audit/.../project fixtures with matching configuration UUID and exact expected_project_path. Cannot remove ordinary projects. dry_run defaults to true. Save evidence and close fixture editors first. Does not delete infobases. Poll edt_operation_status.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"project":{"type":"string"},"expected_project_path":{"type":"string"},"dry_run":{"type":"boolean","default":true}},"required":["project","expected_project_path"]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){
        try {
            Map<String,Object> a=Map.copyOf(p.getRaw());
            String id=BackgroundJobRegistry.getInstance().startJob("creation-audit-cleanup",()->new Gson().toJson(
                    EdtNativeProjects.cleanupCreationAudit((String)a.get("project"),(String)a.get("expected_project_path"),!Boolean.FALSE.equals(a.get("dry_run")))));
            return CompletableFuture.completedFuture(ToolResult.success(new Gson().toJson(Map.of("job_id",id,"state","queued","next_tool","edt_operation_status"))));
        } catch(Exception e){return CompletableFuture.completedFuture(ToolResult.failure(e.getMessage()));}
    }
}
