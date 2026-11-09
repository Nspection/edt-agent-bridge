// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.google.gson.*;
import com.codepilot1c.core.edt.infobase.*;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.tools.*;
import org.eclipse.core.resources.ResourcesPlugin;

/** Bridges task orchestration to existing native implementations. No arbitrary command/script routing. */
public final class TaskSessionBackend implements TaskSessionService.Backend {
    private static final Gson JSON=new GsonBuilder().serializeNulls().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
    private Path storage(){return ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core");}
    public Map<String,Object> verifyClient(String project,String endpoint)throws Exception {
        var base=new EdtRuntimeService().resolveDefaultInfobase(project);if(base==null)return ClientBindingVerifier.failed("base_unavailable");
        return VanessaMcpBridge.getInstance().verifyClient(endpoint,ClientBindingVerifier.targetHash(base.getConnectionString().asConnectionString()));
    }
    public Map<String,Object> formChange(String endpoint,String baseline)throws Exception{return RuntimeFormService.workspace().changed(baseline,endpoint);}
    public Map<String,Object> binding(String project,String profile)throws Exception {
        var p=ResourcesPlugin.getWorkspace().getRoot().getProject(project);if(!p.isOpen()||p.getLocation()==null)throw new IllegalArgumentException("Open local project required");
        var base=new EdtRuntimeService().resolveDefaultInfobase(project);if(base==null)throw new IllegalStateException("Associate an explicitly permitted test infobase first");
        String connection=InfobasePaths.connectionIdentity(base.getConnectionString().asConnectionString());
        var runner=RunnerProfileStore.workspace().get(project,profile);
        if(Boolean.TRUE.equals(runner.get("configured"))) {
            Map<String,Object> params=TaskSessionService.object(runner.get("profile"));String target;
            if(RunnerProfileStore.serverTarget(params))target=new ServerInfobaseTarget((String)params.get("server_address"),(String)params.get("server_infobase")).connection();
            else target=com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences.newFileInfobaseReference((String)params.get("infobase_path")).getConnectionString().asConnectionString();
            if(!connection.equals(InfobasePaths.connectionIdentity(target)))throw new IllegalStateException("Saved runner profile targets another infobase");
        }
        return Map.of("project",project,"project_location",p.getLocation().toFile().toPath().toRealPath().toString(),"base_uuid",base.getUuid().toString(),"base_identity_sha256",InfobasePaths.hash(connection),"runner_sha256",TaskSessionService.fingerprint(runner),"runner_configured",Boolean.TRUE.equals(runner.get("configured")));
    }
    static String argumentsJson(Map<String,Object> arguments){return JSON.toJson(arguments);}
    @SuppressWarnings("unchecked") public Map<String,Object> call(String tool,Map<String,Object>a)throws Exception {
        var result=new ToolExecutionService(ToolRegistry.getInstance()).execute(new com.codepilot1c.core.model.ToolCall(UUID.randomUUID().toString(),tool,argumentsJson(a))).get(90,TimeUnit.SECONDS);
        if(!result.isSuccess())throw new IllegalStateException("Task step failed; inspect "+tool+" with the original command");
        return JSON.fromJson(result.getContent(),LinkedHashMap.class);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> json(Path file)throws Exception {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>2*1024*1024)throw new IllegalStateException("Resource proof unavailable");return JSON.fromJson(Files.readString(file,java.nio.charset.StandardCharsets.UTF_8),LinkedHashMap.class);
    }
    private void target(Map<String,Object> binding,Map<String,Object> proof){if(!binding.get("project").equals(proof.get("project"))||!binding.get("base_uuid").equals(proof.get("base_uuid")))throw new IllegalStateException("Resource belongs to another project/base");}
    public Map<String,Object> resource(Map<String,Object> binding,String kind,String id)throws Exception {
        return switch(kind) {
            case "run"->{var r=DevelopmentRunService.getInstance().status(id,0);if(!binding.get("project").equals(r.get("project")))throw new IllegalStateException("Run target differs");
                if(!Objects.equals(binding.get("base_identity_sha256"),r.get("base_identity_sha256"))){var base=new EdtRuntimeService().resolveDefaultInfobase((String)binding.get("project"));
                    if(base==null||!legacyFileRunMatches(binding,r,base.getConnectionString().asConnectionString(),base.getUuid().toString()))throw new IllegalStateException("Run target differs");}yield AgentBrief.select(r,"run_id","phase","terminal","event_cursor");}
            case "host"->{var service=new NativeHostService();var target=service.testDataTarget(id,false);if(!binding.get("project").equals(target.project())||!binding.get("base_uuid").equals(target.baseUuid()))throw new IllegalStateException("Host target differs");yield AgentBrief.select(service.status(id),"host_id","project","mode","state","cleaned","source_removed","database_verified_absent");}
            case "data_journal"->{var target=new TestDataLedger.Target((String)binding.get("project"),(String)binding.get("base_uuid"));var ledger=new TestDataLedger(storage().resolve("test-data"));var evidence=JSON.fromJson(ledger.evidence(id,target),LinkedHashMap.class);evidence.put("plan_sha256",ledger.preview(id,target,0,1).get("plan_sha256").getAsString());yield evidence;}
            case "data_set"->{if(!id.matches("set-[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid set ID");var proof=json(storage().resolve("test-data-sets").resolve(id+".json"));target(binding,proof);var t=new TestDataLedger.Target((String)binding.get("project"),(String)binding.get("base_uuid"));var service=sets();var preview=JSON.fromJson(service.preview(id,(String)proof.get("data_run_id"),t),LinkedHashMap.class);yield AgentBrief.select(preview,"set_id","data_run_id","phase","state","plan_sha256","cleanup_order");}
            case "import"->{UUID.fromString(id);var proof=json(new NativeInfobaseGateway().stateRoot().resolve(id).resolve("import-result.json"));if(!binding.get("project").equals(proof.get("project")))throw new IllegalStateException("Import project differs");yield AgentBrief.select(proof,"project","import_id","mode","snapshot_project","snapshot_path","snapshot_hash","snapshot_removed","disposable_test_project");}
            case "database"-> {yield new OwnedFileBaseService().preview(id,(String)binding.get("project"),(String)binding.get("base_uuid"));}
            case "audit_project"->{requireAuditProject(binding,id);var p=ResourcesPlugin.getWorkspace().getRoot().getProject(id);if(!p.exists()||p.getLocation()==null)throw new IllegalStateException("Audit project unavailable");yield com.codepilot1c.core.edt.metadata.EdtNativeProjects.cleanupCreationAudit(id,p.getLocation().toString(),true);}
            default->throw new IllegalArgumentException("Unsupported task resource");
        };
    }
    static boolean legacyFileRunMatches(Map<String,Object> binding,Map<String,Object> run,String connection,String uuid)throws Exception {
        if(run.containsKey("base_identity_format")||!Objects.equals(binding.get("project"),run.get("project"))||!Objects.equals(binding.get("base_uuid"),uuid))return false;
        String canonical=InfobasePaths.connectionIdentity(connection);
        if(!canonical.startsWith("file:")||!Objects.equals(binding.get("base_identity_sha256"),InfobasePaths.hash(canonical)))return false;
        Path path=Path.of(canonical.substring(5)).toRealPath();
        String previous=InfobasePaths.hash(path.toString().toLowerCase(Locale.ROOT));
        return previous.equals(run.get("base_identity_sha256"));
    }
    static void requireAuditProject(Map<String,Object> binding,String project){if(!project.equals(binding.get("project")))throw new IllegalStateException("Audit project belongs to another task target");}
    private TestDataSetService sets()throws Exception{return new TestDataSetService(storage().resolve("test-data-sets"),new TestDataLedger(storage().resolve("test-data")),new TestDataCreateService.NativeBackend());}
    private Map<String,Object> cleanupJournal(Map<String,Object>b,String id,Map<String,Object>preview)throws Exception {
        var target=new TestDataLedger.Target((String)b.get("project"),(String)b.get("base_uuid"));var ledger=new TestDataLedger(storage().resolve("test-data"));
        var plan=ledger.preview(id,target,0,100);if(!Objects.equals(preview.get("plan_sha256"),plan.get("plan_sha256").getAsString()))throw new IllegalStateException("Journal cleanup plan changed");
        if(plan.get("pending_creations").getAsInt()>0)throw new IllegalStateException("Pending creation blocks teardown");
        var cleanup=new TestDataCleanupService(ledger,new TestDataCreateService.NativeBackend());
        for(int offset=0;;offset+=100) {
            var page=ledger.list(id,target,offset,100);
            for(JsonElement raw:page.getAsJsonArray("records")) {
                var record=raw.getAsJsonObject();String recordId=record.get("record_id").getAsString();var prior=ledger.cleanupForRecord(id,target,recordId);
                if(prior!=null) {var status=cleanup.resumePrepared(id,target,prior.get("cleanup_id").getAsString());if("completed".equals(status.get("state").getAsString()))continue;return Map.of("kind","data_journal","id",id,"cleanup_verified",false,"details",JSON.fromJson(status,LinkedHashMap.class));}
                if(!record.has("host_id")||!record.has("ownership")||!"native_host_confirmed".equals(record.get("ownership").getAsString()))throw new IllegalStateException("Unverified record retained; use original data commands for review");
                var current=ledger.preview(id,target,0,1);var operation=cleanup.start(id,target,"task-cleanup-"+id+recordId,record.get("host_id").getAsString(),recordId,current.get("plan_sha256").getAsString());
                return Map.of("kind","data_journal","id",id,"cleanup_verified",false,"details",JSON.fromJson(operation,LinkedHashMap.class));
            }
            if(!page.get("has_more").getAsBoolean())break;
        }
        var current=ledger.preview(id,target,0,1);ledger.close(id,target,current.get("plan_sha256").getAsString());
        return Map.of("kind","data_journal","id",id,"cleanup_verified",true,"cleanup_mode","mark_deletion","physical_record_deletion",false);
    }
    public Map<String,Object> cleanup(Map<String,Object>b,String kind,String id,Map<String,Object>preview)throws Exception {
        // Native implementations re-check hashes, target identity and ownership at application time.
        boolean complete=false;Map<String,Object> details=new LinkedHashMap<>();
        switch(kind) {
            case "run"->{var current=DevelopmentRunService.getInstance().status(id,0);if(!Boolean.TRUE.equals(current.get("terminal")))current=DevelopmentRunService.getInstance().cancel(id);var brief=AgentBrief.run(current,0);complete=Boolean.TRUE.equals(current.get("terminal"))&&Boolean.TRUE.equals(brief.get("owned_processes_closed"));details.putAll(brief);}
            case "host"->{details.putAll(new NativeHostService().cleanup(id));complete=Boolean.TRUE.equals(details.get("cleaned"));}
            case "data_journal"->{return cleanupJournal(b,id,preview);}
            case "data_set"->{
                var target=new TestDataLedger.Target((String)b.get("project"),(String)b.get("base_uuid"));String run=(String)preview.get("data_run_id");var service=sets();
                var state=service.cleanupTaskStep(id,run,target,(String)preview.get("plan_sha256"));
                details.putAll(JSON.fromJson(state,LinkedHashMap.class));complete="cleanup".equals(details.get("phase"))&&"completed".equals(details.get("state"));
                details.put("physical_data_deletion",false);details.put("cleanup_mode","mark_deletion");
            }
            case "import"->{details.putAll(new ProjectFromApplicationService().cleanup(id));complete=Boolean.TRUE.equals(details.get("snapshot_removed"));}
            case "database"->{details.putAll(new OwnedFileBaseService().cleanup(id,(String)b.get("project"),(String)b.get("base_uuid"),(String)preview.get("plan_sha256")));complete=Boolean.TRUE.equals(details.get("cleanup_verified"));}
            case "audit_project"->{String path=(String)preview.get("project_path");if(path==null)path=(String)preview.get("path");if(path==null)throw new IllegalStateException("Native audit preview lacks exact path");details.putAll(com.codepilot1c.core.edt.metadata.EdtNativeProjects.cleanupCreationAudit(id,path,false));complete=Boolean.TRUE.equals(details.get("removed"));}
            default->throw new IllegalArgumentException("Unsupported task resource");
        }
        var out=new LinkedHashMap<String,Object>();out.put("kind",kind);out.put("id",id);out.put("cleanup_verified",complete);out.put("details",details);return out;
    }
}
