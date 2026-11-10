// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.google.gson.*;
import com.codepilot1c.core.mcp.host.*;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
/** Fixed direct TestClient adapter to existing preparation, session and action journals. */
public final class UiRunBackend implements UiRunService.Backend {
 private static final Gson JSON=new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
 static void require(String tool){var cfg=McpHostConfigStore.getInstance().load();if(cfg.getMutationPolicy()!=McpHostConfig.MutationPolicy.ALLOW||!new DefaultMcpToolExposurePolicy(cfg).isExposed(tool))throw new IllegalStateException("UI run dependency unavailable: "+tool);}
 public static Set<String> dependencies(){return Set.of("edt_runtime_prepare","edt_test_client","edt_form_state","edt_form_action","edt_task_session","edt_task_step","edt_operation_status");}
 private static Path storage(){return org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core");}
 private TaskSessionService session(String client)throws Exception{return new TaskSessionService(storage().resolve("ui-run-sessions"),new DirectSession(client));}
 public Map<String,Object> prepare(Map<String,Object> a,String key)throws Exception{
  require("edt_runtime_prepare");var args=new LinkedHashMap<String,Object>(Map.of("project",a.get("project"),"base_uuid",a.get("base_uuid"),"intent","ui","engine","testclient","idempotency_key",key));for(String k:List.of("client_kind","readiness_timeout_s"))if(a.containsKey(k))args.put(k,a.get(k));
  var service=RuntimePreparationService.workspace();var r=service.prepare(args);if(r.get("job_id") instanceof String job)await(job);return service.status((String)r.get("preparation_id"));
 }
 public Map<String,Object> preparationProgress(String preparation)throws Exception{require("edt_runtime_prepare");var r=RuntimePreparationService.workspace().status(preparation);return AgentBrief.select(r,"state","stage","engine","terminal","reason","job_id","phase_timings_ms","elapsed_ms","slow_preparation","slowest_stage","slow_threshold_ms");}
 public Map<String,Object> client(String preparation)throws Exception{require("edt_test_client");var p=RuntimePreparationService.workspace().status(preparation);for(Object o:(List<?>)p.get("resources")){var r=TaskSessionService.object(o);if("testclient".equals(r.get("kind")))return new DirectTestClientService().status((String)r.get("id"));}return Map.of();}
 public String openSession(String project,String client,String run)throws Exception{require("edt_task_session");var s=session(client);String id=(String)s.open(project,null,null,run,true).get("session_id");if(!Boolean.TRUE.equals(s.verifyClient(id).get("live_client_binding_verified")))throw new IllegalStateException("Direct task client unavailable");return id;}
 public Map<String,Object> observe(String id,String client,Map<String,Object> timing)throws Exception{require("edt_form_state");var a=new LinkedHashMap<String,Object>(timing);a.put("view","windows");return session(client).call(id,"initial","edt_form_state",a,Map.of());}
 public Map<String,Object> submit(String id,String client,Map<String,Object> action)throws Exception{require("edt_form_action");return session(client).call(id,"batch","edt_form_action",action,Map.of());}
 public Map<String,Object> collect(String id,String client,Map<String,Object> submitted)throws Exception{String job=(String)submitted.get("job_id");if(job!=null)await(job);var full=new DirectTestClientService().result(client,(String)submitted.get("operation_id"));return collectedResult(full,()->session(client).stepStatus(id,"batch"));}
 interface CollectionCheck {void run()throws Exception;}
 static Map<String,Object> collectedResult(Map<String,Object> full,CollectionCheck check){var result=new LinkedHashMap<>(full);try{check.run();result.put("task_collection_verified",true);}catch(Exception failure){result.put("task_collection_verified",false);result.put("task_collection_error_type",failure.getClass().getSimpleName());}return result;}
 static void requireReceipt(Path receipt){if(!Files.isRegularFile(receipt,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("Original preparation receipt unavailable; cleanup ownership cannot be verified");}
 public List<Map<String,Object>> capture(String client)throws Exception{
  require("edt_process_snapshot");var s=new DirectTestClientService().status(client);if(!Boolean.TRUE.equals(s.get("live_client_binding_verified")))throw new IllegalStateException("Owned client not verified for capture");long pid=((Number)s.get("pid")).longValue();var rows=(List<?>)ProcessWindowCapture.execute("list",pid,null,null).get("processes");var process=TaskSessionService.object(rows.get(0));if(!Objects.equals(process.get("process_started_at"),s.get("process_started_at")))throw new IllegalStateException("Owned process changed");var windows=((List<?>)process.get("windows")).stream().map(TaskSessionService::object).filter(w->Boolean.TRUE.equals(w.get("visible"))).toList();var result=new ArrayList<Map<String,Object>>();for(var w:windows.stream().limit(4).toList())try{result.add(ProcessWindowCapture.execute("snapshot",pid,(String)s.get("process_started_at"),(String)w.get("window_id")));}catch(Exception unavailable){result.add(Map.of("window_id",w.get("window_id"),"capture_error_type",unavailable.getClass().getSimpleName()));}return result;
 }
 public Map<String,Object> cleanupPlan(String preparation)throws Exception{
  require("edt_runtime_prepare");if(preparation==null||!preparation.matches("prep-[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid preparation identity");Path receipt=storage().resolve("runtime-preparations").resolve(preparation+".json");requireReceipt(receipt);return RuntimePreparationService.workspace().cleanupPreview(preparation);
 }
 public Map<String,Object> cleanup(String preparation,String expected)throws Exception{
  require("edt_runtime_prepare");var plan=cleanupPlan(preparation);if(!Objects.equals(expected,plan.get("plan_sha256")))throw new IllegalStateException("STALE_PREPARATION_CLEANUP");
  var r=RuntimePreparationService.workspace().cleanup(preparation,expected);return Map.of("cleaned","cleaned".equals(r.get("state")),"resources",r.get("resources"),"business_data_removed",false);
 }
 public boolean closeSession(String id,String client)throws Exception{require("edt_task_session");return "closed".equals(session(client).close(id).get("state"));}
 static Map<String,Object> await(String id)throws Exception{
  require("edt_operation_status");long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(10);while(System.nanoTime()<deadline){var s=BackgroundJobRegistry.getInstance().awaitStatus(id,25000).orElseThrow(()->new IllegalStateException("Original job expired; no replay"));if(s.getState().name().equals("FAILED"))throw new IllegalStateException("Original job failed; inspect its receipt");if(s.getState().name().equals("DONE"))return s.getResult()==null?Map.of():JSON.fromJson(s.getResult(),LinkedHashMap.class);}throw new IllegalStateException("Original job still pending; no replay");
 }
 private static final class DirectSession implements TaskSessionService.Backend {
  private final String client;DirectSession(String client){this.client=client;}
  public Map<String,Object> binding(String project,String profile)throws Exception{var b=new RuntimePreparationBackend().binding(project);if(!Boolean.TRUE.equals(b.get("configuration_synchronized")))throw new IllegalStateException("Native EQUAL required");return b;}
  public Map<String,Object> verifyClient(String project,String endpoint)throws Exception{require("edt_test_client");var s=new DirectTestClientService().status(client);boolean verified=project.equals(s.get("project"))&&Boolean.TRUE.equals(s.get("connected"))&&Boolean.TRUE.equals(s.get("live_client_binding_verified"));if(!verified)return Map.of("live_client_binding_verified",false);return Map.of("live_client_binding_verified",true,"process_id",s.get("pid"),"process_started_at",s.get("process_started_at"),"profile",client,"base_identity_sha256",s.get("base_identity_sha256"),"proof_kind","owned_direct_testclient");}
  public Map<String,Object> call(String tool,Map<String,Object> input)throws Exception{
   require("edt_task_step");require(tool);var a=new LinkedHashMap<>(input);a.remove("endpoint");a.remove("client_binding");a.put("client_id",client);
   if(tool.equals("edt_form_state"))return new DirectTestClientService().observe(a);
   if(tool.equals("edt_form_action"))return new DirectTestClientService().act(a);
   if(tool.equals("edt_operation_status")){var s=BackgroundJobRegistry.getInstance().getStatus((String)input.get("job_id")).orElseThrow();var out=new LinkedHashMap<String,Object>();out.put("state",s.getState().name());if(s.getResult()!=null)out.put("result",JSON.fromJson(s.getResult(),Object.class));return out;}
   throw new IllegalArgumentException("Unsupported direct task tool");
  }
  public Map<String,Object> resource(Map<String,Object>b,String k,String id){throw new IllegalArgumentException("Resources belong to the preparation journal");}
  public Map<String,Object> cleanup(Map<String,Object>b,String k,String id,Map<String,Object>p){throw new IllegalArgumentException("Use preparation cleanup");}
 }
}
