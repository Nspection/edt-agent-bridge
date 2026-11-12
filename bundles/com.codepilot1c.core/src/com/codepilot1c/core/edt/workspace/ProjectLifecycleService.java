// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.workspace;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import com.google.gson.*;
import com.codepilot1c.core.edt.development.TaskSessionService;
/** Exact-project native maintenance, with hash-bound preview and durable no-replay journal. */
public final class ProjectLifecycleService {
 public interface Backend {
  Map<String,Object> snapshot(String project)throws Exception;
  Map<String,Object> perform(String project,String operation,boolean rebuild,String expectedPath,Consumer<String> phase)throws Exception;
 }
 private static final Map<String,Object> LOCKS=new ConcurrentHashMap<>();
 private static final Gson JSON=new Gson();
 private final Path root;private final Backend backend;
 public ProjectLifecycleService(Path root,Backend backend)throws Exception{this.root=root.toAbsolutePath().normalize();this.backend=backend;Files.createDirectories(this.root);if(!this.root.toRealPath().equals(this.root))throw new IllegalStateException("Linked lifecycle journal");}
 public static ProjectLifecycleService workspace()throws Exception{return new ProjectLifecycleService(org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/project-lifecycle"),new NativeProjectLifecycle());}
 public Map<String,Object> execute(Map<String,Object> args)throws Exception {
  if(!Set.of("project","action","operation","rebuild","expected_plan_sha256","idempotency_key").containsAll(args.keySet()))throw new IllegalArgumentException("Unsupported lifecycle field");
  String project=required(args,"project"),action=Objects.toString(args.getOrDefault("action","status"));
  if(!Set.of("status","preview","apply").contains(action))throw new IllegalArgumentException("Unknown lifecycle action");
  if(action.equals("status"))return backend.snapshot(project);
  String operation=required(args,"operation");if(!Set.of("open","close","reopen","clean").contains(operation))throw new IllegalArgumentException("Unknown lifecycle operation");
  if(args.containsKey("rebuild") && !(args.get("rebuild") instanceof Boolean))throw new IllegalArgumentException("rebuild must be boolean");
  boolean rebuild=!Boolean.FALSE.equals(args.get("rebuild"));if(args.containsKey("rebuild")&&!operation.equals("clean"))throw new IllegalArgumentException("rebuild is only for clean");
  if(action.equals("preview"))return preview(project,operation,rebuild);
  String key=required(args,"idempotency_key");if(key.length()>160)throw new IllegalArgumentException("idempotency_key too long");
  String digest=TaskSessionService.fingerprint(args),id=TaskSessionService.fingerprint(List.of(project,key));Path file=root.resolve(id+".json");
  synchronized(LOCKS.computeIfAbsent(root+":"+project,k->new Object())) {
   if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){
    var saved=read(file);if(!digest.equals(saved.get("request_sha256")))throw new IllegalStateException("IDEMPOTENCY_KEY_REUSED");return saved;
   }
   var plan=preview(project,operation,rebuild);
   if(!Objects.equals(required(args,"expected_plan_sha256"),plan.get("plan_sha256")))throw new IllegalStateException("STALE_PROJECT_LIFECYCLE_PREVIEW");
   if(!Boolean.TRUE.equals(plan.get("allowed")))throw new IllegalStateException("Project maintenance blocked: "+plan.get("blockers"));
   var result=new LinkedHashMap<String,Object>();result.put("project",project);result.put("operation",operation);result.put("request_sha256",digest);result.put("state","outcome_unknown");result.put("outcome_unknown",true);result.put("phase","dispatching");save(file,result);
   try {
    var actual=backend.perform(project,operation,rebuild,((Map<?,?>)plan.get("snapshot")).get("project_path").toString(),phase->{result.put("phase",phase);try{save(file,result);}catch(Exception e){throw new IllegalStateException("Cannot checkpoint lifecycle operation",e);}});
    result.putAll(actual);result.put("state","completed");result.put("outcome_unknown",false);result.put("model_readiness","not_verified");result.put("database_updated",false);
   }catch(Exception e){result.put("state","failed");result.put("error_type",e.getClass().getSimpleName());result.put("automatic_retry_allowed",false);}
   save(file,result);return result;
  }
 }
 private Map<String,Object> preview(String project,String operation,boolean rebuild)throws Exception {
  var snapshot=backend.snapshot(project);var blockers=new ArrayList<String>();
  Object raw=snapshot.get("blockers");if(raw instanceof Collection<?> rows)for(Object row:rows)blockers.add(row.toString());
  boolean open=Boolean.TRUE.equals(snapshot.get("open"));
  if(!operation.equals("open")&&!open)blockers.add("project_closed");
  var plan=new LinkedHashMap<String,Object>();plan.put("project",project);plan.put("operation",operation);plan.put("rebuild",rebuild);plan.put("snapshot",snapshot);plan.put("blockers",blockers);plan.put("allowed",blockers.isEmpty());plan.put("plan_sha256",TaskSessionService.fingerprint(plan));return plan;
 }
 private static String required(Map<String,Object> a,String key){Object value=a.get(key);if(!(value instanceof String s)||s.isBlank()||s.length()>512)throw new IllegalArgumentException(key+" required");return s;}
 @SuppressWarnings("unchecked") private static Map<String,Object> read(Path file)throws Exception{if(Files.isSymbolicLink(file)||Files.size(file)>65536)throw new IllegalStateException("Lifecycle journal changed");return JSON.fromJson(Files.readString(file,StandardCharsets.UTF_8),LinkedHashMap.class);}
 private static void save(Path file,Map<String,Object> value)throws Exception{if(Files.isSymbolicLink(file))throw new IllegalStateException("Linked lifecycle journal");Path tmp=Files.createTempFile(file.getParent(),"lifecycle-",".tmp");try{Files.writeString(tmp,JSON.toJson(value),StandardCharsets.UTF_8);Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}}
}
