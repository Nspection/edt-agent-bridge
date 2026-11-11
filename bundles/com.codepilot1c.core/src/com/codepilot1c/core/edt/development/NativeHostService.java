// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.NullProgressMonitor;
import com.google.gson.*;
import com.codepilot1c.core.edt.metadata.*;
import com.codepilot1c.core.edt.forms.*;
import com.codepilot1c.core.edt.external.*;
import com.codepilot1c.core.edt.infobase.NativeInfobaseGateway;
import com.codepilot1c.core.edt.runtime.*;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder.ThickClientMode;
/** Owns native-created temporary artifacts. No mutation of startup modules or shared configuration settings. */
public final class NativeHostService {
    private static final Gson JSON=new Gson();
    private static final Map<String,NativeHostChannel> CHANNELS=new ConcurrentHashMap<>();
    private static final Map<String,Process> PROCESSES=new ConcurrentHashMap<>();
    private static final Map<String,Object> LOCKS=new ConcurrentHashMap<>();
    private final Path root;
    private final EdtMetadataService metadata=new EdtMetadataService();
    private final EdtRuntimeService runtime=new EdtRuntimeService();
    private final NativeInfobaseGateway bases=new NativeInfobaseGateway();
    public NativeHostService() throws Exception {
        root=ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/native-host");Files.createDirectories(root);
    }
    private Path dir(String id){if(!id.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid host_id");return root.resolve(id);}
    private JsonObject read(String id)throws Exception{return JsonParser.parseString(Files.readString(dir(id).resolve("host.json"))).getAsJsonObject();}
    private void write(String id,JsonObject s)throws Exception{Files.writeString(dir(id).resolve("host.json"),JSON.toJson(s),StandardCharsets.UTF_8);}
    private String string(JsonObject s,String key){return s.get(key).getAsString();}
    public Map<String,Object> prepare(Map<String,Object> a)throws Exception{return prepare(a,id->{});}
    public Map<String,Object> prepare(Map<String,Object> a,java.util.function.Consumer<String> reserved)throws Exception{
        a=NativePackageManager.getInstance().resolve(a);
        String project=RuntimeExecutionPlan.required(a,"project"),mode=RuntimeExecutionPlan.required(a,"mode");
        if(!Set.of("external","metadata").contains(mode))throw new IllegalArgumentException("mode must be external/metadata");
        Path nativePath=Path.of(RuntimeExecutionPlan.required(a,"native_path")).toRealPath();
        if(!nativePath.getFileName().toString().endsWith(".zip")||Files.size(nativePath)>16*1024*1024)throw new IllegalArgumentException("Expected a native ZIP up to 16 MiB");
        var gateway=new EdtMetadataGateway();new MetadataProjectReadinessChecker(gateway).ensureReady(gateway.resolveProject(project));
        String expected=RuntimeExecutionPlan.required(a,"expected_native_sha256");if(!fileHash(nativePath).equals(expected))throw new IllegalArgumentException("Native ZIP SHA256 mismatch");
        var base=runtime.resolveDefaultInfobase(project);if(base==null)throw new IllegalStateException("Bind an infobase to the project first");
        String id=UUID.randomUUID().toString().replace("-",""),name="BridgeAgentHost"+id.substring(0,12),external=name+"External";
        Path folder=dir(id);Files.createDirectory(folder);privateFile(folder);
        JsonObject s=new JsonObject();s.addProperty("host_id",id);s.addProperty("project",project);s.addProperty("mode",mode);s.addProperty("name",name);if(mode.equals("external"))s.addProperty("external_project",external);s.addProperty("base_uuid",base.getUuid().toString());s.addProperty("base_identity_sha256",com.codepilot1c.core.edt.infobase.InfobasePaths.hash(com.codepilot1c.core.edt.infobase.InfobasePaths.connectionIdentity(base.getConnectionString().asConnectionString())));s.addProperty("state","preparing");write(id,s);reserved.accept(id);
        try{
            Path archive=folder.resolve("native.zip");Files.copy(nativePath,archive);s.addProperty("native_sha256",expected);
            if(mode.equals("external")){
                var externalService=new EdtExternalObjectService();
                externalService.createProcessing(new ExternalCreateProcessingRequest(project,external,name,folder.resolve("external-project").toString(),null,name,"Owned temporary runtime host "+id));
                s.addProperty("external_created",true);write(id,s);
                createForm(external,"ExternalDataProcessor."+name,true);s.addProperty("external_hash",treeHash(folder.resolve("external-project/src")));write(id,s);
                var artifact=externalService.build(project,external,"ExternalDataProcessor."+name);
                s.addProperty("artifact_path",Objects.toString(artifact.get("artifact_path")));s.addProperty("artifact_sha256",Objects.toString(artifact.get("artifact_sha256")));
                s.addProperty("external_hash",treeHash(folder.resolve("external-project/src")));s.addProperty("launch_strategy","external_epf");
            }else{
                s.addProperty("host_layout_version",2);s.addProperty("launch_strategy","internal_url");
                metadata.createMetadata(new CreateMetadataRequest(project,MetadataKind.DATA_PROCESSOR,name,name,"Owned temporary runtime host "+id,Map.of()));
                s.addProperty("metadata_created",true);s.addProperty("metadata_hash",treeHash(objectDirectory(project,name)));write(id,s);
                createForm(project,"DataProcessor."+name,false);
                s.addProperty("metadata_hash",treeHash(objectDirectory(project,name)));write(id,s);
            }
            s.addProperty("state","prepared");write(id,s);
            return status(id);
        }catch(Exception e){s.addProperty("state","prepare_failed");write(id,s);throw new IllegalStateException("host_id="+id+" preparation failed: "+e.getMessage(),e);}
    }
    private void createForm(String project,String owner,boolean bootstrap)throws Exception{
        var form=metadata.createForm(new CreateFormRequest(project,owner,"Host",FormUsage.OBJECT,true,true,"EDT Agent Host","Temporary MCP executor",60000L));
        metadata.updateFormModel(new UpdateFormModelRequest(project,form.formFqn(),List.of(Map.of("op","set_event_handler","target","form","event","OnOpen","handler_name","HostOpen"))));
        String source;try(var input=getClass().getResourceAsStream("/resources/agent-host/HostForm.bsl")){
            if(input==null)throw new IOException("Host form template missing");source=new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("__BOOTSTRAP__",bootstrap?"Истина":"Ложь");
        }
        var module=metadata.ensureModuleArtifact(new EnsureModuleArtifactRequest(project,form.formFqn(),ModuleArtifactKind.MODULE,true,""));
        IFile file=ResourcesPlugin.getWorkspace().getRoot().getFile(new org.eclipse.core.runtime.Path(module.modulePath()));
        try(var input=new ByteArrayInputStream(source.replace("\r\n","\n").replace("\n","\r\n").getBytes(StandardCharsets.UTF_8))){file.setContents(input,IResource.FORCE,new NullProgressMonitor());}
        file.getProject().refreshLocal(IResource.DEPTH_INFINITE,new NullProgressMonitor());
    }
    private void ensureProjectReady(JsonObject s){
        var gateway=new EdtMetadataGateway();new MetadataProjectReadinessChecker(gateway).ensureReady(gateway.resolveProject(string(s,"project")));
    }
    private void baseCheck(JsonObject s){
        var current=runtime.resolveDefaultInfobase(string(s,"project"));if(current==null||!Objects.equals(current.getUuid().toString(),string(s,"base_uuid")))throw new IllegalStateException("Project default infobase changed; host operation refused");
        if(s.has("base_identity_sha256")&&!s.get("base_identity_sha256").getAsString().equals(com.codepilot1c.core.edt.infobase.InfobasePaths.hash(com.codepilot1c.core.edt.infobase.InfobasePaths.connectionIdentity(current.getConnectionString().asConnectionString()))))throw new IllegalStateException("Host infobase connection changed; operation refused");
    }
    public Map<String,Object> start(String id)throws Exception{
        synchronized(LOCKS.computeIfAbsent(id,k->new Object())){
            JsonObject s=read(id);if(CHANNELS.containsKey(id))return status(id);
            if(s.has("cleanup_in_progress"))throw new IllegalStateException("Cleanup is pending; finish cleanup before starting");
            if(!Set.of("prepared","stopped").contains(string(s,"state")))throw new IllegalStateException("Host is not prepared/stopped");
            ensureProjectReady(s);baseCheck(s);
            if(string(s,"mode").equals("metadata")){
                if(!string(s,"metadata_hash").equals(treeHash(objectDirectory(string(s,"project"),string(s,"name")))))throw new IllegalStateException("Temporary metadata was edited; review before starting");
                boolean reuse=s.has("loaded_into_base")&&s.get("loaded_into_base").getAsBoolean()&&Boolean.TRUE.equals(runtime.synchronizationState(string(s,"project")).get("configuration_synchronized"));
                s.addProperty("base_update_skipped",reuse);
                if(!reuse){
                    s.addProperty("state","updating_base");write(id,s);
                    try{bases.update(string(s,"project"),runtime.resolveDefaultInfobase(string(s,"project")),new NullProgressMonitor());s.addProperty("loaded_into_base",true);}
                    catch(Exception e){s.addProperty("state","update_failed");write(id,s);throw e;}
                }
            }
            launch(id,s,string(s,"mode").equals("metadata"));return status(id);
        }
    }
    private void launch(String id,JsonObject s,boolean inMetadata)throws Exception{
        Path folder=dir(id),artifact=inMetadata?null:Path.of(string(s,"artifact_path"));
        if((artifact!=null&&!fileHash(artifact).equals(string(s,"artifact_sha256")))||!fileHash(folder.resolve("native.zip")).equals(string(s,"native_sha256")))throw new IllegalStateException("Host artifact changed");
        NativeHostChannel channel=new NativeHostChannel(folder.resolve("requests"));CHANNELS.put(id,channel);
        JsonObject manifest=new JsonObject();manifest.addProperty("port",Integer.toString(channel.port()));manifest.addProperty("token",channel.token());manifest.addProperty("native_path",folder.resolve("native.zip").toString());manifest.addProperty("metadata_form",inMetadata?"Обработка."+string(s,"name")+".Форма.Host":"");
        Path config=folder.resolve("session.json");Files.deleteIfExists(folder.resolve("session.json.stage"));Files.writeString(config,manifest.toString(),StandardCharsets.UTF_8);privateFile(config);
        try{
            String project=string(s,"project");var base=runtime.resolveDefaultInfobase(project);var info=runtime.resolveThickClientInfo(base);
            var builder=new RuntimeExecutionCommandBuilder(info.component().getFile(),ThickClientMode.ENTERPRISE);
            builder.forInfobase(base.getConnectionString(),false);runtime.applyAccessSettings(builder,runtime.resolveAccessSettings(base));if(inMetadata)builder.urlFragment("e1cib/app/Обработка."+string(s,"name"));else builder.execute(artifact.toString());builder.startupOption("EDTAgentHost="+config);builder.disableStartupDialogs();builder.disableStartupMessages();builder.logTo(folder.resolve("client.log").toFile(),true);
            ProcessBuilder process=builder.toProcessBuilder();process.directory(folder.toFile());process.environment().clear();process.environment().putAll(RunnerEnvironment.isolated(System.getenv()));process.redirectErrorStream(true);process.redirectOutput(folder.resolve("process.log").toFile());
            Process child=process.start();PROCESSES.put(id,child);s.addProperty("pid",child.pid());s.addProperty("process_start",child.info().startInstant().orElseThrow().toString());s.addProperty("state","starting");write(id,s);
        }catch(Exception e){CHANNELS.remove(id);channel.close();s.addProperty("state","start_failed");write(id,s);throw e;}
    }
    /** Identity check only; never launches/restarts a client or reads access credentials. */
    public TestDataLedger.Target testDataTarget(String id,boolean requireRunning)throws Exception {
        synchronized(LOCKS.computeIfAbsent(id,k->new Object())) {
            JsonObject state=read(id);baseCheck(state);
            if(requireRunning) {
                if(state.has("cleanup_in_progress") || state.has("cleaned"))throw new IllegalStateException("Host cleanup is pending/completed");
                NativeHostChannel channel=CHANNELS.get(id);
                Process client=PROCESSES.get(id);
                if(channel==null || !Boolean.TRUE.equals(channel.status().get("connected")) || Boolean.TRUE.equals(channel.status().get("stopping")) || client==null || !client.isAlive())throw new IllegalStateException("Host must already be running and connected");
            }
            return new TestDataLedger.Target(string(state,"project"),string(state,"base_uuid"));
        }
    }
    public Map<String,Object> assertionTarget(String id,boolean running)throws Exception {
        var target=testDataTarget(id,running);var state=read(id);if(!state.has("base_identity_sha256"))throw new IllegalStateException("Legacy host lacks connection proof; prepare a new host");
        return Map.of("project",target.project(),"base_uuid",target.baseUuid(),"base_identity_sha256",state.get("base_identity_sha256").getAsString());
    }
    @SuppressWarnings("unchecked") public Map<String,Object> execute(String id,Map<String,Object> a)throws Exception{
        JsonObject s=read(id);baseCheck(s);if(s.has("cleanup_in_progress"))throw new IllegalStateException("Host cleanup is in progress");NativeHostChannel channel=CHANNELS.get(id);if(channel==null)throw new IllegalStateException("Host not connected; inspect status before restarting");
        String actualState=Objects.toString(status(id).get("state"),"");
        if(!actualState.equals("running"))throw new IllegalStateException("Host is not ready for execution: "+actualState);
        String mode=(String)a.getOrDefault("execution_mode","execute");
        var payload=new HashMap<String,Object>(a);payload.put("mode",mode);String context=(String)a.getOrDefault("context","server");
        if(mode.equals("query")&&!context.equals("server"))throw new IllegalArgumentException("Queries require server context");
        if(mode.equals("batch")){String batch=NativeHostBatchPlan.payload(a);NativeHostBatchPlan.requireCapabilities(a,(Map<?,?>)channel.status().get("identity"));return channel.submit(RuntimeExecutionPlan.required(a,"idempotency_key"),"batch",batch,Map.of());}
        return channel.submit(RuntimeExecutionPlan.required(a,"idempotency_key"),context,RuntimeExecutionPlan.payload(payload),(Map<String,Object>)a.getOrDefault("parameters",Map.of()));
    }
    public Map<String,Object> snapshots(String id,String request)throws Exception{read(id);if(request!=null)result(id,request);var images=NativeHostChannel.snapshots(dir(id).resolve("requests"));return Map.of("host_id",id,"snapshots",images.stream().filter(image->request==null||request.equals(image.get("request_id"))).toList());}
    public Map<String,Object> snapshots(String id,String request,Map<String,Object> args)throws Exception {
        var options=com.codepilot1c.core.edt.editor.SnapshotAlignment.options(args);
        var output=new LinkedHashMap<>(snapshots(id,request));var images=new ArrayList<Map<String,Object>>();
        for(Object raw:(List<?>)output.get("snapshots")) {
            @SuppressWarnings("unchecked") var image=(Map<String,Object>)raw;
            images.add(com.codepilot1c.core.edt.editor.SnapshotAlignment.decorateOrReport(image,options));
        }
        output.put("snapshots",images);if(options.grid())output.put("alignment_all_succeeded",images.stream().noneMatch(image->image.containsKey("alignment_error")));return output;
    }

    public Map<String,Object> result(String id,String request)throws Exception{
        var channel=CHANNELS.get(id);if(channel!=null)return channel.result(request);
        // Use the same recovery rules as a reopened endpoint: cancellation is a known outcome.
        return NativeHostChannel.persistedResult(dir(id).resolve("requests"),request);
    }

    public Map<String,Object> status(String id)throws Exception{
        JsonObject s=read(id);var out=new LinkedHashMap<String,Object>();for(String k:List.of("host_id","project","mode","name","state","launch_strategy","external_project","loaded_into_base","base_update_skipped","source_removed","database_verified_absent","cleanup_in_progress","cleaned","database_removal_synchronized","external_project_removed","verification_host_id","verification_request_id","database_verification_strategy","verification_report_sha256","verification_inventory_sha256","verification_snapshot_sha256","verification_generation"))if(s.has(k))out.put(k,JSON.fromJson(s.get(k),Object.class));
        IProject external=s.has("external_project")?ResourcesPlugin.getWorkspace().getRoot().getProject(string(s,"external_project")):null;
        boolean registered=external!=null&&externalRegistered(external),files=Files.exists(dir(id).resolve("external-project"));
        out.put("external_registration_present",registered);out.put("external_files_present",files);
        if(s.has("cleaned") && s.get("cleaned").getAsBoolean() && (registered||files)){out.put("cleaned",false);out.put("state","cleanup_incomplete");}
        var channel=CHANNELS.get(id);if(channel!=null){out.put("channel",channel.status());if(Boolean.TRUE.equals(channel.status().get("connected"))&&!s.has("cleanup_in_progress"))out.put("state","running");}
        Path phase=dir(id).resolve("session.json.stage");if(Files.isRegularFile(phase)){String value=Files.readString(phase,StandardCharsets.UTF_8).replace("\uFEFF", "").trim();out.put("startup_phase",value);if(value.equals("initialization_failed")||value.equals("transport_failed"))out.put("state",value);}
        Process process=PROCESSES.get(id);if(process!=null&&!process.isAlive()){out.put("state","client_exited");out.put("exit_code",process.exitValue());}
        if(channel==null&&Set.of("starting","running").contains(string(s,"state")))out.put("state","interrupted_by_restart");return out;
    }
    public Map<String,Object> stop(String id)throws Exception{
        synchronized(LOCKS.computeIfAbsent(id,k->new Object())){
            JsonObject s=read(id);if(s.has("cleaned")&&s.get("cleaned").getAsBoolean())return status(id);stopClient(id,s);s.addProperty("state","stopped");write(id,s);return status(id);
        }
    }
    private void stopClient(String id,JsonObject s)throws Exception{
        var channel=CHANNELS.get(id);if(channel!=null)channel.requestStop();
        Process process=PROCESSES.get(id);
        if(process!=null){if(!process.waitFor(4,TimeUnit.SECONDS)){process.destroy();if(!process.waitFor(3,TimeUnit.SECONDS)){process.destroyForcibly();if(!process.waitFor(3,TimeUnit.SECONDS))throw new IllegalStateException("Own client did not stop");}}PROCESSES.remove(id);}
        else if(s.has("pid")){
            var handle=ProcessHandle.of(s.get("pid").getAsLong());if(handle.isPresent()&&handle.get().isAlive()){
                var h=handle.get();if(!h.info().startInstant().map(Object::toString).orElse("").equals(string(s,"process_start")))throw new IllegalStateException("PID ownership changed; refusing to stop another process");
                String command=h.info().commandLine().orElse("");if(!command.contains(dir(id).resolve("session.json").toString()))throw new IllegalStateException("Cannot verify old host process ownership");
                h.destroy();try{h.onExit().get(5,TimeUnit.SECONDS);}catch(TimeoutException e){h.destroyForcibly();h.onExit().get(5,TimeUnit.SECONDS);}
            }
        }
        CHANNELS.remove(id);if(channel!=null)channel.close();Files.deleteIfExists(dir(id).resolve("session.json"));
    }
    public Map<String,Object> cleanup(String id)throws Exception{return cleanup(id,null);}
    public Map<String,Object> cleanup(String id,String verifierId)throws Exception{
        if(verifierId!=null){
            dir(verifierId);if(id.equals(verifierId))throw new IllegalArgumentException("Cleanup verifier must be another running host");
            String first=id.compareTo(verifierId)<0?id:verifierId,second=id.compareTo(verifierId)<0?verifierId:id;
            synchronized(LOCKS.computeIfAbsent(first,k->new Object())){
                synchronized(LOCKS.computeIfAbsent(second,k->new Object())){return cleanupLocked(id,verifierId);}
            }
        }
        return cleanupLocked(id,null);
    }
    private Map<String,Object> cleanupLocked(String id,String verifierId)throws Exception{
        synchronized(LOCKS.computeIfAbsent(id,k->new Object())){
            JsonObject s=read(id);
            if(s.has("cleaned")&&s.get("cleaned").getAsBoolean()){
                cleanupExternalProject(id,s);return status(id);
            }
            if(s.has("metadata_created")){ensureProjectReady(s);baseCheck(s);}s.addProperty("cleanup_in_progress",true);write(id,s);stopClient(id,s);String project=string(s,"project"),name=string(s,"name");
            if(s.has("metadata_created")&&!s.has("source_removed")){
                Path object=objectDirectory(project,name);
                if(!s.has("source_delete_started")){
                    if(!s.has("metadata_hash")||!string(s,"metadata_hash").equals(treeHash(object)))throw new IllegalStateException("Owned metadata differs from recorded snapshot; keep it for review");
                    s.add("source_files",JSON.toJsonTree(OwnedHostSources.snapshot(object)));s.addProperty("source_delete_started",true);write(id,s);
                }
                @SuppressWarnings("unchecked") Map<String,String> sourceFiles=JSON.fromJson(s.get("source_files"),Map.class);
                OwnedHostSources.verifyRemaining(object,sourceFiles);
                if(metadataPresent(project,name)){
                    try{metadata.deleteMetadata(new DeleteMetadataRequest(project,"DataProcessor."+name,true));}
                    catch(MetadataOperationException error){if(metadataPresent(project,name))throw error;}
                }
                // Native refactoring can remove the model before Eclipse finishes deleting files.
                // Recheck every remainder against the pre-delete manifest and use workspace scheduling.
                if(Files.exists(object)){
                    OwnedHostSources.verifyRemaining(object,sourceFiles);
                    IProject owner=new EdtMetadataGateway().resolveProject(project);IFolder folder=owner.getFolder("src/DataProcessors/"+name);
                    try{owner.getWorkspace().run(monitor->{folder.refreshLocal(IResource.DEPTH_INFINITE,monitor);if(folder.exists())folder.delete(true,monitor);},owner,org.eclipse.core.resources.IWorkspace.AVOID_UPDATE,new NullProgressMonitor());}
                    catch(org.eclipse.core.runtime.CoreException error){if(metadataPresent(project,name))throw error;}
                    // Model deletion has been verified. Remove only byte-identical owned leftovers.
                    if(Files.exists(object))OwnedHostSources.deleteRemaining(object,sourceFiles);
                    owner.refreshLocal(IResource.DEPTH_INFINITE,new NullProgressMonitor());
                }
                if(Files.exists(object))throw new IllegalStateException("Metadata source remains after native delete");
                s.addProperty("source_removed",true);write(id,s);
            }
            if(s.has("metadata_created")&&!s.has("database_verified_absent")){
                if(verifierId!=null){
                    verifyAbsentUsingHost(id,s,verifierId);
                }else{
                s.addProperty("state","removing_from_base");write(id,s);
                // An interrupted verifier must not blindly repeat a successful FULL reload.
                boolean equal=Boolean.TRUE.equals(runtime.synchronizationState(project).get("configuration_synchronized"));
                if(!s.has("database_removal_synchronized")||!equal){
                    bases.update(project,runtime.resolveDefaultInfobase(project),new NullProgressMonitor());
                    s.addProperty("database_removal_synchronized",true);write(id,s);
                }
                if(s.has("host_layout_version")&&s.get("host_layout_version").getAsInt()>=2){
                    var comparison=bases.compareStoredConfigurations(project,dir(id).resolve("verification"),new NullProgressMonitor());
                    Path inventory=Path.of(comparison.get("configuration_export").toString()).resolve("Configuration.xml"),report=Path.of(comparison.get("comparison_report").toString());
                    NativeHostVerification.requireStoredAbsent(inventory,report,name);baseCheck(s);
                    s.addProperty("database_verified_absent",true);s.addProperty("database_verification_strategy","platform_main_database_comparison");s.addProperty("verification_snapshot_sha256",comparison.get("snapshot_sha256").toString());s.addProperty("verification_generation",comparison.get("configuration_generation").toString());s.addProperty("verification_report_sha256",fileHash(report));s.addProperty("verification_inventory_sha256",fileHash(inventory));write(id,s);
                }else{
                // Independent runtime lookup proves absence, rather than inferring it only from file deletion.
                launch(id,s,false);NativeHostChannel channel=CHANNELS.get(id);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
                try {
                while(!Boolean.TRUE.equals(channel.status().get("connected"))){
                    String phase=Objects.toString(status(id).get("startup_phase"),"");
                    Process child=PROCESSES.get(id);
                    if(phase.equals("initialization_failed")||child==null||!child.isAlive())throw new IllegalStateException("Cleanup verifier initialization failed; host_id="+id);
                    if(System.nanoTime()>deadline)throw new IllegalStateException("Cleanup verifier could not connect; host_id="+id);Thread.sleep(200);
                }
                var request=channel.submit("cleanup-verify-"+UUID.randomUUID(),"server","Результат = Метаданные.Обработки.Найти("+RuntimeExecutionPlan.literal(name)+") = Неопределено;",Map.of());
                String requestId=request.get("request_id").toString();Map<String,Object> result;
                do{result=channel.result(requestId);if(Boolean.TRUE.equals(result.get("terminal")))break;if(System.nanoTime()>deadline)throw new IllegalStateException("Cleanup verification timed out");Thread.sleep(200);}while(true);
                NativeHostVerification.requireAbsent(result);
                s.addProperty("database_verified_absent",true);write(id,s);
                } finally {
                    stopClient(id,s);s.addProperty("state",s.has("database_verified_absent")?"cleanup_pending":"cleanup_verification_failed");write(id,s);
                }
                }
                }
            }
            cleanupExternalProject(id,s);
            if(s.has("artifact_path")&&Files.exists(Path.of(string(s,"artifact_path"))))new EdtExternalObjectService().cleanupArtifact(string(s,"artifact_path"),string(s,"artifact_sha256"));
            // Keep only non-executable ownership tombstone; code, results and credentials are removed.
            try(var paths=Files.walk(dir(id))){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())if(!p.equals(dir(id))&&!p.equals(dir(id).resolve("host.json")))Files.delete(p);}
            s.remove("pid");s.remove("process_start");s.remove("cleanup_in_progress");s.addProperty("state","cleaned");s.addProperty("cleaned",true);write(id,s);return status(id);
        }
    }
    /** Reuses an already verified channel on the SAME base; no FULL reload or verifier restart. */
    private void verifyAbsentUsingHost(String id,JsonObject state,String verifierId)throws Exception {
        if(id.equals(verifierId))throw new IllegalArgumentException("Cleanup verifier must be another running host");
        String project=string(state,"project"),name=string(state,"name");
        if(Files.exists(objectDirectory(project,name))||metadataPresent(project,name))throw new IllegalStateException("Temporary source reappeared; preserve it for review");
        if(!Boolean.TRUE.equals(runtime.synchronizationState(project).get("configuration_synchronized")))throw new IllegalStateException("Existing-host verification requires a synchronized base; no automatic FULL reload");
        var verifier=assertionTarget(verifierId,true);
        NativeHostVerification.requireSameBase(string(state,"base_uuid"),string(state,"base_identity_sha256"),verifier);
        if(!"running".equals(status(verifierId).get("state")))throw new IllegalStateException("Cleanup verifier is not ready");
        NativeHostChannel channel=CHANNELS.get(verifierId);
        String code="Результат = Метаданные.Обработки.Найти("+RuntimeExecutionPlan.literal(name)+") = Неопределено;";
        var request=channel.submit("cleanup-absence-"+id,"server",code,Map.of());String requestId=request.get("request_id").toString();
        state.addProperty("verification_host_id",verifierId);state.addProperty("verification_request_id",requestId);write(id,state);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);Map<String,Object> result;
        do{
            result=channel.result(requestId);if(Boolean.TRUE.equals(result.get("terminal")))break;
            if(System.nanoTime()>deadline)throw new IllegalStateException("Existing-host cleanup verification timed out; inspect verification_request_id");
            Thread.sleep(200);
        }while(true);
        NativeHostVerification.requireAbsent(result);
        baseCheck(state);assertionTarget(verifierId,true);
        state.addProperty("database_verified_absent",true);state.addProperty("database_verification_strategy","existing_native_host");write(id,state);
    }
    /** Removes only the owned external project; never updates the base or claims DB verification. */
    public Map<String,Object> cleanupExternal(String id)throws Exception {
        synchronized(LOCKS.computeIfAbsent(id,k->new Object())) {
            JsonObject s=read(id);stopClient(id,s);
            if(!s.has("cleaned")){s.addProperty("state","stopped");s.addProperty("cleanup_in_progress",true);write(id,s);}
            cleanupExternalProject(id,s);write(id,s);return status(id);
        }
    }
    private boolean externalRegistered(IProject p) {
        var gateway=new EdtMetadataGateway();
        return p.exists() || gateway.getDtProjectManager().getDtProject(p)!=null || gateway.getV8ProjectManager().getProject(p)!=null;
    }
    private void cleanupExternalProject(String id,JsonObject s)throws Exception {
        Path external=dir(id).resolve("external-project").toAbsolutePath().normalize();
        if(!s.has("external_project")){if(Files.exists(external))throw new IllegalStateException("Unexpected external files; preserve for review");s.addProperty("external_project_removed",true);write(id,s);return;}
        IProject p=ResourcesPlugin.getWorkspace().getRoot().getProject(string(s,"external_project"));
        boolean registered=externalRegistered(p),files=Files.exists(external);
        if(registered){
            if(p.getLocation()==null||!p.getLocation().toFile().toPath().toAbsolutePath().normalize().equals(external))throw new IllegalStateException("External project ownership changed");
            if(files&&!p.getLocation().toFile().toPath().toRealPath().equals(external.toRealPath()))throw new IllegalStateException("External project location changed");
        }
        if(files){
            if(!s.has("external_delete_started")){
                if(!s.has("external_hash")||!string(s,"external_hash").equals(treeHash(external.resolve("src"))))throw new IllegalStateException("External project changed; preserve it for review");
                s.add("external_project_files",JSON.toJsonTree(OwnedHostSources.snapshot(external)));s.addProperty("external_delete_started",true);write(id,s);
            }
            @SuppressWarnings("unchecked") Map<String,String> snapshot=JSON.fromJson(s.get("external_project_files"),Map.class);
            OwnedHostSources.verifyRemaining(external,snapshot);
        }
        if(registered){
            stopOwnedProject(p);
            if(p.exists())p.getWorkspace().run(monitor->p.delete(false,true,monitor),p.getWorkspace().getRoot(),IWorkspace.AVOID_UPDATE,new NullProgressMonitor());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(externalRegistered(p)){
                if(System.nanoTime()>deadline)throw new IllegalStateException("Native external project registration remains; host_id="+id);
                Thread.sleep(100);
            }
        }
        if(files){
            @SuppressWarnings("unchecked") Map<String,String> snapshot=JSON.fromJson(s.get("external_project_files"),Map.class);
            OwnedHostSources.deleteRemaining(external,snapshot);
        }
        if(externalRegistered(p)||Files.exists(external))throw new IllegalStateException("External sources or registration remain");
        s.addProperty("external_project_removed",true);write(id,s);
    }
    private void stopOwnedProject(IProject project)throws Exception{
        var context=org.eclipse.core.runtime.Platform.getBundle("com._1c.g5.v8.dt.core").getBundleContext();
        var reference=context.getServiceReference(com._1c.g5.v8.dt.core.platform.IWorkspaceOrchestrator.class);
        if(reference==null)throw new IllegalStateException("Native EDT workspace orchestrator unavailable; preserve owned project");
        var orchestrator=context.getService(reference);
        try{
            if(orchestrator==null)throw new IllegalStateException("Native EDT workspace orchestrator stopped");
            var gateway=new EdtMetadataGateway();var model=gateway.getDtProjectManager().getDtProject(project);
            // An orphan Eclipse registration can outlive both EDT models and its files.
            if(model==null&&gateway.getV8ProjectManager().getProject(project)==null)return;
            orchestrator.stopWorkspaceProjects(List.of(new com._1c.g5.v8.dt.core.lifecycle.WorkspaceProjectStopRequest(project,com._1c.g5.v8.dt.core.lifecycle.ProjectStopType.DELETE)),new NullProgressMonitor());
            if(model!=null)orchestrator.waitUntilStopped(model,java.time.Duration.ofSeconds(30));
        }finally{context.ungetService(reference);}
    }
    private boolean metadataPresent(String project,String name){
        try{metadata.previewDelete(project,"DataProcessor."+name);return true;}
        catch(MetadataOperationException e){if(e.getCode()==MetadataOperationCode.METADATA_NOT_FOUND)return false;throw e;}
    }
    private Path objectDirectory(String project,String name){return new EdtMetadataGateway().resolveProject(project).getLocation().toFile().toPath().resolve("src/DataProcessors").resolve(name);}
    private static String fileHash(Path p)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
    private static String treeHash(Path directory)throws Exception{
        if(!Files.isDirectory(directory))throw new IllegalStateException("Owned source directory missing");StringBuilder value=new StringBuilder();
        try(var files=Files.walk(directory)){for(Path p:files.filter(Files::isRegularFile).sorted().toList()){if(Files.isSymbolicLink(p))throw new IllegalStateException("Symlink in owned sources");value.append(directory.relativize(p)).append(':').append(fileHash(p)).append('\n');}}
        return NativeHostChannel.hash(value.toString());
    }
    private static void privateFile(Path path)throws Exception{
        var acl=Files.getFileAttributeView(path,AclFileAttributeView.class);if(acl!=null){var owner=Files.getOwner(path);acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(Files.isDirectory(path)?EnumSet.of(AclEntryFlag.FILE_INHERIT,AclEntryFlag.DIRECTORY_INHERIT):EnumSet.noneOf(AclEntryFlag.class)).build()));}
        else Files.setPosixFilePermissions(path,Files.isDirectory(path)?PosixFilePermissions.fromString("rwx------"):PosixFilePermissions.fromString("rw-------"));
    }
}
