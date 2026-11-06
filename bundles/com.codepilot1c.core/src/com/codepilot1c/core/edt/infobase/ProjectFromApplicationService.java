// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.NullProgressMonitor;
import com.google.gson.Gson;
import com.codepilot1c.core.edt.compare.EdtComparisonService;
import static com.codepilot1c.core.edt.infobase.InfobaseProvisionService.*;

/** Full native export/import. Existing projects are updated through a reviewable native comparison. */
public class ProjectFromApplicationService {
    private final NativeInfobaseGateway gateway;
    public ProjectFromApplicationService() {this(new NativeInfobaseGateway());}
    public ProjectFromApplicationService(NativeInfobaseGateway gateway) {this.gateway=gateway;}
    public Map<String,Object> preview(Map<String,Object> a) throws Exception {
        validateCredentials(a,"login","password");
        String mode=required(a,"mode"),project=required(a,"project"),version=required(a,"runtime_version");
        if(!Set.of("create","compare").contains(mode)) throw new IllegalArgumentException("mode must be create or compare");
        Path database=InfobasePaths.absolute(required(a,"database_path"));
        InfobasePaths.absolute(database.resolve("1Cv8.1CD").toString());
        if(!Files.isRegularFile(database.resolve("1Cv8.1CD"))) throw new IllegalArgumentException("Existing file infobase required");
        boolean disposable=flag(a,"disposable_test_project",false);
        if(disposable && (!mode.equals("create") || !project.startsWith("BridgeInfobaseAudit")))
            throw new IllegalArgumentException("Disposable create is restricted to explicit BridgeInfobaseAudit* test projects");
        var plan=new LinkedHashMap<String,Object>();plan.put("disposable_test_project",disposable);
        plan.put("project_version",NativeInfobaseGateway.projectVersion(version));
        plan.put("mode",mode);plan.put("project",project);plan.put("runtime_version",version);plan.put("database_path",database.toString());
        plan.put("database_before",InfobasePaths.fingerprint(database.resolve("1Cv8.1CD")));
        if(mode.equals("create")) {
            Path target=InfobasePaths.absolute(required(a,"project_path"));
            if(target.startsWith(database) || database.startsWith(target)) throw new IllegalArgumentException("Project and infobase directories must not overlap");
            if(!target.getFileName().toString().equals(project)) throw new IllegalArgumentException("project_path must end in the exact project name");
            if(Files.exists(target) || ResourcesPlugin.getWorkspace().getRoot().getProject(project).exists()) throw new IllegalArgumentException("Target project/path already exists");
            plan.put("project_path",target.toString());
        } else {
            gateway.project(project);plan.put("source_writes",false);plan.put("next_step","Inspect and apply the native comparison through edt_compare / edt_merge_plan / edt_merge_apply");
        }
        var reference=gateway.reference(database);var owners=gateway.ownerProjects(reference);
        plan.put("application_owners",owners);
        boolean bind=mode.equals("create") && flag(a,"bind_application",true);
        String reassign=optional(a,"reassign_from_project");
        if(reassign!=null) gateway.project(reassign);
        validateOwnerPolicy(bind,owners,reassign);
        plan.put("bind_application",bind);plan.put("reassign_from_project",reassign==null?"":reassign);
        plan.put("runtime_component",gateway.runtimeDescription(reference,version));
        plan.put("credentials_fingerprint",InfobasePaths.hash(Objects.toString(a.get("login"),"")+"\u0000"+Objects.toString(a.get("password"),"")));
        String hash=InfobasePaths.fingerprintHash(plan);plan.remove("credentials_fingerprint");plan.put("preview_sha256",hash);plan.put("dry_run",true);return plan;
    }
    static void validateOwnerPolicy(boolean bind,List<String> owners,String reassign) {
        if(reassign!=null && (!bind || !owners.equals(List.of(reassign)))) throw new IllegalArgumentException("reassign_from_project must be the sole current owner for create+bind");
        if(bind && !owners.isEmpty() && reassign==null) throw new IllegalArgumentException("APPLICATION_ALREADY_BOUND: use bind_application=false or explicitly set reassign_from_project="+owners);
    }
    public Map<String,Object> run(Map<String,Object> a,Consumer<Map<String,Object>> progress) throws Exception {
        String project=required(a,"project"),mode=required(a,"mode");Path db=InfobasePaths.absolute(required(a,"database_path"));
        var ref=gateway.reference(db);
        var keys=new ArrayList<>(InfobaseWorkflow.resourceKeys(project,ref));
        if(optional(a,"reassign_from_project")!=null) keys.add("project:"+optional(a,"reassign_from_project"));
        try(var flow=new InfobaseWorkflow(keys,progress)) {
            flow.stage("preflight");requirePreview(a,preview(a));
            String id=UUID.randomUUID().toString();Path operation=gateway.stateRoot().resolve(id);Files.createDirectories(operation);flow.details(Map.of("operation_directory",operation.toString(),"import_id",id));
            var info=gateway.runtime(ref,required(a,"runtime_version"));
            flow.stage("export_application");
            Path exported=gateway.export(info,ref,operation.resolve("configuration"),gateway.arguments(optional(a,"login"),optional(a,"password"),new NullProgressMonitor(),operation.resolve("export.log")));
            String targetName=mode.equals("create")?project:"BridgeInfobaseSnapshot"+id.replace("-","");
            Path target=mode.equals("create")?InfobasePaths.absolute(required(a,"project_path")):operation.resolve(targetName);
            var result=new LinkedHashMap<String,Object>();result.put("import_id",id);result.put("project",project);result.put("mode",mode);result.put("configuration_export",exported.toString());
            result.put("database_path",db.toString());result.put("operation_directory",operation.toString());
            if(mode.equals("compare") || flag(a,"disposable_test_project",false)) {
                result.put("snapshot_project",targetName);result.put("snapshot_path",target.toString());
                if(mode.equals("create")) {result.put("disposable_test_project",true);result.put("project_path",target.toString());}
                result.put("status","importing");Files.writeString(operation.resolve("import-result.json"),new Gson().toJson(result));
            }
            try {
            flow.stage("import_native_project");gateway.importNew(exported,target,required(a,"runtime_version"));
            flow.stage("wait_model");gateway.awaitProject(targetName);
            if(mode.equals("create")) {
                boolean bind=flag(a,"bind_application",true);String previous=optional(a,"reassign_from_project");boolean detached=false,oldPrimary=false;
                if(bind) {
                    flow.stage("bind_application");
                    if(previous!=null) {
                        if(!gateway.ownerProjects(ref).equals(List.of(previous))) throw new IllegalStateException("Application owner changed during import; binding not moved");
                        oldPrimary=gateway.detachApplication(previous,ref);detached=true;
                    } else if(!gateway.ownerProjects(ref).isEmpty()) throw new IllegalStateException("Application acquired another owner during import; binding refused");
                    try {
                        gateway.selectRuntimeForApplication(project,ref,required(a,"runtime_version"));
                        gateway.bind(project,ref,optional(a,"login"),optional(a,"password"),true);
                        if(!gateway.bound(project,ref)) throw new IllegalStateException("Application binding not verified");
                    } catch(Exception error) {
                        if(detached) {gateway.removeOwnAssociation(project,ref);gateway.restoreApplication(previous,ref,oldPrimary);}
                        throw error;
                    }
                }
                result.put("status","completed");result.put("application_bound",bind);
                if(previous!=null) result.put("reassigned_from_project",previous);
                result.put("project_path",target.toString());result.put("model_ready",true);
                if(flag(a,"disposable_test_project",false)) {
                    result.put("disposable_test_project",true);result.put("snapshot_project",targetName);
                    result.put("snapshot_path",target.toString());result.put("snapshot_hash",treeHash(target));
                }
            } else {
                // Full comparison includes deletions. Agent/user chooses rules explicitly; no hidden source overwrite.
                flow.stage("prepare_native_comparison");
                Object session=EdtComparisonService.getInstance().execute("compare",Map.of("action","start","main_project",project,"other_project",targetName,"parse_bsl_structure",true,"allow_deletions",true));
                result.put("status","review_required");result.put("comparison",session);result.put("snapshot_project",targetName);
                result.put("snapshot_path",target.toString());result.put("snapshot_hash",treeHash(target));
                result.put("next_tools",List.of("edt_compare","edt_merge_plan","edt_merge_apply"));
                result.put("source_writes",false);
            }
            result.put("operation_directory",operation.toString());result.put("application_migrations_verified",false);
            Files.writeString(operation.resolve("import-result.json"),new Gson().toJson(result));
            flow.stage(mode.equals("create")?"completed":"review_required");return result;
            } catch(Exception | LinkageError error) {
                result.put("status","failed");result.put("error",safeError(error,a));
                if(Files.isDirectory(target.resolve("src"))) result.put("snapshot_hash",treeHash(target));
                Files.writeString(operation.resolve("import-result.json"),new Gson().toJson(result));
                throw error;
            }
        }
    }
    public Map<String,Object> cleanup(String id) throws Exception {
        UUID.fromString(id);Path operation=gateway.stateRoot().resolve(id);
        @SuppressWarnings("unchecked") Map<String,Object> result=new Gson().fromJson(Files.readString(operation.resolve("import-result.json")),Map.class);
        boolean disposable=Boolean.TRUE.equals(result.get("disposable_test_project"));
        if(!"compare".equals(result.get("mode")) && !disposable) throw new IllegalArgumentException("Only owned snapshots or explicitly disposable imported test projects can be removed");
        String name=disposable?(String)result.get("project"):"BridgeInfobaseSnapshot"+id.replace("-","");
        if(disposable && !name.startsWith("BridgeInfobaseAudit")) throw new IllegalStateException("Not an owned disposable project");
        Path expected=disposable?InfobasePaths.absolute((String)result.get("project_path")):operation.resolve(name);
        if(!name.equals(result.get("snapshot_project")) || !expected.toString().equals(result.get("snapshot_path"))) throw new IllegalStateException("Snapshot identity mismatch");
        IProject project=ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        if(!project.exists()) return Map.of("import_id",id,"snapshot_removed",true);
        if(!project.getLocation().toFile().toPath().equals(expected) || !Objects.equals(treeHash(expected),result.get("snapshot_hash")))
            throw new IllegalStateException("Snapshot was moved or edited; automatic removal refused");
        if(!disposable && result.containsKey("comparison")) {
        @SuppressWarnings("unchecked") Map<String,Object> comparison=(Map<String,Object>)result.get("comparison");
        String session=(String)comparison.get("session_id");
        // Existing comparison close checks ownership and unsaved editor content before cancellation.
        EdtComparisonService.getInstance().execute("compare",Map.of("action","close","session_id",session));
        }
        gateway.assertNoDirtyEditors(project);
        project.delete(true,true,new NullProgressMonitor());
        result.put("snapshot_removed",true);Files.writeString(operation.resolve("import-result.json"),new Gson().toJson(result));
        return Map.of("import_id",id,"snapshot_removed",true,"export_and_log_retained",true);
    }
    static String treeHash(Path root) throws Exception {
        try(var files=Files.walk(root)) {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            for(Path p:files.sorted().toList()) {
                InfobasePaths.absolute(p.toString());
                if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)) continue;
                digest.update(root.relativize(p).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte)0);
                var content=java.security.MessageDigest.getInstance("SHA-256");
                try(var input=Files.newInputStream(p)) {byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))>=0)content.update(buffer,0,n);}
                digest.update(content.digest());
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
