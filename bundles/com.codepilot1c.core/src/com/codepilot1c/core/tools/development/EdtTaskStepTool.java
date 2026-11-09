// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.*;
@ToolMeta(name="edt_task_step",category="qa",mutating=true,tags={"workspace","edt","sensitive"})
public final class EdtTaskStepTool extends AbstractTool {
 public String getDescription(){return "Execute ONE existing tool in a bound task context, or inspect a claimed step and verify its explicit semantic-text postcondition after successful completion. step_key is durable: same arguments replay the submission, unknown outcome is never resent. No auto business success, no XML manipulation, original commands remain available. Async host preparation: call status to collect host_id into cleanup inventory. Fresh observations need fresh keys.";}
 public String getParameterSchema(){return com.codepilot1c.core.edt.development.AgentResponses.schema("""
{"type": "object", "additionalProperties": false, "properties": {"action": {"enum": ["call", "status", "verify"]}, "session_id": {"type": "string"}, "step_key": {"type": "string"}, "tool": {"enum": ["edt_check", "edt_form_state", "edt_form_action", "edt_runtime_host", "edt_test_data", "edt_data_assert"]}, "arguments": {"type": "object"}, "expect": {"type": "object", "additionalProperties": false, "properties": {"kind": {"enum": ["window_equals", "element_contains", "element_not_contains", "messages_contains", "messages_empty"]}, "element": {"type": "string"}, "text": {"type": "string"}, "window": {"type": "string"}}, "required": ["kind"]}}, "required": ["action", "session_id"]}
""");}
 protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{

 var s=TaskSessionService.workspace();String id=p.requireString("session_id"),action=p.requireString("action");
 var args=TaskSessionService.object(p.getRaw().get("arguments"));var expected=TaskSessionService.object(p.getRaw().get("expect"));
 var out=switch(action){case "call"->s.call(id,p.requireString("step_key"),p.requireString("tool"),args,expected);case "status"->s.stepStatus(id,p.requireString("step_key"));case "verify"->s.verify(id,expected);default->throw new IllegalArgumentException("Unknown action");};return com.codepilot1c.core.edt.development.AgentResponses.success(out,p.getRaw());

 }catch(Exception e){return com.codepilot1c.core.edt.development.AgentErrors.failure("edt_task_step","execution",e,false);}});}
}
