// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
/** Shared dependency jobs used by preferences and MCP; no process/DB/trust operations. */
public final class DependencyPackages {
 private static volatile boolean stopped;
 private static final Map<String,String> JOBS=new ConcurrentHashMap<>();
 private DependencyPackages(){}
 public static void start(){stopped=false;}
 public static void stop(){stopped=true;}
 public static boolean stopped(){return stopped;}
 public static VerifiedDependencyPackage service(String kind)throws Exception {return kind.equals("python")?VerifiedDependencyPackage.python():VanessaPackages.service(kind);}
 public static Map<String,Object> status(String kind)throws Exception {if(kind.equals("vanessa"))return VanessaPackages.status();var out=new LinkedHashMap<>(service(kind).status());if(!Boolean.TRUE.equals(out.get("ready")))out.put("next_call",Map.of("tool","edt.run","arguments",Map.of("op","edt_test_client","args",Map.of("action","prepare_runtime"))));return out;}
 public static synchronized Map<String,Object> prepare(String kind)throws Exception {
  if(stopped)throw new IllegalStateException("Dependency lifecycle stopped");var service=service(kind);var jobs=BackgroundJobRegistry.getInstance();String key=service.root().toString(),id=JOBS.get(key);
  if(id!=null&&jobs.getStatus(id).map(s->!Set.of("DONE","FAILED").contains(s.getState().name())).orElse(false))return Map.of("job_id",id,"next_call",WorkflowReplies.job(id));
  id=jobs.startJob(kind+"-package",progress->{try{return AgentResponses.JSON.toJson(kind.equals("vanessa")?VanessaPackages.ensure(()->stopped,stage->progress.accept(Map.of("stage",stage))):service.ensure(()->stopped,stage->progress.accept(Map.of("stage",stage))));}catch(Exception error){throw new java.io.IOException("Dependency package unavailable; inspect status and retry ("+error.getClass().getSimpleName()+")");}});JOBS.put(key,id);return Map.of("job_id",id,"next_call",WorkflowReplies.job(id));
 }
}
