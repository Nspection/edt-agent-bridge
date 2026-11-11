// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import com.google.gson.*;
import com.codepilot1c.core.mcp.host.McpRuntimeRouting;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;

/** Durable preparation. A claimed start is never replayed, including after an EDT restart. */
public final class RuntimePreparationService {
    public interface Backend {
        Map<String,Object> binding(String project)throws Exception;
        default Map<String,Object> refreshBinding(String project)throws Exception{return binding(project);}
        Map<String,Object> route(Map<String,Object> args)throws Exception;
        Map<String,Object> environment()throws Exception;
        Map<String,Object> prepareEnvironment(Consumer<String> reserved)throws Exception;
        Map<String,Object> startClient(Map<String,Object> request,String environment,Consumer<String> reserved)throws Exception;
        default Map<String,Object> nativePackage()throws Exception{return Map.of("ready",false,"reason","provide_verified_native_ZIP_and_sha256");}
        Map<String,Object> prepareHost(Map<String,Object> args,Consumer<String> reserved)throws Exception;
        void startHost(String id)throws Exception;
        Map<String,Object> ready(String engine,Map<String,Object> request,List<Map<String,Object>> resources)throws Exception;
        Map<String,Object> cleanup(String kind,String id)throws Exception;
    }
    private static final Gson JSON=new GsonBuilder().serializeNulls().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
    private static final Object CLAIMS=new Object();
    private static final ThreadLocal<Consumer<Map<String,Object>>> PROGRESS=new ThreadLocal<>();
    private static final Map<String,Object> LOCKS=new ConcurrentHashMap<>();
    private static final Set<String> ACTIVE=ConcurrentHashMap.newKeySet();
    private final Path root;private final Backend backend;
    public RuntimePreparationService(Path root,Backend backend)throws Exception{this.root=root.toAbsolutePath().normalize();this.backend=backend;Files.createDirectories(this.root);checkRoot();}
    public static RuntimePreparationService workspace()throws Exception{return new RuntimePreparationService(org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/runtime-preparations"),new RuntimePreparationBackend());}
    private void checkRoot()throws Exception{if(!root.toRealPath().equals(root)||Files.isSymbolicLink(root))throw new IllegalStateException("Preparation storage moved");}
    private String key(String id){return root+":"+id;}
    private Object lock(String id){return LOCKS.computeIfAbsent(key(id),k->new Object());}
    private Path file(String id)throws Exception{checkRoot();if(id==null||!id.matches("prep-[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid preparation_id");return root.resolve(id+".json");}
    @SuppressWarnings("unchecked") private Map<String,Object> read(String id)throws Exception{Path p=file(id);if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)>256*1024)throw new IllegalStateException("Preparation journal unavailable");var d=JSON.fromJson(Files.readString(p),LinkedHashMap.class);if(!id.equals(d.get("preparation_id")))throw new IllegalStateException("Preparation identity changed");return d;}
    private void write(Map<String,Object>d)throws Exception{String id=(String)d.get("preparation_id");synchronized(lock(id)){Path dest=file(id);if(Files.isSymbolicLink(dest))throw new IllegalStateException("Linked preparation journal");String data=JSON.toJson(d);if(data.length()>128*1024)throw new IllegalStateException("Preparation journal capacity");Path tmp=Files.createTempFile(root,"prep-",".tmp");try{Files.writeString(tmp,data,StandardCharsets.UTF_8);Files.move(tmp,dest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}var progress=PROGRESS.get();if(progress!=null)progress.accept(Map.of("preparation_id",id,"stage",d.getOrDefault("stage","reserved"),"state",d.get("state"),"engine",d.getOrDefault("engine","pending")));}}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object o){return o instanceof Map<?,?>?(Map<String,Object>)o:Map.of();}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> resources(Map<String,Object>d){return (List<Map<String,Object>>)d.get("resources");}
    private static String required(Map<String,Object>a,String k){Object v=a.get(k);if(!(v instanceof String s)||s.isBlank()||s.length()>256)throw new IllegalArgumentException(k+" required (max256)");return s;}
    private static Map<String,Object> request(Map<String,Object>a){
        if(!Set.of("project","base_uuid","intent","engine","required_actions","previous_state","idempotency_key","native_path","expected_native_sha256","profile_id","client_kind","readiness_timeout_s").containsAll(a.keySet()))throw new IllegalArgumentException("Unsupported preparation field");
        String project=required(a,"project"),uuid=required(a,"base_uuid");if(!UUID.fromString(uuid).toString().equals(uuid))throw new IllegalArgumentException("Canonical base_uuid required");required(a,"idempotency_key");DirectClientStartup.timeout(a);DirectClientStartup.kind(a);
        McpRuntimeRouting.choose(a,false);if(a.containsKey("profile_id")&&!required(a,"profile_id").matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid profile_id");
        if(a.containsKey("native_path")!=a.containsKey("expected_native_sha256"))throw new IllegalArgumentException("Provide both Native path and SHA256 or omit both");
        if(a.containsKey("native_path"))required(a,"native_path");if(a.containsKey("expected_native_sha256")&&!required(a,"expected_native_sha256").matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Native SHA256 required");
        return new LinkedHashMap<>(a);
    }
    private void current(Map<String,Object>d)throws Exception{var a=object(d.get("request"));var live=backend.binding((String)a.get("project"));if(!Boolean.TRUE.equals(live.get("configuration_synchronized")))throw new IllegalStateException("NEEDS_UPDATE");if(!TaskSessionService.fingerprint(live).equals(d.get("binding_sha256")))throw new IllegalStateException("BINDING_CHANGED");}
    private void timing(Map<String,Object>d){long now=System.currentTimeMillis();var timings=new LinkedHashMap<>(object(d.get("phase_timings_ms")));String previous=Objects.toString(d.get("stage"),"queued");long delta=Math.max(0,now-((Number)d.getOrDefault("phase_started_ms",now)).longValue());timings.put(previous,((Number)timings.getOrDefault(previous,0)).longValue()+delta);d.put("phase_timings_ms",timings);d.put("phase_started_ms",now);d.put("elapsed_ms",Math.max(0,now-((Number)d.getOrDefault("created_at_ms",now)).longValue()));}
    private void stage(Map<String,Object>d,String stage)throws Exception{timing(d);d.put("stage",stage);write(d);}
    private void resource(Map<String,Object>d,String kind,String id,boolean created){try{if(resources(d).stream().noneMatch(r->kind.equals(r.get("kind"))&&id.equals(r.get("id")))){resources(d).add(new LinkedHashMap<>(Map.of("kind",kind,"id",id,"created",created,"cleanup_state",created?"pending":"borrowed")));write(d);}}catch(Exception e){throw new IllegalStateException("Cannot journal reserved resource",e);}}
    private static String family(Map<String,Object>a){String engine=Objects.toString(a.get("engine"),"auto");if(engine.equals("vanessa")||"scenario".equals(a.get("intent")))return "borrowed";return engine.equals("native")||Set.of("code","query","data").contains(Objects.toString(a.get("intent"),""))?"native":"testclient";}
    private void guardOtherPreparations(String id,Map<String,Object>a)throws Exception{
        try(var paths=Files.list(root)){var files=paths.filter(p->p.getFileName().toString().matches("prep-[a-f0-9]{64}[.]json")).limit(513).toList();if(files.size()>512)throw new IllegalStateException("Preparation journal limit; archive reviewed cleaned receipts");
            for(Path p:files){String other=p.getFileName().toString().replace(".json","");if(other.equals(id))continue;var d=read(other);if(!Objects.equals(object(d.get("request")).get("project"),a.get("project"))||"cleaned".equals(d.get("state")))continue;
                boolean unresolved=ACTIVE.contains(key(other))||Set.of("reserved","preparing","outcome_unknown","awaiting_runtime","cleanup_incomplete").contains(d.get("state"));
                if(unresolved||!resources(d).isEmpty()&&!"borrowed".equals(family(a))&&(!"ready".equals(d.get("state"))||family(a).equals(d.get("engine"))))throw new IllegalStateException("Inspect/cleanup original preparation before a new key: "+other);
                if(!resources(d).isEmpty()&&"ready".equals(d.get("state"))){
                    // A saved ready receipt cannot authorize a switch after channel loss.
                    current(d);var live=backend.ready((String)d.get("engine"),object(d.get("request")),resources(d));
                    if(!Boolean.TRUE.equals(live.get("ready")))throw new IllegalStateException("Inspect/cleanup original preparation before a new key: "+other);
                }
            }
        }
    }
    public Map<String,Object> prepare(Map<String,Object> arguments)throws Exception{
        var a=request(arguments);String id="prep-"+TaskSessionService.fingerprint(List.of(a.get("project"),a.get("idempotency_key")));
        synchronized(CLAIMS){synchronized(lock(id)){
            if(Files.exists(file(id))){var old=read(id);if(!TaskSessionService.fingerprint(a).equals(old.get("request_sha256")))throw new IllegalStateException("Idempotency key arguments changed");return status(id);}
            guardOtherPreparations(id,a);
            var d=new LinkedHashMap<String,Object>();d.put("preparation_id",id);d.put("request",a);d.put("request_sha256",TaskSessionService.fingerprint(a));d.put("resources",new ArrayList<>());d.put("state","reserved");d.put("created_at_ms",System.currentTimeMillis());d.put("phase_started_ms",System.currentTimeMillis());write(d);
            CountDownLatch gate=new CountDownLatch(1);var armed=new java.util.concurrent.atomic.AtomicBoolean();ACTIVE.add(key(id));
            try{String job=BackgroundJobRegistry.getInstance().startJob("runtime-preparation",progress->{try{PROGRESS.set(progress);gate.await();if(!armed.get())return JSON.toJson(Map.of("state","outcome_unknown","preparation_id",id));run(id);ACTIVE.remove(key(id));return JSON.toJson(status(id));}finally{PROGRESS.remove();ACTIVE.remove(key(id));}});d.put("job_id",job);write(d);armed.set(true);}
            finally{gate.countDown();if(!armed.get())ACTIVE.remove(key(id));}
            return brief(d);
        }}
    }
    private void run(String id)throws Exception{
        var d=read(id);var a=object(d.get("request"));
        try{
            if(Set.of("running","outcome_unknown").contains(Objects.toString(a.get("previous_state"),""))){d.put("state","blocked");d.put("reason","inspect_original_operation");write(d);return;}
            stage(d,"checking_binding");var binding=backend.binding((String)a.get("project"));
            if(!a.get("base_uuid").equals(binding.get("base_uuid"))){d.put("state","blocked");d.put("reason","base_uuid_mismatch_or_unbound");write(d);return;}
            if(!Boolean.TRUE.equals(binding.get("configuration_synchronized"))){
                stage(d,"refreshing_equality");var before=new LinkedHashMap<>(binding);before.remove("configuration_synchronized");binding=backend.refreshBinding((String)a.get("project"));var after=new LinkedHashMap<>(binding);after.remove("configuration_synchronized");if(!TaskSessionService.fingerprint(before).equals(TaskSessionService.fingerprint(after)))throw new IllegalStateException("BINDING_CHANGED");
            }
            if(!Boolean.TRUE.equals(binding.get("configuration_synchronized"))){d.put("state","needs_update");d.put("reason","native_EQUAL_required_no_update_dispatched");write(d);return;}
            d.put("binding_sha256",TaskSessionService.fingerprint(binding));
            stage(d,"selecting_engine");var routeArgs=new LinkedHashMap<String,Object>();for(String k:List.of("project","intent","engine","required_actions","previous_state"))if(a.containsKey(k))routeArgs.put(k,a.get(k));var plan=backend.route(routeArgs);
            if(!"planned".equals(plan.get("state"))){d.put("state","blocked");d.put("reason",plan.getOrDefault("reason","route_unavailable"));write(d);return;}
            String engine=(String)plan.get("engine");d.put("engine",engine);write(d);current(d);
            if(engine.equals("testclient")){
                stage(d,"preparing_python");var env=backend.environment();
                if(!Boolean.TRUE.equals(env.get("ready"))){if(!"needs_setup".equals(env.get("state")))throw new IllegalStateException("Inspect original Python preparation");env=backend.prepareEnvironment(e->resource(d,"python_environment",e,true));}
                if(!Boolean.TRUE.equals(env.get("ready")))throw new IllegalStateException("Managed Python not verified");String envId=required(env,"environment_id");resource(d,"python_environment",envId,Boolean.TRUE.equals(env.get("created")));current(d);
                stage(d,"starting_testclient");var client=backend.startClient(a,envId,c->resource(d,"testclient",c,true));
                if(!resources(d).stream().anyMatch(r->"testclient".equals(r.get("kind"))&&Objects.equals(r.get("id"),client.get("client_id"))))throw new IllegalStateException("Client identity not reserved");
            }else if(engine.equals("native")){
                var hostArgs=new LinkedHashMap<String,Object>(Map.of("project",a.get("project"),"mode","external"));
                if(a.containsKey("native_path")){hostArgs.put("native_path",a.get("native_path"));hostArgs.put("expected_native_sha256",a.get("expected_native_sha256"));}
                else {stage(d,"preparing_native_package");var artifact=backend.nativePackage();if(!Boolean.TRUE.equals(artifact.get("ready"))){d.put("state","needs_native_package");d.put("reason",artifact.getOrDefault("reason","native_package_unavailable"));write(d);return;}hostArgs.put("native_path",artifact.get("native_path"));hostArgs.put("expected_native_sha256",artifact.get("expected_native_sha256"));hostArgs.put("managed_refresh",true);}
                stage(d,"preparing_native");var host=backend.prepareHost(hostArgs,h->resource(d,"host",h,true));String hostId=required(host,"host_id");current(d);
                stage(d,"starting_native");backend.startHost(hostId);
            }
            stage(d,"checking_runtime");current(d);var ready=backend.ready(engine,a,resources(d));d.put("runtime",ready);d.put("state",Boolean.TRUE.equals(ready.get("ready"))?"ready":engine.equals("native")?"awaiting_runtime":"needs_runner_setup");write(d);
        }catch(Exception failure){d.put("state",resources(d).isEmpty()?"blocked":"outcome_unknown");d.put("reason","preparation_failed_inspect_original_stage_and_resources");write(d);if(failure instanceof InterruptedException)Thread.currentThread().interrupt();}
        finally{timing(d);d.put("timing_finalized",true);write(d);}
    }
    public static Set<String> prepareOperations(Map<String,Object>a){
        var result=new HashSet<String>();result.add("edt_infobase_state");result.add("edt_infobase_refresh");String engine=Objects.toString(a.get("engine"),"auto"),intent=Objects.toString(a.get("intent"),"");
        if(engine.equals("testclient")||engine.equals("auto")&&intent.equals("ui"))result.add("edt_test_client");
        if(engine.equals("vanessa")||engine.equals("auto")&&Set.of("ui","scenario").contains(intent)){result.add("edt_runner_profile");result.add("edt_runtime_catalog");}
        if(engine.equals("native")||engine.equals("auto")&&Set.of("code","query","data").contains(intent))result.add("edt_runtime_host");return result;
    }
    public Set<String> operations(Map<String,Object>a)throws Exception{
        if("prepare".equals(a.get("action")))return prepareOperations(a);
        var d=read(required(a,"preparation_id"));var result=new HashSet<String>();
        if(Set.of("status","wait","details").contains(a.get("action")))result.addAll(prepareOperations(object(d.get("request"))));
        for(var r:resources(d))result.add("host".equals(r.get("kind"))?"edt_runtime_host":"edt_test_client");return result;
    }
    private static void describeTiming(Map<String,Object> out){var phases=object(out.get("phase_timings_ms"));if(out.get("elapsed_ms") instanceof Number elapsed){out.put("slow_preparation",elapsed.longValue()>=10000);out.put("slow_threshold_ms",10000);out.put("slowest_stage",phases.entrySet().stream().max(Comparator.comparingLong(e->((Number)e.getValue()).longValue())).map(Map.Entry::getKey).orElse("not_observed"));}}
    private Map<String,Object> brief(Map<String,Object>d){var out=new LinkedHashMap<String,Object>();for(String k:List.of("preparation_id","state","stage","reason","engine","job_id","runtime","phase_timings_ms","elapsed_ms"))if(d.containsKey(k))out.put(k,d.get(k));var a=object(d.get("request"));out.put("project",a.get("project"));out.put("base_uuid",a.get("base_uuid"));out.put("resources",resources(d));describeTiming(out);out.put("ready",false);out.put("terminal",!ACTIVE.contains(key((String)d.get("preparation_id")))&&!"awaiting_runtime".equals(d.get("state")));out.put("configuration_updated",false);out.put("trust_changed",false);out.put("next_call",Map.of("tool","edt.run","arguments",Map.of("op","prepare","args",Map.of("action","status","preparation_id",d.get("preparation_id")))));return out;}
    public Map<String,Object> status(String id)throws Exception{
        synchronized(lock(id)){var d=read(id);var out=brief(d);if(ACTIVE.contains(key(id))){if(!Boolean.TRUE.equals(d.get("timing_finalized"))&&d.get("phase_started_ms") instanceof Number since){var times=new LinkedHashMap<>(object(d.get("phase_timings_ms")));String phase=Objects.toString(d.get("stage"),"queued");times.put(phase,((Number)times.getOrDefault(phase,0)).longValue()+Math.max(0,System.currentTimeMillis()-since.longValue()));out.put("phase_timings_ms",times);if(d.get("created_at_ms") instanceof Number began)out.put("elapsed_ms",Math.max(0,System.currentTimeMillis()-began.longValue()));}describeTiming(out);out.put("state","preparing");out.put("terminal",false);return out;}
            if(Set.of("reserved","preparing").contains(d.get("state"))){out.put("state","outcome_unknown");out.put("reason","interrupted_by_restart_no_replay");return out;}
            if(Set.of("ready","awaiting_runtime").contains(d.get("state"))){try{current(d);var r=backend.ready((String)d.get("engine"),object(d.get("request")),resources(d));out.put("runtime",r);boolean ready=Boolean.TRUE.equals(r.get("ready"));out.put("ready",ready);boolean pending=!ready&&"awaiting_runtime".equals(d.get("state"))&&"native".equals(d.get("engine"))&&Set.of("starting","running").contains(Objects.toString(r.get("state"),""));out.put("state",ready?"ready":pending?"awaiting_runtime":"blocked");out.put("terminal",!pending);if(ready&&"awaiting_runtime".equals(d.get("state"))){d.put("state","ready");d.put("runtime",r);write(d);}}catch(Exception changed){out.put("state","blocked");out.put("reason","live_binding_or_runtime_unavailable");out.put("terminal",true);}}return out;
        }
    }
 public Map<String,Object> waitFor(String id,int timeout)throws Exception {
  String job;synchronized(lock(id)){job=(String)read(id).get("job_id");}
  return WorkflowReplies.waitFor(job,timeout,()->ACTIVE.contains(key(id)),()->status(id));
 }
    public Map<String,Object> cleanupPreview(String id)throws Exception{synchronized(lock(id)){if(ACTIVE.contains(key(id)))throw new IllegalStateException("Preparation still running");var d=read(id);var out=new LinkedHashMap<String,Object>();out.put("preparation_id",id);out.put("resources",resources(d));out.put("state",d.get("state"));out.put("plan_sha256",TaskSessionService.fingerprint(out));return out;}}
    public Map<String,Object> cleanup(String id,String expected)throws Exception{synchronized(lock(id)){
        var plan=cleanupPreview(id);if(!Objects.equals(expected,plan.get("plan_sha256")))throw new IllegalStateException("STALE_CLEANUP_PREVIEW");var d=read(id);var rs=resources(d);
        // Client/host before its environment. Borrowed resources are never stopped/deleted.
        var order=new ArrayList<>(rs);Collections.reverse(order);for(var r:order){if(!Boolean.TRUE.equals(r.get("created"))||"cleaned".equals(r.get("cleanup_state")))continue;
            r.put("cleanup_state","claimed");write(d);try{var result=backend.cleanup((String)r.get("kind"),(String)r.get("id"));if(!Boolean.TRUE.equals(result.get("cleaned")))throw new IllegalStateException("Cleanup not verified");r.put("cleanup_state","cleaned");write(d);}catch(Exception preserved){r.put("cleanup_state","preserved");d.put("state","cleanup_incomplete");write(d);return brief(d);}}
        d.put("state","cleaned");write(d);return brief(d);
    }}
}
