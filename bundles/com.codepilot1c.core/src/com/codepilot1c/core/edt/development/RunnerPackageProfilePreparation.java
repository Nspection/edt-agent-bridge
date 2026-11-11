// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Function;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
/** A cold package download and profile write always have an observable original job. */
public final class RunnerPackageProfilePreparation {
 interface Jobs {boolean running(String id);String start(Callable<String> task);}
 private record Request(String hash,String job){}
 private final Map<String,Request> requests=new HashMap<>();private final Jobs jobs;private final Function<Map<String,Object>,Map<String,Object>> defaults;
 private static final RunnerPackageProfilePreparation INSTANCE=new RunnerPackageProfilePreparation(new Jobs(){public boolean running(String id){return BackgroundJobRegistry.getInstance().getStatus(id).map(s->!Set.of("DONE","FAILED").contains(s.getState().name())).orElse(false);}public String start(Callable<String> task){return BackgroundJobRegistry.getInstance().startJob("runner-profile-prepare",()->task.call());}},VanessaPackages::profileDefaults);
 RunnerPackageProfilePreparation(Jobs jobs,Function<Map<String,Object>,Map<String,Object>> defaults){this.jobs=jobs;this.defaults=defaults;}
 public static RunnerPackageProfilePreparation shared(){return INSTANCE;}
 public synchronized Map<String,Object> putExplicit(RunnerProfileStore store,String project,String profileId,Map<String,Object> profile)throws Exception {RunnerProfileStore.validateProfile(profile,false);String key=store.operationKey(project,profileId);var active=requests.get(key);if(active!=null&&jobs.running(active.job()))throw new IllegalStateException("Profile preparation is active; inspect original job "+active.job());return store.put(project,profileId,profile);}
 public synchronized Map<String,Object> prepare(RunnerProfileStore store,String project,String profileId,Map<String,Object> profile)throws Exception {
  RunnerProfileStore.validateProfile(profile,true);String key=store.operationKey(project,profileId);var copy=Map.copyOf(profile);String hash=VerifiedDependencyPackage.digest(AgentResponses.JSON.toJson(new TreeMap<>(copy)).getBytes(StandardCharsets.UTF_8));var previous=requests.get(key);
  if(previous!=null&&jobs.running(previous.job())){if(!hash.equals(previous.hash()))throw new IllegalStateException("Another profile preparation is running; inspect original job "+previous.job());return Map.of("job_id",previous.job(),"reused",true,"next_call",WorkflowReplies.job(previous.job()));}
  if(requests.size()>=128){requests.entrySet().removeIf(e->!jobs.running(e.getValue().job()));if(requests.size()>=128)throw new IllegalStateException("Profile preparation capacity reached; observe active jobs");}
  String original=store.stateHash(project,profileId);String job=jobs.start(()->{var prepared=defaults.apply(copy);synchronized(this){if(!original.equals(store.stateHash(project,profileId)))throw new IllegalStateException("Profile changed while preparing packages; preserve current profile and inspect original job");return AgentResponses.JSON.toJson(store.put(project,profileId,prepared));}});requests.put(key,new Request(hash,job));return Map.of("job_id",job,"reused",false,"next_call",WorkflowReplies.job(job));
 }
}
