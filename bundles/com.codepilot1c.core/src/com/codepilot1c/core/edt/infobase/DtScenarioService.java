// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.codepilot1c.core.edt.development.*;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;

/** Local approval and single-use receipts for one exact registered server test base. */
public final class DtScenarioService {
    private static final Object LOCK = new Object();
    private static final Gson JSON = new Gson();
    private static final long TTL_MS = Duration.ofHours(2).toMillis();
    private static final Set<String> PLAN_FIELDS = Set.of("project", "base_uuid", "runtime_version",
            "server_address", "server_infobase", "dt_path", "start_testclient", "update_mode", "accept_legal_updates", "restored_base_choice", "backup_before_restore", "allow_passwordless_restore_with_trace",
            "task_access_path", "expected_task_access_sha256");
    private final NativeInfobaseGateway gateway = new NativeInfobaseGateway();
    private final Path root = gateway.stateRoot().resolve("dt-scenarios");

    public Map<String,Object> preview(Map<String,Object> args) throws Exception {
        if (!PLAN_FIELDS.containsAll(args.keySet())) throw new IllegalArgumentException("Unsupported scenario field");
        var restore = new LinkedHashMap<String,Object>();
        String mode = Objects.toString(args.get("update_mode"),"restore_dt");
        if (!Set.of("restore_dt","full","incremental").contains(mode))
            throw new IllegalArgumentException("Invalid scenario update mode");
        boolean restoreDt = "restore_dt".equals(mode);
        if (!restoreDt && !Collections.disjoint(args.keySet(),Set.of("dt_path","server_address","server_infobase","runtime_version")))
            throw new IllegalArgumentException("DT and server import fields apply only to restore_dt");
        if (args.containsKey("allow_passwordless_restore_with_trace")
                && (!restoreDt || !(args.get("allow_passwordless_restore_with_trace") instanceof Boolean)))
            throw new IllegalArgumentException("Passwordless restore with trace applies only to restore_dt");
        if (args.containsKey("backup_before_restore")
                && (!restoreDt || !(args.get("backup_before_restore") instanceof Boolean)))
            throw new IllegalArgumentException("backup_before_restore is a boolean for restore_dt only");
        boolean backup = Boolean.TRUE.equals(args.get("backup_before_restore"));
        var accessFile=postRestoreAccess(args);
        if (!accessFile.isEmpty()) {
            if (!restoreDt) throw new IllegalArgumentException("Task access file applies only after DT import");
            var path=InfobasePaths.absolute((String)accessFile.get("task_access_path"));
            if (Files.size(path)>256*1024) throw new IllegalArgumentException("Task source exceeds the size limit");
            if (!sha(path).equals(accessFile.get("expected_task_access_sha256")))
                throw new IllegalStateException("Post-restore access file hash changed");
            ServerInfobaseProvisionService.readTaskAccessFile(accessFile);
        }
        String restoredBaseChoice = restoredBaseChoice(args, restoreDt);
        String project = RuntimeExecutionPlan.required(args,"project");
        for (var key : restoreDt ? List.of("project","runtime_version","server_address","server_infobase","dt_path") : List.<String>of()) {
            restore.put(key, RuntimeExecutionPlan.required(args,key));
        }
        String uuid = RuntimeExecutionPlan.required(args,"base_uuid");
        if (!UUID.fromString(uuid).toString().equals(uuid)) throw new IllegalArgumentException("Canonical UUID required");
        restore.putAll(Map.of("kind","server","server_operation","restore","restore_existing",true,
                "set_primary",true,"replace_primary",false,"update_from_project",true));
        if (args.containsKey("start_testclient") && !(args.get("start_testclient") instanceof Boolean))
            throw new IllegalArgumentException("start_testclient must be boolean");
        boolean testClient = Boolean.TRUE.equals(args.get("start_testclient"));
        if (args.containsKey("accept_legal_updates") && !(args.get("accept_legal_updates") instanceof Boolean))
            throw new IllegalArgumentException("accept_legal_updates must be boolean");
        if (Boolean.TRUE.equals(args.get("accept_legal_updates")) && !testClient)
            throw new IllegalArgumentException("License confirmation applies only to an approved TestClient");
        var registration = new RegisteredInfobaseGateway();
        var binding = registration.describeScope(project,uuid);
        if (registration.describe(project,uuid).get("owners") instanceof List<?> owners
                && owners.stream().anyMatch(owner->!project.equals(owner)))
            throw new IllegalStateException("Scenario base belongs to another project");
        var plan = restoreDt ? new ServerInfobaseProvisionService().preview(restore) : Map.<String,Object>of();
        if (restoreDt && !uuid.equals(plan.get("base_uuid"))) throw new IllegalStateException("Registered server UUID differs from the requested base");
        if (!uuid.equals(binding.get("primary_uuid"))) throw new IllegalStateException("Scenario requires the exact existing primary application");
        String digest = restoreDt ? sha(InfobasePaths.absolute((String)restore.get("dt_path"))) : "";
        var scope = testClient ? new DirectTestClientService().consentScope(Map.of("project",project,"client_kind","thin")) : Map.of();
        if (testClient && !uuid.equals(scope.get("base_uuid"))) throw new IllegalStateException("TestClient target changed");
        var result = new LinkedHashMap<String,Object>();
        result.put("request", new LinkedHashMap<>(args));
        result.put("update_mode",mode);
        if (restoreDt) {
            result.put("restored_base_choice",restoredBaseChoice);
            result.put("backup_before_restore",backup);
        }
        result.put("approved_target_identity_sha256",binding.get("target_identity_sha256"));
        if (restoreDt) {
            result.put("restore_args",restore);
            result.put("restore_preview_sha256",plan.get("preview_sha256"));
            var stableRestore = new LinkedHashMap<>(plan);
            stableRestore.remove("preview_sha256");
            result.put("restore_operation_sha256",TaskSessionService.fingerprint(stableRestore));
        }
        result.put("binding_sha256",TaskSessionService.fingerprint(binding));
        result.put("dt_sha256",digest);
        result.put("testclient_scope_sha256",TaskSessionService.fingerprint(scope));
        var trace=gateway.platformCommandTrace();
        result.put("trace",trace.name());
        result.put("update_trace_credential_warning",true);
        result.put("trace_risk","EDT platform tracing can write infobase credentials to the local trace in plain text.");
        var steps=new ArrayList<String>();
        if (restoreDt) {
            if (backup) steps.add("backup");
            steps.add("restore_dt");
            steps.add("verify_post_restore_access");
        }
        steps.add((restoreDt ? "full" : mode)+"_to_equal");
        if (testClient) steps.add("start_testclient");
        if (testClient && restoreDt && !"manual".equals(restoredBaseChoice)) steps.add("confirm_restored_base_if_present");
        if (Boolean.TRUE.equals(args.get("accept_legal_updates"))) steps.add("confirm_legal_updates_if_present");
        result.put("steps",steps);
        result.put("operation_sha256",operationFingerprint(result));
        result.put("plan_sha256",TaskSessionService.fingerprint(result));
        return result;
    }

    static String operationFingerprint(Map<String,Object> plan) {
        var operation = new LinkedHashMap<>(plan);
        for (var key : List.of("trace","trace_passwordless_restore","post_restore_trace_credential_warning","update_trace_credential_warning","trace_risk","steps","plan_sha256","operation_sha256"))
            operation.remove(key);
        if (operation.containsKey("restore_operation_sha256"))
            operation.remove("restore_preview_sha256");
        return TaskSessionService.fingerprint(operation);
    }

    private static Map<String,Object> postRestoreAccess(Map<String,Object> args) {
        if (!args.containsKey("task_access_path") && !args.containsKey("expected_task_access_sha256"))
            return Map.of();
        return Map.of("task_access_path",RuntimeExecutionPlan.required(args,"task_access_path"),
                "expected_task_access_sha256",RuntimeExecutionPlan.required(args,"expected_task_access_sha256"));
    }

    static String restoredBaseChoice(Map<String,Object> args, boolean restoreDt) {
        if (!args.containsKey("restored_base_choice")) return restoreDt ? "copy" : "";
        if (!restoreDt) throw new IllegalArgumentException("restored_base_choice applies only to restore_dt");
        if (!(args.get("restored_base_choice") instanceof String choice)
                || !Set.of("copy","moved","manual").contains(choice)) {
            throw new IllegalArgumentException("Invalid restored_base_choice");
        }
        return choice;
    }

    public Map<String,Object> approve(Map<String,Object> args,String expected,String previousRunId,
            Consumer<Map<String,Object>> progress) throws Exception {
        var plan = preview(args);
        checkPlanHash(plan,expected);
        synchronized (LOCK) {
            Map<String,Object> previous = null;
            if (previousRunId != null) {
                previous = read(previousRunId);
                checkRevision(previous,plan);
                if (Objects.equals(previous.get("plan_sha256"),plan.get("plan_sha256"))
                        && Boolean.TRUE.equals(previous.get("update_trace_credential_warning"))) return status(previousRunId);
            }
            return saveApproval(plan,previousRunId,previous,"full_cycle");
        }
    }

    static boolean withinFullCycle(Map<String,Object> previous,Map<String,Object> plan) {
        var original=object(previous.get("request"));
        var revised=object(plan.get("request"));
        return (!Boolean.TRUE.equals(plan.get("post_restore_trace_credential_warning"))
                    || Boolean.TRUE.equals(previous.get("post_restore_trace_credential_warning")))
                && (!Boolean.TRUE.equals(plan.get("update_trace_credential_warning"))
                    || Boolean.TRUE.equals(previous.get("update_trace_credential_warning")))
                && (!Boolean.TRUE.equals(revised.get("accept_legal_updates"))
                    || Boolean.TRUE.equals(original.get("accept_legal_updates")))
                && (!"moved".equals(plan.get("restored_base_choice"))
                    || "moved".equals(previous.get("restored_base_choice")))
                && (!Boolean.TRUE.equals(previous.get("backup_before_restore"))
                    || Boolean.TRUE.equals(plan.get("backup_before_restore")))
                && (Objects.toString(previous.get("dt_sha256"),"").isEmpty()
                    || Objects.equals(previous.get("dt_sha256"),plan.get("dt_sha256"))
                        && Objects.equals(original.get("dt_path"),revised.get("dt_path")));
    }

    private static void checkRevision(Map<String,Object> previous,Map<String,Object> plan) {
        requireUnexpired(previous);
        String state=(String)previous.get("state");
        if (!Set.of("approved","restored_access_pending").contains(state))
            throw new IllegalStateException("The task has no pending stage that can be revised");
        if (!Objects.equals(previous.get("binding_sha256"),plan.get("binding_sha256")))
            throw new IllegalStateException("Changed EDT target requires a new task preview");
        if ("restored_access_pending".equals(state)) {
            var original=object(previous.get("request"));
            var revised=object(plan.get("request"));
            for (var key : List.of("update_mode","runtime_version","server_address","server_infobase","dt_sha256","backup_before_restore")) {
                Object before=previous.containsKey(key)?previous.get(key):original.get(key);
                Object after=plan.containsKey(key)?plan.get(key):revised.get(key);
                if (!Objects.equals(before,after))
                    throw new IllegalStateException("Cannot change a completed import stage");
            }
        }
    }

    private Map<String,Object> saveApproval(Map<String,Object> plan,String previousRunId,
            Map<String,Object> previous,String policy) throws Exception {
        var receipt = new LinkedHashMap<String,Object>(plan);
        String id = previousRunId==null ? UUID.randomUUID().toString() : previousRunId;
        receipt.put("run_id",id);
        boolean resumed=previous!=null && "restored_access_pending".equals(previous.get("state"));
        receipt.put("state",resumed ? "restored_access_pending" : "approved");
        if(resumed) {
            receipt.put("restore_journal",previous.get("restore_journal"));
            receipt.put("job_id",previous.get("job_id"));
        }
        receipt.put("risk_policy",policy);
        receipt.put("durable_full_dispatch",true);
        receipt.put("expires_at_ms",previous==null ? System.currentTimeMillis()+TTL_MS : previous.get("expires_at_ms"));
        write(receipt);
        return status(id);
    }

    public Map<String,Object> status(String id) throws Exception {
        synchronized (LOCK) {
            var r = read(id);
            var out = new LinkedHashMap<String,Object>();
            for (String key : List.of("run_id","state","expires_at_ms","steps","job_id","client_id","restore_journal","reason","update_mode","restored_base_choice","restored_base_operation_id","license_stage","license_operation_id","risk_policy","backup_before_restore","testclient_preflight","access_reconnect","full_update_failure")) {
                if (r.containsKey(key)) out.put(key,r.get(key));
            }
            var request=object(r.get("request"));
            for (String key : List.of("project","base_uuid","dt_path","accept_legal_updates"))
                if(request.containsKey(key))out.put(key,request.get(key));
            boolean confirmed=hasImportMarker(r);
            if (confirmed) {
                boolean synchronizedBase=Set.of("restored","starting_testclient","testclient_started",
                        "testclient_requires_inspection").contains(r.get("state"));
                out.put("import_completed",true);
                out.put("full_reload_required",!synchronizedBase);
                out.put("repeat_restore",false);
                if (!synchronizedBase) out.put("operator_action","restored_access_pending".equals(r.get("state"))
                        ?"verify_restored_access_then_full_without_reimport"
                        :"inspect_original_import_claim_before_continuing");
            }
            if (canContinueImport(r) && !expired(r)
                    && postRestoreTraceAllowed(gateway.platformCommandTrace(),r)) {
                out.put("next_call",Map.of("tool","edt.edit","arguments",Map.of("op","edt_infobase_provision",
                        "args",Map.of("action","scenario_continue_after_restore","run_id",id))));
            }
            if ("approved".equals(r.get("state")) || "restored".equals(r.get("state"))
                    || "restored_access_pending".equals(r.get("state"))) {
                if (expired(r)) out.put("state","expired");
            }
            return out;
        }
    }

    public Map<String,Object> run(String id,String caller,Consumer<Map<String,Object>> progress) throws Exception {
        Map<String,Object> receipt;
        Map<String,Object> apply;
        boolean traceCredentialsApproved;
        synchronized (LOCK) {
            receipt = read(id);
            if (!"approved".equals(receipt.get("state"))) return status(id);
            requireUnexpired(receipt);
        }
        var live = preview(object(receipt.get("request")));
        synchronized (LOCK) {
            var current = read(id);
            if (!receipt.equals(current)) return status(id);
            requireUnexpired(current);
            if (legacyRestore(receipt)) {
                return Map.of("run_id",id,"state","approved","execution_state","awaiting_edt_reapproval",
                        "reason","legacy_restore_approval","import_completed",false,
                        "operator_action","preview_original_request_and_approve_same_run_in_edt");
            }
            if (receipt.get("operation_sha256") instanceof String approved) {
                if (!approved.equals(live.get("operation_sha256")))
                    throw new IllegalStateException("Approved task operation changed");
            } else {
                var previousFormat = new LinkedHashMap<>(live);
                previousFormat.remove("operation_sha256");
                previousFormat.remove("restore_operation_sha256");
                previousFormat.remove("update_trace_credential_warning");
                previousFormat.remove("trace_risk");
                previousFormat.remove("plan_sha256");
                if (!Objects.equals(TaskSessionService.fingerprint(previousFormat),receipt.get("plan_sha256")))
                    throw new IllegalStateException("DT scenario plan changed");
                receipt.put("operation_sha256",live.get("operation_sha256"));
                write(receipt);
            }
            traceCredentialsApproved=Boolean.TRUE.equals(receipt.get("update_trace_credential_warning"));
            var trace = NativeInfobaseGateway.PlatformTrace.valueOf((String)live.get("trace"));
            if (!updateTraceAllowed(trace,receipt)) return requireTraceReapproval(id,receipt);
            apply = new LinkedHashMap<>();
            if ("restore_dt".equals(live.get("update_mode"))) {
                apply.putAll(object(live.get("restore_args")));
                apply.put("dry_run",false);
                apply.put("scenario_dt_sha256",live.get("dt_sha256"));
                apply.put("scenario_binding_sha256",live.get("binding_sha256"));
                apply.put("expected_preview_sha256",live.get("restore_preview_sha256"));
            }
            dispatch(receipt,"restoring");
        }
        try {
            var request=object(receipt.get("request"));
            String project=(String)request.get("project"), uuid=(String)request.get("base_uuid");
            String target=(String)receipt.get("approved_target_identity_sha256");
            var monitor=new org.eclipse.core.runtime.NullProgressMonitor();
            com.codepilot1c.core.edt.runtime.EdtRuntimeService.BeforeNativeUpdate checkBinding=()->{
                if (!updateTraceAllowed(gateway.platformCommandTrace(),receipt))
                    throw new IllegalStateException("EDT platform trace changed before the approved update");
                var binding=new RegisteredInfobaseGateway().describeScope(project,uuid);
                if(!Objects.equals(receipt.get("binding_sha256"),TaskSessionService.fingerprint(binding)))
                    throw new IllegalStateException("Approved project binding changed");
            };
            Map<String,Object> result=switch((String)receipt.get("update_mode")) {
                case "restore_dt" -> new ServerInfobaseProvisionService(caller).runApprovedScenario(apply,progress,
                        false,traceCredentialsApproved,
                        !Boolean.FALSE.equals(receipt.get("backup_before_restore")),true,journal->{
                            synchronized(LOCK){
                                receipt.put("restore_journal",journal.toString());
                                try{write(receipt);}catch(Exception failure){
                                    throw new IllegalStateException("Cannot persist restore journal before import",failure);
                                }
                            }
                        });
                case "incremental" -> gateway.updateIncremental(project,monitor,uuid,target,checkBinding);
                case "full" -> gateway.updateApprovedFull(project,uuid,target,monitor,checkBinding);
                default -> throw new IllegalStateException("Unknown approved update mode");
            };
            boolean equal = Boolean.TRUE.equals(result.get("configuration_synchronized"));
            synchronized (LOCK) {
                receipt.put("state",equal?"restored":Boolean.TRUE.equals(result.get("import_completed"))
                        && "restored_access_pending".equals(result.get("state"))
                            ?"restored_access_pending":"restore_requires_inspection");
                if (result.containsKey("restore_journal")) receipt.put("restore_journal",result.get("restore_journal"));
                write(receipt);
            }
        } catch (Exception | LinkageError failure) {
            synchronized (LOCK) {
                receipt.put("state","restore_requires_inspection");
                receipt.put("reason","inspect_original_job_and_restore_journal");
                if (failure instanceof FullUpdateFailure full) receipt.put("full_update_failure",full.diagnostic());
                write(receipt);
            }
            if (failure instanceof FullUpdateFailure full) throw full;
            if (Boolean.TRUE.equals(receipt.get("update_trace_credential_warning")))
                throw new IllegalStateException("EDT did not confirm completion. Inspect the original job in EDT.");
            throw failure;
        }
        return status(id);
    }

    public Map<String,Object> continueAfterRestore(String id) throws Exception {
        Map<String,Object> receipt;
        Map<String,Object> request;
        synchronized(LOCK){
            receipt=read(id);
            if(!canContinueImport(receipt))return status(id);
            requireUnexpired(receipt);
            request=object(receipt.get("request"));
            verifyContinuationBinding(request,receipt);
            requireCurrentImport(request,receipt);
            if (!postRestoreTraceAllowed(gateway.platformCommandTrace(),receipt)) {
                // Preserve the confirmed import when a previous job stopped before recording this stage.
                if (!"restored_access_pending".equals(receipt.get("state"))) {
                    receipt.put("state","restored_access_pending");
                    write(receipt);
                }
                return requireTraceReapproval(id,receipt);
            }
            receipt.remove("reason");
            receipt.remove("access_reconnect");
            receipt.remove("full_update_failure");
            dispatch(receipt,"post_restore_full_running");
        }
        var fullMayHaveStarted=new java.util.concurrent.atomic.AtomicBoolean();
        try{
            String project=(String)request.get("project"),uuid=(String)request.get("base_uuid");
            var full=gateway.updateApprovedFull(project,uuid,(String)receipt.get("approved_target_identity_sha256"),
                    new org.eclipse.core.runtime.NullProgressMonitor(),()->{
                        verifyContinuationBinding(request,receipt);
                        requireCurrentImport(request,receipt);
                        requirePostRestoreTrace(receipt);
                        var target=new ServerInfobaseTarget((String)request.get("server_address"),(String)request.get("server_infobase"));
                        var ref=gateway.serverReference(target);
                        if(!uuid.equals(ref.getUuid().toString()))
                            throw new IllegalStateException("Restored infobase registration changed");
                        var monitor=new org.eclipse.core.runtime.NullProgressMonitor();
                        var accessFile=postRestoreAccess(request);
                        var credentials=accessFile.isEmpty() ? null : ServerInfobaseProvisionService.readTaskAccessFile(accessFile);
                        var access=credentials==null ? gateway.existingServerArguments(ref,null,null,monitor,null)
                                : gateway.arguments(credentials.user(),credentials.password(),monitor,null);
                        // EDT can reconnect its own Designer on startup, even after a confirmed import.
                        boolean disconnected=gateway.disconnectForAccessVerification(project,ref);
                        try {
                            verifyContinuationBinding(request,receipt);
                            var staging=Files.createTempDirectory(gateway.stateRoot(),"post-restore-access-");
                            requirePostRestoreTrace(receipt);
                            gateway.export(gateway.runtime(ref,(String)request.get("runtime_version")),
                                    ref,staging.resolve("configuration"),access);
                            if(credentials!=null && (!Objects.equals(credentials.user(),access.getUsername())
                                    || !Objects.equals(credentials.password(),Objects.toString(access.getPassword(),""))))
                                throw new IllegalStateException("EDT substituted the approved post-restore credentials");
                            verifyContinuationBinding(request,receipt);
                            requirePostRestoreTrace(receipt);
                            if(credentials!=null) gateway.restoredAccess(ref,credentials.user(),credentials.password());
                            var savedNow=gateway.existingServerArguments(ref,null,null,monitor,null);
                            if(access.getAccess()!=savedNow.getAccess()
                                    || !Objects.equals(access.getUsername(),savedNow.getUsername())
                                    || !Objects.equals(access.getPassword(),savedNow.getPassword()))
                                throw new IllegalStateException("Stored EDT access changed after verification");
                            verifyContinuationBinding(request,receipt);
                            requirePostRestoreTrace(receipt);
                            gateway.requireFull(ref);
                            new ServerInfobaseProvisionService(gateway).clearConfirmedImportClaim(target,
                                    Path.of(RuntimeExecutionPlan.required(receipt,"restore_journal")));
                        } catch (Exception | LinkageError failure) {
                            if (disconnected) {
                                String reconnect="unconfirmed";
                                try {
                                    verifyContinuationBinding(request,receipt);
                                    requirePostRestoreTrace(receipt);
                                    boolean approved=Boolean.TRUE.equals(receipt.get("update_trace_credential_warning"))
                                            || Boolean.TRUE.equals(receipt.get("post_restore_trace_credential_warning"));
                                    if (gateway.reconnectAfterFailedAccessVerification(project,ref,approved))
                                        reconnect="restored";
                                } catch (Exception | LinkageError reconnectFailure) {
                                    failure.addSuppressed(reconnectFailure);
                                }
                                receipt.put("access_reconnect",reconnect);
                            }
                            throw failure;
                        }
                    },()->{
                        verifyContinuationBinding(request,receipt);
                        requireCurrentImport(request,receipt);
                        requirePostRestoreTrace(receipt);
                        synchronized(LOCK) {dispatch(receipt,"post_restore_full_dispatched");}
                        fullMayHaveStarted.set(true);
                    });
            synchronized(LOCK){
                receipt.put("state",Boolean.TRUE.equals(full.get("configuration_synchronized"))
                        ?"restored":"post_restore_full_requires_inspection");
                write(receipt);
            }
        }catch(Exception | LinkageError failure){
            synchronized(LOCK){
                receipt.put("state",fullMayHaveStarted.get()
                        ? "post_restore_full_requires_inspection" : "restored_access_pending");
                receipt.put("reason",fullMayHaveStarted.get()
                        ? "inspect_original_full_job_without_reimport" : "verify_restored_access_before_full");
                if (failure instanceof FullUpdateFailure full) receipt.put("full_update_failure",full.diagnostic());
                write(receipt);
            }
            if (failure instanceof FullUpdateFailure full) throw full;
            if (Boolean.TRUE.equals(receipt.get("post_restore_trace_credential_warning"))
                    || Boolean.TRUE.equals(receipt.get("update_trace_credential_warning")))
                throw new IllegalStateException(fullMayHaveStarted.get()
                        ? "First FULL did not confirm synchronization. Inspect the original job in EDT."
                        : "Post-restore access verification failed or FULL could not start. Inspect the original job in EDT.");
            throw failure;
        }
        return status(id);
    }

    static boolean updateTraceAllowed(NativeInfobaseGateway.PlatformTrace trace,Map<String,Object> receipt) {
        return trace==NativeInfobaseGateway.PlatformTrace.INACTIVE
                || Boolean.TRUE.equals(receipt.get("update_trace_credential_warning"));
    }

    static boolean postRestoreTraceAllowed(NativeInfobaseGateway.PlatformTrace trace,Map<String,Object> receipt) {
        return updateTraceAllowed(trace,receipt)
                || (trace==NativeInfobaseGateway.PlatformTrace.ACTIVE
                    && Boolean.TRUE.equals(receipt.get("post_restore_trace_credential_warning")));
    }

    private void requirePostRestoreTrace(Map<String,Object> receipt) {
        if (!postRestoreTraceAllowed(gateway.platformCommandTrace(),receipt))
            throw new IllegalStateException("EDT command tracing state is unverified or its credential risk was not approved");
    }

    private static boolean hasImportMarker(Map<String,Object> receipt) {
        return "restore_dt".equals(receipt.get("update_mode"))
                && receipt.get("restore_journal") instanceof String path
                && Files.isRegularFile(Path.of(path).resolveSibling("import-confirmed"),LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean canContinueImport(Map<String,Object> receipt) {
        if (!hasImportMarker(receipt)) return false;
        String state=(String)receipt.get("state");
        if ("restored_access_pending".equals(state)) return true;
        if (!Boolean.TRUE.equals(receipt.get("durable_full_dispatch"))
                || !Set.of("restoring","restore_requires_inspection","post_restore_full_running").contains(state))
            return false;
        if (!(receipt.get("job_id") instanceof String job)) return false;
        return BackgroundJobRegistry.getInstance().getStatus(job).map(info -> switch(info.getState()) {
            case DONE, FAILED -> true;
            case PENDING, RUNNING -> false;
        }).orElse(true);
    }

    private void requireCurrentImport(Map<String,Object> request,Map<String,Object> receipt)throws Exception {
        var target=new ServerInfobaseTarget((String)request.get("server_address"),(String)request.get("server_infobase"));
        new ServerInfobaseProvisionService(gateway).requireCurrentConfirmedImport(target,
                Path.of(RuntimeExecutionPlan.required(receipt,"restore_journal")));
    }

    private static void verifyContinuationBinding(Map<String,Object> request,Map<String,Object> receipt)throws Exception {
        var binding=new RegisteredInfobaseGateway().describeScope((String)request.get("project"),(String)request.get("base_uuid"));
        if(!Objects.equals(receipt.get("binding_sha256"),TaskSessionService.fingerprint(binding)))
            throw new IllegalStateException("Approved project binding changed after import");
    }

    public Map<String,Object> startClient(String id) throws Exception {
        Map<String,Object> receipt;
        Map<String,Object> args;
        synchronized (LOCK) {
            receipt = read(id);
            if (!"restored".equals(receipt.get("state"))) return status(id);
            requireUnexpired(receipt);
            var request = object(receipt.get("request"));
            if (!Boolean.TRUE.equals(request.get("start_testclient"))) throw new IllegalStateException("TestClient was not approved");
            String project = (String)request.get("project"), uuid = (String)request.get("base_uuid");
            var binding = new RegisteredInfobaseGateway().describeScope(project,uuid);
            if (!Objects.equals(receipt.get("binding_sha256"),TaskSessionService.fingerprint(binding)))
                throw new IllegalStateException("Approved project binding changed");
            args = Map.of("project",project,"base_uuid",uuid,"client_kind","thin");
            receipt.remove("testclient_preflight");
            dispatch(receipt,"starting_testclient");
        }
        try {
            var result = new DirectTestClientService().startApprovedDtScenario(args,id,
                    (String)receipt.get("testclient_scope_sha256"), client -> {
                        synchronized (LOCK) {
                            receipt.put("client_id",client);
                            try {write(receipt);} catch (Exception error) {throw new IllegalStateException("Cannot record reserved TestClient",error);}
                        }
                    });
            synchronized (LOCK) {
                receipt.put("state","testclient_started");
                write(receipt);
            }
            var answer = new LinkedHashMap<>(status(id));
            answer.put("client",result);
            if (Set.of("copy","moved").contains(Objects.toString(receipt.get("restored_base_choice"),"manual"))) {
                answer.put("next_call", Map.of("tool","edt.edit","arguments",Map.of(
                        "op","edt_infobase_provision","args",Map.of(
                                "action","scenario_confirm_restored_base","run_id",id))));
            }
            return answer;
        } catch (DirectTestClientService.PreflightBlocked blocked) {
            synchronized (LOCK) {
                if (receipt.containsKey("client_id")) throw blocked;
                receipt.put("state","restored");
                receipt.put("testclient_preflight",blocked.result());
                write(receipt);
            }
            var answer = new LinkedHashMap<>(status(id));
            answer.put("client_started",false);
            if (blocked.result().containsKey("next_call")) answer.put("next_call",blocked.result().get("next_call"));
            return answer;
        } catch (Exception failure) {
            synchronized (LOCK) {
                receipt.put("state","testclient_requires_inspection");
                write(receipt);
            }
            throw failure;
        }
    }

    private static final String RESTORED_BASE_FORM = "ОбщаяФорма.БлокировкаРаботыСВнешнимиРесурсами";

    public Map<String,Object> confirmRestoredBase(String id) throws Exception {
        RuntimeConsentPolicy.assertToolAllowed("edt_test_client");
        RuntimeConsentPolicy.assertToolAllowed("edt_form_action");
        synchronized (LOCK) {
            var receipt = read(id);
            requireUnexpired(receipt);
            if (!"testclient_started".equals(receipt.get("state"))
                    || !"restore_dt".equals(receipt.get("update_mode"))) {
                throw new IllegalStateException("A started restore scenario TestClient is required");
            }
            String choice = Objects.toString(receipt.get("restored_base_choice"), "manual");
            String element = switch (choice) {
                case "copy" -> "ЭтоКопияИнформационнойБазы";
                case "moved" -> "ИнформационнаяБазаПеремещена";
                default -> "";
            };
            if (element.isEmpty()) return Map.of("run_id",id,"state","manual_required");
            String client = (String)receipt.get("client_id");
            var service = new DirectTestClientService();
            var live = service.status(client);
            if (!Boolean.TRUE.equals(live.get("live_client_binding_verified"))
                    || !Objects.equals(live.get("base_uuid"),object(receipt.get("request")).get("base_uuid"))) {
                throw new IllegalStateException("Original scenario TestClient binding is not verified");
            }
            if (receipt.get("restored_base_operation_id") instanceof String operationId) {
                return service.result(client,operationId);
            }
            String key="dt-scenario-"+id+"-restored-base";
            var original=service.scenarioResultByKey(client,id,key);
            if(original.isPresent())return original.orElseThrow();
            var report = service.observe(Map.of("client_id",client,"view","elements"));
            var window = object(report.get("window"));
            if (!Boolean.TRUE.equals(report.get("success")) || window == null
                    || !RESTORED_BASE_FORM.equals(window.get("form_name"))) {
                return Map.of("run_id",id,"state","no_matching_restored_base_form","client_id",client);
            }
            var submitted = service.actApprovedDtScenario(id,Map.of("client_id",client,"action","command","element",element,
                    "expected_window",window.get("title"),"expected_form",RESTORED_BASE_FORM,
                    "snapshot_id",report.get("snapshot_id"),"idempotency_key",key));
            receipt.put("restored_base_operation_id",submitted.get("operation_id"));
            write(receipt);
            return submitted;
        }
    }

    private static final String LICENSE_FORM =
            "Обработка.ЛегальностьПолученияОбновлений.Форма.ПроверкаЛегальностиПолученияОбновления";

    public Map<String,Object> confirmUpdates(String id) throws Exception {
        RuntimeConsentPolicy.assertToolAllowed("edt_test_client");
        RuntimeConsentPolicy.assertToolAllowed("edt_form_action");
        synchronized (LOCK) {
            var receipt=read(id);
            requireUnexpired(receipt);
            if (!"testclient_started".equals(receipt.get("state"))
                    || !Boolean.TRUE.equals(object(receipt.get("request")).get("accept_legal_updates"))) {
                throw new IllegalStateException("License confirmation was not included in this scenario");
            }
            String client=(String)receipt.get("client_id");
            var service=new DirectTestClientService();
            var before=service.status(client);
            if (!Boolean.TRUE.equals(before.get("live_client_binding_verified"))
                    || !Objects.equals(before.get("base_uuid"),object(receipt.get("request")).get("base_uuid"))) {
                throw new IllegalStateException("Original scenario TestClient binding is not verified");
            }
            String stage=Objects.toString(receipt.get("license_stage"),"privacy");
            if (receipt.get("license_operation_id") instanceof String operationId) {
                var result=service.result(client,operationId);
                if (!verifiedLicenseStep(stage,result)) {
                    return Map.of("run_id",id,"state","inspect_original_license_operation","operation_id",operationId,"client_id",client);
                }
                if ("continue".equals(stage)) return Map.of("run_id",id,"state","accepted","client_id",client);
                stage="privacy".equals(stage)?"check":"continue";
            }
            String key="dt-scenario-"+id+"-"+stage;
            var original=service.scenarioResultByKey(client,id,key);
            if(original.isPresent()){
                var result=original.orElseThrow();
                receipt.put("license_stage",stage);
                receipt.put("license_operation_id",result.get("operation_id"));
                write(receipt);
                return result;
            }
            var report=service.observe(Map.of("client_id",client,"view","elements"));
            var window=object(report.get("window"));
            if (!Boolean.TRUE.equals(report.get("success")) || window==null || !LICENSE_FORM.equals(window.get("form_name"))) {
                return Map.of("run_id",id,"state","no_matching_license_form","client_id",client);
            }
            String action=switch(stage){case "privacy"->"uncheck";case "check"->"check";case "continue"->"command";default->throw new IllegalStateException("Invalid license stage");};
            String element=switch(stage){case "privacy"->"РазрешитьОтправкуСтатистики";case "check"->"ПодтверждениеБулево";default->"ФормаПродолжить";};
            var submitted=service.actApprovedDtScenario(id,Map.of("client_id",client,"action",action,"element",element,
                    "expected_window",window.get("title"),"expected_form",LICENSE_FORM,
                    "snapshot_id",report.get("snapshot_id"),"idempotency_key",key));
            receipt.put("license_stage",stage);
            receipt.put("license_operation_id",submitted.get("operation_id"));
            write(receipt);
            var result=new LinkedHashMap<>(submitted);
            result.put("run_id",id);
            result.put("license_stage",stage);
            return result;
        }
    }
    static boolean verifiedLicenseStep(String stage,Map<String,Object> result) {
        if(!Boolean.TRUE.equals(result.get("success"))||Boolean.TRUE.equals(result.get("outcome_unknown")))return false;
        if("continue".equals(stage)) {
            var window=object(result.get("window"));
            return window!=null && window.get("form_name") instanceof String name
                    && !name.isBlank() && !LICENSE_FORM.equals(name);
        }
        var control=object(result.get("control"));
        return control!=null && Boolean.TRUE.equals(control.get("state_verified"))
                && Boolean.valueOf("check".equals(stage)).equals(control.get("value_after"));
    }
    private void dispatch(Map<String,Object> receipt,String state) throws Exception {
        String job = BackgroundJobRegistry.currentJobId();
        if (job == null) throw new IllegalStateException("Scenario requires a tracked background job");
        receipt.put("job_id",job);
        receipt.put("state",state);
        write(receipt);
    }

    private static boolean legacyRestore(Map<String,Object> receipt) {
        return "restore_dt".equals(receipt.get("update_mode")) && !receipt.containsKey("restore_operation_sha256");
    }

    private static Map<String,Object> requireTraceReapproval(String id,Map<String,Object> receipt) {
        var previewArgs=new LinkedHashMap<>(object(receipt.get("request")));
        previewArgs.put("action","scenario_preview");
        return Map.of("run_id",id,"state",receipt.get("state"),"execution_state","awaiting_edt_reapproval",
                "reason","edt_trace_credential_confirmation_required","import_completed",hasImportMarker(receipt),
                "repeat_restore",false,"operator_action","register_authorized_plan_on_same_run_after_preview",
                "next_call",Map.of("tool","edt.edit","arguments",Map.of(
                        "op","edt_infobase_provision","args",previewArgs)));
    }

    private static void checkPlanHash(Map<String,Object> plan,String expected) {
        if (!Objects.equals(plan.get("plan_sha256"),expected)) throw new IllegalStateException("DT scenario plan changed");
    }
    private static boolean expired(Map<String,Object> r) {
        return System.currentTimeMillis() >= ((Number)r.get("expires_at_ms")).longValue();
    }
    private static void requireUnexpired(Map<String,Object> r) {
        if (expired(r)) throw new IllegalStateException("DT scenario approval expired");
    }
    private Path file(String id) throws Exception {
        if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid run_id");
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root) || !root.toRealPath().equals(root.toAbsolutePath().normalize()))
            throw new IllegalStateException("Scenario journal path changed");
        Path path = root.resolve(id+".json");
        if (Files.isSymbolicLink(path)) throw new IllegalStateException("Linked scenario receipt");
        return path;
    }
    private Map<String,Object> read(String id) throws Exception {
        Path path = file(id);
        if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>65536)
            throw new IllegalStateException("Scenario receipt unavailable");
        Map<String,Object> r = JSON.fromJson(Files.readString(path),new TypeToken<LinkedHashMap<String,Object>>(){}.getType());
        if (!id.equals(r.get("run_id"))) throw new IllegalStateException("Scenario identity changed");
        return r;
    }
    private void write(Map<String,Object> r) throws Exception {
        Path path = file((String)r.get("run_id"));
        Path temp = Files.createTempFile(root,"dt-scenario-",".tmp");
        try {
            Files.writeString(temp,JSON.toJson(r));
            Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) {return (Map<String,Object>)value;}
    static String sha(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65536];
            for (int n; (n=in.read(buffer))!=-1;) digest.update(buffer,0,n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
