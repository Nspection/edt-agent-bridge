// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.io.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.*;
import org.eclipse.core.resources.ResourcesPlugin;
import com.codepilot1c.core.edt.infobase.InfobasePaths;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder.ThickClientMode;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;

/** Stage1 owns its local client and private pipe. Never attaches to an arbitrary listening port. */
public final class DirectTestClientService {
    private static final Gson JSON=new GsonBuilder().serializeNulls().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
    private static final Map<String,Pipe> PIPES=new ConcurrentHashMap<>();
    private static final Map<String,DirectClientJournal> JOURNALS=new ConcurrentHashMap<>();
    private static final List<String> RESOURCES=List.of("worker.py","tables.py","controls.py","dialogs.py","vendor/tc1c.py","vendor/protocol_diagnostics.py","vendor/_collection.py","vendor/guids.py","vendor/LICENSE","vendor/SOURCE.json");
    private static final Object START_LOCK=new Object();
    private final long lifecycle=RuntimeProcessLifecycle.global().epoch();
    private final Path root;
    private final EdtRuntimeService runtime=new EdtRuntimeService();
    public DirectTestClientService()throws Exception{
        root=ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/direct-test-client");Files.createDirectories(root);
    }
    private Path dir(String id)throws Exception{return DirectClientStorage.resolve(root,id);}
    private Object lock(String id){return DirectClientLifecycle.lock(DirectClientStorage.key(root,id));}
    private Map<String,Object> load(String id)throws Exception {return loadState(dir(id));}
    @SuppressWarnings("unchecked") private static Map<String,Object> loadState(Path folder)throws Exception {
        Path file=folder.resolve("client.json");if(Files.isSymbolicLink(file)||Files.isSymbolicLink(folder)||Files.size(file)>32768)throw new IllegalStateException("Client storage changed");return JSON.fromJson(Files.readString(file,StandardCharsets.UTF_8),LinkedHashMap.class);
    }
    static String reserveDirectory(Path root)throws Exception {
        // Tools instantiate services per request; the reservation lock must span instances.
        synchronized(START_LOCK){
            if(DirectClientStorage.pending(root).stream().filter(path->{try{return !DirectClientStorage.cleaned(path);}catch(Exception unavailable){return true;}}).count()>=4)throw new IllegalStateException("Clean up existing direct clients before starting another");
            String id=UUID.randomUUID().toString().replace("-","");Files.createDirectory(root.resolve(id));return id;
        }
    }
    private void save(String id,Map<String,Object> state)throws Exception{
        synchronized(lock(id)){
        Path file=dir(id).resolve("client.json"),tmp=dir(id).resolve("client.tmp");if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))DirectClientLifecycle.beforeSave(loadState(dir(id)),state);if(!Boolean.TRUE.equals(state.get("cleaned"))&&!state.containsKey("last_failure")&&Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)){var current=loadState(dir(id));if(current.containsKey("last_failure"))state.put("last_failure",current.get("last_failure"));}if(Files.isSymbolicLink(file)||Files.isSymbolicLink(tmp))throw new IllegalStateException("Client storage changed");Files.writeString(tmp,JSON.toJson(state),StandardCharsets.UTF_8);Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }
    }
    private static String required(Map<String,Object> a,String key){return RuntimeExecutionPlan.required(a,key);}
    private static String baseHash(String connection){return ClientBindingVerifier.targetHash(connection);}
    private DirectClientJournal journal(String id)throws Exception{
        DirectClientJournal existing=JOURNALS.get(id);if(existing!=null)return existing;
        synchronized(lock(id)){if(Boolean.TRUE.equals(load(id).get("cleaned")))throw new IllegalStateException("Cleanup removed operation details");existing=JOURNALS.get(id);if(existing==null){existing=new DirectClientJournal(dir(id).resolve("operations"));JOURNALS.put(id,existing);}return existing;}
    }
    private void verifyTarget(Map<String,Object> state)throws Exception{
        String project=String.valueOf(state.get("project"));var base=runtime.resolveDefaultInfobase(project);
        if(base==null||!base.getUuid().toString().equals(state.get("base_uuid"))||!baseHash(base.getConnectionString().asConnectionString()).equals(state.get("base_identity_sha256")))throw new IllegalStateException("Project binding changed; no TestClient action dispatched");
        verifyProcess(state);
    }
    private static ProcessHandle verifyProcess(Map<String,Object> state)throws Exception{
        long pid=((Number)state.get("pid")).longValue();var process=ProcessHandle.of(pid).orElseThrow(()->new IllegalStateException("Owned TestClient exited"));
        if(!process.isAlive()||!process.info().startInstant().map(Instant::toString).orElse("").equals(state.get("process_started_at")))throw new IllegalStateException("Owned process identity changed");
        var info=process.info();String executable=info.command().orElseThrow();
        if(!Set.of("1cv8.exe","1cv8c.exe").contains(Path.of(executable).getFileName().toString().toLowerCase(Locale.ROOT)))throw new IllegalStateException("Unsupported owned client executable");
        // One correlated process snapshot supplies both target and port. On Windows the
        // existing WMI helper also checks executable/start time before returning arguments.
        var arguments=info.arguments().map(List::of).orElseGet(()->{try{return ClientBindingVerifier.windowsArguments(pid,executable,info.startInstant().orElseThrow());}catch(Exception e){throw new IllegalStateException("Process arguments unavailable");}});
        if(!ClientBindingVerifier.processTarget(arguments).equals(state.get("base_identity_sha256")))throw new IllegalStateException("Live TestClient targets another base");
        if(!process.isAlive()||!process.info().startInstant().map(Instant::toString).orElse("").equals(state.get("process_started_at")))throw new IllegalStateException("Owned process changed during verification");
        String port=state.get("port").toString();boolean matched=false;
        for(int i=0;i+1<arguments.size();i++)if(arguments.get(i).equalsIgnoreCase("-TPort")&&arguments.get(i+1).equals(port))matched=true;
        if(!matched)throw new IllegalStateException("Owned TestClient port changed");return process;
    }
    public Map<String,Object> start(Map<String,Object> args)throws Exception {return start(args,id->{});}
    public Map<String,Object> start(Map<String,Object> args,java.util.function.Consumer<String> reserved)throws Exception {
        RuntimeProcessLifecycle.global().require(lifecycle);
        if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows"))throw new IllegalStateException("Direct TestClient stage1 supports Windows only");
        var timeline=new StartupTimeline();int readinessSeconds=DirectClientStartup.timeout(args);String clientKind=DirectClientStartup.kind(args);
        String project=required(args,"project");var base=runtime.resolveDefaultInfobase(project);if(base==null)throw new IllegalStateException("Bind a permitted test base first");
        if(!Boolean.TRUE.equals(runtime.synchronizationState(project).get("configuration_synchronized")))throw new IllegalStateException("Project/base must be native EQUAL; this command never updates the base");
        var consentScope=consentScope(args);var consentLease=RuntimeConsentPolicy.claimOrRequire("edt_test_client",Map.of("project",project,"client_kind",clientKind),consentScope);
        DirectClientStorage.active(root);
        timeline.next("selecting_python");try(var selection=PythonEnvironmentService.workspace().select(args)){
        Path python=selection.python();
        String id=RuntimeProcessLifecycle.global().launch(lifecycle,()->reserveDirectory(root));
        var state=new LinkedHashMap<String,Object>();state.put("consent_lease",consentLease);state.put("client_id",id);state.put("project",project);state.put("base_uuid",base.getUuid().toString());state.put("base_identity_sha256",baseHash(base.getConnectionString().asConnectionString()));state.put("state","starting");state.put("readiness_timeout_s",readinessSeconds);state.put("client_kind",clientKind);state.put("startup_phase","preparing_worker");state.put("protocol_connected",false);if(selection.environmentId()!=null)state.put("environment_id",selection.environmentId());timeline.next("preparing_worker");state.put("startup_timeline",timeline.snapshot());save(id,state);reserved.accept(id);
        try{
            Pipe pipe=DirectClientLifecycle.active(root.resolve(id),()->load(id),()->RuntimeProcessLifecycle.global().launch(lifecycle,()->{
            Path worker=dir(id).resolve("worker");Files.createDirectory(worker);
            var ownedFiles=new LinkedHashMap<String,String>();state.put("worker_files_sha256",ownedFiles);save(id,state);
            for(String name:RESOURCES){Path file=worker.resolve(name);Files.createDirectories(file.getParent());try(var input=DirectTestClientService.class.getResourceAsStream("/resources/test-client/"+name)){if(input==null)throw new IllegalStateException("Packaged TestClient worker missing");Files.copy(input,file);}ownedFiles.put(name,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));save(id,state);}
            Pipe ownedPipe=new Pipe(python,worker.resolve("worker.py"),dir(id),selection.environmentId()==null?null:python.getParent().getParent().getParent().resolve("temp"));try{RuntimeProcessLifecycle.global().own(lifecycle,dir(id)+"/worker",ownedPipe);}catch(RuntimeException failed){ownedPipe.close();throw failed;}PIPES.put(id,ownedPipe);state.put("worker_pid",ownedPipe.process.pid());state.put("worker_started_at",ownedPipe.process.info().startInstant().orElseThrow().toString());try{save(id,state);}catch(Exception failed){PIPES.remove(id,ownedPipe);ownedPipe.close();RuntimeProcessLifecycle.global().forget(dir(id)+"/worker");throw failed;}return ownedPipe;}));
            var probe=pipe.call(Map.of("action","probe"));if(!Boolean.TRUE.equals(probe.get("success")))throw new IllegalStateException("Python runtime requires pywin32 and cryptography; prepare the explicit isolated runtime first");
            int port;try(var reservation=new ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))){port=reservation.getLocalPort();}
            var info=runtime.resolveThickClientInfo(base);var access=runtime.resolveAccessSettings(base);
            Path executable=clientKind.equals("thick")?info.component().getFile().toPath():info.component().getFile().toPath().resolveSibling("1cv8c.exe");if(!Files.isRegularFile(executable))throw new IllegalStateException("Selected platform client executable unavailable");
            var version=com.sun.jna.platform.win32.VersionUtil.getFileVersionInfo(executable.toString());int ms=version.dwFileVersionMS.intValue(),ls=version.dwFileVersionLS.intValue();String platform=(ms>>>16)+"."+(ms&65535)+"."+(ls>>>16)+"."+(ls&65535);state.put("platform_version",platform);
            var builder=new RuntimeExecutionCommandBuilder(executable.toFile(),ThickClientMode.ENTERPRISE);builder.forInfobase(base.getConnectionString(),false);builder.clientType(clientKind.equals("thick")?com._1c.g5.v8.dt.platform.services.model.ClientType.THICK_CLIENT:com._1c.g5.v8.dt.platform.services.model.ClientType.THIN_CLIENT);
            if(access.isInfobaseAuthentication()){if(access.getUserName()!=null)builder.userName(access.getUserName());if(access.getPassword()!=null)builder.userPassword(access.getPassword());}else builder.osAuthentication(true);
            builder.disableStartupDialogs().disableStartupMessages().logTo(dir(id).resolve("client.log").toFile(),true);
            timeline.next("launching_process");state.put("startup_timeline",timeline.snapshot());save(id,state);var command=builder.toProcessBuilder();command.command().addAll(List.of("/TESTCLIENT","-TPort",Integer.toString(port)));if((ms>>>16)==8&&(ms&65535)>=5)command.command().add("/i85");command.directory(dir(id).toFile());command.environment().clear();command.environment().putAll(RunnerEnvironment.isolated(System.getenv()));command.redirectErrorStream(true);command.redirectOutput(dir(id).resolve("process.log").toFile());DirectClientLifecycle.active(root.resolve(id),()->load(id),()->RuntimeProcessLifecycle.global().launch(lifecycle,()->{requireConsent(state);Process child=command.start();RuntimeProcessLifecycle.global().ownProcess(lifecycle,dir(id)+"/client",child);
            timeline.next("connecting_protocol");state.put("startup_timeline",timeline.snapshot());state.put("startup_phase","connecting_protocol");state.put("pid",child.pid());state.put("process_started_at",child.info().startInstant().orElseThrow().toString());state.put("port",Integer.toString(port));try{save(id,state);}catch(Exception failed){try{RuntimeProcessLifecycle.global().closeOwned(dir(id)+"/client");}catch(Exception preserved){failed.addSuppressed(preserved);}throw failed;}return child;}));
            return finishStartup(id,state,pipe,readinessSeconds,timeline);
        }catch(Exception error){synchronized(lock(id)){if(Set.of("stopped","cleaned").contains(load(id).get("state")))return status(id);}timeline.finish("failed");state.put("startup_timeline",timeline.snapshot());state.put("startup_error_type",error.getClass().getSimpleName());state.put("startup_observation",startupObservation(state));state.put("state","start_failed");save(id,state);try{stop(id);}catch(Exception preserved){error.addSuppressed(preserved);}throw new IllegalStateException("client_id="+id+" start failed: "+error.getMessage(),error);}
        }
    }
    public Map<String,Object> consentScope(Map<String,Object> args)throws Exception {
        String project=required(args,"project"),kind=DirectClientStartup.kind(args);var base=runtime.resolveDefaultInfobase(project);if(base==null)throw new IllegalStateException("Bound base required");var info=runtime.resolveThickClientInfo(base);
        Path executable=kind.equals("thick")?info.component().getFile().toPath():info.component().getFile().toPath().resolveSibling("1cv8c.exe");
        return RuntimeConsentPolicy.scope(project,"testclient",Map.of("client_kind",kind,"executable",executable.toRealPath().toString(),"executable_sha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(executable)))));
    }
    public Map<String,Object> consent(Map<String,Object> args)throws Exception {
        String action=required(args,"action");if(action.startsWith("consent_revoke"))return RuntimeConsentPolicy.operation("edt_test_client",Map.of(),Map.of(),args);
        var selector=new LinkedHashMap<String,Object>();selector.put("project",required(args,"project"));selector.put("client_kind",DirectClientStartup.kind(args));return RuntimeConsentPolicy.operation("edt_test_client",selector,consentScope(selector),args);
    }
    private void requireConsent(Map<String,Object> state)throws Exception {RuntimeConsentPolicy.assertToolAllowed("edt_test_client");var args=Map.<String,Object>of("project",state.get("project"),"client_kind",state.getOrDefault("client_kind","thin"));RuntimeConsentPolicy.workspace().requireUse(consentScope(args),TaskSessionService.object(state.get("consent_lease")));}
    private Map<String,Object> startupContext(String id)throws Exception {
        var state=load(id);verifyTarget(state);requireConsent(state);if(!"starting".equals(state.get("state"))||journal(id).blockingOperation().isPresent())throw new IllegalStateException("Bootstrap is startup-only; business barriers cannot be bypassed");var process=verifyProcess(state);return new LinkedHashMap<>(Map.of("pid",process.pid(),"started",process.info().startInstant().orElseThrow().toString(),"executable",process.info().command().orElseThrow(),"capability","testclient","consent_scope",consentScope(Map.of("project",state.get("project"),"client_kind",state.getOrDefault("client_kind","thin"))),"consent_lease",state.get("consent_lease")));
    }
    public Map<String,Object> startupInspect(String id)throws Exception{return StartupConsentService.inspect(dir(id),()->startupContext(id),"edt_test_client",Map.of("client_id",id));}
    public Map<String,Object> startupAnswer(Map<String,Object> args)throws Exception {String id=required(args,"client_id");return StartupConsentService.answer(dir(id),()->startupContext(id),args);}
    public Map<String,Object> resume(String id)throws Exception {
        var state=load(id);DirectClientLifecycle.requireActive(state);requireConsent(state);StartupConsentService.requireNoUnknown(dir(id));if("running".equals(state.get("state")))return status(id);verifyTarget(state);if(Boolean.TRUE.equals(state.get("startup_handshake_attempted"))&&!Boolean.TRUE.equals(state.get("protocol_connected")))throw new IllegalStateException("Original handshake outcome unknown; no reconnect, inspect/stop/cleanup only");return finishStartup(id,state,pipe(id),DirectClientStartup.timeout(state),new StartupTimeline());
    }
    private Map<String,Object> finishStartup(String id,Map<String,Object> state,Pipe pipe,int readinessSeconds,StartupTimeline timeline)throws Exception {
        verifyTarget(state);requireConsent(state);var budget=new DirectClientStartup.Budget(readinessSeconds,System::nanoTime);
        if(!Boolean.TRUE.equals(state.get("protocol_connected"))){
            while(budget.remainingMillis()>0){
                verifyTarget(state);var question=startupInspect(id);
                if(Boolean.TRUE.equals(question.get("success"))||Boolean.TRUE.equals(question.get("platform_question_present"))){state.put("startup_phase","awaiting_platform_consent");state.put("startup_decision",question);save(id,state);return status(id);}
                var listener=pipe.call(Map.of("action","listener","port",Integer.parseInt(state.get("port").toString()),"pid",state.get("pid")));
                if(Boolean.TRUE.equals(listener.get("listening"))){int handshakeBudget;synchronized(lock(id)){handshakeBudget=budget.remainingMillis();if(handshakeBudget==0)break;var current=load(id);DirectClientLifecycle.requireActive(current);if(Boolean.TRUE.equals(current.get("startup_handshake_attempted")))throw new IllegalStateException("Original handshake already claimed; inspect original startup, no reconnect");state.put("startup_handshake_attempted",true);save(id,state);}var connected=pipe.call(Map.of("action","connect","port",Integer.parseInt(state.get("port").toString()),"pid",state.get("pid"),"platform_version",state.get("platform_version"),"startup_timeout_ms",handshakeBudget));
                    if(!Boolean.TRUE.equals(connected.get("success"))){state.put("startup_phase","handshake_outcome_unknown");state.put("startup_diagnostics",connected);save(id,state);return status(id);}
                    state.put("protocol_connected",true);save(id,state);break;
                }
                Thread.sleep(200);
            }
        }
        if(!Boolean.TRUE.equals(state.get("protocol_connected"))){state.put("startup_phase","awaiting_runtime");save(id,state);return status(id);}
        state.put("startup_phase","waiting_for_ui");save(id,state);var startup=DirectClientStartup.await(ms->pipe.call(Map.of("operation","ready","startup_timeout_ms",ms)),()->{verifyTarget(state);requireConsent(state);},budget);
        var ready=TaskSessionService.object(startup.getOrDefault("observation",startup));
        state.put("startup_diagnostics",ready);
        if(!Boolean.TRUE.equals(ready.get("success"))){var question=startupInspect(id);if(Boolean.TRUE.equals(question.get("success"))||Boolean.TRUE.equals(question.get("platform_question_present"))){state.put("startup_phase","awaiting_platform_consent");state.put("startup_decision",question);}else state.put("startup_phase","awaiting_runtime");save(id,state);return status(id);}
        state.remove("startup_decision");timeline.finish("ready");state.put("startup_timeline",timeline.snapshot());state.put("ui_ready",true);state.put("startup_phase","ready");state.put("state","running");verifyTarget(state);save(id,state);return status(id);
    }
    public Map<String,Object> status(String id)throws Exception {
        var state=load(id);var out=new LinkedHashMap<String,Object>();for(String key:List.of("client_id","project","base_uuid","base_identity_sha256","state","platform_version","pid","process_started_at","cleaned","environment_id","ui_ready","client_kind","startup_phase","protocol_connected","startup_diagnostics","last_failure","startup_timeline","startup_error_type","startup_observation","consent_lease","startup_decision","startup_handshake_attempted"))if(state.containsKey(key))out.put(key,state.get(key));out.put("transport","testclient");
        if(state.get("startup_timeline") instanceof Map<?,?>)out.put("startup_timeline",StartupTimeline.live(TaskSessionService.object(state.get("startup_timeline")),System.currentTimeMillis()));boolean verified=false;if(PIPES.containsKey(id))try{verifyTarget(state);verified=true;}catch(Exception unavailable){}out.put("live_client_binding_verified",verified);Pipe current=PIPES.get(id);boolean connected=verified&&current!=null&&current.available()&&"running".equals(state.get("state"));out.put("connected",connected);boolean blocked=false;if(!Boolean.TRUE.equals(state.get("cleaned"))){var barrier=journal(id).blockingOperation();blocked=barrier.isPresent();barrier.ifPresent(value->out.put("blocking_operation_id",value));if(blocked){var blockedRecord=journal(id).read(barrier.orElseThrow());if("outcome_unknown".equals(blockedRecord.get("state")))out.put("ui_ready",false);out.put("mutation_barrier_reason","active_or_unknown_operation");}}out.put("mutation_blocked",blocked);boolean consentValid=false;if(!Set.of("stopped","cleaned").contains(state.get("state")))try{requireConsent(state);StartupConsentService.requireNoUnknown(dir(id));consentValid=true;}catch(Exception invalid){}out.put("consent_valid",consentValid);out.put("can_observe",connected);out.put("can_mutate",connected&&!blocked&&consentValid);return out;
    }
    private Map<String,Object> startupObservation(Map<String,Object> state){
        var out=new LinkedHashMap<String,Object>();out.put("observed_before_owned_process_stop",true);
        try{var process=verifyProcess(state);out.put("process_alive",process.isAlive());var listed=ProcessWindowCapture.execute("list",process.pid(),null,null);var rows=(List<?>)listed.get("processes");var row=TaskSessionService.object(rows.get(0));var windows=(List<?>)row.get("windows");out.put("window_count",windows.size());out.put("visible_window_count",windows.stream().map(TaskSessionService::object).filter(w->Boolean.TRUE.equals(w.get("visible"))).count());out.put("observation_available",true);}
        catch(Exception unavailable){out.put("observation_available",false);out.put("observation_error_type",unavailable.getClass().getSimpleName());}
        return out;
    }
    public Map<String,Object> observe(Map<String,Object> args)throws Exception {
        String id=required(args,"client_id");var state=load(id);if(!"running".equals(state.get("state")))throw new IllegalStateException("Direct client is not running");verifyTarget(state);
        var payload=new LinkedHashMap<>(args);payload.remove("transport");payload.remove("client_id");if(!Set.of("view","fields","snapshot_id","table","columns","wait_timeout_ms","operation_timeout_s").containsAll(payload.keySet()))throw new IllegalArgumentException("Direct observation accepts only view/fields/snapshot_id/table/columns/wait_timeout_ms/operation_timeout_s; max_depth/group/only_visible/endpoint/delta/pagination are Vanessa options");if("table".equals(payload.get("view"))){required(payload,"table");validateColumns(payload.get("columns"));}else if(payload.containsKey("table")||payload.containsKey("columns"))throw new IllegalArgumentException("table/columns require view=table");DirectClientTiming.validate(payload);if(payload.containsKey("wait_timeout_ms")){if(payload.containsKey("snapshot_id"))throw new IllegalArgumentException("Saved details do not wait");}payload.put("operation",payload.containsKey("snapshot_id")?"details":"observe");DirectClientTransport.validatePayload(payload);Map<String,Object> result;try{result=pipe(id).call(payload);}catch(Exception unavailable){result=DirectClientTransport.failure(unavailable,false);}try{noteFailure(id,result,null);}catch(Exception diagnosticUnavailable){/* The actual observation remains authoritative. */}result.put("client_id",id);result.put("live_client_binding_verified",true);return result;
    }
    private void noteFailure(String id,Map<String,Object> result,String operation)throws Exception {
        if(Boolean.TRUE.equals(result.get("success"))||!result.containsKey("diagnostics"))return;
        synchronized(lock(id)){var fresh=load(id);if(Boolean.TRUE.equals(fresh.get("cleaned")))return;var failure=new LinkedHashMap<String,Object>();for(String key:List.of("state","reason","stage","failed_step_index","transport_state","execution_state","action_dispatched","outcome_unknown","diagnostics"))if(result.containsKey(key))failure.put(key,result.get(key));failure.put("recorded_at",Instant.now().toString());failure.put("historical",true);if(operation!=null)failure.put("operation_id",operation);fresh.put("last_failure",failure);save(id,fresh);}
    }
    private Pipe pipe(String id){var pipe=PIPES.get(id);if(pipe==null||!pipe.available())throw new DirectClientTransport.WorkerFailure(false,Map.of("failure_kind","worker_unavailable_before_request","pipe_request_state","not_sent","worker_alive",pipe!=null&&pipe.process.isAlive()),null);return pipe;}
    public Map<String,Object> act(Map<String,Object> args)throws Exception {
        String id=required(args,"client_id"),key=required(args,"idempotency_key");var state=load(id);if(Boolean.TRUE.equals(state.get("cleaned")))throw new IllegalStateException("Cleanup removed operation details");
        var payload=new LinkedHashMap<>(args);payload.keySet().removeAll(Set.of("transport","client_id","idempotency_key"));boolean dialog="dialog_answer".equals(payload.get("action"));if(dialog)validateDialog(payload,state);else validateAction(payload);payload.put("operation",dialog?"dialog_answer":"batch".equals(payload.get("action"))?"batch":"action");
        var wirePayload=DirectClientTransport.wirePayload(payload,DirectClientJournal.hash(key));var journal=journal(id);var previous=journal.existing(key,payload);if(previous.isPresent())return existingReply(journal,previous.orElseThrow(),payload,id);if(!"running".equals(state.get("state")))throw new IllegalStateException("Direct client is not running");verifyTarget(state);requireConsent(state);StartupConsentService.requireNoUnknown(dir(id));var claim=dialog?journal.claimDialog(key,payload):journal.claim(key,payload);var record=claim.record();String operation=String.valueOf(record.get("operation_id"));
        if(!claim.created())return existingReply(journal,record,payload,id);
        CountDownLatch submitted=new CountDownLatch(1);var armed=new java.util.concurrent.atomic.AtomicBoolean();String job;
        try{job=BackgroundJobRegistry.getInstance().startJob("test-client-action",()->{
            submitted.await();if(!armed.get())return JSON.toJson(Map.of("success",false,"action_dispatched",false,"outcome_unknown",false,"state","rejected","message","Operation reservation was not persisted"));Map<String,Object> result;
            try{var currentState=load(id);verifyTarget(currentState);requireConsent(currentState);}catch(Exception unavailable){var rejected=new LinkedHashMap<String,Object>(Map.of("success",false,"outcome_unknown",false,"action_dispatched",false,"state","rejected","message","Live client binding changed before dispatch"));var completed=journal.read(operation);completed.put("result",rejected);completed.put("state","failed");journal.write(operation,completed);return JSON.toJson(correlatedResult(rejected,payload,operation));}
            try{result=pipe(id).call(wirePayload);}catch(Exception unavailable){result=DirectClientTransport.failure(unavailable,true);}
            if(!dialog&&Boolean.TRUE.equals(result.get("dialog_available")))result.put("next_call",Map.of("tool","edt.run","arguments",Map.of("op","edt_test_client","args",Map.of("action","dialog_inspect","client_id",id,"blocking_operation_id",operation))));
            var completed=journal.read(operation);completed.put("result",result);completed.put("state",Boolean.TRUE.equals(result.get("outcome_unknown"))?"outcome_unknown":Boolean.TRUE.equals(result.get("success"))?"completed":"failed");journal.write(operation,completed);try{noteFailure(id,result,operation);}catch(Exception diagnosticUnavailable){/* Durable operation result already contains diagnostics. */}return JSON.toJson(correlatedResult(result,payload,operation));
        });record.put("job_id",job);journal.write(operation,record);armed.set(true);}finally{submitted.countDown();}
        return Map.of("job_id",job,"client_id",id,"operation_id",operation,"next_tool","edt_operation_status");
    }
    public Map<String,Object> inspectDialog(Map<String,Object> args)throws Exception {
        if(!Set.of("action","client_id","blocking_operation_id","operation_timeout_s").containsAll(args.keySet()))throw new IllegalArgumentException("Unsupported dialog inspection field");
        String id=required(args,"client_id"),original=required(args,"blocking_operation_id");var state=load(id);verifyTarget(state);
        var parent=journal(id).read(original);
        if(!"outcome_unknown".equals(parent.get("state"))||!(parent.get("result") instanceof Map<?,?> result)||!Boolean.TRUE.equals(result.get("dialog_available"))||!"usable".equals(result.get("transport_state")))throw new IllegalStateException("Original operation has no captured usable confirmation");
        var payload=new LinkedHashMap<String,Object>();payload.put("operation","dialog_inspect");payload.put("blocking_operation_id",original);if(args.containsKey("operation_timeout_s"))payload.put("operation_timeout_s",args.get("operation_timeout_s"));DirectClientTiming.validate(payload);
        var observed=pipe(id).call(payload);observed.put("client_id",id);observed.put("base_uuid",state.get("base_uuid"));
        if(Boolean.TRUE.equals(observed.get("success"))){
            var options=new ArrayList<Map<String,Object>>();
            if(Boolean.TRUE.equals(observed.get("answer_available")))for(Object value:(List<?>)observed.get("buttons")){
                var button=TaskSessionService.object(value);var grant=Map.of("application_confirmation",true,"base_uuid",state.get("base_uuid"),"blocking_operation_id",original,"dialog_hash",observed.get("dialog_hash"),"button",button.get("name"));
                var answer=new LinkedHashMap<String,Object>(grant);answer.remove("application_confirmation");answer.put("action","dialog_answer");answer.put("client_id",id);answer.put("idempotency_key","dialog-answer:"+observed.get("dialog_hash"));answer.put("authorization",grant);
                options.add(Map.of("label",button.get("title"),"next_call",Map.of("tool","edt.run","arguments",Map.of("op","edt_test_client","args",answer))));
            }
            observed.put("decision",Map.of("kind","application_confirmation","requires_explicit_user_authorization",true,"message","Only authorize an application confirmation. Security/trust/password/license prompts require manual handling. Original outcome remains unknown.","options",options));
        }
        return observed;
    }
    static void validateDialog(Map<String,Object> payload,Map<String,Object> state){
        if(!Set.of("action","blocking_operation_id","dialog_hash","button","authorization","base_uuid","operation_timeout_s").containsAll(payload.keySet()))throw new IllegalArgumentException("Unsupported dialog answer field");
        for(String field:List.of("blocking_operation_id","dialog_hash"))if(!required(payload,field).matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid "+field);
        String button=required(payload,"button");if(!button.matches("Button[0-9]"))throw new IllegalArgumentException("Use an exact inspected button name");
        String base=required(payload,"base_uuid");if(!base.equals(state.get("base_uuid")))throw new IllegalArgumentException("Authorization base differs from owned client");
        var expected=Map.of("application_confirmation",true,"base_uuid",base,"blocking_operation_id",payload.get("blocking_operation_id"),"dialog_hash",payload.get("dialog_hash"),"button",button);
        if(!expected.equals(payload.get("authorization")))throw new IllegalArgumentException("Explicit exact one-shot application confirmation authorization required; no security/trust/license/password prompts");
        DirectClientTiming.validate(payload);
    }
    private static Map<String,Object> existingReply(DirectClientJournal journal,Map<String,Object> record,Map<String,Object> payload,String client)throws Exception {
        String operation=String.valueOf(record.get("operation_id"));if(record.get("result") instanceof Map<?,?>)return present(journal.result(operation),payload,operation);if(record.get("job_id") instanceof String job)return Map.of("job_id",job,"client_id",client,"operation_id",operation,"next_tool","edt_operation_status","reused",true);return journal.result(operation);
    }
    private static final Set<String> TABLE_ACTIONS=Set.of("table_read","table_select","table_add","table_set");
    private static final Set<String> TABLE_FIELDS=Set.of("table","columns","row_match","cells","max_rows","offset","expect_count","expect_rows");
    private static void textMap(Object value,String name){
        if(!(value instanceof Map<?,?> map)||map.isEmpty()||map.size()>8||map.entrySet().stream().anyMatch(e->!(e.getKey() instanceof String key)||key.isBlank()||key.length()>128||!(e.getValue() instanceof String text)||text.length()>4096))throw new IllegalArgumentException(name+" requires 1..8 named text values");
    }
    private static int boundedInt(Object value,String name,int min,int max){
        if(!(value instanceof Number n))throw new IllegalArgumentException(name+" must be an integer");
        try{int result=new java.math.BigDecimal(n.toString()).intValueExact();if(result<min||result>max)throw new ArithmeticException();return result;}catch(ArithmeticException e){throw new IllegalArgumentException(name+" is outside bounds");}
    }
    private static void validateColumns(Object value){
        if(!(value instanceof List<?> cols)||cols.isEmpty()||cols.size()>8||new HashSet<>(cols).size()!=cols.size()||cols.stream().anyMatch(v->!(v instanceof String name)||name.isBlank()||name.length()>128))throw new IllegalArgumentException("columns requires 1..8 unique element names");
    }
    private static void validateTable(Map<String,Object> args,String action){
        String table=required(args,"table");if(table.length()>128)throw new IllegalArgumentException("Table name exceeds limit");
        if(action.equals("table_read")){
            validateColumns(args.get("columns"));
            if(args.containsKey("cells"))throw new IllegalArgumentException("table_read does not write cells");
            if(args.containsKey("row_match"))textMap(args.get("row_match"),"row_match");
            if(args.containsKey("max_rows"))boundedInt(args.get("max_rows"),"max_rows",1,50);
            if(args.containsKey("offset"))boundedInt(args.get("offset"),"offset",0,1000);
            if(args.containsKey("expect_count"))boundedInt(args.get("expect_count"),"expect_count",0,1000);
            if(args.containsKey("expect_rows")){
                if(!(args.get("expect_rows") instanceof List<?> rows)||rows.size()>50)throw new IllegalArgumentException("expect_rows requires up to50 rows");
                for(Object row:rows){textMap(row,"expect_rows");if(!((Map<?,?>)row).keySet().equals(new HashSet<>((List<?>)args.get("columns"))))throw new IllegalArgumentException("Expected row keys must match columns exactly");}
            }
        }else{
            if(args.keySet().stream().anyMatch(k->Set.of("columns","max_rows","offset","expect_count","expect_rows").contains(k)))throw new IllegalArgumentException("Read options require table_read");
            if(action.equals("table_add")){if(args.containsKey("row_match"))throw new IllegalArgumentException("table_add does not select an existing row");}
            else textMap(args.get("row_match"),"row_match");
            if(action.equals("table_select")){if(args.containsKey("cells"))throw new IllegalArgumentException("table_select does not write cells");}
            else {if(!(args.get("cells") instanceof Map<?,?> cells)||cells.isEmpty()||cells.size()>8||cells.entrySet().stream().anyMatch(e->!(e.getKey() instanceof String key)||key.isBlank()||key.length()>128||!(e.getValue() instanceof Boolean)&&(!(e.getValue() instanceof String text)||text.isEmpty()||text.length()>4096)))throw new IllegalArgumentException("cells requires 1..8 exact nonempty text or boolean values");}
        }
        if(args.keySet().stream().anyMatch(k->Set.of("element","value","navigation_link","steps","position","date").contains(k)))throw new IllegalArgumentException("Field does not apply to table action");
    }
    public static void validateAction(Map<String,Object> args){
        if(JSON.toJson(args).getBytes(StandardCharsets.UTF_8).length>128*1024)throw new IllegalArgumentException("Action exceeds 128 KiB");
        var allowed=new HashSet<>(Set.of("action","expected_window","expected_form","snapshot_id","element","value","navigation_link","expect","steps","wait_timeout_ms","operation_timeout_s","response_mode","position","date"));allowed.addAll(TABLE_FIELDS);
        if(!allowed.containsAll(args.keySet()))throw new IllegalArgumentException("Unsupported direct action field");
        if(!(args.get("expected_window") instanceof String window)||window.length()>1024)throw new IllegalArgumentException("Exact expected_window is required");required(args,"snapshot_id");String action=required(args,"action");
        DirectClientTiming.validate(args);
        if(args.containsKey("response_mode")&&!Set.of("full","summary").contains(args.get("response_mode")))throw new IllegalArgumentException("response_mode must be full or summary");
        if(args.containsKey("expected_form")&&!(args.get("expected_form") instanceof String))throw new IllegalArgumentException("expected_form must be text");
        if(action.equals("batch")){
            if(!Set.of("action","expected_window","expected_form","snapshot_id","steps","response_mode","operation_timeout_s").containsAll(args.keySet()))throw new IllegalArgumentException("Batch-level fields do not execute actions; use explicit steps");
            if(!(args.get("steps") instanceof List<?> steps)||steps.isEmpty()||steps.size()>16)throw new IllegalArgumentException("Batch requires 1..16 steps");
            for(Object item:steps){if(!(item instanceof Map<?,?> map))throw new IllegalArgumentException("Invalid batch step");var step=new LinkedHashMap<String,Object>();map.forEach((k,v)->step.put(String.valueOf(k),v));if(step.containsKey("operation_timeout_s")||step.containsKey("snapshot_id")||step.containsKey("response_mode")||step.containsKey("steps")||"batch".equals(step.get("action")))throw new IllegalArgumentException("Unsupported direct batch step");step.put("snapshot_id",args.get("snapshot_id"));step.put("operation_timeout_s",DirectClientTiming.seconds(args));validateAction(step);}return;
        }
        if(TABLE_ACTIONS.contains(action))validateTable(args,action);
        else{
            if(!Set.of("open","close","input","command","wait","check","uncheck","tab","activate","choose_value","expand","collapse","increase","decrease","set_position","set_date","open_choices").contains(action))throw new IllegalArgumentException("Unsupported direct action");
            if(args.keySet().stream().anyMatch(TABLE_FIELDS::contains)||args.containsKey("steps")||args.containsKey("navigation_link")&&!action.equals("open")||args.containsKey("value")&&!Set.of("input","choose_value","set_position").contains(action)||args.containsKey("element")&&!Set.of("input","command","check","uncheck","tab","activate","choose_value","expand","collapse","increase","decrease","set_position","set_date","open_choices").contains(action))throw new IllegalArgumentException("Field does not apply to this direct action");
            if(action.equals("open")){String link=required(args,"navigation_link");if(!link.matches("e1cib/(list|app|data)/[^\\s\\x00-\\x1f]+")||link.length()>2048)throw new IllegalArgumentException("Use an in-base navigation link");}
            if(Set.of("input","command","check","uncheck","tab","activate","choose_value","expand","collapse","increase","decrease","set_position","set_date","open_choices").contains(action))required(args,"element");
            if(args.containsKey("position")&&!action.equals("set_position")||args.containsKey("date")&&!action.equals("set_date"))throw new IllegalArgumentException("Control parameter does not apply to this action");
            if(action.equals("choose_value")&&(!(args.get("value") instanceof String choice)||choice.isBlank()||choice.length()>4096))throw new IllegalArgumentException("Exact choice text is required");
            if(args.containsKey("value")&&(!(args.get("value") instanceof String text)||text.length()>4096))throw new IllegalArgumentException("Control value must be text up to 4096 characters");
            if(action.equals("set_position")&&(!(args.get("position") instanceof Number n)||n.doubleValue()!=n.intValue()||n.intValue()<0||n.intValue()>100))throw new IllegalArgumentException("position must be an integer percentage 0..100");
            if(action.equals("set_date")&&args.get("expect") instanceof Map<?,?> e&&e.get("fields") instanceof Map<?,?> fields&&fields.containsKey(args.get("element")))throw new IllegalArgumentException("Calendar verification needs a different bound scalar field");
            if(action.equals("set_date")){try{if(!(args.get("date") instanceof String date)||!date.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException();java.time.LocalDate.parse(date);}catch(Exception invalid){throw new IllegalArgumentException("date must be a valid ISO date");}}
            if(action.equals("input")&&(!(args.get("value") instanceof String text)||text.length()>4096))throw new IllegalArgumentException("Input requires text up to 4096 characters");
        }
        if("wait".equals(action)&&args.get("expect") instanceof Map<?,?> e&&e.containsKey("window")&&!window.equals(e.get("window")))throw new IllegalArgumentException("Wait window conditions conflict");
        if(args.get("expect") instanceof Map<?,?> e&&e.containsKey("elements")){
            if(!(e.get("elements") instanceof Map<?,?> elements)||elements.isEmpty()||elements.size()>16)throw new IllegalArgumentException("expect.elements requires 1..16 exact names");
            for(var entry:elements.entrySet())if(!(entry.getKey() instanceof String name)||name.isBlank()||name.length()>128||!(entry.getValue() instanceof Map<?,?> states)||states.isEmpty()||!Set.of("visible","enabled","readonly").containsAll(states.keySet())||states.values().stream().anyMatch(v->!(v instanceof Boolean)))throw new IllegalArgumentException("Element conditions require exact boolean visible/enabled/readonly states");
        }
        if(args.containsKey("expect")){if(!(args.get("expect") instanceof Map<?,?> expected)||!Set.of("window","fields","elements").containsAll(expected.keySet())||expected.isEmpty())throw new IllegalArgumentException("Invalid expect");if(expected.containsKey("window")&&!(expected.get("window") instanceof String))throw new IllegalArgumentException("Expected window must be text");if(expected.containsKey("fields")){if(!(expected.get("fields") instanceof Map<?,?> fields)||fields.isEmpty()||fields.size()>16||fields.entrySet().stream().anyMatch(e->!(e.getKey() instanceof String)||!(e.getValue() instanceof String)))throw new IllegalArgumentException("Expected fields must be 1..16 exact text values");}}
    }
    static Map<String,Object> correlatedResult(Map<String,Object> full,Map<String,Object> request,String operation){var out=new LinkedHashMap<>(present(full,request,operation));out.put("operation_id",operation);return out;}
    static Map<String,Object> present(Map<String,Object> full,Map<String,Object> request,String operation){
        if(!"summary".equals(request.get("response_mode")))return full;
        var out=new LinkedHashMap<String,Object>();
        for(String key:List.of("success","state","outcome_unknown","action_dispatched","postcondition_verified","message","reason","stage","failed_step_index","wait","transport_state","execution_state","action_dispatch_state","retry_safe","diagnostics","mutation_blocked","payload_truncated","execution_success","original_state","dialog_available","next_call"))if(full.containsKey(key))out.put(key,full.get(key));
        out.put("operation_id",operation);out.put("details_tool","edt_test_client");out.put("details_action","result");
        Object results=full.get("results");var steps=new ArrayList<Map<String,Object>>();
        List<?> entries="batch".equals(request.get("action"))?(results instanceof List<?> list?list:List.of()):List.of(full);
        for(Object entry:entries)if(entry instanceof Map<?,?> step){var summary=new LinkedHashMap<String,Object>();for(String key:List.of("step_index","action","success","state","reason","action_dispatched","postcondition_verified","snapshot_id","wait"))if(step.containsKey(key))summary.put(key,step.get(key));if(!"batch".equals(request.get("action"))){summary.putIfAbsent("step_index",0);summary.putIfAbsent("action",request.get("action"));}steps.add(summary);}
        out.put("steps",steps);out.put("finished_steps",steps.size());out.put("passed_steps",steps.stream().filter(s->Boolean.TRUE.equals(s.get("success"))).count());out.put("verified_steps",steps.stream().filter(s->Boolean.TRUE.equals(s.get("postcondition_verified"))).count());if(request.get("steps") instanceof List<?> requested)out.put("total_steps",requested.size());else out.put("total_steps",1);
        if(out.get("failed_step_index")==null)for(var step:steps)if(!Boolean.TRUE.equals(step.get("success"))){out.put("failed_step_index",step.get("step_index"));break;}
        if(Boolean.TRUE.equals(full.get("payload_truncated")))for(String count:List.of("finished_steps","passed_steps","verified_steps"))if(full.containsKey(count))out.put(count,full.get(count));
        if(full.containsKey("window"))out.put("window",full.get("window"));
        if(full.containsKey("snapshot_id"))out.put("snapshot_id",full.get("snapshot_id"));return out;
    }
    public Map<String,Object> result(String id,String operation)throws Exception {if(Boolean.TRUE.equals(load(id).get("cleaned")))throw new IllegalStateException("Cleanup removed operation details");return journal(id).result(operation);}
    public Map<String,Object> stop(String id)throws Exception {
        return StartupConsentService.exclusiveWindow(dir(id),()->{synchronized(lock(id)){
        var state=load(id);if(Boolean.TRUE.equals(state.get("cleaned")))return status(id);
        var context=StartupConsentService.processContext(state.get("pid"),state.get("process_started_at"));
        StartupConsentService.stopOwnedHelper(dir(id));if(!"stopped".equals(state.get("state")))state.put("state","stopping");save(id,state);
        try{
            var pipe=PIPES.remove(id);if(pipe!=null)pipe.close();RuntimeProcessLifecycle.global().forget(dir(id)+"/worker");
            if(state.containsKey("pid")){
                var handle=ProcessHandle.of(((Number)state.get("pid")).longValue());
                if(handle.isPresent()&&OwnedProcessIdentity.original(handle.get(),java.time.Instant.parse(state.get("process_started_at").toString()))){
                    var owned=verifyProcess(state);owned.destroy();try{owned.onExit().get(4,TimeUnit.SECONDS);}catch(TimeoutException timeout){if(OwnedProcessIdentity.original(owned,java.time.Instant.parse(state.get("process_started_at").toString())))owned.destroyForcibly();owned.onExit().get(4,TimeUnit.SECONDS);}
                }
            }
            RuntimeProcessLifecycle.global().forget(dir(id)+"/client");
        }finally{StartupConsentService.restoreOwnedVisibility(dir(id),context);}
        state.remove("pid");state.remove("process_started_at");state.put("state","stopped");save(id,state);return status(id);
        }});
    }
    public Map<String,Object> cleanup(String id)throws Exception {
        return StartupConsentService.exclusiveWindow(dir(id),()->{synchronized(lock(id)){
        var state=load(id);if(Boolean.TRUE.equals(state.get("cleaned")))return status(id);stop(id);state=load(id);Path folder=dir(id);List<Path> paths;try(var files=Files.walk(folder)){paths=files.limit(300).toList();}if(paths.size()>=300)throw new IllegalStateException("Unexpected client storage; preserve for review");
        for(Path file:paths){
            if(Files.isSymbolicLink(file)||!file.toRealPath().startsWith(folder.toRealPath()))throw new IllegalStateException("Client storage changed; preserve for review");
            if(!Files.isRegularFile(file))continue;String relative=folder.relativize(file).toString().replace('\\','/');
            if(relative.startsWith("worker/")){
                String name=relative.substring(7);if(Files.size(file)>1024*1024)throw new IllegalStateException("Owned worker size changed");if(!(state.get("worker_files_sha256") instanceof Map<?,?> hashes)||!Objects.equals(hashes.get(name),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))))throw new IllegalStateException("Owned worker changed; preserve for review: "+name);
            }else if(!Set.of("client.json","client.tmp","worker.log","client.log","process.log","startup-question.json","startup-question.json.tmp").contains(relative)&&!relative.matches("startup-answers/[a-f0-9]{64}\\.(json|json\\.tmp)")&&!relative.matches("operations/[a-f0-9]{64}\\.(json|tmp)"))throw new IllegalStateException("Unknown owned-client file; preserve for review");
        }
        for(Path file:paths.stream().sorted(Comparator.reverseOrder()).toList())if(!file.equals(folder)&&!file.equals(folder.resolve("client.json")))deleteOwned(file);
        var tombstone=new LinkedHashMap<String,Object>();for(String key:List.of("client_id","project","base_uuid"))tombstone.put(key,state.get(key));tombstone.put("state","cleaned");tombstone.put("cleaned",true);save(id,tombstone);JOURNALS.remove(id);DirectClientStorage.archive(root,folder);return status(id);}});
    }
    private static void deleteOwned(Path file)throws Exception{
        for(int attempt=0;;attempt++)try{Files.deleteIfExists(file);return;}catch(java.nio.file.FileSystemException locked){if(attempt>=20)throw locked;Thread.sleep(100);}
    }
    static boolean pythonEnvironmentInUse(String environmentId){
        try{var service=new DirectTestClientService();{
            for(Path path:DirectClientStorage.pending(service.root)){
                var state=loadState(path);if(!environmentId.equals(state.get("environment_id")))continue;
                if(!Set.of("stopped","cleaned").contains(state.get("state")))return true;
                if(state.get("worker_pid") instanceof Number pid){var process=ProcessHandle.of(pid.longValue());if(process.isPresent()&&process.get().isAlive()&&process.get().info().startInstant().map(Instant::toString).orElse("").equals(state.get("worker_started_at")))return true;}
            }
        }return false;}catch(Exception unavailable){return true;}
    }
    public static void shutdown(){
        for(var pipe:PIPES.values())pipe.close();PIPES.clear();
        try{var service=new DirectTestClientService();{for(Path path:DirectClientStorage.active(service.root))try{service.stop(path.getFileName().toString());}catch(Exception preserved){/* Keep ownership evidence; never terminate a different process. */}}}catch(Exception preserved){}
    }
    private static final class Pipe implements AutoCloseable {
        final Process process;final ExecutorService reader;final InputStream input;volatile boolean closed;
        Pipe(Path python,Path script,Path directory,Path runtimeTemp)throws Exception {
            var builder=new ProcessBuilder(python.toString(),"-I","-B","-u",script.toString());builder.directory(directory.toFile());builder.environment().clear();builder.environment().putAll(RunnerEnvironment.isolated(System.getenv()));if(runtimeTemp!=null){builder.environment().put("TEMP",runtimeTemp.toString());builder.environment().put("TMP",runtimeTemp.toString());}builder.redirectError(directory.resolve("worker.log").toFile());process=builder.start();input=process.getInputStream();reader=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"bridge-test-client-pipe");t.setDaemon(true);return t;});
        }
        synchronized Map<String,Object> call(Map<String,Object> request)throws Exception {
            long started=System.nanoTime();boolean attempted=false;
            if(closed||!process.isAlive())throw new DirectClientTransport.WorkerFailure(false,Map.of("failure_kind","worker_unavailable_before_request","pipe_request_state","not_sent","worker_pid",process.pid(),"worker_alive",process.isAlive()),null);
            byte[] bytes=JSON.toJson(request).getBytes(StandardCharsets.UTF_8);if(bytes.length>128*1024)throw new IllegalArgumentException("Direct request exceeds limit");
            try{
                attempted=true;process.getOutputStream().write(bytes);process.getOutputStream().write('\n');process.getOutputStream().flush();
                Future<String> reply=reader.submit(()->{var out=new ByteArrayOutputStream();for(int n;(n=input.read())!=-1;){if(n=='\n')return out.toString(StandardCharsets.UTF_8);if(out.size()>=256*1024)throw new IOException("Direct reply exceeds limit");out.write(n);}throw new EOFException("Direct worker closed");});
                @SuppressWarnings("unchecked") Map<String,Object> value=JSON.fromJson(reply.get(DirectClientTiming.replySeconds(request),TimeUnit.SECONDS),LinkedHashMap.class);if(value==null)throw new IOException("Invalid worker result");if(DirectClientTransport.shouldClose(value))close();return value;
            }catch(Exception unavailable){var diagnostics=Map.<String,Object>of("failure_kind",unavailable instanceof TimeoutException?"worker_reply_timeout":"worker_io_failure","error_type",unavailable.getClass().getSimpleName(),"pipe_request_state",attempted?"delivery_not_proven":"not_sent","worker_pid",process.pid(),"worker_alive",process.isAlive(),"elapsed_ms",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),"reply_timeout_s",DirectClientTiming.replySeconds(request));close();throw new DirectClientTransport.WorkerFailure(attempted,diagnostics,unavailable);}
        }

        boolean available(){return !closed&&process.isAlive();}
        public void close(){closed=true;process.destroy();try{input.close();process.getOutputStream().close();if(!process.waitFor(3,TimeUnit.SECONDS))process.destroyForcibly();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(IOException ignored){}reader.shutdownNow();}
    }
}
