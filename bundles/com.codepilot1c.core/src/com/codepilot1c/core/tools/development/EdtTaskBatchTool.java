// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.*;import java.util.concurrent.CompletableFuture;import com.codepilot1c.core.tools.*;import com.codepilot1c.core.edt.development.*;
@ToolMeta(name="edt_task_batch",category="qa",mutating=true,tags={"workspace","edt","sensitive"})
public final class EdtTaskBatchTool extends AbstractTool {
 public String getDescription(){return "Advance 1..16 sequential existing task steps in one bound session. Each form action needs expect. Durable batch_key and step keys; repeat same plan to continue a running step without resubmission. Stops on failure, timeout, binding drift or unknown outcome. Host lifecycle and test-data orchestration stay in their original tools; submission alone cannot prove their completion. status never dispatches. No rollback/automatic restore; original step tools remain available.";}
 public String getParameterSchema(){return AgentResponses.schema("""
 {"type":"object","additionalProperties":false,"properties":{"action":{"enum":["advance","status"]},"session_id":{"type":"string"},"batch_key":{"type":"string"},"timeout_ms":{"type":"integer","minimum":0,"maximum":25000,"default":20000},"steps":{"type":"array","minItems":1,"maxItems":16,"items":{"type":"object","additionalProperties":false,"properties":{"step_key":{"type":"string","maxLength":60},"tool":{"enum":["edt_check","edt_form_state","edt_form_action","edt_data_assert"]},"arguments":{"type":"object"},"expect":{"type":"object"}},"required":["step_key","tool","arguments"]}}},"required":["action","session_id","batch_key"]}
 """);}
 protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{
  var service=TaskSessionService.workspace();String id=p.requireString("session_id"),key=p.requireString("batch_key"),action=p.requireString("action");Map<String,Object> out;
  if(action.equals("status"))out=service.batchStatus(id,key);else if(action.equals("advance")){if(!(p.getRaw().get("steps") instanceof List<?> steps)||steps.stream().anyMatch(s->!(s instanceof Map<?,?>)))throw new IllegalArgumentException("steps required");out=service.batchAdvance(id,key,steps.stream().map(TaskSessionService::object).toList(),AgentResponses.integer(p.getRaw(),"timeout_ms",20000,0,25000));}else throw new IllegalArgumentException("Unknown action");return AgentResponses.success(out,p.getRaw());
 }catch(Exception e){return AgentErrors.failure(getName(),"batch",e,false);}});}
}
