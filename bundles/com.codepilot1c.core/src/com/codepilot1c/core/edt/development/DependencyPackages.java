// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
/** Shared dependency jobs used by preferences and MCP; no process/DB/trust operations. */
public final class DependencyPackages {
 static final class Generation implements java.util.function.BooleanSupplier {
  private volatile boolean cancelled;
  synchronized void cancel(){cancelled=true;}
  void requireActive(){if(cancelled)throw new IllegalStateException("Dependency lifecycle stopped");}
  @Override public boolean getAsBoolean(){return cancelled;}
 }
 private static volatile Generation generation=new Generation();
 private static final Map<String,String> JOBS=new ConcurrentHashMap<>();
 private DependencyPackages(){}
 public static synchronized void start(){if(generation.getAsBoolean())generation=new Generation();}
 public static synchronized void stop(){generation.cancel();JOBS.clear();}
 public static boolean stopped(){return generation.getAsBoolean();}
 static Generation ticket(){return generation;}
 public static VerifiedDependencyPackage service(String kind)throws Exception {return kind.equals("python")?VerifiedDependencyPackage.python():kind.equals("powershell")?VerifiedDependencyPackage.powerShell():VanessaPackages.service(kind);}
 public static Map<String,Object> status(String kind)throws Exception {if(kind.equals("vanessa"))return VanessaPackages.status();var out=new LinkedHashMap<>(service(kind).status());if(kind.equals("powershell")){if(!Boolean.TRUE.equals(out.get("ready")))out.put("next_call",RuntimeConsentPolicy.call("edt_runner_profile",Map.of("action","prepare_powershell")));return out;}if(!Boolean.TRUE.equals(out.get("ready")))out.put("next_call",Map.of("tool","edt.run","arguments",Map.of("op","edt_test_client","args",Map.of("action","prepare_runtime"))));return out;}
 public static synchronized Map<String,Object> prepare(String kind)throws Exception {
  var ticket=ticket();if(ticket.getAsBoolean())throw new IllegalStateException("Dependency lifecycle stopped");var service=service(kind);var jobs=BackgroundJobRegistry.getInstance();String key=service.root().toString(),id=JOBS.get(key);
  if(id!=null&&jobs.getStatus(id).map(s->!Set.of("DONE","FAILED").contains(s.getState().name())).orElse(false))return Map.of("job_id",id,"next_call",WorkflowReplies.job(id));
  id=jobs.startJob(kind+"-package",progress->{try{return AgentResponses.JSON.toJson(kind.equals("vanessa")?VanessaPackages.ensure(ticket,stage->progress.accept(Map.of("stage",stage))):service.ensure(ticket,stage->progress.accept(Map.of("stage",stage))));}catch(Exception error){throw new java.io.IOException("Dependency package unavailable; inspect status and retry ("+error.getClass().getSimpleName()+")");}});JOBS.put(key,id);return Map.of("job_id",id,"next_call",WorkflowReplies.job(id));
 }
}
