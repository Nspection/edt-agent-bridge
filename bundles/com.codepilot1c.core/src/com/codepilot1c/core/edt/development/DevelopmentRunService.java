package com.codepilot1c.core.edt.development;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import com.google.gson.Gson;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.edt.debug.EdtDebugService;
import com.codepilot1c.core.edt.ast.DebugStatusRequest;

/** Independent scenario worker and EDT debug channel, correlated by a persistent run id. */
public final class DevelopmentRunService {
    public record Profile(String project,Path platformBin,Path infobase,Path vanessa,Path template,String user,Path managerInfobase,String managerUser,String configuration,int timeout,boolean update,boolean visual,String expectedVanessaSha256) {}
    public interface Backend {
        void validate(Profile profile) throws Exception;
        void update(Profile profile,NullProgressMonitor monitor) throws Exception;
        Map<String,Object> debug(Profile profile) throws Exception;
        boolean suspended(Profile profile) throws Exception;
        Map<String,Object> managed(String action,Path workspace,Map<String,String> parameters) throws Exception;
        default void closeDebug(Profile profile,Map<String,Object> session) throws Exception {}
    }
    private static volatile DevelopmentRunService instance;
    public static DevelopmentRunService getInstance() throws Exception {
        if(instance==null){synchronized(DevelopmentRunService.class){if(instance==null){Path storage=ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/development");instance=new DevelopmentRunService(storage,new PlatformBackend(storage),new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),task->{Thread thread=new Thread(task,"codepilot-development-run");thread.setDaemon(true);return thread;}));}}}
        return instance;
    }
    private final Path storage;private final Backend backend;private final ExecutorService workers;
    private final Map<String,Run> runs=new LinkedHashMap<>();private final Map<String,String> requests=new HashMap<>(),activeBases=new HashMap<>();
    public DevelopmentRunService(Path storage,Backend backend,ExecutorService workers) throws Exception {this.storage=storage;this.backend=backend;this.workers=workers;Files.createDirectories(storage.resolve("runs"));}
    private static final Gson GSON=new Gson();
    static final class Run {
        final String id,key,fingerprint,base;final Profile profile;final Path controller;final AtomicBoolean cancel=new AtomicBoolean();final NullProgressMonitor monitor=new NullProgressMonitor();
        final List<Map<String,Object>> events=new ArrayList<>();String phase="queued",vaId,progressKey;long sequence=0;Map<String,Object> va=Map.of(),debug=Map.of();String error;boolean terminal;
        Run(String id,String key,String fingerprint,String base,Profile profile,Path controller){this.id=id;this.key=key;this.fingerprint=fingerprint;this.base=base;this.profile=profile;this.controller=controller;}
    }
    public synchronized Map<String,Object> start(Map<String,Object> arguments) throws Exception {
        String project=required(arguments,"project"),request=required(arguments,"idempotency_key");
        if(request.length()>200){throw new IllegalArgumentException("idempotency_key is too long");}
        String key=project+"|"+request,fingerprint=GSON.toJson(new TreeMap<>(arguments));
        if(requests.containsKey(key)){Run old=runs.get(requests.get(key));if(!old.fingerprint.equals(fingerprint)){throw new IllegalArgumentException("Idempotency key was already used for different arguments");}return snapshot(old,0);}
        Profile profile=new Profile(project,Path.of(required(arguments,"platform_bin")).toAbsolutePath().normalize(),Path.of(required(arguments,"infobase_path")).toAbsolutePath().normalize(),Path.of(required(arguments,"vanessa_epf")).toAbsolutePath().normalize(),Path.of(required(arguments,"params_template")).toAbsolutePath().normalize(),(String)arguments.getOrDefault("user_name","Администратор"),Path.of((String)arguments.getOrDefault("manager_infobase_path",required(arguments,"infobase_path"))).toAbsolutePath().normalize(),(String)arguments.getOrDefault("manager_user_name",arguments.getOrDefault("user_name","Администратор")),(String)arguments.get("configuration_name"),((Number)arguments.getOrDefault("timeout_s",600)).intValue(),Boolean.TRUE.equals(arguments.get("update_db")),!Boolean.FALSE.equals(arguments.get("visual_checkpoint")),(String)arguments.get("expected_vanessa_sha256"));
        if(profile.timeout<30 || profile.timeout>7200){throw new IllegalArgumentException("timeout_s must be between 30 and 7200");}
        backend.validate(profile);
        String base=baseIdentity(profile.infobase);
        var bases=new LinkedHashSet<String>(List.of(base,baseIdentity(profile.managerInfobase)));
        for(String path:bases){if(activeBases.containsKey(path)){throw new IllegalStateException("Another development run owns this target or manager infobase: "+activeBases.get(path));}}
        if(runs.size()>=128){var completed=runs.values().stream().filter(r->r.terminal).findFirst();if(completed.isEmpty()){throw new RejectedExecutionException("Development run registry is full");}Run old=completed.get();runs.remove(old.id);requests.remove(old.key);}
        String id="dev-"+UUID.randomUUID();Path controller=runnerController(storage,project);
        Run run=new Run(id,key,fingerprint,base,profile,controller);runs.put(id,run);requests.put(key,id);for(String path:bases){activeBases.put(path,id);}
        try{event(run,"queued",Map.of());workers.submit(()->execute(run));}catch(Exception e){runs.remove(id);requests.remove(key);activeBases.values().removeIf(id::equals);throw e;}
        return snapshot(run,0);
    }
    private static String baseIdentity(Path path) throws Exception {return (Files.exists(path)?path.toRealPath():path.toAbsolutePath().normalize()).toString().toLowerCase(Locale.ROOT);}
    static Path runnerController(Path workspaceStorage,String project) throws Exception {
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        String workspace=java.util.HexFormat.of().formatHex(digest.digest(workspaceStorage.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8))).substring(0,12);
        String name=java.util.HexFormat.of().formatHex(digest.digest(project.getBytes(StandardCharsets.UTF_8))).substring(0,12);
        return Path.of(System.getenv().getOrDefault("LOCALAPPDATA",System.getProperty("java.io.tmpdir")),"CodePilot1C","qa",workspace,name);
    }
    public synchronized Map<String,Object> status(String id,long after) throws Exception {
        Run run=runs.get(id);if(run!=null){return snapshot(run,after);}
        if(id==null || !id.matches("dev-[0-9a-f-]{36}")){throw new IllegalArgumentException("Invalid run_id");}
        Path saved=storage.resolve("runs").resolve(id+".json");if(!Files.isRegularFile(saved)){throw new IllegalArgumentException("Unknown development run");}
        @SuppressWarnings("unchecked") Map<String,Object> result=GSON.fromJson(Files.readString(saved,StandardCharsets.UTF_8),Map.class);
        if(!Boolean.TRUE.equals(result.get("terminal"))){result.put("phase_before_restart",result.get("phase"));result.put("phase","interrupted_by_restart");result.put("note","Read managed controller state before starting a replacement; no previous worker was resumed.");}return result;
    }
    public synchronized Map<String,Object> cancel(String id) throws Exception {Run run=owned(id);if(run.terminal){return snapshot(run,0);}run.cancel.set(true);run.monitor.setCanceled(true);event(run,"cancel_requested",Map.of());return snapshot(run,0);}
    public Map<String,Object> continueRun(String id,String vaRunId,String checkpoint,String screenshotHash) throws Exception {
        Run run; synchronized(this){run=owned(id);if(run.terminal || run.cancel.get() || !"awaiting_visual_review".equals(run.phase) || !Objects.equals(run.vaId,vaRunId)){throw new IllegalStateException("Run is not awaiting this visual checkpoint");}}
        Map<String,Object> answer=backend.managed("Continue",run.controller,Map.of("RunId",vaRunId,"CheckpointId",checkpoint,"ScreenshotSha256",screenshotHash));
        synchronized(this){run.va=answer;event(run,"visual_review_accepted",Map.of("checkpoint_id",checkpoint));return snapshot(run,0);}
    }
    private Run owned(String id){Run run=runs.get(id);if(run==null){throw new IllegalArgumentException("Unknown or inactive run_id");}return run;}
    private void execute(Run run) {
        try {
            checkCancel(run);
            // Stop only processes marked by this project's prior managed runner.
            backend.managed("Stop",run.controller,Map.of());checkCancel(run);
            if(run.profile.update){transition(run,"updating_schema",Map.of());backend.update(run.profile,run.monitor);checkCancel(run);transition(run,"schema_updated",Map.of("application_migrations_verified",false));}
            transition(run,"starting_debug",Map.of());Map<String,Object> debug=backend.debug(run.profile);synchronized(this){run.debug=debug;}
            Object urls=debug.get("debug_server_urls");if(!(urls instanceof List<?> list) || list.isEmpty()){throw new IllegalStateException("EDT did not provide a debugger URL");}
            checkCancel(run);transition(run,"starting_scenario",Map.of());
            Map<String,String> parameters=new LinkedHashMap<>();parameters.put("RunId",run.id);synchronized(this){run.vaId=run.id;}parameters.put("PlatformBin",run.profile.platformBin.toString());parameters.put("Infobase",run.profile.infobase.toString());parameters.put("ManagerInfobase",run.profile.managerInfobase.toString());parameters.put("ManagerUserName",run.profile.managerUser);parameters.put("VanessaEpf",run.profile.vanessa.toString());parameters.put("ParamsTemplate",run.profile.template.toString());parameters.put("UserName",run.profile.user);parameters.put("DebuggerUrl",String.valueOf(list.get(0)));parameters.put("TimeoutSeconds",String.valueOf(run.profile.timeout));if(run.profile.visual){parameters.put("VisualCheckpoint","");}
            Map<String,Object> va=backend.managed("Start",run.controller,parameters);
            synchronized(this){run.va=va;if(!Objects.equals(run.vaId,va.get("RunId"))){throw new IllegalStateException("Managed runner returned a different identity");}}
            if(run.vaId==null || run.vaId.equals("null")){throw new IllegalStateException("Managed runner returned no run identity");}
            boolean paused=false;
            while(true) {
                checkCancel(run);boolean suspended=backend.suspended(run.profile);
                if(suspended!=paused){backend.managed(suspended?"PauseClock":"ResumeClock",run.controller,Map.of("RunId",run.vaId));paused=suspended;}
                va=backend.managed("Status",run.controller,Map.of());
                if(!Objects.equals(run.vaId,va.get("RunId"))){throw new IllegalStateException("Managed controller was replaced by another run; no foreign process was stopped");}
                String status=String.valueOf(va.get("Status"));
                @SuppressWarnings("unchecked") Map<String,Object> progress=va.get("Progress") instanceof Map<?,?> m?(Map<String,Object>)m:Map.of();
                synchronized(this){run.va=va;String phase=suspended?"paused_by_debugger":String.valueOf(progress.getOrDefault("Phase","scenario_running"));String progressKey=GSON.toJson(progress);if(!phase.equals(run.phase) || !Objects.equals(progressKey,run.progressKey)){run.progressKey=progressKey;event(run,phase,Map.of("progress",progress));}}
                if(Set.of("passed","failed","timeout","exited_without_report","stopped").contains(status)) {
                    if(!"passed".equals(status)){throw new IllegalStateException("Vanessa run ended with status="+status);}
                    @SuppressWarnings("unchecked") Map<String,Object> report=va.get("Report") instanceof Map<?,?> m?(Map<String,Object>)m:Map.of();
                    if(!(report.get("Tests") instanceof Number count) || count.intValue()<1){throw new IllegalStateException("Vanessa reported no executed tests");}
                    stopOwn(run);finish(run,"completed",null);return;
                }
                Thread.sleep(500);
            }
        } catch(CancellationException e){try{stopOwn(run);}catch(Exception stop){finish(run,"cleanup_failed",stop.getMessage());return;}finish(run,"cancelled",null);}
        catch(Exception e){try{stopOwn(run);}catch(Exception stop){finish(run,"cleanup_failed",e.getMessage()+"; cleanup: "+stop.getMessage());return;}finish(run,run.cancel.get()?"cancelled":"failed",e.getMessage());}
    }
    private void checkCancel(Run run){if(run.cancel.get()){throw new CancellationException();}}
    private void stopOwn(Run run) throws Exception {
        Exception failure=null;
        if(run.vaId!=null){try{Map<String,Object> current=backend.managed("Status",run.controller,Map.of());if(Objects.equals(run.vaId,current.get("RunId"))){var cleanup=backend.managed("Stop",run.controller,Map.of());synchronized(this){run.va=new LinkedHashMap<>(run.va);run.va.put("OwnedPids",List.of());run.va.put("Cleanup",cleanup);run.va.put("owned_processes_closed",true);}}}catch(Exception e){failure=e;}}
        try{backend.closeDebug(run.profile,run.debug);}catch(Exception e){if(failure==null){failure=e;}else{failure.addSuppressed(e);}}
        if(failure!=null){throw failure;}
    }
    private synchronized void transition(Run run,String phase,Map<String,Object> details) throws Exception {event(run,phase,details);}
    private synchronized void finish(Run run,String phase,String error){run.error=error;run.terminal=true;activeBases.values().removeIf(run.id::equals);try{event(run,phase,Map.of());}catch(Exception ignored){run.phase=phase;}}
    private void event(Run run,String phase,Map<String,Object> details) throws Exception {
        run.phase=phase;Map<String,Object> event=new LinkedHashMap<>();event.put("sequence",++run.sequence);event.put("at",Instant.now().toString());event.put("phase",phase);event.putAll(details);run.events.add(event);if(run.events.size()>500){run.events.remove(0);}persist(run);
    }
    private Map<String,Object> snapshot(Run run,long after) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("run_id",run.id);result.put("project",run.profile.project);result.put("phase",run.phase);result.put("terminal",run.terminal);result.put("event_cursor",run.sequence);result.put("events",run.events.stream().filter(e->((Number)e.get("sequence")).longValue()>after).toList());result.put("managed_run_id",run.vaId);result.put("managed",run.va);result.put("debug",run.debug);result.put("controller_workspace",run.controller.toString());result.put("error",run.error);result.put("application_migrations_verified",false);result.put("note","Schema update and scenario success do not certify all application migration handlers. Native VA action timeouts remain independent of the pause-aware runner watchdog.");return result;
    }
    private void persist(Run run) throws Exception {
        Path file=storage.resolve("runs").resolve(run.id+".json"),temporary=Files.createTempFile(storage.resolve("runs"),"state-",".json");Files.writeString(temporary,GSON.toJson(snapshot(run,0)),StandardCharsets.UTF_8);
        try{Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
    }
    private static String required(Map<String,Object> a,String key){Object v=a.get(key);if(!(v instanceof String s) || s.isBlank()){throw new IllegalArgumentException(key+" is required");}return s;}
    static final class PlatformBackend implements Backend {
        final ManagedVanessaProcess runner;final EdtRuntimeService runtime=new EdtRuntimeService();
        PlatformBackend(Path storage) throws Exception {runner=new ManagedVanessaProcess(storage);}
        public void validate(Profile profile) throws Exception {
            RunnerProfileStore.ensureSeparateBases(profile.managerInfobase,profile.infobase);
            RunnerScenarioParams.validate(profile.template,profile.visual);
            for(Path file:List.of(profile.platformBin.resolve("1cv8.exe"),profile.vanessa,profile.template)){if(!Files.isRegularFile(file)){throw new IllegalArgumentException("Missing runner input: "+file);}}
            if(profile.expectedVanessaSha256==null || !profile.expectedVanessaSha256.matches("[A-Fa-f0-9]{64}")){throw new IllegalArgumentException("needs_setup: save the verified Vanessa SHA256 in edt_runner_profile");}
            String actual=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(profile.vanessa)));
            if(!actual.equalsIgnoreCase(profile.expectedVanessaSha256)){throw new IllegalStateException("needs_setup: Vanessa package changed; verify it before updating the profile hash");}
            for(Path base:List.of(profile.infobase,profile.managerInfobase)){if(!Files.isRegularFile(base.resolve("1Cv8.1CD"))){throw new IllegalArgumentException("Missing file infobase: "+base);}}
            String connection=runtime.resolveDefaultInfobase(profile.project).getConnectionString().asConnectionString();
            var matcher=java.util.regex.Pattern.compile("(?i)(?:^|;)\\s*File\\s*=\\s*\"([^\"]+)\"").matcher(connection);
            if(!matcher.find() || !Path.of(matcher.group(1)).toAbsolutePath().normalize().equals(profile.infobase)){throw new IllegalArgumentException("Profile file infobase must match the project's connected EDT infobase");}
        }
        public void update(Profile profile,NullProgressMonitor monitor) throws Exception {
            if(Boolean.TRUE.equals(runtime.connectForDevelopment(profile.project,monitor).get("configuration_synchronized"))){return;}
            if(!runtime.updateInfobase(profile.project,true,monitor)){throw new IllegalStateException("EDT schema update did not complete successfully");}
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            do{if(Boolean.TRUE.equals(runtime.synchronizationState(profile.project).get("configuration_synchronized"))){return;}if(monitor.isCanceled()){throw new CancellationException();}Thread.sleep(200);}while(System.nanoTime()<deadline);
            throw new IllegalStateException("EDT update returned success but schema equality was not confirmed; inspect edt_infobase_state before launching");
        }
        public Map<String,Object> debug(Profile profile) throws Exception {
            if(!Boolean.TRUE.equals(runtime.connectForDevelopment(profile.project,new NullProgressMonitor()).get("configuration_synchronized"))){throw new IllegalStateException("needs_update: project and base configuration are not equal after connection; inspect edt_infobase_state");}
            String configuration=profile.configuration;
            if(configuration==null || configuration.isBlank()){configuration=(String)EdtDebugService.getInstance().prepareDebugConfiguration(profile.project,null,false).get("configuration");}
            return EdtDebugService.getInstance().launchDebugSession(profile.project,configuration,false);
        }
        public void closeDebug(Profile profile,Map<String,Object> session) {if(session.get("owned_debug_session_id") instanceof String id){EdtDebugService.getInstance().closeOwnedDebugSession(profile.project,id);}}
        public boolean suspended(Profile profile){return EdtDebugService.getInstance().debugStatus(new DebugStatusRequest(profile.project)).suspended();}
        public Map<String,Object> managed(String action,Path workspace,Map<String,String> params) throws Exception {return runner.call(action,workspace,params);}
    }
}
