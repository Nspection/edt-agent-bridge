// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
import com.codepilot1c.core.edt.infobase.*;

@ToolMeta(name="edt_infobase_provision",category="workspace",mutating=true,tags={"workspace","edt","infobase"})
public final class EdtInfobaseProvisionTool extends AbstractTool {
    private static final Gson JSON=new Gson();
    private final InfobaseProvisionService service=new InfobaseProvisionService();
    @Override public String getDescription() { return "Preview/create a file or server infobase, restore DT using the native EDT platform launcher, verify access by full configuration export, bind as project application and optionally perform FULL reload from project. No clicks or shell. dry_run=true by default; apply requires expected_preview_sha256. kind=server uses structured server_address and server_operation=create|restore|bind. New server creation uses unique cluster/SQL names; preserve creation_id from preview. New file paths must not exist. restore_existing=true first creates a backup DT and disconnects only this project's synchronization. Password/login refer to the RESTORED DB; existing_* are for backup/restore of the OLD DB. Returns job_id; poll edt_operation_status for stages/result/failure. Any create/restore requires a first full reload even after restart. Application migration handlers are not certified by schema equality. Git branch selection remains a separate explicit action."; }
    @Override public String getParameterSchema() {return """
{"type": "object", "additionalProperties": false, "properties": {"project": {"type": "string"}, "database_path": {"type": "string", "description": "Exact absolute local file-infobase directory authorized for this task"}, "runtime_version": {"type": "string", "description": "Explicit installed 1C platform version, e.g. 8.5.1.1522"}, "login": {"type": "string", "description": "Infobase login after DT restore/import"}, "password": {"type": "string", "description": "Infobase password; never returned"}, "dry_run": {"type": "boolean", "default": true}, "expected_preview_sha256": {"type": "string", "description": "Unchanged hash from the immediately preceding dry_run"}, "dt_path": {"type": "string"}, "disposable_test_base": {"type":"boolean","default":false,"description":"Only a newly created file base: issue an ownership lease for explicit task cleanup. Never use for a working base."}, "restore_existing": {"type": "boolean", "default": false, "description": "Explicitly allow replacing this exact existing file infobase; backup DT is mandatory and automatic"}, "existing_login": {"type": "string", "description": "Login for backup/restore of the old database before replacement"}, "existing_password": {"type": "string"}, "set_primary": {"type": "boolean", "default": true}, "replace_primary": {"type": "boolean", "default": false}, "update_from_project": {"type": "boolean", "default": true, "description": "After restore, use native FULL reloadInfobase then verify EQUAL. If false, mandatory full reload marker survives restart"}, "kind": {"type": "string", "enum": ["file", "server"], "default": "file"}, "server_operation": {"type": "string", "enum": ["create", "restore", "bind"], "default": "create"}, "server_address": {"type": "string"}, "server_infobase": {"type": "string"}, "creation_id": {"type": "string", "description": "Preserve the UUID returned by server create preview when applying"}, "name_prefix": {"type": "string", "default": "EdtAgent"}, "dbms": {"type": "string", "enum": ["postgresql", "mssql"], "default": "postgresql"}, "db_server": {"type": "string"}, "db_user": {"type": "string"}, "db_password": {"type": "string"}}, "required": ["project", "runtime_version"]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p) {
        Map<String,Object> a=Collections.unmodifiableMap(new LinkedHashMap<>(p.getRaw()));
        try {
            InfobaseProvisionService selected="server".equals(a.get("kind"))?new ServerInfobaseProvisionService():service;
            Map<String,Object> preview=selected.preview(a);
            if(InfobaseProvisionService.flag(a,"dry_run",true)) return CompletableFuture.completedFuture(ToolResult.success(JSON.toJson(preview)));
            InfobaseProvisionService.requirePreview(a,preview);
            String id=BackgroundJobRegistry.getInstance().startJob("edt_infobase_provision",progress->{
                try{return JSON.toJson(selected.run(a,progress));}
                catch(Exception | LinkageError e){throw new IllegalStateException(InfobaseProvisionService.safeError(e,a));}
            });
            return CompletableFuture.completedFuture(ToolResult.success(JSON.toJson(Map.of("job_id",id,"state","queued","next_tool","edt_operation_status"))));
        } catch(Exception | LinkageError e) {
            return CompletableFuture.completedFuture(ToolResult.failure(InfobaseProvisionService.safeError(e,a)));
        }
    }
}
