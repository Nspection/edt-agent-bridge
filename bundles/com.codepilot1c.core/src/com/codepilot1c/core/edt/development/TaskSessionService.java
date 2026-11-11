// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.google.gson.*;
import com.codepilot1c.core.edt.infobase.InfobasePaths;

/** Persistent task context. A declared resource is never sufficient proof of cleanup ownership. */
public final class TaskSessionService {
    public interface Backend {
        Map<String,Object> binding(String project,String profile)throws Exception;
        Map<String,Object> call(String tool,Map<String,Object> arguments)throws Exception;
        Map<String,Object> resource(Map<String,Object> binding,String kind,String id)throws Exception;
        Map<String,Object> cleanup(Map<String,Object> binding,String kind,String id,Map<String,Object> preview)throws Exception;
        default Map<String,Object> verifyClient(String project,String endpoint)throws Exception{return ClientBindingVerifier.failed("identity_backend_unavailable");}
        default Map<String,Object> formChange(String endpoint,String baseline)throws Exception{throw new IllegalStateException("Form change observation unavailable");}
    }
    private static final Gson JSON=new GsonBuilder().serializeNulls().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
    private static final Map<String,Object> LOCKS=new ConcurrentHashMap<>();
    private final Path root;private final Backend backend;
    public TaskSessionService(Path root,Backend backend)throws Exception{this.root=root.toAbsolutePath().normalize();this.backend=backend;Files.createDirectories(this.root);}
    public static TaskSessionService workspace()throws Exception{return new TaskSessionService(org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/task-sessions"),new TaskSessionBackend());}
    public static String fingerprint(Object value){return InfobasePaths.hash(JSON.toJson(canonical(value)));}
    private static Object canonical(Object value){
        if(value instanceof Map<?,?> m){Map<String,Object> out=new TreeMap<>();m.forEach((k,v)->out.put(String.valueOf(k),canonical(v)));return out;}
        if(value instanceof List<?> a)return a.stream().map(TaskSessionService::canonical).toList();
        if(value instanceof Number n)return new java.math.BigDecimal(n.toString()).stripTrailingZeros();return value;
    }
    private Object lock(String id){return LOCKS.computeIfAbsent(root+":"+id,k->new Object());}
    private Path file(String id){if(id==null || !id.matches("task-[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid session_id");return root.resolve(id+".json");}
    @SuppressWarnings("unchecked") private Map<String,Object> read(String id)throws Exception{Path p=file(id);if(Files.isSymbolicLink(p)||Files.size(p)>4*1024*1024)throw new IllegalArgumentException("Invalid task state");var value=JSON.fromJson(Files.readString(p,StandardCharsets.UTF_8),LinkedHashMap.class);if(!id.equals(value.get("session_id")))throw new IllegalStateException("Session identity mismatch");return value;}
    private void save(Map<String,Object> doc)throws Exception{String content=JSON.toJson(doc);if(content.length()>2*1024*1024)throw new IllegalStateException("Task state capacity exceeded");Path dest=file((String)doc.get("session_id"));if(Files.isSymbolicLink(dest))throw new IllegalStateException("Linked task state");Path tmp=Files.createTempFile(root,"task-",".tmp");try{Files.writeString(tmp,content,StandardCharsets.UTF_8);Files.move(tmp,dest,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(tmp);}}
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object value){return value instanceof Map<?,?>?(Map<String,Object>)value:Map.of();}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> resources(Map<String,Object>d){return (List<Map<String,Object>>)d.get("resources");}
    public Map<String,Object> open(String project,String profile,String endpoint,String key)throws Exception{return open(project,profile,endpoint,key,false);}
    public Map<String,Object> open(String project,String profile,String endpoint,String key,boolean requireClient)throws Exception {
        if(project==null||project.isBlank()||key==null||key.isBlank()||key.length()>200)throw new IllegalArgumentException("project and idempotency_key required");
        profile=profile==null?"default":profile;endpoint=VanessaMcpBridge.localEndpoint(endpoint==null?"http://127.0.0.1:9874/mcp":endpoint);
        String id="task-"+UUID.nameUUIDFromBytes((project+"\0"+key).getBytes(StandardCharsets.UTF_8));
        synchronized(lock(id)) {
            Map<String,Object> binding=backend.binding(project,profile);
            if(Files.exists(file(id))){var old=read(id);if(!binding.equals(object(old.get("binding")))||!endpoint.equals(old.get("endpoint"))||requireClient!=Boolean.TRUE.equals(old.get("client_binding_required")))throw new IllegalStateException("Session key belongs to another binding; inspect old session");return brief(old);}
            Map<String,Object> doc=new LinkedHashMap<>();doc.put("session_id",id);doc.put("project",project);doc.put("profile_id",profile);doc.put("endpoint",endpoint);doc.put("binding",binding);doc.put("created_at",Instant.now().toString());doc.put("state","open");doc.put("client_binding_required",requireClient);doc.put("resources",new ArrayList<>());doc.put("steps",new LinkedHashMap<>());doc.put("revision",0);save(doc);return brief(doc);
        }
    }
    private void validate(Map<String,Object> doc)throws Exception {
        if(!"open".equals(doc.get("state")))throw new IllegalStateException("Task session is closed");
        if(!object(doc.get("binding")).equals(backend.binding((String)doc.get("project"),(String)doc.get("profile_id"))))throw new IllegalStateException("Task binding changed: project location, base UUID, connection or runner profile; no action sent");
    }
    private void validateCleanup(Map<String,Object> doc)throws Exception {
        try{validate(doc);}catch(Exception changed){
            boolean removedBase=resources(doc).stream().anyMatch(r->"database".equals(r.get("kind"))&&"cleaned".equals(r.get("cleanup_state")));
            boolean onlyTeardownRemainders=resources(doc).stream().filter(r->!"cleaned".equals(r.get("cleanup_state"))).allMatch(r->Set.of("database","import","audit_project").contains(r.get("kind")));
            if(!removedBase && onlyTeardownRemainders)for(var resource:resources(doc))if("database".equals(resource.get("kind"))) {
                var proof=backend.resource(object(doc.get("binding")),"database",(String)resource.get("id"));
                if(Boolean.TRUE.equals(proof.get("detach_started"))||Boolean.TRUE.equals(proof.get("delete_started"))||Boolean.TRUE.equals(proof.get("cleaned")))removedBase=true;
            }
            if(!removedBase||!onlyTeardownRemainders||!"open".equals(doc.get("state")))throw changed;
        }
    }
    private Map<String,Object> brief(Map<String,Object>d){var out=AgentBrief.select(d,"session_id","project","profile_id","endpoint","created_at","state","revision","binding","client_binding_required","last_client_binding");out.put("resources",resources(d));out.put("live_client_binding_verified",false);out.put("note","Project/base/profile pinned. verify_client establishes fresh local process correlation; expected_window remains mandatory. Cached proof is not current verification.");return out;}
    private static Map<String,Object> stableClient(Map<String,Object> proof){return AgentBrief.select(proof,"process_id","process_started_at","profile","base_identity_sha256","proof_kind");}
    public Map<String,Object> verifyClient(String id)throws Exception {synchronized(lock(id)){
        var doc=read(id);validate(doc);var proof=backend.verifyClient((String)doc.get("project"),(String)doc.get("endpoint"));
        if(Boolean.TRUE.equals(proof.get("live_client_binding_verified"))){doc.put("last_client_binding",stableClient(proof));doc.put("client_binding_required",true);save(doc);}
        var out=new LinkedHashMap<>(proof);out.put("session_id",id);return out;
    }}
    private Map<String,Object> requireClient(Map<String,Object> doc)throws Exception {
        if(!Boolean.TRUE.equals(doc.get("client_binding_required")))return Map.of();
        var expected=object(doc.get("last_client_binding"));if(expected.isEmpty())throw new IllegalStateException("verify_client required before form operations; no action sent");
        var proof=backend.verifyClient((String)doc.get("project"),(String)doc.get("endpoint"));
        if(!Boolean.TRUE.equals(proof.get("live_client_binding_verified"))||!fingerprint(expected).equals(fingerprint(stableClient(proof))))throw new IllegalStateException("Live TestClient binding changed or unavailable; verify_client again after inspecting the target; no form action sent");
        return expected;
    }
    public Map<String,Object> status(String id)throws Exception{synchronized(lock(id)){var d=read(id);var out=brief(d);try{validate(d);out.put("binding_current",true);}catch(Exception e){out.put("binding_current",false);return out;}if(Boolean.TRUE.equals(d.get("client_binding_required"))){try{requireClient(d);out.put("live_client_binding_verified",true);}catch(Exception e){out.put("live_client_binding_verified",false);}}return out;}}

    private static boolean hasUncollected(Map<String,Object>d){return object(d.get("steps")).values().stream().map(TaskSessionService::object).anyMatch(s->!s.containsKey("result") || (object(s.get("result")).containsKey("job_id")&&!Boolean.TRUE.equals(s.get("collected"))));}
    /** Retry collection of the original step only; never dispatch an action during cleanup. */
    Map<String,Object> closeAfterCollectingStep(String id,String stepKey)throws Exception{synchronized(lock(id)){var doc=read(id);var step=object(object(doc.get("steps")).get(stepKey));if(!step.isEmpty()&&step.get("result") instanceof Map<?,?>&&object(step.get("result")).containsKey("job_id")&&!Boolean.TRUE.equals(step.get("collected")))stepStatus(id,stepKey);return close(id);}}
    public Map<String,Object> close(String id)throws Exception{synchronized(lock(id)){var d=read(id);if(hasUncollected(d)||resources(d).stream().anyMatch(x->!"cleaned".equals(x.get("cleanup_state"))))throw new IllegalStateException("Resources remain; preview cleanup first");d.put("state","closed");save(d);return brief(d);}}
    public Map<String,Object> attach(String id,String kind,String resourceId)throws Exception {
        synchronized(lock(id)){var doc=read(id);validate(doc);track(doc,kind,resourceId);save(doc);return brief(doc);}
    }
    private void track(Map<String,Object> d,String kind,String id)throws Exception {
        if(!Set.of("run","host","data_set","data_journal","import","database","audit_project").contains(kind)||id==null||id.length()>200)throw new IllegalArgumentException("Unsupported resource kind/id");
        backend.resource(object(d.get("binding")),kind,id); // validates identity now AND independently again during cleanup
        if(resources(d).stream().noneMatch(x->kind.equals(x.get("kind"))&&id.equals(x.get("id")))){if(resources(d).size()>=100)throw new IllegalStateException("Max 100 task resources");resources(d).add(new LinkedHashMap<>(Map.of("kind",kind,"id",id,"cleanup_state","pending")));d.put("revision",((Number)d.get("revision")).longValue()+1);}
    }
    public Map<String,Object> call(String id,String stepKey,String tool,Map<String,Object> input,Map<String,Object> condition)throws Exception {
        synchronized(lock(id)) {
            var doc=read(id);validate(doc);Map<String,Object>a=new LinkedHashMap<>(input);
            if(!Set.of("edt_check","edt_form_state","edt_form_action","edt_runtime_host","edt_test_data","edt_data_assert").contains(tool))throw new IllegalArgumentException("Unsupported task step tool");
            for(String field:List.of("project","endpoint","profile_id","response"))if(a.containsKey(field))throw new IllegalArgumentException("Task supplies "+field+"; do not override");
            if(stepKey==null||stepKey.isBlank()||stepKey.length()>100)throw new IllegalArgumentException("step_key required (max 100)");
            var steps=new LinkedHashMap<>(object(doc.get("steps")));String fingerprint=fingerprint(List.of(tool,input,condition));
            var old=object(steps.get(stepKey));
            if(!old.isEmpty()) {
                if(!fingerprint.equals(old.get("fingerprint")))throw new IllegalStateException("step_key arguments changed");
                if(!old.containsKey("result"))throw new IllegalStateException("outcome_unknown: step was claimed; inspect the original tool before replacement");
                return new LinkedHashMap<>(object(old.get("result")));
            }
            if(steps.size()>=100)throw new IllegalStateException("Max 100 task steps; create another task");
            String project=(String)doc.get("project");
            if(tool.startsWith("edt_form_")){a.put("endpoint",doc.get("endpoint"));var proof=requireClient(doc);if(tool.equals("edt_form_action")&&!proof.isEmpty()){if(a.containsKey("client_binding"))throw new IllegalArgumentException("Task supplies client_binding");a.put("client_binding",proof);}}
            else if(tool.equals("edt_data_assert")){if(!"start".equals(a.get("action")))throw new IllegalArgumentException("Use assertion status by ID");String host=RuntimeFormService.required(a,"host_id");backend.resource(object(doc.get("binding")),"host",host);track(doc,"host",host);a.put("project",project);}
            else if(tool.equals("edt_check")){String action=(String)a.get("action");if(!Set.of("baseline","changes","start","receipt").contains(action))throw new IllegalArgumentException("Use original status/cancel/details commands by ID");a.put("project",project);if(action.equals("start"))a.put("profile_id",doc.get("profile_id"));}
            else if(tool.equals("edt_test_data"))a.put("project",project);
            else if(tool.equals("edt_runtime_host")) {
                if("prepare".equals(a.get("action")))a.put("project",project);
                else {String host=RuntimeFormService.required(a,"host_id");backend.resource(object(doc.get("binding")),"host",host);}
            }
            String key=a.get("idempotency_key") instanceof String s?s:null;
            if(tool.equals("edt_form_action")) {
                if(key==null)throw new IllegalArgumentException("idempotency_key required");
                a.put("idempotency_key",InfobasePaths.hash(id+":"+key)); // endpoint dedup keys cannot collide across tasks
            }
            if(!condition.isEmpty()){if(!tool.equals("edt_form_action"))throw new IllegalArgumentException("expect is supported only for a form action; use verify after other steps");validateCondition(condition);}
            steps.put(stepKey,new LinkedHashMap<>(Map.of("fingerprint",fingerprint,"tool",tool,"condition",condition,"state","claimed")));doc.put("steps",steps);save(doc);
            Map<String,Object> result=backend.call(tool,a);
            var submitted=new LinkedHashMap<>(object(steps.get(stepKey)));submitted.put("result",result);steps.put(stepKey,submitted);doc.put("steps",steps);save(doc);
            if(result.get("run_id") instanceof String run && tool.equals("edt_check"))track(doc,"run",run);
            if(result.get("data_run_id") instanceof String journal && tool.equals("edt_test_data"))track(doc,"data_journal",journal);
            if(result.get("set_id") instanceof String set && tool.equals("edt_test_data"))track(doc,"data_set",set);
            if(result.get("host_id") instanceof String host && tool.equals("edt_runtime_host"))track(doc,"host",host);
            var step=new LinkedHashMap<>(object(steps.get(stepKey)));step.put("result",result);step.put("state","submitted");steps.put(stepKey,step);doc.put("steps",steps);
            save(doc);var out=new LinkedHashMap<>(result);out.put("session_id",id);out.put("step_key",stepKey);out.put("postcondition_verified",false);out.put("next_tool","edt_task_step");return out;
        }
    }
    /** Compact reconstruction of recorded work. No dispatch, restore or implicit replay. */
    public Map<String,Object> resume(String id,int offset,int limit)throws Exception {
        if(offset<0||limit<1||limit>50)throw new IllegalArgumentException("Invalid resume pagination");
        synchronized(lock(id)){
            var doc=read(id);var out=new LinkedHashMap<>(status(id));var rows=new ArrayList<Map<String,Object>>();
            for(var entry:object(doc.get("steps")).entrySet()){
                var step=object(entry.getValue());var row=AgentBrief.select(step,"tool","state","collected","last_status");row.put("step_key",entry.getKey());if(!object(step.get("last_status")).isEmpty())row.put("state",object(step.get("last_status")).get("state"));
                if(!step.containsKey("result"))row.put("state","outcome_unknown");
                row.put("references",AgentBrief.select(object(step.get("result")),"job_id","run_id","host_id","request_id","assertion_id","snapshot_id","set_id","data_run_id","baseline_id","check_id"));rows.add(row);
            }
            out.put("steps",rows.stream().skip(offset).limit(limit).toList());out.put("offset",offset);out.put("total_steps",rows.size());out.put("has_more",(long)offset+limit<rows.size());out.put("batches",object(doc.get("batches")).values().stream().map(TaskSessionService::object).map(b->AgentBrief.select(b,"batch_key","state","next_index","step_count","last_status")).toList());
            out.put("pending_resources",resources(doc).stream().filter(r->!"cleaned".equals(r.get("cleanup_state"))).count());out.put("observations_are_historical",true);out.put("next_tool",Boolean.TRUE.equals(out.get("binding_current"))?"edt_task_step":"edt_task_session");return out;
        }
    }
    private static String batchStepKey(String batch,String key){return "batch-"+InfobasePaths.hash(batch).substring(0,12)+"-"+key;}
    /** Plan fingerprints only. Payloads/credentials are not persisted in a batch record. */
    private List<Map<String,Object>> batchPlan(List<Map<String,Object>> plan){
        if(plan.isEmpty()||plan.size()>16)throw new IllegalArgumentException("Batch requires 1..16 steps");Set<String>keys=new HashSet<>();List<Map<String,Object>> rows=new ArrayList<>();
        for(var step:plan){for(String field:step.keySet())if(!Set.of("step_key","tool","arguments","expect").contains(field))throw new IllegalArgumentException("Unknown batch step field");
            String key=RuntimeFormService.required(step,"step_key"),tool=RuntimeFormService.required(step,"tool");if(key.length()>60||!keys.add(key))throw new IllegalArgumentException("Unique step_key up to 60 characters required");
            if(!Set.of("edt_check","edt_form_state","edt_form_action","edt_data_assert").contains(tool))throw new IllegalArgumentException("Unsupported batch tool");
            if(!(step.get("arguments") instanceof Map<?,?>)||step.containsKey("expect")&&!(step.get("expect") instanceof Map<?,?>))throw new IllegalArgumentException("Batch arguments/expect must be objects");
            var a=object(step.get("arguments"));for(String f:List.of("project","endpoint","profile_id","response"))if(a.containsKey(f))throw new IllegalArgumentException("Task supplies "+f);
            var expected=object(step.get("expect"));if(tool.equals("edt_form_action")){if(expected.isEmpty())throw new IllegalArgumentException("Batch form action requires explicit expect");validateCondition(expected);RuntimeFormService.required(a,"idempotency_key");}else if(!expected.isEmpty())throw new IllegalArgumentException("expect only for form actions");
            // Reject unsupported action variants before any batch side effect.
            if(tool.equals("edt_check")&&!Set.of("baseline","changes","start","receipt").contains(a.get("action")))throw new IllegalArgumentException("Unsupported batch check action");
            if(tool.equals("edt_data_assert")&&!"start".equals(a.get("action")))throw new IllegalArgumentException("Batch assertions require start");
            rows.add(new LinkedHashMap<>(step));
        }return rows;
    }
    private Map<String,Object> batchBrief(String id,Map<String,Object> batch){var out=AgentBrief.select(batch,"batch_key","state","next_index","step_count","last_status","results");out.put("session_id",id);out.put("automatic_replay",false);out.put("observations_are_historical",true);out.put("next_tool","edt_task_batch");return out;}
    public Map<String,Object> batchStatus(String id,String key)throws Exception{synchronized(lock(id)){
        var doc=read(id);validate(doc);var batches=new LinkedHashMap<>(object(doc.get("batches")));var batch=new LinkedHashMap<>(object(batches.get(key)));if(batch.isEmpty())throw new IllegalArgumentException("Unknown batch_key");
        if("running".equals(batch.get("state"))){int index=((Number)batch.get("next_index")).intValue();String stepKey=(String)((List<?>)batch.get("step_keys")).get(index);var observed=stepStatus(id,stepKey);
            doc=read(id);batches=new LinkedHashMap<>(object(doc.get("batches")));batch=new LinkedHashMap<>(object(batches.get(key)));batch.put("last_status",observed);String state=(String)observed.get("state");
            if(state.equals("completed")){if(Boolean.TRUE.equals(((List<?>)batch.get("expects")).get(index))&&!Boolean.TRUE.equals(observed.get("postcondition_verified")))batch.put("state","failed");else{var results=new ArrayList<Object>((List<?>)batch.get("results"));results.add(observed);batch.put("results",results);batch.put("next_index",index+1);batch.put("state",index+1==((Number)batch.get("step_count")).intValue()?"completed":"pending");}}else batch.put("state",state);
            batches.put(key,batch);doc.put("batches",batches);save(doc);
        }return batchBrief(id,batch);
    }}
    public Map<String,Object> batchAdvance(String id,String key,List<Map<String,Object>> raw,int timeout)throws Exception {
        if(key==null||key.isBlank()||key.length()>100||timeout<0||timeout>25000)throw new IllegalArgumentException("Invalid batch_key/timeout_ms");var plan=batchPlan(raw);String hash=fingerprint(plan);long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeout);
        synchronized(lock(id)){
            var doc=read(id);validate(doc);var batches=new LinkedHashMap<>(object(doc.get("batches")));var batch=new LinkedHashMap<>(object(batches.get(key)));
            if(batch.isEmpty()){
                if(batches.size()>=32)throw new IllegalStateException("Max 32 batches per task");for(var step:plan)if(object(doc.get("steps")).containsKey(batchStepKey(key,(String)step.get("step_key"))))throw new IllegalStateException("Batch step key is already owned");
                if(object(doc.get("steps")).size()+plan.size()>100)throw new IllegalStateException("Batch exceeds remaining task step capacity");
                batch.put("batch_key",key);batch.put("fingerprint",hash);batch.put("state","pending");batch.put("next_index",0);batch.put("step_count",plan.size());batch.put("step_keys",plan.stream().map(s->batchStepKey(key,(String)s.get("step_key"))).toList());batch.put("expects",plan.stream().map(s->!object(s.get("expect")).isEmpty()).toList());batch.put("results",new ArrayList<>());batches.put(key,batch);doc.put("batches",batches);save(doc);
            }else if(!hash.equals(batch.get("fingerprint")))throw new IllegalStateException("batch_key arguments changed");
            if(Set.of("completed","failed","outcome_unknown").contains(batch.get("state")))return batchBrief(id,batch);
            while(((Number)batch.get("next_index")).intValue()<plan.size()){
                int index=((Number)batch.get("next_index")).intValue();var step=plan.get(index);String stepKey=batchStepKey(key,(String)step.get("step_key"));Map<String,Object> state;
                try{
                    call(id,stepKey,(String)step.get("tool"),object(step.get("arguments")),object(step.get("expect")));
                    do{state=stepStatus(id,stepKey);if(!"running".equals(state.get("state"))||System.nanoTime()>=deadline)break;Thread.sleep(Math.min(250,Math.max(1,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))));}while(true);
                }catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}catch(Exception e){
                    var recorded=object(object(read(id).get("steps")).get(stepKey));state=new LinkedHashMap<>(AgentErrors.describe("edt_task_batch","step",e,false));state.put("state",!recorded.isEmpty()?"outcome_unknown":"blocked");
                }
                // call/status wrote the current resource inventory; never overwrite it with the old document.
                doc=read(id);batches=new LinkedHashMap<>(object(doc.get("batches")));batch=new LinkedHashMap<>(object(batches.get(key)));batch.put("last_status",state);
                if("completed".equals(state.get("state"))){
                    if(!object(step.get("expect")).isEmpty()&&!Boolean.TRUE.equals(state.get("postcondition_verified"))){batch.put("state","failed");}
                    else {var results=new ArrayList<Object>((List<?>)batch.get("results"));var receipt=new LinkedHashMap<>(state);receipt.put("batch_step_key",step.get("step_key"));results.add(receipt);batch.put("results",results);batch.put("next_index",index+1);batch.put("state",index+1==plan.size()?"completed":"pending");}
                }else batch.put("state",state.get("state"));batches.put(key,batch);doc.put("batches",batches);save(doc);
                if(!"pending".equals(batch.get("state"))||System.nanoTime()>=deadline)break;
            }return batchBrief(id,batch);
        }
    }
    public Map<String,Object> formChange(String id,String baseline)throws Exception{synchronized(lock(id)){var doc=read(id);validate(doc);return backend.formChange((String)doc.get("endpoint"),baseline);}}
    public void assertRun(String id,String run)throws Exception {synchronized(lock(id)){var doc=read(id);validate(doc);backend.resource(object(doc.get("binding")),"run",run);}}
    public Map<String,Object> stepStatus(String id,String key)throws Exception {
        synchronized(lock(id)) {
            var doc=read(id);validate(doc);var steps=new LinkedHashMap<>(object(doc.get("steps")));var step=new LinkedHashMap<>(object(steps.get(key)));
            if(step.isEmpty())throw new IllegalArgumentException("Unknown step_key");var result=object(step.get("result"));if(result.isEmpty())return Map.of("state","outcome_unknown","postcondition_verified",false);
            Map<String,Object> operation=result;boolean done=true,success=true;
            if(result.get("job_id") instanceof String job){operation=backend.call("edt_operation_status",Map.of("job_id",job));done=Set.of("DONE","FAILED").contains(operation.get("state"));success="DONE".equals(operation.get("state"));}
            if("edt_check".equals(step.get("tool")) && result.get("run_id") instanceof String run) {
                backend.resource(object(doc.get("binding")),"run",run);
                operation=backend.call("edt_check",Map.of("action","status","run_id",run));
                done=Boolean.TRUE.equals(operation.get("terminal"));success=done&&"completed".equals(operation.get("phase"));
            }
            if("edt_data_assert".equals(step.get("tool"))&&result.get("assertion_id") instanceof String assertion){operation=backend.call("edt_data_assert",Map.of("action","status","assertion_id",assertion));done=Set.of("passed","failed","outcome_unknown","cancelled").contains(operation.get("state"));success="passed".equals(operation.get("state"))&&Boolean.TRUE.equals(operation.get("business_result_verified"));}
            Map<String,Object> payload=object(operation.get("result"));if(payload.isEmpty()&&!result.containsKey("job_id"))payload=operation;
            if(payload.containsKey("success"))success&=Boolean.TRUE.equals(payload.get("success"));
            if("edt_form_action".equals(step.get("tool")))success&=Boolean.TRUE.equals(payload.get("success"));
            Map<String,Object> out=new LinkedHashMap<>();out.put("session_id",id);out.put("step_key",key);out.put("state",("interrupted_by_restart".equals(operation.get("phase"))||"outcome_unknown".equals(operation.get("state")))?"outcome_unknown":done?(success?"completed":"failed"):"running");out.put("postcondition_verified",false);
            if(done&&success) {
                if(payload.get("host_id") instanceof String host && "edt_runtime_host".equals(step.get("tool")))track(doc,"host",host);
                if(payload.get("run_id") instanceof String run && "edt_check".equals(step.get("tool")))track(doc,"run",run);
                if(payload.get("data_run_id") instanceof String journal && "edt_test_data".equals(step.get("tool")))track(doc,"data_journal",journal);
                if(payload.get("set_id") instanceof String set && "edt_test_data".equals(step.get("tool")))track(doc,"data_set",set);
                Map<String,Object> condition=object(step.get("condition"));if(!condition.isEmpty())out.putAll(evaluate(doc,condition));
                out.put("business_result_verified",Boolean.TRUE.equals(out.get("postcondition_verified"))||"edt_data_assert".equals(step.get("tool"))&&Boolean.TRUE.equals(payload.get("business_result_verified")));
            }
            if(result.containsKey("job_id"))out.put("job_id",result.get("job_id"));step.put("last_status",new LinkedHashMap<>(out));steps.put(key,step);doc.put("steps",steps);if(done&&success||knownDirectCompletion(doc,step,operation,payload)){step.put("collected",true);steps.put(key,step);doc.put("steps",steps);}save(doc);return out;
        }
    }
    // A received known failure is collected evidence, not a successful test.
    // Restrict this to the owned direct adapter and the exact original operation.
    private static boolean knownDirectCompletion(Map<String,Object> doc,Map<String,Object> step,Map<String,Object> operation,Map<String,Object> payload){
        var result=object(step.get("result"));return "owned_direct_testclient".equals(object(doc.get("last_client_binding")).get("proof_kind"))&&"edt_form_action".equals(step.get("tool"))&&"DONE".equals(operation.get("state"))&&result.get("operation_id") instanceof String id&&id.equals(payload.get("operation_id"))&&payload.get("success") instanceof Boolean&&Boolean.FALSE.equals(payload.get("outcome_unknown"))&&!"outcome_unknown".equals(payload.get("state"));
    }
    private static void validateCondition(Map<String,Object> c) {
        for(String key:c.keySet())if(!Set.of("kind","element","text","window").contains(key))throw new IllegalArgumentException("Unknown condition parameter "+key);
        String kind=RuntimeFormService.required(c,"kind");if(!Set.of("window_equals","element_contains","element_not_contains","messages_contains","messages_empty").contains(kind))throw new IllegalArgumentException("Unsupported explicit postcondition");
        if(kind.startsWith("element_"))RuntimeFormService.required(c,"element");
        if(kind.endsWith("contains"))RuntimeFormService.required(c,"text");
        if(kind.equals("window_equals"))RuntimeFormService.required(c,"window");
    }
    public Map<String,Object> verify(String id,Map<String,Object> condition)throws Exception {
        synchronized(lock(id)){var doc=read(id);validate(doc);return evaluate(doc,condition);}
    }
    private Map<String,Object> evaluate(Map<String,Object>doc,Map<String,Object>c)throws Exception {
        validateCondition(c);var clientProof=requireClient(doc);
        String kind=RuntimeFormService.required(c,"kind");Map<String,Object>a=new LinkedHashMap<>();a.put("endpoint",doc.get("endpoint"));a.put("limit",100);
        switch(kind){case "window_equals"->a.put("view","elements");case "element_contains","element_not_contains"->{a.put("view","element");a.put("element",RuntimeFormService.required(c,"element"));}case "messages_contains","messages_empty"->a.put("view","messages");default->throw new IllegalArgumentException("Unsupported explicit postcondition");}
        if(Set.of("element_contains","element_not_contains","messages_contains").contains(kind)&&RuntimeFormService.required(c,"text").isEmpty())throw new IllegalArgumentException("Nonempty comparison text required");
        Map<String,Object> observed=backend.call("edt_form_state",a);if(!(observed.get("items") instanceof List<?>)||!(observed.get("snapshot_id") instanceof String))throw new IllegalStateException("Unverified form observation");String text=String.join("\n",((List<?>)observed.getOrDefault("items",List.of())).stream().map(String::valueOf).toList());
        boolean complete=!Boolean.TRUE.equals(observed.get("has_more"))&&!Boolean.TRUE.equals(observed.get("text_segmented"));boolean windowOk=!c.containsKey("window")||Objects.equals(c.get("window"),observed.get("window"));
        boolean match=switch(kind){case "window_equals"->Objects.equals(RuntimeFormService.required(c,"window"),observed.get("window"));case "element_contains","messages_contains"->text.contains(RuntimeFormService.required(c,"text"));case "element_not_contains"->complete&&!text.contains(RuntimeFormService.required(c,"text"));default->complete&&text.isBlank();};
        return Map.of("postcondition_verified",match&&windowOk,"observation_complete",complete,"snapshot_id",observed.get("snapshot_id"),"condition_kind",kind,"live_client_binding_verified",!clientProof.isEmpty()&&!requireClient(doc).isEmpty());
    }
    public Map<String,Object> preview(String id)throws Exception {
        synchronized(lock(id)){var doc=read(id);validateCleanup(doc);if(hasUncollected(doc))throw new IllegalStateException("Uncollected/unknown task steps; inspect edt_task_step status before cleanup");List<Map<String,Object>>plan=new ArrayList<>();
            for(var r:resources(doc)){if("cleaned".equals(r.get("cleanup_state")))continue;var row=new LinkedHashMap<>(r);try{row.put("evidence",backend.resource(object(doc.get("binding")),(String)r.get("kind"),(String)r.get("id")));row.put("eligible",true);}catch(Exception e){row.put("eligible",false);row.put("reason","ownership_or_state_unverified");}plan.add(row);}
            plan.sort(Comparator.comparingInt(x->List.of("run","data_set","data_journal","host","import","database","audit_project").indexOf(x.get("kind"))));
            Map<String,Object> out=new LinkedHashMap<>();out.put("session_id",id);out.put("revision",doc.get("revision"));out.put("resources",plan);out.put("physical_record_deletion",false);out.put("untracked_resources","not_covered");out.put("plan_sha256",fingerprint(out));return out;}
    }
    /** One guarded cleanup pass; pending data or failed resource blocks dependent teardown. Repeat preview to continue. */
    public Map<String,Object> cleanup(String id,String expected)throws Exception {
        synchronized(lock(id)){var doc=read(id);validateCleanup(doc);var plan=preview(id);if(expected==null||!expected.equals(plan.get("plan_sha256")))throw new IllegalStateException("Cleanup plan stale; preview again");List<Map<String,Object>>out=new ArrayList<>();
            for(Object raw:(List<?>)plan.get("resources")){var row=object(raw);String kind=(String)row.get("kind"),resource=(String)row.get("id");if(!Boolean.TRUE.equals(row.get("eligible"))){out.add(Map.of("kind",kind,"id",resource,"state","blocked"));break;}
                Map<String,Object> receipt=backend.cleanup(object(doc.get("binding")),kind,resource,object(row.get("evidence")));out.add(receipt);boolean cleaned=Boolean.TRUE.equals(receipt.get("cleanup_verified"));for(var r:resources(doc))if(kind.equals(r.get("kind"))&&resource.equals(r.get("id")))r.put("cleanup_state",cleaned?"cleaned":"pending");doc.put("revision",((Number)doc.get("revision")).longValue()+1);save(doc);if(!cleaned)break;
            }
            return Map.of("session_id",id,"results",out,"all_tracked_resources_cleaned",resources(doc).stream().allMatch(x->"cleaned".equals(x.get("cleanup_state"))),"untracked_resources","not_covered","physical_record_deletion",false);
        }
    }
}
