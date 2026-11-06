// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.eclipse.core.runtime.NullProgressMonitor;
import com.google.gson.Gson;

/** Preview -> fresh fingerprint -> background native operations. No fallback shell or desktop input. */
public class InfobaseProvisionService {
    private final NativeInfobaseGateway gateway;
    public InfobaseProvisionService() { this(new NativeInfobaseGateway()); }
    public InfobaseProvisionService(NativeInfobaseGateway gateway) {this.gateway=gateway;}
    public static String required(Map<String,Object> a,String key) {
        Object value=a.get(key);
        if(!(value instanceof String s) || s.isBlank()) throw new IllegalArgumentException(key+" is required");
        return s;
    }
    public static boolean flag(Map<String,Object> a,String key,boolean fallback) {
        Object v=a.get(key);if(v==null)return fallback;
        if(!(v instanceof Boolean)) throw new IllegalArgumentException(key+" must be boolean");return Boolean.TRUE.equals(v);
    }
    static String optional(Map<String,Object> a,String key) {Object v=a.get(key);return v instanceof String s?s:null;}
    static void validateCredentials(Map<String,Object> a,String loginKey,String passwordKey) {
        String login=optional(a,loginKey),password=optional(a,passwordKey);
        if(password!=null && !password.isEmpty() && (login==null || login.isBlank()))
            throw new IllegalArgumentException(passwordKey+" requires a nonblank "+loginKey);
    }
    static void requireDtUnchanged(Map<String,Object> plan,Path dt) throws Exception {
        InfobasePaths.absolute(dt.toString());
        if(!Objects.equals(plan.get("dt_source"),InfobasePaths.fingerprint(dt)))
            throw new IllegalStateException("STALE_PREVIEW: DT changed during preparation; restore was not started");
    }
    public Map<String,Object> preview(Map<String,Object> a) throws Exception {
        validateCredentials(a,"login","password");validateCredentials(a,"existing_login","existing_password");
        String project=required(a,"project"),version=required(a,"runtime_version");
        Path database=InfobasePaths.absolute(required(a,"database_path"));
        boolean overwrite=flag(a,"restore_existing",false);
        Path dt=a.containsKey("dt_path")?InfobasePaths.absolute(required(a,"dt_path")):null;
        if(dt!=null && (!Files.isRegularFile(dt) || Files.size(dt)==0 || !dt.toString().toLowerCase(Locale.ROOT).endsWith(".dt")))
            throw new IllegalArgumentException("dt_path must name a nonempty DT file");
        if(dt!=null && dt.startsWith(database)) throw new IllegalArgumentException("DT source must be outside the target infobase directory");
        Path file=database.resolve("1Cv8.1CD");
        if(overwrite) {
            if(dt==null || !Files.isRegularFile(file)) throw new IllegalArgumentException("restore_existing requires DT and an existing file infobase");
            InfobasePaths.absolute(file.toString());
        } else if(Files.exists(database,LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("New infobase target must not exist; existing databases require restore_existing=true");
        }
        gateway.project(project);
        gateway.checkPrimary(project,database,flag(a,"set_primary",true),flag(a,"replace_primary",false));
        var ref=gateway.reference(database);
        if(gateway.ownerProjects(ref).stream().anyMatch(owner->!owner.equals(project)))
            throw new IllegalArgumentException("Target infobase is associated with another EDT project; explicitly move its application before restore");
        String runtimeDescription=gateway.runtimeDescription(ref,version);
        var plan=new LinkedHashMap<String,Object>();
        plan.put("project",project);plan.put("database_path",database.toString());plan.put("runtime_version",version);
        plan.put("restore_existing",overwrite);plan.put("database_before",InfobasePaths.fingerprint(file));
        plan.put("dt_source",dt==null?Map.of():InfobasePaths.fingerprint(dt));
        plan.put("previous_primary",gateway.primaryIdentity(project));
        plan.put("set_primary",flag(a,"set_primary",true));plan.put("replace_primary",flag(a,"replace_primary",false));
        plan.put("update_from_project",flag(a,"update_from_project",true));plan.put("update_mode","full");
        plan.put("native_update_method","reloadInfobase");
        plan.put("runtime_component",runtimeDescription);
        plan.put("credentials_fingerprint",InfobasePaths.hash(new Gson().toJson(List.of(
            Objects.toString(a.get("login"),""),Objects.toString(a.get("password"),""),
            Objects.toString(a.get("existing_login"),""),Objects.toString(a.get("existing_password"),"")))));
        String hash=InfobasePaths.fingerprintHash(plan);plan.remove("credentials_fingerprint");
        plan.put("preview_sha256",hash);plan.put("dry_run",true);
        plan.put("stages",List.of("preflight","create_or_backup","restore_dt","verify_restore","bind_application","full_reload","verify_result"));
        return plan;
    }
    public static void requirePreview(Map<String,Object> a,Map<String,Object> plan) {
        if(!Objects.equals(a.get("expected_preview_sha256"),plan.get("preview_sha256")))
            throw new IllegalStateException("STALE_PREVIEW: preview again; the target, DT or request has changed");
    }
    public Map<String,Object> run(Map<String,Object> a,Consumer<Map<String,Object>> progress) throws Exception {
        String name=required(a,"project");Path db=InfobasePaths.absolute(required(a,"database_path"));
        var ref=gateway.reference(db);
        try(var flow=new InfobaseWorkflow(InfobaseWorkflow.resourceKeys(name,ref),progress)) {
            flow.stage("preflight");Map<String,Object> plan=preview(a);requirePreview(a,plan);
            Path operation=gateway.stateRoot().resolve(UUID.randomUUID().toString());Files.createDirectories(operation);flow.details(Map.of("operation_directory",operation.toString()));
            var result=new LinkedHashMap<String,Object>();result.put("operation_directory",operation.toString());
            result.put("project",name);result.put("database_path",db.toString());result.put("preview_sha256",plan.get("preview_sha256"));
            gateway.pinVersion(ref,required(a,"runtime_version"));
            var info=gateway.runtime(ref,required(a,"runtime_version"));
            var monitor=new NullProgressMonitor();
            boolean overwrite=flag(a,"restore_existing",false);
            if(overwrite) {
                flow.stage("backup_existing");gateway.disconnectBeforeRestore(name,ref);
                Path backup=operation.resolve("before-restore.dt");
                gateway.dump(info,ref,backup,gateway.arguments(optional(a,"existing_login"),optional(a,"existing_password"),monitor,operation.resolve("backup.log")));
                result.put("backup_dt",backup.toString());
                flow.details(Map.of("backup_dt",backup.toString()));flow.stage("backup_completed");
            } else {
                flow.stage("create_infobase");Files.createDirectories(db.getParent());Files.createDirectory(db);
                gateway.create(info,ref);
            }
            // Persist BEFORE restore: even failed restores must never reuse old incremental history.
            gateway.requireFull(ref);
            if(a.containsKey("dt_path")) {
                requireDtUnchanged(plan,InfobasePaths.absolute(required(a,"dt_path")));
                flow.stage("restore_dt");
                gateway.restore(info,ref,InfobasePaths.absolute(required(a,"dt_path")),
                    gateway.arguments(overwrite?optional(a,"existing_login"):null,overwrite?optional(a,"existing_password"):null,monitor,operation.resolve("restore.log")));
            }
            flow.stage("verify_restore");
            if(!Files.isRegularFile(db.resolve("1Cv8.1CD")) || Files.size(db.resolve("1Cv8.1CD"))==0)
                throw new IllegalStateException("Native create/restore returned without a database file");
            Path exported=gateway.export(info,ref,operation.resolve("restored-configuration"),
                gateway.arguments(optional(a,"login"),optional(a,"password"),monitor,operation.resolve("verify.log")));
            result.put("restore_verified",true);result.put("configuration_export",exported.toString());
            flow.stage("wait_project_context");gateway.awaitProject(name);
            flow.stage("bind_application");
            if(flag(a,"set_primary",true) && !Objects.equals(plan.get("previous_primary"),gateway.primaryIdentity(name)))
                throw new IllegalStateException("Primary application changed during restore; database retained, binding not applied");
            gateway.checkPrimary(name,db,flag(a,"set_primary",true),flag(a,"replace_primary",false));
            gateway.selectRuntimeForApplication(name,ref,required(a,"runtime_version"));
            gateway.bind(name,ref,optional(a,"login"),optional(a,"password"),flag(a,"set_primary",true));
            if(!gateway.bound(name,ref)) throw new IllegalStateException("EDT did not persist the application association");
            result.put("application_bound",true);
            if(flag(a,"update_from_project",true)) {
                flow.stage("full_reload");result.putAll(gateway.update(name,ref,monitor));
            } else {result.put("configuration_synchronized",false);result.put("full_reload_required",true);}
            flow.stage("verify_result");result.put("application_migrations_verified",false);
            result.put("completed_at",Instant.now().toString());result.put("status","completed");
            Files.writeString(operation.resolve("result.json"),new Gson().toJson(result));
            flow.stage("completed");return result;
        }
    }
    /** Exact secret replacement supplements generic log sanitizing for native exception messages. */
    public static String safeError(Throwable error,Map<String,Object> a) {
        String message=error.getClass().getSimpleName()+": "+Objects.toString(error.getMessage(),"");
        if(error.getSuppressed().length>0) {
            message+="; recovery errors: "+Arrays.stream(error.getSuppressed()).limit(4)
                .map(failure->failure.getClass().getSimpleName()+": "+Objects.toString(failure.getMessage(),""))
                .collect(java.util.stream.Collectors.joining("; "));
        }
        for(String key:List.of("password","existing_password")) {
            String secret=optional(a,key);if(secret!=null && !secret.isEmpty()) message=message.replace(secret,"<redacted>");
        }
        return com.codepilot1c.core.logging.LogSanitizer.redactSecrets(message);
    }
}
