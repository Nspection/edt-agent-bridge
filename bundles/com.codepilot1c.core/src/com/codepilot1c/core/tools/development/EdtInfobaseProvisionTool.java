// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
import com.codepilot1c.core.edt.infobase.*;
import com.codepilot1c.core.edt.development.RuntimeExecutionPlan;

@ToolMeta(name="edt_infobase_provision",category="workspace",mutating=true,tags={"workspace","edt","infobase","sensitive"})
public final class EdtInfobaseProvisionTool extends AbstractTool {
    private static final Gson JSON=new Gson();
    private final InfobaseProvisionService service=new InfobaseProvisionService();
    @Override public String getDescription() { return "For a task authorized in chat, use scenario_preview on the exact primary test base UUID. Report consequences or ask for missing decisions in chat. scenario_approve registers the exact preview hash without an EDT prompt, then scenario_run executes it. Use the same run_id to revise or continue. Backup is opt-in. Optional task_access_path and expected_task_access_sha256 supply access without inline passwords. The preview describes local credential logging risk. For confirmed import continue with scenario_continue_after_restore, never import again. If requested, scenario_start_testclient launches one owned client after EQUAL and scenario_confirm_updates handles the exact expected 1C form. For a bound existing server base, bind supports task_access_path and expected_task_access_sha256 with exact base_uuid, verifies the numbered local HTML task before saving access in EDT, and never restores DT. Preview/create a file or server infobase, restore DT using the native EDT platform launcher, verify access by full configuration export, bind as project application and optionally perform FULL reload from project. No clicks or shell. dry_run=true by default; apply requires expected_preview_sha256. kind=server uses structured server_address and server_operation=create|restore|bind. New server creation uses unique cluster/SQL names; preserve creation_id from preview. New file paths must not exist. restore_existing=true first creates a backup DT and disconnects only this project's synchronization. Password/login refer to the RESTORED DB; existing_* are for backup/restore of the OLD DB. Returns job_id; poll edt_operation_status for stages/result/failure. For server restore, saved EDT access to the exact registered old base is reused when existing_login is omitted. awaiting_infobase_access asks for manual access in EDT. From the original MCP session, request_secure_access opens protected local input; continue_access consumes the same access_request_id once after secure_input_ready. cancel_access closes an exact owned question before backup or during verification. Access control takes only action, original job_id and expected_access_request_id. Never send a new password in MCP or chat. Protected credentials are saved only with the separate checkbox; the first native FULL requires saved access. Observe the original job to verify completion. Never repeat an import with unknown outcome. Server restore provides a credential-free restore_journal. For edt_command_tracing_active or edt_command_tracing_unverified leave EDT tracing unchanged and report the block to the operator. If import_completed=true, do not repeat the import. Any create/restore requires a first full reload even after restart. Application migration handlers are not certified by schema equality. Git branch selection remains a separate explicit action."; }
    @Override public String getParameterSchema() {return """
{"type": "object", "additionalProperties": false, "properties": {"action":{"type":"string","enum":["provision","cancel_access","request_secure_access","continue_access","scenario_preview","scenario_approve","scenario_run","scenario_continue_after_restore","scenario_status","scenario_start_testclient","scenario_confirm_restored_base","scenario_confirm_updates"],"default":"provision"},"run_id":{"type":"string"},"base_uuid":{"type":"string"},"start_testclient":{"type":"boolean"},"update_mode":{"enum":["restore_dt","full","incremental"]},"accept_legal_updates":{"type":"boolean"},"backup_before_restore":{"type":"boolean","default":false,"description":"Optional backup before replacing the approved server base."},"restored_base_choice":{"type":"string","enum":["copy","moved","manual"],"description":"DT restore scenario only. Defaults to copy. Use moved only if explicitly requested. Confirm the exact dialog on the approved base through a supported MCP action. If unavailable, report manual confirmation only when the dialog blocks the next step."},"expected_plan_sha256":{"type":"string"},"task_access_path":{"type":"string","description":"Exact local UTF-8 TXT or numbered HTML task export. Use a temporary local TXT for credentials already supplied in chat; pass only file path and SHA-256. Never repeat the password in tool arguments or output. Saving access requires confirmation in EDT."},"expected_task_access_sha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},"job_id":{"type":"string"},"expected_access_request_id":{"type":"string"},"project": {"type": "string"}, "database_path": {"type": "string", "description": "Exact absolute local file-infobase directory authorized for this task"}, "runtime_version": {"type": "string", "description": "Explicit installed 1C platform version, e.g. 8.5.1.1522"}, "login": {"type": "string", "description": "Infobase login after DT restore/import"}, "password": {"type": "string", "description": "Infobase password; never returned"}, "dry_run": {"type": "boolean", "default": true}, "expected_preview_sha256": {"type": "string", "description": "Unchanged hash from the immediately preceding dry_run"}, "dt_path": {"type": "string"}, "disposable_test_base": {"type":"boolean","default":false,"description":"Only a newly created file base: issue an ownership lease for explicit task cleanup. Never use for a working base."}, "restore_existing": {"type": "boolean", "default": false, "description": "Explicitly allow replacing this exact existing file infobase; backup DT is mandatory and automatic"}, "existing_login": {"type": "string", "description": "Login for backup/restore of the old database before replacement"}, "existing_password": {"type": "string"}, "set_primary": {"type": "boolean", "default": true}, "replace_primary": {"type": "boolean", "default": false}, "update_from_project": {"type": "boolean", "default": true, "description": "After restore, use native FULL reloadInfobase then verify EQUAL. If false, mandatory full reload marker survives restart"}, "kind": {"type": "string", "enum": ["file", "server"], "default": "file"}, "server_operation": {"type": "string", "enum": ["create", "restore", "bind"], "default": "create"}, "server_address": {"type": "string"}, "server_infobase": {"type": "string"}, "creation_id": {"type": "string", "description": "Preserve the UUID returned by server create preview when applying"}, "name_prefix": {"type": "string", "default": "EdtAgent"}, "dbms": {"type": "string", "enum": ["postgresql", "mssql"], "default": "postgresql"}, "db_server": {"type": "string"}, "db_user": {"type": "string"}, "db_password": {"type": "string"}}}
        """;}
    private CompletableFuture<ToolResult> scenario(Map<String,Object> args,String action,String caller)throws Exception {
        var input=new LinkedHashMap<>(args);
        input.remove("action");
        var scenario=new DtScenarioService();
        if("scenario_preview".equals(action))
            return CompletableFuture.completedFuture(ToolResult.success(JSON.toJson(scenario.preview(input))));
        if("scenario_approve".equals(action)){
            String expected=RuntimeExecutionPlan.required(input,"expected_plan_sha256");
            input.remove("expected_plan_sha256");
            String previousRunId=input.containsKey("run_id") ? RuntimeExecutionPlan.required(input,"run_id") : null;
            input.remove("run_id");
            return queuedScenario(BackgroundJobRegistry.getInstance().startJob("dt-scenario-approval",
                    progress->JSON.toJson(scenario.approve(input,expected,previousRunId,progress))));
        }
        if(!input.keySet().equals(Set.of("run_id")))throw new IllegalArgumentException("Scenario requires only run_id");
        String id=RuntimeExecutionPlan.required(input,"run_id");
        if("scenario_status".equals(action))
            return CompletableFuture.completedFuture(ToolResult.success(JSON.toJson(scenario.status(id))));
        if("scenario_confirm_restored_base".equals(action))
            return CompletableFuture.completedFuture(ToolResult.success(JSON.toJson(scenario.confirmRestoredBase(id))));
        if("scenario_confirm_updates".equals(action))
            return CompletableFuture.completedFuture(ToolResult.success(JSON.toJson(scenario.confirmUpdates(id))));
        return queuedScenario(switch(action){
            case "scenario_run" -> BackgroundJobRegistry.getInstance().startJob("dt-scenario-restore",progress->JSON.toJson(scenario.run(id,caller,progress)));
            case "scenario_continue_after_restore" -> BackgroundJobRegistry.getInstance().startJob("dt-scenario-post-restore-full",()->JSON.toJson(scenario.continueAfterRestore(id)));
            case "scenario_start_testclient" -> BackgroundJobRegistry.getInstance().startJob("dt-scenario-testclient",()->JSON.toJson(scenario.startClient(id)));
            default -> throw new IllegalArgumentException("Unknown DT scenario action");
        });
    }
    private static CompletableFuture<ToolResult> queuedScenario(String job) {
        return CompletableFuture.completedFuture(ToolResult.success(JSON.toJson(Map.of(
                "job_id",job,"state","queued","next_tool","edt_operation_status"))));
    }
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p) {
        return doExecute(p, ToolExecutionContext.unscoped());
    }

    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p, ToolExecutionContext context) {
        String caller = context.sessionId();
        Map<String,Object> a=Collections.unmodifiableMap(new LinkedHashMap<>(p.getRaw()));
        try {
            String action = Objects.toString(a.get("action"), "provision");
            if (action.startsWith("scenario_")) return scenario(a,action,caller);
            if (Set.of("cancel_access","request_secure_access","continue_access").contains(action)) {
                if (!Set.of("action","job_id","expected_access_request_id").equals(a.keySet()))
                    throw new IllegalArgumentException("Access control requires only job_id and expected_access_request_id");
                String job = RuntimeExecutionPlan.required(a,"job_id");
                String request = RuntimeExecutionPlan.required(a,"expected_access_request_id");
                var result = switch (action) {
                    case "request_secure_access" -> ServerRestoreAccessPrompt.requestSecureInput(job, request, caller);
                    case "continue_access" -> ServerRestoreAccessPrompt.continueAccess(job, request, caller);
                    default -> ServerRestoreAccessPrompt.cancel(job, request, caller);
                };
                return CompletableFuture.completedFuture(ToolResult.success(JSON.toJson(result)));
            }
            if (a.containsKey("job_id") || a.containsKey("expected_access_request_id"))
                throw new IllegalArgumentException("Access control arguments require an access action");
            InfobaseProvisionService selected="server".equals(a.get("kind"))?new ServerInfobaseProvisionService(caller):service;
            if(a.containsKey("task_access_path")||a.containsKey("expected_task_access_sha256")) {
                if(!(selected instanceof ServerInfobaseProvisionService server))
                    throw new IllegalArgumentException("Task access only applies to a registered server base");
                var plan=server.previewTaskAccess(a);
                if(InfobaseProvisionService.flag(a,"dry_run",true))
                    return CompletableFuture.completedFuture(ToolResult.success(JSON.toJson(plan)));
                InfobaseProvisionService.requirePreview(a,plan);
                return queuedScenario(BackgroundJobRegistry.getInstance().startJob("edt_infobase_task_access",progress->
                        JSON.toJson(server.runTaskAccess(a,progress))));
            }
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
