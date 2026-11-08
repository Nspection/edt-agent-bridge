// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
import com.codepilot1c.core.edt.infobase.*;

@ToolMeta(name="edt_project_from_application",category="workspace",mutating=true,tags={"workspace","edt","infobase"})
public final class EdtProjectFromApplicationTool extends AbstractTool {
    private static final Gson JSON=new Gson();
    private final ProjectFromApplicationService service=new ProjectFromApplicationService();
    @Override public String getDescription() { return "Import configuration from an explicit file infobase through native EDT export/import. mode=create creates a NEW project and binds the source application; mode=compare creates an owned snapshot project and prepares native comparison against the EXISTING destination project, including deletions and structured BSL. No destination source writes in compare mode; finish via edt_compare, edt_merge_plan and edt_merge_apply or hand off the SAME native session to the user. dry_run=true by default; apply requires expected_preview_sha256. Poll edt_operation_status. action=cleanup with import_id closes the owned comparison and removes only its unmodified generated snapshot, preserving exports/logs. Never substitutes importing project sources for loading a DT into a database."; }
    @Override public String getParameterSchema() {return """
{"type": "object", "additionalProperties": false, "properties": {"bind_application":{"type":"boolean","default":true,"description":"create: bind source DB to the new project; false preserves its existing association"},"reassign_from_project":{"type":"string","description":"create: explicitly transfer application from this exact existing owner; native EDT forbids assigning a DB to two projects"},"disposable_test_project":{"type":"boolean","default":false,"description":"For create only: mark an explicitly disposable BridgeInfobaseAudit* project for owned cleanup after tests"},"project": {"type": "string"}, "database_path": {"type": "string", "description": "Exact absolute local file-infobase directory authorized for this task"}, "runtime_version": {"type": "string", "description": "Explicit installed 1C platform version, e.g. 8.5.1.1522"}, "login": {"type": "string", "description": "Infobase login after DT restore/import"}, "password": {"type": "string", "description": "Infobase password; never returned"}, "dry_run": {"type": "boolean", "default": true}, "expected_preview_sha256": {"type": "string", "description": "Unchanged hash from the immediately preceding dry_run"}, "mode": {"type": "string", "enum": ["create", "compare"]}, "project_path": {"type": "string", "description": "For create: new absolute project directory ending in project name"}, "action": {"type": "string", "enum": ["run", "cleanup"], "default": "run"}, "import_id": {"type": "string", "description": "For cleanup: returned import_id; only owned unmodified comparison snapshot is deleted"}}, "required": []}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p) {
        Map<String,Object> a=Collections.unmodifiableMap(new LinkedHashMap<>(p.getRaw()));
        try {
            if ("cleanup".equals(a.get("action"))) return CompletableFuture.completedFuture(ToolResult.success(JSON.toJson(service.cleanup(InfobaseProvisionService.required(a,"import_id")))));
            Map<String,Object> preview=service.preview(a);
            if(InfobaseProvisionService.flag(a,"dry_run",true)) return CompletableFuture.completedFuture(ToolResult.success(JSON.toJson(preview)));
            InfobaseProvisionService.requirePreview(a,preview);
            String id=BackgroundJobRegistry.getInstance().startJob("edt_project_from_application",progress->{
                try{return JSON.toJson(service.run(a,progress));}
                catch(Exception | LinkageError e){throw new IllegalStateException(InfobaseProvisionService.safeError(e,a));}
            });
            return CompletableFuture.completedFuture(ToolResult.success(JSON.toJson(Map.of("job_id",id,"state","queued","next_tool","edt_operation_status"))));
        } catch(Exception | LinkageError e) {
            return CompletableFuture.completedFuture(ToolResult.failure(InfobaseProvisionService.safeError(e,a)));
        }
    }
}
