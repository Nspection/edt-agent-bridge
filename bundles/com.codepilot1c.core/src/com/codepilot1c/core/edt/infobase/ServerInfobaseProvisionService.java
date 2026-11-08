// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.eclipse.core.runtime.NullProgressMonitor;
import com.google.gson.Gson;
/** Native EDT create/restore/bind workflow. New SQL names derive from a one-use UUID. */
public final class ServerInfobaseProvisionService extends InfobaseProvisionService {
    private final NativeInfobaseGateway gateway;
    public ServerInfobaseProvisionService(){this(new NativeInfobaseGateway());}
    public ServerInfobaseProvisionService(NativeInfobaseGateway gateway){super(gateway);this.gateway=gateway;}
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
        if(op.equals("create")){plan.put("creation_id",id);plan.put("database_name","eb_"+id.replace("-",""));plan.put("db_server",a.get("db_server"));plan.put("dbms",Objects.toString(a.get("dbms"),"postgresql"));plan.put("db_user",a.get("db_user"));}
        plan.put("dt_source",dt==null?Map.of():InfobasePaths.fingerprint(dt));
        plan.put("update_from_project",flag(a,"update_from_project",!op.equals("bind")));plan.put("set_primary",flag(a,"set_primary",true));plan.put("replace_primary",flag(a,"replace_primary",false));
        plan.put("credential_hash",InfobasePaths.hash(new Gson().toJson(List.of(Objects.toString(a.get("db_password"),""),Objects.toString(a.get("password"),""),Objects.toString(a.get("existing_password"),""),Objects.toString(a.get("login"),""),Objects.toString(a.get("existing_login"),"")))));
        String hash=InfobasePaths.fingerprintHash(plan);plan.remove("credential_hash");plan.put("preview_sha256",hash);plan.put("dry_run",true);
        plan.put("note","Server preview identifies endpoint, DT and request parameters; it is not a snapshot of live database data. Restore always makes a native backup first. New creation uses unique cluster and SQL names, and a one-use creation_id.");return plan;
    }
    private Path claim(String id){return gateway.stateRoot().resolve("server-create-"+id+".claim");}
    public static com._1c.g5.v8.dt.platform.services.model.DatabaseType databaseType(Map<String,Object> a){return switch(Objects.toString(a.get("dbms"),"postgresql")){case "postgresql"->com._1c.g5.v8.dt.platform.services.model.DatabaseType.POSTGRE_SQL;case "mssql"->com._1c.g5.v8.dt.platform.services.model.DatabaseType.MS_SQL_SERVER;default->throw new IllegalArgumentException("dbms must be postgresql or mssql");};}
    @Override public Map<String,Object> run(Map<String,Object> a,Consumer<Map<String,Object>> progress) throws Exception {
        if(operation(a).equals("create") && !a.containsKey("creation_id"))throw new IllegalArgumentException("Apply must preserve creation_id from preview");
        String project=required(a,"project"),op=operation(a);var target=target(a,creationId(a));var ref=gateway.serverReference(target);
        try(var flow=new InfobaseWorkflow(InfobaseWorkflow.resourceKeys(project,ref),progress)) {
            flow.stage("preflight");var plan=preview(a);requirePreview(a,plan);
            Path operation=gateway.stateRoot().resolve(UUID.randomUUID().toString());Files.createDirectories(operation);
            flow.details(Map.of("operation_directory",operation.toString(),"server_address",target.address(),"server_infobase",target.name()));
            var result=new LinkedHashMap<String,Object>();result.put("project",project);result.put("kind","server");result.put("server_address",target.address());result.put("server_infobase",target.name());result.put("connection_string",target.connection());result.put("operation_directory",operation.toString());
            gateway.pinVersion(ref,required(a,"runtime_version"));var info=gateway.runtime(ref,required(a,"runtime_version"));var monitor=new NullProgressMonitor();
            if(op.equals("create")) {
                String id=required(a,"creation_id");Files.createDirectories(claim(id).getParent());Files.createFile(claim(id));
                result.put("creation_id",id);result.put("database_name",plan.get("database_name"));Files.writeString(operation.resolve("server-operation.json"),new Gson().toJson(result));
                flow.stage("create_server_infobase");gateway.createServer(info,ref,a,(String)plan.get("database_name"));gateway.requireFull(ref);
            } else if(op.equals("restore")) {
                flow.stage("backup_existing");gateway.disconnectBeforeRestore(project,ref);
                Path backup=operation.resolve("before-restore.dt");gateway.dump(info,ref,backup,gateway.arguments(optional(a,"existing_login"),optional(a,"existing_password"),monitor,operation.resolve("backup.log")));result.put("backup_dt",backup.toString());flow.details(Map.of("backup_dt",backup.toString()));gateway.requireFull(ref);
            }
            if(a.containsKey("dt_path")) {
                Path dt=InfobasePaths.absolute(required(a,"dt_path"));requireDtUnchanged(plan,dt);flow.stage("restore_dt");
                gateway.restore(info,ref,dt,gateway.arguments(op.equals("restore")?optional(a,"existing_login"):null,op.equals("restore")?optional(a,"existing_password"):null,monitor,operation.resolve("restore.log")));gateway.requireFull(ref);
            }
            if(a.containsKey("dt_path"))gateway.restoredAccess(ref,optional(a,"login"),optional(a,"password"));
            flow.stage("verify_server_access");Path exported=gateway.export(info,ref,operation.resolve("configuration"),gateway.arguments(optional(a,"login"),optional(a,"password"),monitor,operation.resolve("verify.log")));result.put("restore_verified",true);result.put("configuration_export",exported.toString());
            gateway.awaitProject(project);flow.stage("bind_application");
            if(flag(a,"set_primary",true) && !Objects.equals(plan.get("previous_primary"),gateway.primaryIdentity(project)))throw new IllegalStateException("Primary application changed during operation; server base retained");
            gateway.checkPrimaryServer(project,ref,flag(a,"set_primary",true),flag(a,"replace_primary",false));
            // Access/runtime updates can asynchronously reconnect a live native synchronization.
            // Detach this exact application first; reload/connect then initializes one fresh flow.
            if(op.equals("bind")){flow.stage("disconnect_application");gateway.disconnectBeforeRestore(project,ref);}
            gateway.selectRuntimeForApplication(project,ref,required(a,"runtime_version"));gateway.bind(project,ref,optional(a,"login"),optional(a,"password"),flag(a,"set_primary",true));
            if(!gateway.bound(project,ref))throw new IllegalStateException("Server application binding not verified");result.put("application_bound",true);
            if(flag(a,"update_from_project",!op.equals("bind"))){flow.stage("full_reload");result.putAll(gateway.update(project,ref,monitor));}else{result.put("configuration_synchronized",false);result.put("full_reload_required",gateway.requiresFull(ref));}
            result.put("application_migrations_verified",false);result.put("status","completed");Files.writeString(operation.resolve("result.json"),new Gson().toJson(result));flow.stage("completed");return result;
        }
    }
}
