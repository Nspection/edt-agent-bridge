// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import com.google.gson.*;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
/** One durable UI attempt; retries observe the receipt and never rerun a mutation. */
public final class UiRunService {
 public interface Backend {
  Map<String,Object> prepare(Map<String,Object> request,String key)throws Exception;
  Map<String,Object> client(String preparation)throws Exception;
  default Map<String,Object> preparationProgress(String preparation)throws Exception{return Map.of();}
  String openSession(String project,String client,String run)throws Exception;
  Map<String,Object> observe(String session,String client,Map<String,Object> timing)throws Exception;
  Map<String,Object> submit(String session,String client,Map<String,Object> action)throws Exception;
  Map<String,Object> collect(String session,String client,Map<String,Object> submitted)throws Exception;
  List<Map<String,Object>> capture(String client)throws Exception;
  Map<String,Object> cleanupPlan(String preparation)throws Exception;
  Map<String,Object> cleanup(String preparation,String expected)throws Exception;
  boolean closeSession(String session,String client)throws Exception;
 }
 private static final Gson JSON=new GsonBuilder().serializeNulls().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
 private static final Object CLAIM=new Object();private static final Map<String,Object> LOCKS=new ConcurrentHashMap<>();private static final Set<String> ACTIVE=ConcurrentHashMap.newKeySet();
 private final Path root;private final Backend backend;
 public UiRunService(Path root,Backend backend)throws Exception{this.root=root.toAbsolutePath().normalize();this.backend=backend;Files.createDirectories(this.root);checkRoot();}
 public static UiRunService workspace()throws Exception{return new UiRunService(org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/ui-runs"),new UiRunBackend());}
 private void checkRoot()throws Exception{if(!root.toRealPath().equals(root)||Files.isSymbolicLink(root))throw new IllegalStateException("UI run storage moved");}
 private Object lock(String id){return LOCKS.computeIfAbsent(root+":"+id,k->new Object());}private String key(String id){return root+":"+id;}
 private Path file(String id)throws Exception{checkRoot();if(id==null||!id.matches("ui-[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid ui_run_id");return root.resolve(id+".json");}
 @SuppressWarnings("unchecked") private Map<String,Object> read(String id)throws Exception{Path f=file(id);if(!Files.isRegularFile(f,LinkOption.NOFOLLOW_LINKS)||Files.size(f)>2*1024*1024)throw new IllegalStateException("UI run receipt unavailable");var d=JSON.fromJson(Files.readString(f),LinkedHashMap.class);if(!id.equals(d.get("ui_run_id")))throw new IllegalStateException("UI receipt identity changed");return d;}
 private void write(Map<String,Object>d)throws Exception{synchronized(lock((String)d.get("ui_run_id"))){Path f=file((String)d.get("ui_run_id"));if(Files.isSymbolicLink(f))throw new IllegalStateException("Linked UI receipt");byte[] data=JSON.toJson(d).getBytes(StandardCharsets.UTF_8);if(data.length>2*1024*1024)throw new IllegalStateException("UI receipt capacity exceeded");Path t=Files.createTempFile(root,"ui-",".tmp");try{Files.write(t,data);Files.move(t,f,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(t);}}}
 private static Map<String,Object> obj(Object o){return TaskSessionService.object(o);}
 private static String required(Map<String,Object>a,String k){if(!(a.get(k) instanceof String s)||s.isBlank()||s.length()>256)throw new IllegalArgumentException(k+" required (max256)");return s;}
 @SuppressWarnings("unchecked") private static List<Map<String,Object>> steps(Map<String,Object>a){return (List<Map<String,Object>>)a.get("steps");}
 public static Map<String,Object> validate(Map<String,Object>a){
  if(!Set.of("project","base_uuid","idempotency_key","steps","client_kind","readiness_timeout_s","capture","auto_cleanup","operation_timeout_s").containsAll(a.keySet()))throw new IllegalArgumentException("Unsupported UI run field");
  required(a,"project");required(a,"idempotency_key");String uuid=required(a,"base_uuid");if(!UUID.fromString(uuid).toString().equals(uuid))throw new IllegalArgumentException("Canonical base_uuid required");DirectClientStartup.kind(a);DirectClientStartup.timeout(a);
  if(!Set.of("none","final","on_failure","final_and_failure").contains(a.getOrDefault("capture","on_failure")))throw new IllegalArgumentException("Invalid capture mode");if(a.containsKey("auto_cleanup")&&!(a.get("auto_cleanup") instanceof Boolean))throw new IllegalArgumentException("auto_cleanup must be boolean");
  if(!(a.get("steps") instanceof List<?> list)||list.isEmpty()||list.size()>16||list.stream().anyMatch(x->!(x instanceof Map<?,?>)))throw new IllegalArgumentException("steps requires1..16 direct actions");
  var batch=Map.<String,Object>of("action","batch","snapshot_id","preflight","expected_window","","steps",a.get("steps"),"operation_timeout_s",DirectClientTiming.seconds(a));DirectTestClientService.validateAction(batch);
  for(var step:steps(a))if(!step.containsKey("expect")&&!"wait".equals(step.get("action"))&&!"table_read".equals(step.get("action")))throw new IllegalArgumentException("Every mutating step needs an explicit expect");
  var last=steps(a).get(steps(a).size()-1);if(!last.containsKey("expect")&&!"wait".equals(last.get("action"))&&!last.containsKey("expect_count")&&!last.containsKey("expect_rows"))throw new IllegalArgumentException("Final explicit UI assertion required");
  return JSON.fromJson(JSON.toJson(a),LinkedHashMap.class);
 }
 public Map<String,Object> start(Map<String,Object> input)throws Exception{
  var a=validate(input);String id="ui-"+TaskSessionService.fingerprint(List.of(a.get("project"),a.get("idempotency_key")));String digest=TaskSessionService.fingerprint(a);
  synchronized(CLAIM){synchronized(lock(id)){
   if(Files.exists(file(id))){var d=read(id);if(!digest.equals(d.get("request_sha256")))throw new IllegalStateException("UI key arguments changed");return status(id);}
   try(var stream=Files.list(root)){var files=stream.filter(f->f.getFileName().toString().matches("ui-[a-f0-9]{64}[.]json")).limit(513).toList();if(files.size()>512)throw new IllegalStateException("Archive reviewed UI receipts first");for(Path f:files){var old=read(f.getFileName().toString().replace(".json",""));if(Objects.equals(old.get("project"),a.get("project"))&&(!Boolean.TRUE.equals(old.get("terminal"))||Boolean.TRUE.equals(old.get("outcome_unknown"))||!Boolean.TRUE.equals(old.get("cleanup_verified"))))throw new IllegalStateException("Inspect/cleanup original UI run: "+old.get("ui_run_id"));}}
   var d=new LinkedHashMap<String,Object>();d.put("ui_run_id",id);d.put("project",a.get("project"));d.put("base_uuid",a.get("base_uuid"));d.put("request_sha256",digest);d.put("state","reserved");d.put("created_at_ms",System.currentTimeMillis());d.put("stage_at_ms",System.currentTimeMillis());d.put("phase_timings_ms",new LinkedHashMap<String,Object>());d.put("stage","queued");d.put("terminal",false);d.put("test_passed",false);d.put("cleanup_verified",false);d.put("total_steps",steps(a).size());d.put("timing_config",Map.of("operation_timeout_s",DirectClientTiming.seconds(a),"readiness_timeout_s",DirectClientStartup.timeout(a)));String prepKey="ui-run:"+id;d.put("preparation_id","prep-"+TaskSessionService.fingerprint(List.of(a.get("project"),prepKey)));write(d);
   var gate=new CountDownLatch(1);var armed=new java.util.concurrent.atomic.AtomicBoolean();ACTIVE.add(key(id));
   try{String job=BackgroundJobRegistry.getInstance().startJob("ui-run",progress->{gate.await();try{if(armed.get())execute(id,a,progress);ACTIVE.remove(key(id));return JSON.toJson(status(id));}finally{ACTIVE.remove(key(id));}});d.put("job_id",job);write(d);armed.set(true);}finally{gate.countDown();if(!armed.get())ACTIVE.remove(key(id));}return brief(d);
  }}
 }
 private void stage(Map<String,Object>d,String stage,Consumer<Map<String,Object>> progress)throws Exception{long now=System.currentTimeMillis();var timings=new LinkedHashMap<>(obj(d.get("phase_timings_ms")));String previous=Objects.toString(d.get("stage"),"queued");long elapsed=Math.max(0,now-((Number)d.getOrDefault("stage_at_ms",now)).longValue());timings.put(previous,((Number)timings.getOrDefault(previous,0)).longValue()+elapsed);d.put("phase_timings_ms",timings);d.put("stage_at_ms",now);d.put("elapsed_ms",Math.max(0,now-((Number)d.getOrDefault("created_at_ms",now)).longValue()));d.put("stage",stage);write(d);progress.accept(Map.of("ui_run_id",d.get("ui_run_id"),"stage",stage));}
 private void execute(String id,Map<String,Object>a,Consumer<Map<String,Object>> progress)throws Exception{
  var d=read(id);String client=null,session=null;boolean submitted=false,preparationAttempted=false;
  try{
   stage(d,"preparing",progress);preparationAttempted=true;var prep=backend.prepare(a,"ui-run:"+id);if(!Objects.equals(prep.get("preparation_id"),d.get("preparation_id")))throw new IllegalStateException("Preparation identity changed");
   var c=backend.client((String)d.get("preparation_id"));d.put("startup",select(c,"pid","process_started_at","client_kind","startup_phase","protocol_connected","startup_diagnostics","startup_timeline","startup_error_type","startup_observation"));client=(String)c.get("client_id");if(client!=null)d.put("client_id",client);
   d.put("preparation_timeline",select(prep,"phase_timings_ms","elapsed_ms","slow_preparation","slowest_stage","slow_threshold_ms"));if(!Boolean.TRUE.equals(prep.get("ready"))){boolean unknown="outcome_unknown".equals(prep.get("state"));d.put("outcome_unknown",unknown);d.put("state",unknown?"outcome_unknown":"blocked");d.put("failed_stage",d.get("stage"));d.put("reason",prep.getOrDefault("reason",prep.get("state")));return;}
   client=(String)c.get("client_id");d.put("client_id",client);write(d);stage(d,"opening_session",progress);session="task-"+UUID.nameUUIDFromBytes((a.get("project")+"\0"+id).getBytes(StandardCharsets.UTF_8));d.put("session_id",session);write(d);if(!session.equals(backend.openSession((String)a.get("project"),client,id)))throw new IllegalStateException("Task session identity changed");
   var initial=backend.observe(session,client,a.containsKey("operation_timeout_s")?Map.of("operation_timeout_s",a.get("operation_timeout_s")):Map.of());if(!Boolean.TRUE.equals(initial.get("success")))throw new IllegalStateException("Initial UI observation failed");
   String expected=(String)steps(a).get(0).get("expected_window");if(!expected.equals(obj(initial.get("window")).get("title"))){d.put("state","failed");d.put("failed_stage",d.get("stage"));d.put("reason","initial_window_mismatch_no_action_dispatched");return;}
   var action=new LinkedHashMap<String,Object>(Map.of("action","batch","snapshot_id",initial.get("snapshot_id"),"expected_window",expected,"idempotency_key","ui-run:"+id,"steps",steps(a),"response_mode","full"));if(a.containsKey("operation_timeout_s"))action.put("operation_timeout_s",a.get("operation_timeout_s"));
   stage(d,"dispatching",progress);submitted=true;d.put("execution_state","dispatch_claimed");write(d);var receipt=backend.submit(session,client,action);d.put("action_receipt",receipt);write(d);stage(d,"waiting_for_result",progress);
   var full=backend.collect(session,client,receipt);d.put("details",full);var summary=DirectTestClientService.present(full,Map.of("action","batch","response_mode","summary","steps",steps(a)),Objects.toString(receipt.get("operation_id"),"unavailable"));summary.put("details_tool","edt_ui_run");summary.put("details_action","details");summary.put("ui_run_id",id);if(full.containsKey("task_collection_verified"))summary.put("task_collection_verified",full.get("task_collection_verified"));if(full.containsKey("task_collection_error_type"))summary.put("task_collection_error_type",full.get("task_collection_error_type"));d.put("summary",summary);
   boolean unknown=Boolean.TRUE.equals(full.get("outcome_unknown"));d.put("outcome_unknown",unknown);boolean pass=!unknown&&!Boolean.FALSE.equals(full.get("task_collection_verified"))&&Boolean.TRUE.equals(full.get("success"))&&Boolean.TRUE.equals(full.get("postcondition_verified"))&&((Number)summary.get("verified_steps")).intValue()==steps(a).size();d.put("test_passed",pass);d.put("execution_state",unknown?"outcome_unknown":"observed");d.put("state",unknown?"outcome_unknown":pass?"completed":"failed");if(!pass)d.put("failed_stage",d.get("stage"));if(Boolean.FALSE.equals(full.get("task_collection_verified")))d.put("reason","task_collection_failed_inspect_saved_execution_result");write(d);
  }catch(Exception e){d.put("failed_stage",d.get("stage"));boolean unknown=submitted||preparationAttempted;d.put("state",unknown?"outcome_unknown":"failed");d.put("outcome_unknown",unknown);d.put("error_type",e.getClass().getSimpleName());d.put("reason","inspect_original_stage_and_receipts_no_replay");}
  finally{
   try{if(!d.containsKey("startup")){var c=backend.client((String)d.get("preparation_id"));d.put("startup",select(c,"pid","process_started_at","client_kind","startup_phase","startup_diagnostics","startup_timeline","startup_error_type","startup_observation"));}}catch(Exception unavailable){d.put("startup_observation_available",false);}
   String capture=Objects.toString(a.getOrDefault("capture","on_failure"));boolean pass=Boolean.TRUE.equals(d.get("test_passed"));if(client!=null&&!capture.equals("none")&&(capture.equals("final_and_failure")||pass&&capture.equals("final")||!pass&&capture.equals("on_failure")))try{stage(d,"capturing",progress);d.put("artifacts",backend.capture(client));}catch(Exception unavailable){d.put("capture_error_type",unavailable.getClass().getSimpleName());}
   write(d);if(!Boolean.FALSE.equals(a.getOrDefault("auto_cleanup",true))){stage(d,"cleanup",progress);cleanupOwn(d,client,session,null);}
   stage(d,"finished",progress);d.put("terminal",true);write(d);
  }
 }
 private static Map<String,Object> select(Map<String,Object> m,String...keys){var out=new LinkedHashMap<String,Object>();for(String k:keys)if(m.containsKey(k))out.put(k,m.get(k));return out;}
 private Map<String,Object> brief(Map<String,Object>d){var out=select(d,"ui_run_id","project","base_uuid","preparation_id","client_id","session_id","job_id","state","stage","terminal","test_passed","outcome_unknown","execution_state","failed_stage","reason","error_type","total_steps","timing_config","startup","preparation_timeline","summary","artifacts","capture_error_type","cleanup_verified","cleanup","cleanup_error_type","session_closed","phase_timings_ms","elapsed_ms");if(!Boolean.TRUE.equals(d.get("terminal"))&&d.get("created_at_ms") instanceof Number began)out.put("elapsed_ms",Math.max(0,System.currentTimeMillis()-began.longValue()));out.put("historical_result",true);out.put("automatic_replay",false);return out;}
 public Map<String,Object> status(String id)throws Exception{synchronized(lock(id)){var d=read(id);var out=brief(d);if(ACTIVE.contains(key(id)))out.put("terminal",false);if(ACTIVE.contains(key(id))&&"preparing".equals(d.get("stage")))try{out.put("preparation_progress",backend.preparationProgress((String)d.get("preparation_id")));var c=backend.client((String)d.get("preparation_id"));if(!c.isEmpty())out.put("startup",select(c,"pid","process_started_at","startup_phase","startup_timeline","startup_diagnostics","startup_error_type"));}catch(Exception unavailable){out.put("live_progress_available",false);}if(!ACTIVE.contains(key(id))&&!Boolean.TRUE.equals(d.get("terminal"))){out.put("state","outcome_unknown");out.put("outcome_unknown",true);out.put("terminal",true);out.put("reason","interrupted_or_unclaimed_no_replay");}return out;}}
 public Map<String,Object> details(String id)throws Exception{synchronized(lock(id)){return read(id);}}
 private void cleanupOwn(Map<String,Object>d,String client,String session,Map<String,Object> expectedPlan)throws Exception{
  try{var plan=expectedPlan==null?backend.cleanupPlan((String)d.get("preparation_id")):expectedPlan;var cleanup=backend.cleanup((String)d.get("preparation_id"),(String)plan.get("plan_sha256"));d.put("cleanup",cleanup);d.put("cleanup_verified",Boolean.TRUE.equals(cleanup.get("cleaned")));}catch(Exception preserved){d.put("cleanup_verified",false);d.put("cleanup_error_type",preserved.getClass().getSimpleName());}
  if(session!=null&&client!=null)try{d.put("session_closed",backend.closeSession(session,client));}catch(Exception preserved){d.put("session_closed",false);}write(d);
 }
 public Map<String,Object> cleanupPreview(String id)throws Exception{synchronized(lock(id)){if(ACTIVE.contains(key(id)))throw new IllegalStateException("UI run still active");var d=read(id);var out=select(d,"ui_run_id","preparation_id","client_id","session_id","terminal","state","cleanup_verified");out.put("preparation_plan",backend.cleanupPlan((String)d.get("preparation_id")));out.put("plan_sha256",TaskSessionService.fingerprint(out));return out;}}
 public Map<String,Object> cleanup(String id,String expected)throws Exception{synchronized(lock(id)){var preview=cleanupPreview(id);if(!Objects.equals(preview.get("plan_sha256"),expected))throw new IllegalStateException("STALE_CLEANUP_PREVIEW");var d=read(id);cleanupOwn(d,(String)d.get("client_id"),(String)d.get("session_id"),obj(preview.get("preparation_plan")));return status(id);}}
}
