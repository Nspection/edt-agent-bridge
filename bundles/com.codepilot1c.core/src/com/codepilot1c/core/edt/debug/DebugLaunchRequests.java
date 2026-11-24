// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.debug;
import java.util.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import com.google.gson.*;import com.codepilot1c.core.edt.development.*;import com.codepilot1c.core.edt.infobase.*;import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
/** One durable launch request and explicit decision; observing never dispatches another launch. */
public final class DebugLaunchRequests {
 public interface Backend {
  Map<String,Object> binding(String project)throws Exception;
  void update(String project)throws Exception;
  default void update(String project,Map<String,Object> binding)throws Exception {
   update(project);
  }
  Map<String,Object> launch(String project,String config,boolean updateAuthorized)throws Exception;
  default Map<String,Object> launch(String project,String config,boolean updateAuthorized,Map<String,Object> binding)throws Exception {
   return launch(project,config,updateAuthorized);
  }
  default List<String> recoveryBlockers() {
   return List.of("runtime_observation_unavailable");
  }
 }
 private static final Object LOCK=new Object();private static final Set<String> ACTIVE=java.util.concurrent.ConcurrentHashMap.newKeySet();private static final Gson JSON=new GsonBuilder().serializeNulls().create();private final Path root;private final Backend backend;
 public DebugLaunchRequests(Path root,Backend b)throws Exception{this.root=root.toAbsolutePath().normalize();backend=b;Files.createDirectories(this.root);checkRoot();}
 private void checkRoot()throws Exception{if(!root.toRealPath().equals(root)||Files.isSymbolicLink(root))throw new IllegalStateException("Launch storage moved");}
 public static DebugLaunchRequests workspace()throws Exception {
  var ws=org.eclipse.core.resources.ResourcesPlugin.getWorkspace();
  return new DebugLaunchRequests(ws.getRoot().getLocation().toFile().toPath()
      .resolve(".metadata/.plugins/com.codepilot1c.core/debug-launch-requests"),new Backend() {
   public Map<String,Object> binding(String project)throws Exception {
    return new RuntimePreparationBackend().binding(project);
   }
   public void update(String project)throws Exception {
    new NativeInfobaseGateway().updateIncremental(project,new org.eclipse.core.runtime.NullProgressMonitor());
   }
   public void update(String project,Map<String,Object> binding)throws Exception {
    new NativeInfobaseGateway().updateIncremental(project,new org.eclipse.core.runtime.NullProgressMonitor(),
        scopeValue(binding,"base_uuid"),scopeValue(binding,"target_identity_sha256"));
   }
   public Map<String,Object> launch(String project,String config,boolean authorized)throws Exception {
    return launch(project,config,authorized,binding(project));
   }
   public Map<String,Object> launch(String project,String config,boolean authorized,Map<String,Object> expected)throws Exception {
    var base=new com.codepilot1c.core.edt.runtime.EdtRuntimeService().resolveDefaultInfobase(project);
    if(base==null)throw new IllegalStateException("Exact launch base required");
    try(var lease=new InfobaseWorkflow(InfobaseWorkflow.resourceKeys(project,base),progress->{})) {
     verifyLaunchBinding(project,base,expected);
     try(var interaction=NativeUpdateInteraction.observeLaunch(project,base,authorized)){
      verifyLaunchBinding(project,base,expected);
      return EdtDebugService.getInstance().launchDebugSession(project,config,false);
     }
    }
   }
   private void verifyLaunchBinding(String project,com._1c.g5.v8.dt.platform.services.model.InfobaseReference base,
           Map<String,Object> expected)throws Exception {
    String target=InfobasePaths.hash(InfobasePaths.connectionIdentity(base.getConnectionString().asConnectionString()));
    if(!base.getUuid().toString().equals(scopeValue(expected,"base_uuid"))
        ||!target.equals(scopeValue(expected,"target_identity_sha256"))
        ||!sameBinding(binding(project),expected))throw new IllegalStateException("Launch binding changed");
   }
   public List<String> recoveryBlockers() {
    var blockers=new ArrayList<String>();
    var debug=org.eclipse.debug.core.DebugPlugin.getDefault();
    if(debug==null)blockers.add("debug_inventory_unavailable");
    else if(Arrays.stream(debug.getLaunchManager().getLaunches()).anyMatch(launch->!launch.isTerminated()))
     blockers.add("live_edt_launch");
    if(InfobaseWorkflow.hasActiveOperations()||NativeUpdateInteraction.hasActiveOperations())blockers.add("native_update_or_launch_in_progress");
    if(ProcessHandle.current().descendants().anyMatch(DebugLaunchRequests::isPlatformProcess))
     blockers.add("live_1c_process_owned_by_edt");
    return blockers;
   }
  });
 }
 private static boolean isPlatformProcess(ProcessHandle process) {
  var command=process.info().command();
  if(command.isEmpty())return process.isAlive(); // A live unidentifiable child cannot be excluded.
  String executable=Path.of(command.get()).getFileName().toString().toLowerCase(Locale.ROOT);
  return Set.of("1cv8","1cv8.exe","1cv8c","1cv8c.exe","1cv8s","1cv8s.exe").contains(executable);
 }
 private Path file(String id)throws Exception{checkRoot();if(id==null||!id.matches("dbg-launch-[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid debug_launch_id");Path f=root.resolve(id+".json");if(Files.isSymbolicLink(f))throw new IllegalStateException("Linked launch receipt");return f;}
 private void save(Map<String,Object>d)throws Exception{Path f=file((String)d.get("debug_launch_id")),t=Files.createTempFile(root,"launch-",".tmp");try{String data=JSON.toJson(d);if(data.length()>128*1024)throw new IllegalStateException("Launch receipt capacity");Files.writeString(t,data,StandardCharsets.UTF_8);Files.move(t,f,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(t);}}
 @SuppressWarnings("unchecked") private Map<String,Object> read(String id)throws Exception{Path f=file(id);if(!Files.isRegularFile(f,LinkOption.NOFOLLOW_LINKS)||Files.size(f)>128*1024)throw new IllegalStateException("Launch receipt unavailable");var d=JSON.fromJson(Files.readString(f),LinkedHashMap.class);if(!id.equals(d.get("debug_launch_id")))throw new IllegalStateException("Launch identity changed");return d;}
 private String activeKey(String id){return root+":"+id;}
 private static Map<String,Object> identity(Map<String,Object>b){var c=new LinkedHashMap<>(b);c.remove("configuration_synchronized");return c;}
 private static boolean sameBinding(Map<String,Object> current,Map<String,Object> expected) {
  return TaskSessionService.fingerprint(identity(current)).equals(TaskSessionService.fingerprint(identity(expected)));
 }
 private Map<String,Object> decision(Map<String,Object>d){var scope=new LinkedHashMap<String,Object>();for(String k:List.of("debug_launch_id","project","configuration_name","binding"))scope.put(k,d.get(k));String hash=TaskSessionService.fingerprint(scope);var choices=new ArrayList<Map<String,Object>>();for(String choice:List.of("update_and_launch","cancel"))choices.add(Map.of("id",choice,"label",choice.equals("update_and_launch")?"Обновить и запустить":"Отменить","updates_database",choice.equals("update_and_launch"),"command",Map.of("tool","edt.debug","arguments",Map.of("op","edt_debug_targets","args",Map.of("action","launch_decide","debug_launch_id",d.get("debug_launch_id"),"expected_decision_sha256",hash,"choice",choice)))));return Map.of("kind","update_before_debug","decision_id",d.get("debug_launch_id"),"decision_sha256",hash,"project",d.get("project"),"base_uuid",TaskSessionService.object(d.get("binding")).get("base_uuid"),"choices",choices,"default_policy","ask_user_unless_task_explicitly_authorizes_database_update");}
 public Map<String,Object> start(String project,String config,String key,String policy)throws Exception {
  synchronized(LOCK) {
   if(project==null||project.isBlank()||config==null||config.isBlank()||key==null||key.isBlank()
       ||key.length()>256||!Set.of("ask","task_authorized_update").contains(policy))
    throw new IllegalArgumentException("Explicit project/configuration/key and known decision_policy required");
   String id="dbg-launch-"+TaskSessionService.fingerprint(List.of(project,key));
   var request=Map.of("project",project,"configuration_name",config,"idempotency_key",key,"decision_policy",policy);
   String digest=TaskSessionService.fingerprint(request);
   if(Files.exists(file(id))) {
    var old=read(id);
    if(!digest.equals(old.get("request_sha256")))throw new IllegalStateException("Launch key arguments changed");
    return status(id);
   }
   var binding=backend.binding(project);
   if(scopeValue(binding,"base_uuid")==null)throw new IllegalStateException("Exact bound base required");
   try(var paths=Files.list(root)) {
    var files=paths.filter(f->f.getFileName().toString().matches("dbg-launch-[a-f0-9]{64}[.]json")).limit(129).toList();
    if(files.size()>128)throw new IllegalStateException("Archive reviewed launch receipts first");
    for(Path f:files) {
     var old=read(f.getFileName().toString().replace(".json",""));
     if(old.containsKey("resolution")||!Set.of("queued","running","outcome_unknown","awaiting_decision").contains(old.get("state")))continue;
     boolean sameProject=project.equals(old.get("project"));
     var previous=TaskSessionService.object(old.get("binding"));
     boolean related=sameTarget(binding,previous);
     boolean separate=hasTargetIdentity(binding)&&hasTargetIdentity(previous)&&!related;
     boolean active=ACTIVE.contains(activeKey((String)old.get("debug_launch_id")));
     if(related||sameProject&&(!separate||active))
      throw new IllegalStateException("Inspect original launch request: "+old.get("debug_launch_id"));
    }
   }
   var d=new LinkedHashMap<String,Object>(request);
   d.put("debug_launch_id",id);
   d.put("request_sha256",digest);
   d.put("binding",binding);
   d.put("state","awaiting_decision");
   save(d);
   if(Boolean.TRUE.equals(binding.get("configuration_synchronized"))||policy.equals("task_authorized_update"))
    queue(d,policy.equals("task_authorized_update"));
   return status(id);
  }
 }
 private static String scopeValue(Map<String,Object> binding,String key) {
  return binding.get(key) instanceof String value&&!value.isBlank()?value:null;
 }
 private static boolean hasTargetIdentity(Map<String,Object> binding) {
  return scopeValue(binding,"base_uuid")!=null&&scopeValue(binding,"target_identity_sha256")!=null;
 }
 private static boolean sameTarget(Map<String,Object> first,Map<String,Object> second) {
  for(String key:List.of("base_uuid","target_identity_sha256")) {
   String value=scopeValue(first,key);
   if(value!=null&&value.equals(scopeValue(second,key)))return true;
  }
  return false;
 }
 public Map<String,Object> recoveryPreview(String id)throws Exception {
  synchronized(LOCK) {
   var receipt=status(id);
   var blockers=new ArrayList<String>();
   if(!"outcome_unknown".equals(receipt.get("state")))blockers.add("outcome_not_unknown");
   if(receipt.containsKey("resolution"))blockers.add("already_resolved");
   if(ACTIVE.contains(activeKey(id)))blockers.add("original_launch_active");
   var registry=BackgroundJobRegistry.getInstance();
   for(String job:registry.listJobs(null)) {
    var entry=registry.getStatus(job).orElse(null);
    if(entry!=null&&Set.of("PENDING","RUNNING").contains(entry.getState().name())) {
     blockers.add("background_operation_active");
     break;
    }
   }
   var old=TaskSessionService.object(receipt.get("binding"));
   var current=backend.binding((String)receipt.get("project"));
   if(!hasTargetIdentity(old)||!hasTargetIdentity(current)
       ||!Objects.equals(old.get("base_uuid"),current.get("base_uuid"))
       ||!Objects.equals(old.get("target_identity_sha256"),current.get("target_identity_sha256"))
       ||!Objects.equals(old.get("project_path"),current.get("project_path")))
    blockers.add("original_target_identity_unverified");
   blockers.addAll(backend.recoveryBlockers());
   var plan=new LinkedHashMap<String,Object>();
   plan.put("debug_launch_id",id);
   plan.put("project",receipt.get("project"));
   plan.put("configuration_name",receipt.get("configuration_name"));
   plan.put("base_uuid",old.get("base_uuid"));
   plan.put("receipt_state",receipt.get("state"));
   plan.put("recorded_stage",receipt.get("stage"));
   plan.put("job_id",receipt.get("job_id"));
   if(receipt.get("job_id") instanceof String jobId) {
    var job=registry.lookupJob(jobId);
    plan.put("job_state",job.getStatus()==null?job.getKind().name():job.getStatus().getState().name());
   }
   plan.put("original_binding_sha256",TaskSessionService.fingerprint(old));
   plan.put("current_binding_sha256",TaskSessionService.fingerprint(identity(current)));
   plan.put("blockers",List.copyOf(blockers));
   plan.put("recovery_allowed",blockers.isEmpty());
   plan.put("requires_user_confirmation","Confirm no original client or update remains active, including outside this EDT.");
   plan.put("expected_plan_sha256",TaskSessionService.fingerprint(plan));
   return plan;
  }
 }
 public Map<String,Object> recover(String id,String expected,boolean confirmed)throws Exception {
  synchronized(LOCK) {
   if(!confirmed)throw new IllegalArgumentException("Explicit quiescence confirmation is required");
   var durable=read(id);
   if(durable.get("resolution") instanceof Map<?,?> resolution&&expected!=null
       &&expected.equals(resolution.get("plan_sha256")))return status(id);
   var plan=recoveryPreview(id);
   if(!Boolean.TRUE.equals(plan.get("recovery_allowed")))throw new IllegalStateException("Launch recovery blocked: "+plan.get("blockers"));
   if(!Objects.equals(expected,plan.get("expected_plan_sha256")))throw new IllegalStateException("STALE_LAUNCH_RECOVERY");
   durable.put("resolution",Map.of("kind","release_launch_block","plan_sha256",expected,
       "resolved_at",java.time.Instant.now().toString(),"quiescence_confirmed",true));
   save(durable);
   return status(id);
  }
 }
 private void queue(Map<String,Object>d,boolean allowUpdate)throws Exception{String id=(String)d.get("debug_launch_id");d.put("state","queued");d.put("update_authorized",allowUpdate);save(d);var gate=new java.util.concurrent.CountDownLatch(1);var armed=new java.util.concurrent.atomic.AtomicBoolean();ACTIVE.add(activeKey(id));try{String job=BackgroundJobRegistry.getInstance().startJob("debug-launch",()->{gate.await();if(!armed.get())throw new IllegalStateException("Launch reservation not persisted");try{return JSON.toJson(execute(id));}finally{ACTIVE.remove(activeKey(id));}});d.put("job_id",job);save(d);armed.set(true);}finally{gate.countDown();if(!armed.get())ACTIVE.remove(activeKey(id));}}
 private Map<String,Object> execute(String id)throws Exception{Map<String,Object>d; synchronized(LOCK){d=read(id);d.put("state","running");save(d);}boolean launchClaimed=false,updateClaimed=false;try{String p=(String)d.get("project");var current=backend.binding(p);if(!sameBinding(current,TaskSessionService.object(d.get("binding"))))throw new IllegalStateException("Launch binding changed");if(!Boolean.TRUE.equals(current.get("configuration_synchronized"))){if(!Boolean.TRUE.equals(d.get("update_authorized")))throw new IllegalStateException("Native EQUAL required; no update authorized");recordStage(d,"update_dispatched");updateClaimed=true;backend.update(p,current);current=backend.binding(p);if(!Boolean.TRUE.equals(current.get("configuration_synchronized")))throw new IllegalStateException("Update did not prove EQUAL");}if(!sameBinding(current,TaskSessionService.object(d.get("binding"))))throw new IllegalStateException("Launch binding changed");recordStage(d,"launch_dispatched");launchClaimed=true;d.put("result",backend.launch(p,(String)d.get("configuration_name"),Boolean.TRUE.equals(d.get("update_authorized")),current));var after=backend.binding(p);d.put("state",sameBinding(after,current)&&Boolean.TRUE.equals(after.get("configuration_synchronized"))?"launched":"launched_unverified");}catch(Exception e){d.put("state",launchClaimed||updateClaimed?"outcome_unknown":"blocked");d.put("error_type",e.getClass().getSimpleName());}synchronized(LOCK){save(d);return status(id);}}
 private void recordStage(Map<String,Object> receipt,String stage)throws Exception {
  synchronized(LOCK) {
   receipt.put("stage",stage);
   save(receipt);
  }
 }
 public Map<String,Object> status(String id)throws Exception{synchronized(LOCK){var d=read(id);if(Set.of("queued","running").contains(d.get("state"))&&!ACTIVE.contains(activeKey(id))){d.put("state","outcome_unknown");d.put("reason","interrupted_or_unclaimed_no_replay");}d.put("terminal",!Set.of("queued","running","awaiting_decision").contains(d.get("state")));d.put("automatic_replay",false);d.put("test_acceptance_verified",false);if(ACTIVE.contains(activeKey(id))&&d.get("job_id") instanceof String job)d.put("progress",BackgroundJobRegistry.getInstance().getProgress(job));if("awaiting_decision".equals(d.get("state")))d.put("decision",decision(d));return d;}}
 public Map<String,Object> decide(String id,String expected,String choice)throws Exception{synchronized(LOCK){var d=read(id);if(!"awaiting_decision".equals(d.get("state"))||!Objects.equals(expected,decision(d).get("decision_sha256")))throw new IllegalStateException("STALE_LAUNCH_DECISION");if(!Set.of("cancel","update_and_launch").contains(choice))throw new IllegalArgumentException("Unknown launch decision");if(choice.equals("cancel")){d.put("state","cancelled");save(d);}else{var current=backend.binding((String)d.get("project"));if(!sameBinding(current,TaskSessionService.object(d.get("binding"))))throw new IllegalStateException("Decision binding changed");queue(d,true);}return status(id);}}
}
