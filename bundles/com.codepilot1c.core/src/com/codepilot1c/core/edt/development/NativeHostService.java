// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.io.*;
import java.time.Instant;
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
    private static final Map<String,Thread> REFRESH_THREADS=new ConcurrentHashMap<>();
    private static final Map<String,Object> LOCKS=new ConcurrentHashMap<>();
    private final long lifecycle=RuntimeProcessLifecycle.global().epoch();
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
    /** Only the pre-reservation readiness gate may prove that no host/process was allocated. */
    public static final class PreparationNotStarted extends IllegalStateException {
        private final Map<String,Object> result;
        PreparationNotStarted(String project,MetadataOperationException cause){super("Native project readiness failed before host reservation",cause);result=Map.ofEntries(
            Map.entry("state","blocked"),Map.entry("phase","before_host_reservation"),Map.entry("project",project),Map.entry("reason","project_not_ready"),
            Map.entry("execution_state","not_started"),Map.entry("retry_safe",cause.isRecoverable()),Map.entry("outcome_unknown",false),
            Map.entry("host_allocated",false),Map.entry("host_resources_created",false),Map.entry("automatic_retry",false),Map.entry("code",cause.getCode().name()),Map.entry("external_project_created",false),Map.entry("process_started",false),Map.entry("database_updated",false),
            Map.entry("recovery","Verify project readiness and original job result before a new prepare; no automatic retry or database update"));}
        public Map<String,Object> result(){return result;}
    }
    static void readinessBeforeReservation(String project,Runnable readiness){try{readiness.run();}catch(MetadataOperationException e){if(e.getCode()==MetadataOperationCode.PROJECT_NOT_READY)throw new PreparationNotStarted(project,e);throw e;}}
    public Map<String,Object> prepare(Map<String,Object> a)throws Exception{return prepare(a,id->{});}
    public Map<String,Object> prepare(Map<String,Object> a,java.util.function.Consumer<String> reserved)throws Exception{
        boolean customNative=a.containsKey("native_path")&&!Boolean.TRUE.equals(a.get("managed_refresh"));
        a=NativePackageManager.getInstance().resolve(a);
        String project=RuntimeExecutionPlan.required(a,"project"),mode=RuntimeExecutionPlan.required(a,"mode");
        if(!Set.of("external","metadata").contains(mode))throw new IllegalArgumentException("mode must be external/metadata");
        Path nativePath=Path.of(RuntimeExecutionPlan.required(a,"native_path")).toRealPath();
        if(!nativePath.getFileName().toString().endsWith(".zip")||Files.size(nativePath)>16*1024*1024)throw new IllegalArgumentException("Expected a native ZIP up to 16 MiB");
        var gateway=new EdtMetadataGateway();readinessBeforeReservation(project,()->new MetadataProjectReadinessChecker(gateway).ensureReady(gateway.resolveProject(project)));
        String expected=RuntimeExecutionPlan.required(a,"expected_native_sha256");if(!fileHash(nativePath).equals(expected))throw new IllegalArgumentException("Native ZIP SHA256 mismatch");
        var base=runtime.resolveDefaultInfobase(project);if(base==null)throw new IllegalStateException("Bind an infobase to the project first");
        if(a.containsKey("expected_base_uuid")&&(!a.get("expected_base_uuid").equals(base.getUuid().toString())||!a.get("expected_base_identity_sha256").equals(com.codepilot1c.core.edt.infobase.InfobasePaths.hash(com.codepilot1c.core.edt.infobase.InfobasePaths.connectionIdentity(base.getConnectionString().asConnectionString())))))throw new IllegalStateException("Refresh target changed before host reservation");
        String id=UUID.randomUUID().toString().replace("-",""),name="BridgeAgentHost"+id.substring(0,12),external=name+"External";
        Path folder=dir(id);Files.createDirectory(folder);privateFile(folder);
        JsonObject s=new JsonObject();s.addProperty("host_id",id);s.addProperty("project",project);s.addProperty("mode",mode);s.addProperty("name",name);if(mode.equals("external"))s.addProperty("external_project",external);s.addProperty("base_uuid",base.getUuid().toString());s.addProperty("base_identity_sha256",com.codepilot1c.core.edt.infobase.InfobasePaths.hash(com.codepilot1c.core.edt.infobase.InfobasePaths.connectionIdentity(base.getConnectionString().asConnectionString())));s.addProperty("state","preparing");s.addProperty("native_origin",customNative?"custom":"managed");s.addProperty("native_release",customNative?"custom":NativePackageService.bundled().releaseTag());s.addProperty("host_template_sha256",templateHash());if(a.containsKey("refresh_parent"))s.addProperty("refresh_parent",a.get("refresh_parent").toString());write(id,s);reserved.accept(id);
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
    private void baseCheck(JsonObject s){verifiedBase(s);}
    private com._1c.g5.v8.dt.platform.services.model.InfobaseReference verifiedBase(JsonObject s){
        var current=runtime.resolveDefaultInfobase(string(s,"project"));if(current==null||!Objects.equals(current.getUuid().toString(),string(s,"base_uuid")))throw new IllegalStateException("Project default infobase changed; host operation refused");
        if(s.has("base_identity_sha256")&&!s.get("base_identity_sha256").getAsString().equals(com.codepilot1c.core.edt.infobase.InfobasePaths.hash(com.codepilot1c.core.edt.infobase.InfobasePaths.connectionIdentity(current.getConnectionString().asConnectionString()))))throw new IllegalStateException("Host infobase connection changed; operation refused");
        return current;
    }
    public Map<String,Object> start(String id)throws Exception{
        synchronized(LOCKS.computeIfAbsent(id,k->new Object())){
            JsonObject s=read(id);requireParentRefresh(s);if(s.has("refresh_in_progress")||Files.exists(refreshFile(id)))throw new IllegalStateException("Host refresh reserved; inspect refresh_status");if(CHANNELS.containsKey(id))return status(id);
            if(s.has("cleanup_in_progress"))throw new IllegalStateException("Cleanup is pending; finish cleanup before starting");
            if(!Set.of("prepared","stopped").contains(string(s,"state")))throw new IllegalStateException("Host is not prepared/stopped");
            ensureProjectReady(s);baseCheck(s);
            if(string(s,"mode").equals("metadata")){
                if(!string(s,"metadata_hash").equals(treeHash(objectDirectory(string(s,"project"),string(s,"name")))))throw new IllegalStateException("Temporary metadata was edited; review before starting");
                boolean reuse=s.has("loaded_into_base")&&s.get("loaded_into_base").getAsBoolean()&&Boolean.TRUE.equals(runtime.synchronizationState(string(s,"project")).get("configuration_synchronized"));
                s.addProperty("base_update_skipped",reuse);
                if(!reuse){
                    s.addProperty("state","updating_base");write(id,s);
                    try{bases.update(string(s,"project"),verifiedBase(s),new NullProgressMonitor());s.addProperty("loaded_into_base",true);}
                    catch(Exception e){s.addProperty("state","update_failed");write(id,s);throw e;}
                }
            }
            if(string(s,"mode").equals("external")){var lease=RuntimeConsentPolicy.claimOrRequire("edt_runtime_host",Map.of("host_id",id),consentScope(id));s.add("consent_lease",JSON.toJsonTree(lease));write(id,s);}
            launch(id,s,string(s,"mode").equals("metadata"));return status(id);
        }
    }
    public Map<String,Object> consentScope(String id)throws Exception {
        JsonObject s=read(id);baseCheck(s);if(!"external".equals(string(s,"mode")))throw new IllegalArgumentException("This consent covers native external mode only; metadata FULL updates are separate");
        if(!fileHash(Path.of(string(s,"artifact_path"))).equals(string(s,"artifact_sha256"))||!fileHash(dir(id).resolve("native.zip")).equals(string(s,"native_sha256"))||!string(s,"external_hash").equals(treeHash(dir(id).resolve("external-project/src"))))throw new IllegalStateException("Owned Native artifact changed");
        return RuntimeConsentPolicy.scope(string(s,"project"),"native_external",Map.of("artifact_path",Path.of(string(s,"artifact_path")).toRealPath().toString(),"artifact_sha256",string(s,"artifact_sha256"),"native_sha256",string(s,"native_sha256"),"host_template_sha256",string(s,"host_template_sha256")));
    }
    public Map<String,Object> consent(Map<String,Object> args)throws Exception {String action=RuntimeExecutionPlan.required(args,"action");if(action.startsWith("consent_revoke"))return RuntimeConsentPolicy.operation("edt_runtime_host",Map.of(),Map.of(),args);String id=RuntimeExecutionPlan.required(args,"host_id");return RuntimeConsentPolicy.operation("edt_runtime_host",Map.of("host_id",id),consentScope(id),args);}
    private void requireConsent(String id,JsonObject state)throws Exception {if("external".equals(string(state,"mode"))){RuntimeConsentPolicy.assertToolAllowed("edt_runtime_host");RuntimeConsentPolicy.workspace().requireUse(consentScope(id),TaskSessionService.object(JSON.fromJson(state.get("consent_lease"),Map.class)));}}
    private Map<String,Object> startupContext(String id)throws Exception {
        JsonObject s=read(id);baseCheck(s);requireConsent(id,s);var bootChannel=CHANNELS.get(id);if(!"starting".equals(string(s,"state"))||bootChannel==null||Boolean.TRUE.equals(bootChannel.status().get("connected")))throw new IllegalStateException("Bootstrap is startup-only");var process=PROCESSES.get(id);if(process==null||!process.isAlive()||!process.info().startInstant().orElseThrow().equals(Instant.parse(string(s,"process_start"))))throw new IllegalStateException("Owned Native process changed");
        var info=process.info();var argv=info.arguments().map(List::of).orElseGet(()->{try{return ClientBindingVerifier.windowsArguments(process.pid(),info.command().orElseThrow(),info.startInstant().orElseThrow());}catch(Exception unavailable){throw new IllegalStateException("Native process target unavailable");}});
        var base=verifiedBase(s);if(!ClientBindingVerifier.processTarget(argv).equals(ClientBindingVerifier.targetHash(base.getConnectionString().asConnectionString())))throw new IllegalStateException("Native process base changed");
        var out=new LinkedHashMap<String,Object>();out.put("pid",process.pid());out.put("started",info.startInstant().orElseThrow().toString());out.put("executable",info.command().orElseThrow());out.put("capability","native_external");out.put("consent_scope",consentScope(id));out.put("consent_lease",JSON.fromJson(s.get("consent_lease"),Map.class));for(String k:List.of("artifact_path","artifact_sha256","native_sha256"))out.put(k,string(s,k));out.put("native_path",dir(id).resolve("native.zip").toString());out.put("module_caption",string(s,"name")+" (Owned temporary runtime host "+id+")");return out;
    }
    public Map<String,Object> startupInspect(String id)throws Exception {return StartupConsentService.inspect(dir(id),()->startupContext(id),"edt_runtime_host",Map.of("host_id",id));}
    public Map<String,Object> startupAnswer(Map<String,Object> args)throws Exception {String id=RuntimeExecutionPlan.required(args,"host_id");return StartupConsentService.answer(dir(id),()->startupContext(id),args);}
    public Map<String,Object> resume(String id)throws Exception {var s=read(id);requireConsent(id,s);StartupConsentService.requireNoUnknown(dir(id));if(!"running".equals(status(id).get("state")))startupContext(id);return status(id);}
    private void launch(String id,JsonObject s,boolean inMetadata)throws Exception{
        Path folder=dir(id),artifact=inMetadata?null:Path.of(string(s,"artifact_path"));
        if((artifact!=null&&!fileHash(artifact).equals(string(s,"artifact_sha256")))||!fileHash(folder.resolve("native.zip")).equals(string(s,"native_sha256")))throw new IllegalStateException("Host artifact changed");
        NativeHostChannel channel=RuntimeProcessLifecycle.global().create(lifecycle,folder+"/channel",()->new NativeHostChannel(folder.resolve("requests")));CHANNELS.put(id,channel);
        try{
        JsonObject manifest=new JsonObject();manifest.addProperty("port",Integer.toString(channel.port()));manifest.addProperty("token",channel.token());manifest.addProperty("native_path",folder.resolve("native.zip").toString());manifest.addProperty("metadata_form",inMetadata?"Обработка."+string(s,"name")+".Форма.Host":"");
        Path config=folder.resolve("session.json");Files.deleteIfExists(folder.resolve("session.json.stage"));Files.writeString(config,manifest.toString(),StandardCharsets.UTF_8);privateFile(config);
            String project=string(s,"project");var base=verifiedBase(s);var info=runtime.resolveThickClientInfo(base);
            var builder=new RuntimeExecutionCommandBuilder(info.component().getFile(),ThickClientMode.ENTERPRISE);
            builder.forInfobase(base.getConnectionString(),false);runtime.applyAccessSettings(builder,runtime.resolveAccessSettings(base));if(inMetadata)builder.urlFragment("e1cib/app/Обработка."+string(s,"name"));else builder.execute(artifact.toString());builder.startupOption("EDTAgentHost="+config);builder.disableStartupDialogs();builder.disableStartupMessages();builder.logTo(folder.resolve("client.log").toFile(),true);
            ProcessBuilder process=builder.toProcessBuilder();process.directory(folder.toFile());process.environment().clear();process.environment().putAll(RunnerEnvironment.isolated(System.getenv()));process.redirectErrorStream(true);process.redirectOutput(folder.resolve("process.log").toFile());
            requireConsent(id,s);baseCheck(s);RuntimeProcessLifecycle.global().launch(lifecycle,()->{Process child=process.start();RuntimeProcessLifecycle.global().ownProcess(lifecycle,folder+"/client",child);PROCESSES.put(id,child);s.addProperty("pid",child.pid());s.addProperty("process_start",child.info().startInstant().orElseThrow().toString());s.addProperty("state","starting");write(id,s);return child;});
        }catch(Exception e){Process child=PROCESSES.get(id);try{RuntimeProcessLifecycle.global().closeOwned(folder+"/client");}catch(Exception preserved){e.addSuppressed(preserved);}if(child!=null&&!child.isAlive())PROCESSES.remove(id,child);try{RuntimeProcessLifecycle.global().closeOwned(folder+"/channel");}catch(Exception preserved){e.addSuppressed(preserved);}CHANNELS.remove(id,channel);s.addProperty("state","start_failed");write(id,s);throw e;}
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
    public Map<String,Object> execute(String id,Map<String,Object> a)throws Exception{
        synchronized(LOCKS.computeIfAbsent(id,k->new Object())){return executeLocked(id,a);}
    }
    @SuppressWarnings("unchecked") private Map<String,Object> executeLocked(String id,Map<String,Object> a)throws Exception{
        JsonObject s=read(id);NativeExecutionPlan plan=NativeExecutionPlan.prepare(a);
        return NativeExecutionGate.execute(()->{
            Path requests=dir(id).resolve("requests");
            if(s.has("results_retained")&&!treeHash(requests).equals(string(s,"retained_results_sha256")))throw new IllegalStateException("Retained Native history changed");
            if(!Files.isDirectory(requests)&&!"prepared".equals(string(s,"state")))throw new IllegalStateException("Native history unavailable; inspect original operation");
            var active=CHANNELS.get(id);if(active!=null)return active.previous(plan);
            return NativeHostChannel.previous(requests,plan);
        },()->{
            requireParentRefresh(s);
            var observed=status(id);var current=TaskSessionService.object(observed.get("compatibility"));
            if(!Boolean.TRUE.equals(current.get("compatible")))throw new CompatibilityRequired(id,current);
            if(!Boolean.TRUE.equals(observed.get("consent_valid"))){requireConsent(id,s);StartupConsentService.requireNoUnknown(dir(id));}
            if(!Boolean.TRUE.equals(observed.get("execution_allowed")))throw new IllegalStateException("Host is not ready: "+observed.get("execution_blockers"));
            NativeHostChannel channel=CHANNELS.get(id);if(channel==null)throw new IllegalStateException("Host disconnected before dispatch");
            if("batch".equals(a.get("execution_mode")))NativeHostBatchPlan.requireCapabilities(a,(Map<?,?>)channel.status().get("identity"));
            return ()->channel.submit(plan);
        });
    }
    public Map<String,Object> snapshots(String id,String request)throws Exception{read(id);if(request!=null)result(id,request);var images=NativeHostChannel.snapshots(dir(id).resolve("requests"));return Map.of("host_id",id,"snapshots",images.stream().filter(image->request==null||request.equals(image.get("request_id"))).toList());}
    public Map<String,Object> snapshots(String id,String request,Map<String,Object> args)throws Exception {
        synchronized(LOCKS.computeIfAbsent(id,k->new Object())){
            JsonObject record=read(id);boolean retained=record.has("results_retained");
            if(retained&&!treeHash(dir(id).resolve("requests")).equals(string(record,"retained_results_sha256")))throw new IllegalStateException("Retained receipts changed; preserve for review");
        var options=com.codepilot1c.core.edt.editor.SnapshotAlignment.options(args);
        var output=new LinkedHashMap<>(snapshots(id,request));var images=new ArrayList<Map<String,Object>>();
        for(Object raw:(List<?>)output.get("snapshots")) {
            @SuppressWarnings("unchecked") var image=(Map<String,Object>)raw;
            images.add(com.codepilot1c.core.edt.editor.SnapshotAlignment.decorateOrReport(image,options));
        }
        output.put("snapshots",images);if(options.grid())output.put("alignment_all_succeeded",images.stream().noneMatch(image->image.containsKey("alignment_error")));if(retained){record.addProperty("retained_results_sha256",treeHash(dir(id).resolve("requests")));write(id,record);}return output;
        }
    }

    public Map<String,Object> result(String id,String request)throws Exception{
        var channel=CHANNELS.get(id);if(channel!=null)return channel.result(request);
        // Use the same recovery rules as a reopened endpoint: cancellation is a known outcome.
        return NativeHostChannel.persistedResult(dir(id).resolve("requests"),request);
    }

    public static final class CompatibilityRequired extends IllegalStateException{
        private final Map<String,Object> result;
        CompatibilityRequired(String id,Map<String,Object> compatibility){super("Native refresh required before dispatch");result=Map.of("host_id",id,"state","blocked","reason","native_refresh_required","execution_state","not_dispatched","retry_safe",true,"compatibility",compatibility,"next_call",Map.of("tool","edt.run","arguments",Map.of("op","edt_runtime_host","args",Map.of("action","ensure_current","host_id",id))));}
        public Map<String,Object> result(){return result;}
    }
    private static String templateSource()throws Exception{
        try(var in=NativeHostService.class.getResourceAsStream("/resources/agent-host/HostForm.bsl")){if(in==null)throw new IOException("Host template missing");return new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n");}
    }
    private static String templateHash()throws Exception{return NativeHostChannel.hash(templateSource());}
    public Map<String,Object> currentness(String id)throws Exception {
        JsonObject state=read(id);var descriptor=NativePackageService.bundled();boolean present=Files.isRegularFile(dir(id).resolve("native.zip"));
        String actual=present?fileHash(dir(id).resolve("native.zip")):"";boolean integrity=state.has("native_sha256")&&actual.equals(string(state,"native_sha256"));
        boolean binary=integrity&&descriptor.zipSha256().equals(actual);
        boolean template=false,owned=false;
        if(!state.has("source_removed")&&!state.has("cleaned")){
            boolean metadata=string(state,"mode").equals("metadata");Path source=metadata?objectDirectory(string(state,"project"),string(state,"name")):dir(id).resolve("external-project/src");
            if(Files.isDirectory(source)){
                owned=state.has(metadata?"metadata_hash":"external_hash")&&treeHash(source).equals(string(state,metadata?"metadata_hash":"external_hash"));
                try(var files=Files.walk(source,8)){
                    var modules=files.filter(p->p.getFileName().toString().equals("Module.bsl")&&p.getParent().getFileName().toString().equals("Host")).toList();
                    template=modules.size()==1&&Files.readString(modules.get(0),StandardCharsets.UTF_8).replace("\r\n","\n").equals(templateSource().replace("__BOOTSTRAP__",metadata?"Ложь":"Истина"));
                }
            }
        }
        boolean custom=state.has("native_origin")?"custom".equals(string(state,"native_origin")):integrity&&!binary;boolean compatible=owned&&template&&(binary||custom&&integrity);
        var out=new LinkedHashMap<String,Object>();out.put("state",compatible?(binary?"current":"custom_verified"):!present?"missing":"refresh_required");out.put("compatible",compatible);out.put("required_native_release",descriptor.releaseTag());out.put("actual_native_release",descriptor.zipSha256().equals(actual)?descriptor.releaseTag():state.has("native_release")?string(state,"native_release"):custom?"custom":"unknown");out.put("required_features",descriptor.features());out.put("actual_protocol",descriptor.zipSha256().equals(actual)?2:null);out.put("required_protocol",2);out.put("required_native_sha256",descriptor.zipSha256());out.put("actual_native_sha256",present?actual:null);out.put("recorded_native_sha256",state.has("native_sha256")?string(state,"native_sha256"):null);out.put("native_current",binary);out.put("template_current",template);out.put("owned_sources_unchanged",owned);out.put("required_template_sha256",templateHash());out.put("network_checked",false);out.put("custom_package",custom);out.put("native_integrity",integrity);
        if(!compatible)out.put("next_call",Map.of("tool","edt.run","arguments",Map.of("op","edt_runtime_host","args",Map.of("action","refresh_preview","host_id",id))));return out;
    }
    private void requireParentRefresh(JsonObject s)throws Exception{
        if(!s.has("refresh_parent"))return;String parent=string(s,"refresh_parent");
        if(REFRESH_THREADS.get(parent)==Thread.currentThread())return;
        if(!"completed".equals(refreshStatus(parent).get("state")))throw new IllegalStateException("Original refresh has not completed; inspect refresh_status before using new host");
    }
    private void requireNoConcurrentRefresh(String id,JsonObject s){
        String parent=s.has("refresh_parent")?string(s,"refresh_parent"):id;Thread owner=REFRESH_THREADS.get(parent);
        if(owner!=null&&owner!=Thread.currentThread())throw new IllegalStateException("Refresh is active; inspect its original job");
    }
    private Path refreshFile(String id){dir(id);return root.resolve("refresh-"+id+".json");}
    private void saveRefresh(String id,JsonObject value)throws Exception{Path file=refreshFile(id),temp=file.resolveSibling(file.getFileName()+".tmp");Files.writeString(temp,AgentResponses.JSON.toJson(value),StandardCharsets.UTF_8);Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    public Map<String,Object> refreshStatus(String id)throws Exception{
        if(!Files.exists(refreshFile(id)))return Map.of("host_id",id,"state","not_requested");
        JsonObject value=JsonParser.parseString(Files.readString(refreshFile(id))).getAsJsonObject();
        @SuppressWarnings("unchecked") var out=(Map<String,Object>)AgentResponses.JSON.fromJson(value,Map.class);
        out.put("stage",value.get("state").getAsString());
        if(!Set.of("completed","failed").contains(value.get("state").getAsString())){out.put("state",REFRESH_THREADS.containsKey(id)?"running":"outcome_unknown");out.put("automatic_resume",false);}
        out.put("terminal",Set.of("completed","failed").contains(value.get("state").getAsString())||!REFRESH_THREADS.containsKey(id));
        if(value.has("new_host_id"))out.put("next_call",Map.of("tool","edt.run","arguments",Map.of("op","edt_runtime_host","args",Map.of("action","status","host_id",value.get("new_host_id").getAsString()))));
        return out;
    }
    public Map<String,Object> refreshPreview(String id)throws Exception{
        synchronized(LOCKS.computeIfAbsent(id,k->new Object())){
            if(Files.exists(refreshFile(id)))return refreshStatus(id);
            JsonObject s=read(id);baseCheck(s);var compatibility=currentness(id);
            if(Boolean.TRUE.equals(compatibility.get("compatible")))return Map.of("host_id",id,"state","current","refresh_required",false,"compatibility",compatibility);
            NativeHostChannel.requireRefreshSafe(dir(id).resolve("requests"));
            if(Boolean.TRUE.equals(compatibility.get("custom_package"))&&!Boolean.TRUE.equals(compatibility.get("native_integrity")))throw new IllegalStateException("Custom Native integrity changed; supply a separately trusted package before refresh");
            if(!Boolean.TRUE.equals(compatibility.get("owned_sources_unchanged")))throw new IllegalStateException("Owned source was edited/missing; preserve and inspect it before refresh");
            var plan=new LinkedHashMap<String,Object>();plan.put("host_id",id);plan.put("project",string(s,"project"));plan.put("base_uuid",string(s,"base_uuid"));plan.put("mode",string(s,"mode"));plan.put("requires_database_update",string(s,"mode").equals("metadata"));if(string(s,"mode").equals("metadata"))plan.put("project_sources_sha256",refreshProjectFingerprint(new EdtMetadataGateway().resolveProject(string(s,"project")).getLocation().toFile().toPath().resolve("src")));plan.put("owned_state_sha256",NativeHostChannel.hash(s.toString()));plan.put("required_template_sha256",templateHash());plan.put("native_origin",compatibility.get("custom_package").equals(true)?"custom":"managed");plan.put("required_native_sha256",Boolean.TRUE.equals(compatibility.get("custom_package"))?string(s,"native_sha256"):NativePackageService.bundled().zipSha256());plan.put("custom_native_preserved",Boolean.TRUE.equals(compatibility.get("custom_package")));
            plan.put("plan_sha256",NativeHostChannel.hash(AgentResponses.JSON.toJson(plan)));plan.put("state","preview");plan.put("refresh_required",true);plan.put("apply_call",Map.of("tool","edt.run","arguments",Map.of("op","edt_runtime_host","args",Map.of("action","refresh","host_id",id,"expected_plan_sha256",plan.get("plan_sha256"),"allow_database_update",false))));return plan;
        }
    }
    public Map<String,Object> ensureCurrent(String id)throws Exception{
        var compatible=currentness(id);if(Boolean.TRUE.equals(compatible.get("compatible"))){var live=status(id);boolean running="running".equals(live.get("state"))&&!Boolean.TRUE.equals(live.get("execution_blocked_by_refresh"));return Map.of("host_id",id,"ready",running,"current",true,"runtime_ready",running,"compatibility",compatible,"changed",false);}
        // Only the package cache may be repaired automatically. Host replacement needs a fresh preview.
        var prepared=NativePackageManager.getInstance().ensure();return Map.of("host_id",id,"ready",false,"package",prepared,"compatibility",compatible,"next_call",Map.of("tool","edt.run","arguments",Map.of("op","edt_runtime_host","args",Map.of("action","refresh_preview","host_id",id))));
    }
    public Map<String,Object> refresh(String id,String expected,boolean allowDatabaseUpdate)throws Exception {
        synchronized(LOCKS.computeIfAbsent(id,k->new Object())){
            if(Files.exists(refreshFile(id)))return refreshStatus(id);
            var preview=refreshPreview(id);if(Boolean.FALSE.equals(preview.get("refresh_required")))return preview;
            if(!Objects.equals(expected,preview.get("plan_sha256")))throw new IllegalStateException("Refresh plan changed; read a new preview");
            if(!read(id).has("base_identity_sha256"))throw new IllegalStateException("Legacy host lacks connection proof for automatic refresh");
            if(Boolean.TRUE.equals(preview.get("requires_database_update"))&&!allowDatabaseUpdate)throw new IllegalStateException("Metadata refresh FULL-updates the whole current project; allow_database_update=true required");
            var preparedPackage=Boolean.TRUE.equals(preview.get("custom_native_preserved"))?null:NativePackageManager.getInstance().ensure();
            var channel=CHANNELS.get(id);NativeHostChannel.requireRefreshSafe(dir(id).resolve("requests"));
            JsonObject s=read(id),journal=new JsonObject();journal.addProperty("old_host_id",id);journal.addProperty("state","claimed");journal.addProperty("plan_sha256",expected);journal.addProperty("project",string(s,"project"));saveRefresh(id,journal);
            s.addProperty("refresh_in_progress",true);write(id,s);
            REFRESH_THREADS.put(id,Thread.currentThread());
            try{
                Map<String,Object> prepareArgs=new LinkedHashMap<>(Map.of("project",string(s,"project"),"mode",string(s,"mode"),"refresh_parent",id,"expected_base_uuid",string(s,"base_uuid"),"expected_base_identity_sha256",string(s,"base_identity_sha256")));
                if(Boolean.TRUE.equals(preview.get("custom_native_preserved"))){
                    Path preserved=root.resolve("refresh-"+id+".native.zip");Files.copy(dir(id).resolve("native.zip"),preserved);requireTrustedCustomHash(fileHash(preserved),preview.get("required_native_sha256").toString());journal.addProperty("custom_native_sha256",preview.get("required_native_sha256").toString());saveRefresh(id,journal);
                    prepareArgs.put("native_path",preserved.toString());prepareArgs.put("expected_native_sha256",journal.get("custom_native_sha256").getAsString());
                }
                else {prepareArgs.put("native_path",preparedPackage.get("native_path"));prepareArgs.put("expected_native_sha256",preparedPackage.get("expected_native_sha256"));prepareArgs.put("managed_refresh",true);}
                if(channel!=null)channel.freezeForRefresh();
                // Existing ownership checks and absence verification remain authoritative.
                cleanup(id);baseCheck(s);journal.addProperty("state","old_cleaned");saveRefresh(id,journal);
                var fresh=prepare(prepareArgs,newId->{try{journal.addProperty("new_host_id",newId);journal.addProperty("state","new_reserved");saveRefresh(id,journal);}catch(Exception e){throw new IllegalStateException("Cannot persist new host reservation",e);}});
                String newId=fresh.get("host_id").toString();
                Path preserved=root.resolve("refresh-"+id+".native.zip");if(Files.exists(preserved)){if(!fileHash(preserved).equals(journal.get("custom_native_sha256").getAsString()))throw new IllegalStateException("Preserved custom Native changed");Files.delete(preserved);}
                journal.addProperty("state","starting");saveRefresh(id,journal);start(newId);journal.addProperty("state","completed");journal.addProperty("launch_accepted",true);journal.addProperty("readiness_verified",false);saveRefresh(id,journal);return refreshStatus(id);
            }catch(Exception e){journal.addProperty("failed_stage",journal.get("state").getAsString());journal.addProperty("state","failed");journal.addProperty("error_type",e.getClass().getSimpleName());journal.addProperty("automatic_resume",false);saveRefresh(id,journal);throw new IllegalStateException("Refresh stopped; inspect refresh_status for original old/new IDs; no automatic replay",e);}finally{REFRESH_THREADS.remove(id);}
        }
    }

    static String refreshProjectFingerprint(Path source)throws Exception{return treeHash(source);}
    public static void requireTrustedCustomHash(String actual,String expected){
        if(expected==null||!expected.matches("[a-f0-9]{64}")||!expected.equals(actual))throw new IllegalStateException("Custom Native differs from its original trusted SHA256; preserve for review");
    }
    public static void validateViewArguments(Map<String,Object> args){
        if("execute".equals(args.get("action"))&&(args.containsKey("cursor")||args.containsKey("timeout_ms")))throw new IllegalArgumentException("execute does not accept cursor/timeout_ms");
        integer(args,"page_size",8,1,16);integer(args,"timeout_ms",20000,0,25000);
        if(args.containsKey("compact")&&!(args.get("compact") instanceof Boolean))throw new IllegalArgumentException("compact must be boolean");
        if(args.containsKey("cursor")&&(!(args.get("cursor") instanceof String cursor)||cursor.length()>8192))throw new IllegalArgumentException("cursor must be a bounded string");
        if(Boolean.FALSE.equals(args.get("compact"))&&args.containsKey("cursor"))throw new IllegalArgumentException("cursor requires compact=true");
    }
    public Map<String,Object> observe(String id,String request,Map<String,Object> args,boolean wait)throws Exception{
        validateViewArguments(args);
        int page=integer(args,"page_size",8,1,16),timeout=integer(args,"timeout_ms",20000,0,25000);String cursor=(String)args.get("cursor");
        if(args.containsKey("compact")&&!(args.get("compact") instanceof Boolean))throw new IllegalArgumentException("compact must be boolean");
        boolean compact=!Boolean.FALSE.equals(args.get("compact"));if(!compact&&!wait)return result(id,request);
        NativeHostChannel channel=CHANNELS.get(id);long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);
        String comparison=cursor;
        while(true){
            long revision=channel==null?0:channel.revision();var raw=result(id,request);var refs=NativeHostChannel.snapshotReferences(dir(id).resolve("requests"),request);
            var projected=new LinkedHashMap<String,Object>(raw);projected.put("_batch",channel==null?NativeHostChannel.persistedBatch(dir(id).resolve("requests"),request):channel.batch(request));
            var view=NativeAgentReplies.view(id,projected,refs,comparison,page);
            var answer=cursor==null?NativeAgentReplies.view(id,projected,refs,null,page):view;answer.put("changed",view.get("changed"));
            if(!wait||Boolean.TRUE.equals(raw.get("terminal"))||comparison!=null&&Boolean.TRUE.equals(view.get("changed"))||Boolean.TRUE.equals(view.get("has_more")))return compact?answer:raw;
            if(comparison==null)comparison=view.get("cursor").toString();
            long left=deadline-System.nanoTime();if(left<=0||channel==null){answer.put("timed_out",left<=0);return compact?answer:raw;}
            channel.awaitRevision(revision,Math.max(1,TimeUnit.NANOSECONDS.toMillis(left)));
        }
    }
    private static int integer(Map<String,Object> args,String key,int fallback,int min,int max){
        if(!args.containsKey(key))return fallback;Object value=args.get(key);if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=Math.rint(n.doubleValue())||n.doubleValue()<min||n.doubleValue()>max)throw new IllegalArgumentException(key+" must be an integer "+min+".."+max);return n.intValue();
    }

    public Map<String,Object> status(String id)throws Exception{
        JsonObject s=read(id);var out=new LinkedHashMap<String,Object>();for(String k:List.of("host_id","project","mode","name","state","launch_strategy","external_project","loaded_into_base","base_update_skipped","source_removed","database_verified_absent","cleanup_in_progress","cleaned","results_retained","database_removal_synchronized","external_project_removed","verification_host_id","verification_request_id","database_verification_strategy","verification_report_sha256","verification_inventory_sha256","verification_snapshot_sha256","verification_generation"))if(s.has(k))out.put(k,JSON.fromJson(s.get(k),Object.class));
        if(!s.has("cleaned"))out.put("compatibility",currentness(id));
        if(s.has("refresh_parent")){String parent=string(s,"refresh_parent");var refresh=refreshStatus(parent);out.put("parent_refresh_id",parent);out.put("parent_refresh_state",refresh.get("state"));out.put("execution_blocked_by_refresh",!"completed".equals(refresh.get("state"))&&REFRESH_THREADS.get(parent)!=Thread.currentThread());}
        IProject external=s.has("external_project")?ResourcesPlugin.getWorkspace().getRoot().getProject(string(s,"external_project")):null;
        boolean registered=external!=null&&externalRegistered(external),files=Files.exists(dir(id).resolve("external-project"));
        out.put("external_registration_present",registered);out.put("external_files_present",files);
        if(s.has("cleaned") && s.get("cleaned").getAsBoolean() && (registered||files)){out.put("cleaned",false);out.put("state","cleanup_incomplete");}
        var channel=CHANNELS.get(id);if(channel!=null){out.put("channel",channel.status());if(Boolean.TRUE.equals(channel.status().get("connected"))&&!s.has("cleanup_in_progress"))out.put("state","running");}
        Path phase=dir(id).resolve("session.json.stage");if(Files.isRegularFile(phase)){String value=Files.readString(phase,StandardCharsets.UTF_8).replace("\uFEFF", "").trim();out.put("startup_phase",value);if(value.equals("initialization_failed")||value.equals("transport_failed"))out.put("state",value);}
        Process process=PROCESSES.get(id);if(process!=null&&!process.isAlive()){out.put("state","client_exited");out.put("exit_code",process.exitValue());}
        if(channel==null&&Set.of("starting","running").contains(string(s,"state")))out.put("state","interrupted_by_restart");boolean consentValid=true;try{requireConsent(id,s);StartupConsentService.requireNoUnknown(dir(id));}catch(Exception invalid){consentValid=false;}out.put("consent_valid",consentValid);
        boolean baseCurrent=true;try{baseCheck(s);}catch(Exception changed){baseCurrent=false;}out.put("base_binding_current",baseCurrent);
        var blockers=NativeExecutionGate.readiness(out,consentValid,baseCurrent,Boolean.TRUE.equals(out.get("execution_blocked_by_refresh"))||s.has("refresh_in_progress")||Files.exists(refreshFile(id)),s.has("cleanup_in_progress"));out.put("execution_blockers",blockers);out.put("execution_allowed",blockers.isEmpty());if(consentValid&&"external".equals(string(s,"mode"))&&"starting".equals(out.get("state")))out.put("next_call",RuntimeConsentPolicy.call("edt_runtime_host",Map.of("action","startup_inspect","host_id",id)));
        return out;
    }
    public Map<String,Object> stop(String id)throws Exception{
        synchronized(LOCKS.computeIfAbsent(id,k->new Object())){
            JsonObject s=read(id);requireNoConcurrentRefresh(id,s);if(s.has("cleaned")&&s.get("cleaned").getAsBoolean())return status(id);stopClient(id,s);s.addProperty("state","stopped");write(id,s);return status(id);
        }
    }
    private void stopClient(String id,JsonObject s)throws Exception{StartupConsentService.exclusiveWindow(dir(id),()->{StartupConsentService.stopOwnedHelper(dir(id));try{stopClientLocked(id,s);}finally{StartupConsentService.restoreOwnedVisibility(dir(id));}return null;});}
    private void stopClientLocked(String id,JsonObject s)throws Exception{
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
        CHANNELS.remove(id);if(channel!=null)channel.close();RuntimeProcessLifecycle.global().forget(dir(id)+"/client");RuntimeProcessLifecycle.global().forget(dir(id)+"/channel");Files.deleteIfExists(dir(id).resolve("session.json"));
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
            JsonObject s=read(id);requireNoConcurrentRefresh(id,s);
            if(s.has("cleaned")&&s.get("cleaned").getAsBoolean()){
                cleanupExternalProject(id,s);
                Path custom=root.resolve("refresh-"+id+".native.zip");if(Files.exists(custom)&&REFRESH_THREADS.get(id)!=Thread.currentThread()){
                    JsonObject journal=JsonParser.parseString(Files.readString(refreshFile(id))).getAsJsonObject();if(!journal.has("custom_native_sha256")||!fileHash(custom).equals(journal.get("custom_native_sha256").getAsString()))throw new IllegalStateException("Preserved custom Native changed");Files.delete(custom);
                }
                if(s.has("results_retained")&&REFRESH_THREADS.get(id)!=Thread.currentThread()){
                    Path receipts=dir(id).resolve("requests");if(!treeHash(receipts).equals(string(s,"retained_results_sha256")))throw new IllegalStateException("Retained receipts were edited; preserve for review");
                    try(var files=Files.walk(receipts)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}
                    s.remove("results_retained");s.remove("retained_results_sha256");write(id,s);
                }return status(id);
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
                    bases.update(project,verifiedBase(s),new NullProgressMonitor());
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
            boolean retain=REFRESH_THREADS.get(id)==Thread.currentThread()&&Files.isDirectory(dir(id).resolve("requests"));
            if(retain){s.addProperty("results_retained",true);s.addProperty("retained_results_sha256",treeHash(dir(id).resolve("requests")));}
            try(var paths=Files.walk(dir(id))){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())if(!p.equals(dir(id))&&!p.equals(dir(id).resolve("host.json"))&&!(retain&&p.startsWith(dir(id).resolve("requests"))))Files.delete(p);}
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
