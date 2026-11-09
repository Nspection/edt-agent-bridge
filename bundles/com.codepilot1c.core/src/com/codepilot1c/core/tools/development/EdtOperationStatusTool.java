// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.*;import java.util.concurrent.CompletableFuture;
import com.codepilot1c.core.tools.*;import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;import com.codepilot1c.core.edt.development.*;
@ToolMeta(name="edt_operation_status",category="workspace",tags={"read-only","workspace","edt","sensitive"})
public final class EdtOperationStatusTool extends AbstractTool {
 public String getDescription(){return "Read or wait up to 25s for a background job; no resubmission. timeout_ms=0 preserves polling. compact includes stable result IDs and outcome flags, never logs/code. Unknown/expired is not success. response optionally projects fields and bounds bytes; edt_result reads omitted details.";}
 public String getParameterSchema(){return AgentResponses.schema("""
 {"type":"object","additionalProperties":false,"properties":{"job_id":{"type":"string"},"compact":{"type":"boolean","default":false},"timeout_ms":{"type":"integer","minimum":0,"maximum":25000,"default":0}},"required":["job_id"]}
 """);}
 protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{
  int timeout=AgentResponses.integer(p.getRaw(),"timeout_ms",0,0,25000);var jobs=BackgroundJobRegistry.getInstance();var optional=jobs.awaitStatus(p.requireString("job_id"),timeout);if(optional.isEmpty())throw new IllegalArgumentException("Unknown or expired operation");
  var s=optional.get();Map<String,Object> out=new LinkedHashMap<>();out.put("job_id",s.getJobId());out.put("kind",s.getKind());out.put("state",s.getState().name());out.put("submitted_at",String.valueOf(s.getSubmittedAt()));out.put("started_at",String.valueOf(s.getStartedAt()));out.put("finished_at",String.valueOf(s.getFinishedAt()));out.put("progress",jobs.getProgress(s.getJobId()));
  if(s.getResult()!=null){try{out.put("result",AgentResponses.JSON.fromJson(s.getResult(),Object.class));}catch(Exception e){out.put("result",s.getResult());}}if(s.getError()!=null){out.put("error",s.getError());out.put("failure",AgentErrors.describe(getName(),String.valueOf(jobs.getProgress(s.getJobId()).getOrDefault("stage",s.getKind())),new IllegalStateException(s.getError()),false));}
  out.put("terminal",Set.of("DONE","FAILED").contains(s.getState().name()));if(timeout>0)out.put("timed_out",!Boolean.TRUE.equals(out.get("terminal")));
  return AgentResponses.success(Boolean.TRUE.equals(p.getRaw().get("compact"))?AgentBrief.operation(out):out,p.getRaw());
 }catch(InterruptedException e){Thread.currentThread().interrupt();return AgentErrors.failure(getName(),"wait",e,true);}catch(Exception e){return AgentErrors.failure(getName(),"status",e,true);}});}
}
