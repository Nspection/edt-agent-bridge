// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.eclipse.core.runtime.NullProgressMonitor;
import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionArguments;
import com.google.gson.Gson;
/** Native EDT create/restore/bind workflow. New SQL names derive from a one-use UUID. */
public final class ServerInfobaseProvisionService extends InfobaseProvisionService {
    private final NativeInfobaseGateway gateway;
    private final String callerSession;
    public ServerInfobaseProvisionService(){this("");}
    public ServerInfobaseProvisionService(String callerSession){this(new NativeInfobaseGateway(), callerSession);}
    public ServerInfobaseProvisionService(NativeInfobaseGateway gateway){this(gateway, "");}
    private ServerInfobaseProvisionService(NativeInfobaseGateway gateway, String callerSession) {
        super(gateway);
        this.gateway = gateway;
        this.callerSession = callerSession;
    }
    static String operation(Map<String,Object> a){String op=Objects.toString(a.get("server_operation"),"create");if(!Set.of("create","restore","bind").contains(op))throw new IllegalArgumentException("server_operation must be create, restore or bind");return op;}
    static String creationId(Map<String,Object> a){return a.containsKey("creation_id")?UUID.fromString(required(a,"creation_id")).toString():UUID.randomUUID().toString();}
    static ServerInfobaseTarget target(Map<String,Object> a,String id){
        String op=operation(a),name;
        if(op.equals("create")) {
            String prefix=Objects.toString(a.get("name_prefix"),"EdtAgent");
            if(!prefix.matches("[A-Za-z][A-Za-z0-9_]{0,19}"))throw new IllegalArgumentException("name_prefix must be a short ASCII identifier");
            name=prefix+"_"+id.replace("-","");
            if(a.containsKey("server_infobase") && !name.equals(a.get("server_infobase")))throw new IllegalArgumentException("create uses the unique name returned by preview; preserve creation_id");
        } else name=required(a,"server_infobase");
        return new ServerInfobaseTarget(required(a,"server_address"),name);
    }
    @Override public Map<String,Object> preview(Map<String,Object> a) throws Exception {
        if(flag(a,"disposable_test_base",false))throw new IllegalArgumentException("Disposable cleanup leases support file bases only");
        validateCredentials(a,"login","password");validateCredentials(a,"existing_login","existing_password");validateCredentials(a,"db_user","db_password");
        if(a.containsKey("database_path"))throw new IllegalArgumentException("Server target cannot include database_path");
        String project=required(a,"project"),version=required(a,"runtime_version"),op=operation(a),id=creationId(a);
        var target=target(a,id);var ref=gateway.serverReference(target);
        if(op.equals("create")) {
            required(a,"db_server");required(a,"db_user");databaseType(a);
            for(String field:List.of("db_server","db_user","db_password")) {
                String value=Objects.toString(a.get(field),"");
                if(value.chars().anyMatch(c->c<32 || c=='\"' || c==';'))throw new IllegalArgumentException("Unsupported delimiter in "+field+" for native EDT create arguments");
            }
            if(Files.exists(claim(id)))throw new IllegalStateException("Creation id already used; inspect the preceding operation before requesting another base");
            if(gateway.isRegistered(ref))throw new IllegalArgumentException("New server target is already registered");
        }
        Path dt=a.containsKey("dt_path")?InfobasePaths.absolute(required(a,"dt_path")):null;
        if(dt!=null && (!Files.isRegularFile(dt) || Files.size(dt)==0 || !dt.toString().toLowerCase(Locale.ROOT).endsWith(".dt")))throw new IllegalArgumentException("Nonempty DT source required");
        if(op.equals("restore") && (!flag(a,"restore_existing",false) || dt==null))throw new IllegalArgumentException("Server restore requires restore_existing=true and dt_path");
        if(!op.equals("restore") && flag(a,"restore_existing",false))throw new IllegalArgumentException("restore_existing is only valid for server_operation=restore");
        if(op.equals("bind") && dt!=null)throw new IllegalArgumentException("bind does not restore DT");
        gateway.project(project);gateway.checkPrimaryServer(project,ref,flag(a,"set_primary",true),flag(a,"replace_primary",false));
        if(gateway.ownerProjects(ref).stream().anyMatch(owner->!owner.equals(project)))throw new IllegalArgumentException("Server application belongs to another project");
        var plan=new LinkedHashMap<String,Object>();plan.put("project",project);plan.put("kind","server");plan.put("server_operation",op);
        plan.put("server_address",target.address());plan.put("server_infobase",target.name());plan.put("connection_string",target.connection());plan.put("runtime_version",version);
        plan.put("previous_primary",gateway.primaryIdentity(project));plan.put("runtime_component",gateway.runtimeDescription(ref,version));
        if (op.equals("restore") && gateway.isRegistered(ref)) plan.put("base_uuid", ref.getUuid().toString());
        if(op.equals("create")){plan.put("creation_id",id);plan.put("database_name","eb_"+id.replace("-",""));plan.put("db_server",a.get("db_server"));plan.put("dbms",Objects.toString(a.get("dbms"),"postgresql"));plan.put("db_user",a.get("db_user"));}
        plan.put("dt_source",dt==null?Map.of():InfobasePaths.fingerprint(dt));
        plan.put("update_from_project",flag(a,"update_from_project",!op.equals("bind")));plan.put("set_primary",flag(a,"set_primary",true));plan.put("replace_primary",flag(a,"replace_primary",false));
        plan.put("credential_hash",InfobasePaths.hash(new Gson().toJson(List.of(Objects.toString(a.get("db_password"),""),Objects.toString(a.get("password"),""),Objects.toString(a.get("existing_password"),""),Objects.toString(a.get("login"),""),Objects.toString(a.get("existing_login"),"")))));
        String hash=InfobasePaths.fingerprintHash(plan);plan.remove("credential_hash");plan.put("preview_sha256",hash);plan.put("dry_run",true);
        plan.put("note","Server preview identifies endpoint, DT and request parameters; it is not a snapshot of live database data. Restore always makes a native backup first. New creation uses unique cluster and SQL names, and a one-use creation_id.");return plan;
    }
    private void verifyRestoreTarget(String op, ServerInfobaseTarget target,
            InfobaseReference ref, Map<String,Object> plan, String project) throws Exception {
        if (!op.equals("restore")) return;
        var current = gateway.serverReference(target);
        String registered = gateway.isRegistered(current) ? current.getUuid().toString() : null;
        if (!Objects.equals(plan.get("base_uuid"), registered)
                || (registered != null && !Objects.equals(ref.getUuid(), current.getUuid()))
                || gateway.ownerProjects(current).stream().anyMatch(owner -> !owner.equals(project))) {
            throw new IllegalStateException("Server restore target registration or ownership changed");
        }
    }

    private void requirePrivateNativeAccess(InfobaseWorkflow flow, String stage, boolean importCompleted) {
        var trace = gateway.platformCommandTrace();
        if (trace == NativeInfobaseGateway.PlatformTrace.INACTIVE) return;
        boolean active = trace == NativeInfobaseGateway.PlatformTrace.ACTIVE;
        String code = active ? "edt_command_tracing_active" : "edt_command_tracing_unverified";
        String message = (active
            ? "EDT command tracing can log 1C credentials. "
            : "Bridge could not verify EDT command tracing settings. ")
            + "Leave EDT settings unchanged and report the block to the operator."
            + (importCompleted ? " The DT import already completed. Do not repeat it." : "");
        flow.finishRestore(code);
        flow.signal(Map.of("stage", "restore_blocked_by_trace", "blocked_before_stage", stage,
            "error_code", code, "message", message, "operator_action", "keep_edt_tracing_unchanged",
            "import_completed", importCompleted, "retry_safe", false));
        throw new IllegalStateException(message);
    }

    @FunctionalInterface
    private interface NativeRead<T> {
        T execute(int attempt) throws Exception;
    }

    private record AccessRead<T>(T value, boolean cancelled, boolean secure, boolean persist) {}

    private <T> AccessRead<T> readWithAccess(InfobaseReference ref, InfobaseWorkflow flow,
            String stage, RuntimeExecutionArguments args, String failed, NativeRead<T> action) {
        boolean secure = false;
        boolean persist = false;
        for (int attempt = 1; attempt <= 3; attempt++) {
            requirePrivateNativeAccess(flow, stage, stage.equals("verify_restored_access"));
            flow.stage(stage);
            try (var prompt = new ServerRestoreAccessPrompt(ref, flow, stage, callerSession)) {
                try {
                    T result = action.execute(attempt);
                    return new AccessRead<>(result, prompt.cancelRequested(),
                        secure && !prompt.nativeAccessConfirmed(), persist);
                } catch (Exception | LinkageError failure) {
                    if (prompt.cancelRequested()) return new AccessRead<>(null, true, secure, persist);
                    var grant = prompt.takeSubmittedGrant();
                    if (grant == null || attempt == 3) throw new IllegalStateException(failed);
                    grant.applyTo(args);
                    secure = true;
                    persist = grant.persist;
                }
            }
        }
        throw new IllegalStateException(failed);
    }

    private static Map<String,Object> cancelled(InfobaseWorkflow flow, Map<String,Object> result,
            boolean importCompleted) {
        result.put("state", "cancelled");
        result.put("status", "cancelled");
        result.put("import_completed", importCompleted);
        result.put("backup_verified", result.containsKey("backup_dt"));
        result.put("success", false);
        flow.finishRestore("cancelled");
        flow.stage("cancelled");
        return result;
    }

    private Path importClaim(ServerInfobaseTarget target) {
        String identity = InfobasePaths.connectionIdentity(target.connection());
        return gateway.stateRoot().resolve("server-restore-" + InfobasePaths.hash(identity) + ".claim");
    }

    private Path claim(String id){return gateway.stateRoot().resolve("server-create-"+id+".claim");}
    public static com._1c.g5.v8.dt.platform.services.model.DatabaseType databaseType(Map<String,Object> a){return switch(Objects.toString(a.get("dbms"),"postgresql")){case "postgresql"->com._1c.g5.v8.dt.platform.services.model.DatabaseType.POSTGRE_SQL;case "mssql"->com._1c.g5.v8.dt.platform.services.model.DatabaseType.MS_SQL_SERVER;default->throw new IllegalArgumentException("dbms must be postgresql or mssql");};}
    @Override public Map<String,Object> run(Map<String,Object> a,Consumer<Map<String,Object>> progress) throws Exception {
        if(operation(a).equals("create") && !a.containsKey("creation_id"))throw new IllegalArgumentException("Apply must preserve creation_id from preview");
        if(flag(a,"disposable_test_base",false))throw new IllegalArgumentException("Disposable cleanup leases currently support new file bases only; server bases require explicit administration");
        String project=required(a,"project"),op=operation(a);var target=target(a,creationId(a));var ref=gateway.serverReference(target);
        try(var flow=new InfobaseWorkflow(InfobaseWorkflow.resourceKeys(project,ref),progress)) {
            boolean restore = op.equals("restore");
            Path operation = gateway.stateRoot().resolve(UUID.randomUUID().toString());
            Path journal = operation.resolve("restore-journal.jsonl");
            if (restore) {
                Files.createDirectories(operation);
                flow.startRestoreJournal(journal);
            }
            flow.stage("preflight");var plan=preview(a);requirePreview(a,plan);
            verifyRestoreTarget(op, target, ref, plan, project);
            if (restore) requirePrivateNativeAccess(flow, "preflight", false);
            if (restore && Files.exists(importClaim(target), LinkOption.NOFOLLOW_LINKS))
                throw new IllegalStateException("A previous import for this server base is unresolved; inspect its original operation");
            if (!restore) Files.createDirectories(operation);
            flow.details(Map.of("operation_directory",operation.toString(),"server_address",target.address(),"server_infobase",target.name()));
            var result=new LinkedHashMap<String,Object>();result.put("project",project);result.put("kind","server");result.put("server_address",target.address());result.put("server_infobase",target.name());result.put("connection_string",target.connection());result.put("operation_directory",operation.toString());
            if (restore) result.put("restore_journal", journal.toString());
            gateway.pinVersion(ref,required(a,"runtime_version"));var info=gateway.runtime(ref,required(a,"runtime_version"));var monitor=new NullProgressMonitor();
            RuntimeExecutionArguments existingAccess = null;
            if(op.equals("create")) {
                String id=required(a,"creation_id");Files.createDirectories(claim(id).getParent());Files.createFile(claim(id));
                result.put("creation_id",id);result.put("database_name",plan.get("database_name"));Files.writeString(operation.resolve("server-operation.json"),new Gson().toJson(result));
                flow.stage("create_server_infobase");gateway.createServer(info,ref,a,(String)plan.get("database_name"));gateway.requireFull(ref);
            } else if(op.equals("restore")) {
                flow.stage("disconnect_existing");gateway.disconnectBeforeRestore(project,ref);
                var backupAccess = gateway.existingServerArguments(ref, optional(a,"existing_login"),
                    optional(a,"existing_password"), monitor, null);
                var backup = readWithAccess(ref, flow, "backup_existing", backupAccess,
                    "Native backup did not complete; DT import was not dispatched", attempt -> {
                        verifyRestoreTarget(op, target, ref, plan, project);
                        Path destination = operation.resolve(attempt == 1 ? "before-restore.dt"
                            : "before-restore-" + attempt + ".dt");
                        gateway.dump(info, ref, destination, backupAccess);
                        return destination;
                    });
                existingAccess = backupAccess;
                if (backup.value() != null) {
                    result.put("backup_dt", backup.value().toString());
                    flow.details(Map.of("backup_dt", backup.value().toString()));
                }
                if (backup.cancelled()) return cancelled(flow, result, false);
                if (backup.secure() && backup.persist()) {
                    verifyRestoreTarget(op, target, ref, plan, project);
                    gateway.restoredAccess(ref, backupAccess.getUsername(), backupAccess.getPassword());
                }
                gateway.requireFull(ref);
            }
            if(a.containsKey("dt_path")) {
                Path dt=InfobasePaths.absolute(required(a,"dt_path"));requireDtUnchanged(plan,dt);
                verifyRestoreTarget(op, target, ref, plan, project);
                var restoreAccess = existingAccess == null
                    ? gateway.arguments(null, null, monitor, operation.resolve("restore.log")) : existingAccess;
                if (restore) requirePrivateNativeAccess(flow, "restore_dt", false);
                flow.stage("restore_dt");
                if (op.equals("restore")) {
                    // Claim before reaching the native import, and retain the marker on any unknown outcome.
                    Path claim = importClaim(target);
                    Files.writeString(claim, operation.getFileName().toString(), StandardOpenOption.CREATE_NEW);
                    try (var prompt = new ServerRestoreAccessPrompt(ref, flow, "restore_dt", callerSession)) {
                        gateway.restore(info, ref, dt, restoreAccess);
                    } catch (Exception | LinkageError failure) {
                        flow.finishRestore("import_outcome_unknown");
                        flow.signal(Map.of("stage", "restore_outcome_unknown", "outcome_unknown", true,
                            "retry_safe", false, "mutation_blocked", true));
                        throw new IllegalStateException("Server DT import did not return a confirmed result; inspect the original operation");
                    }
                    restoreAccess.setPassword(null);
                    restoreAccess.setUsername(null);
                    flow.details(Map.of("import_completed", true));
                    try {
                        Files.writeString(operation.resolve("import-confirmed"), "native_import_completed",
                            StandardOpenOption.CREATE_NEW);
                        Files.delete(claim);
                    } catch (IOException | SecurityException failure) {
                        String code = "restore_import_confirmation_write_failed";
                        String message = "DT import completed, but Bridge could not record its confirmation. Do not repeat the import.";
                        flow.finishRestore(code);
                        flow.signal(Map.of("stage", "restore_import_confirmation_failed", "error_code", code,
                            "message", message, "retry_safe", false, "mutation_blocked", true));
                        throw new IllegalStateException(message);
                    }
                } else {
                    gateway.restore(info, ref, dt, restoreAccess);
                }
                gateway.requireFull(ref);
            }
            String login = optional(a,"login");
            String password = optional(a,"password");
            if(a.containsKey("dt_path") && !restore)gateway.restoredAccess(ref,login,password);
            var verifyAccess = gateway.arguments(login,password,monitor,restore ? null : operation.resolve("verify.log"));
            Path exported;
            if (restore) {
                var verified = readWithAccess(ref, flow, "verify_restored_access", verifyAccess,
                    "DT import completed; restored infobase access verification did not succeed", attempt -> {
                        verifyRestoreTarget(op, target, ref, plan, project);
                        return gateway.export(info,ref,operation.resolve(attempt == 1 ? "configuration"
                            : "configuration-" + attempt),verifyAccess);
                    });
                if (verified.cancelled()) return cancelled(flow, result, true);
                exported = verified.value();
                if (verified.secure() && !verified.persist()) {
                    verifyAccess.setPassword(null);
                    verifyAccess.setUsername(null);
                    result.put("restore_verified", true);
                    result.put("configuration_export", exported.toString());
                    result.put("import_completed", true);
                    result.put("configuration_synchronized", false);
                    result.put("full_reload_required", true);
                    result.put("success", false);
                    result.put("status", "restored_access_verified_without_saving");
                    result.put("state", "restored_access_verified_without_saving");
                    flow.finishRestore("restored_access_verified_without_saving");
                    flow.stage("restored_access_verified_without_saving");
                    return result;
                }
            } else {
                flow.stage("verify_server_access");
                exported = gateway.export(info,ref,operation.resolve("configuration"),verifyAccess);
            }
            result.put("restore_verified",true);result.put("configuration_export",exported.toString());
            gateway.awaitProject(project);flow.stage("bind_application");
            verifyRestoreTarget(op, target, ref, plan, project);
            if(flag(a,"set_primary",true) && !Objects.equals(plan.get("previous_primary"),gateway.primaryIdentity(project)))throw new IllegalStateException("Primary application changed during operation; server base retained");
            gateway.checkPrimaryServer(project,ref,flag(a,"set_primary",true),flag(a,"replace_primary",false));
            // Access/runtime updates can asynchronously reconnect a live native synchronization.
            // Detach this exact application first; reload/connect then initializes one fresh flow.
            if(op.equals("bind")){flow.stage("disconnect_application");gateway.disconnectBeforeRestore(project,ref);}
            if (restore) requirePrivateNativeAccess(flow, "bind_application", true);
            gateway.selectRuntimeForApplication(project,ref,required(a,"runtime_version"));
            if (restore) requirePrivateNativeAccess(flow, "bind_application", true);
            // EDT's native access requestor may update the arguments during a successful export.
            boolean passwordAccess = verifyAccess.getAccess() == InfobaseAccess.INFOBASE;
            try {
                gateway.bind(project, ref, passwordAccess ? verifyAccess.getUsername() : null,
                    passwordAccess ? verifyAccess.getPassword() : null, flag(a,"set_primary",true));
            } catch (Exception | LinkageError failure) {
                if (!restore) throw failure;
                throw new IllegalStateException("DT import completed; EDT could not bind the verified server access");
            } finally {
                verifyAccess.setUsername(null);
                verifyAccess.setPassword(null);
            }
            if(!gateway.bound(project,ref))throw new IllegalStateException("Server application binding not verified");result.put("application_bound",true);
            if (flag(a,"update_from_project",!op.equals("bind"))) {
                flow.stage("full_reload");
                if (op.equals("restore")) {
                    requirePrivateNativeAccess(flow, "full_reload", true);
                    try (var prompt = new ServerRestoreAccessPrompt(ref, flow, "full_reload", callerSession)) {
                        result.putAll(gateway.update(project,ref,monitor));
                    } catch (Exception | LinkageError failure) {
                        throw new IllegalStateException("DT import completed; native FULL verification did not succeed");
                    }
                } else {
                    result.putAll(gateway.update(project,ref,monitor));
                }
            } else {
                result.put("configuration_synchronized",false);
                result.put("full_reload_required",gateway.requiresFull(ref));
            }
            result.put("application_migrations_verified",false);result.put("status","completed");Files.writeString(operation.resolve("result.json"),new Gson().toJson(result));
            flow.finishRestore("completed");
            flow.stage("completed");
            return result;
        }
    }
}
