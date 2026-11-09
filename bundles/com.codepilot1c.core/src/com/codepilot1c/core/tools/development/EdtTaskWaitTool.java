// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.*;
@ToolMeta(name="edt_task_wait",category="qa",mutating=false,tags={"workspace","edt","sensitive"})
public final class EdtTaskWaitTool extends AbstractTool {
 public String getDescription(){return "Bounded wait (0..25000ms) for development-run events, an owned task-step operation, an explicit form predicate, or form_change from after_snapshot_id. Form change keeps baseline scope and endpoint and returns <=20 added/removed lines (summary only). Run wait uses monitor notification, not caller-side polling; form wait reads only, at <=1 observation/sec, returns a snapshot ID without the whole form. Timeout is not success. Binding is checked on every form observation; no actions or automatic debugger continuation. Original status/debug/observe commands stay available.";}
 public String getParameterSchema(){return com.codepilot1c.core.edt.development.AgentResponses.schema("""
{"type": "object", "additionalProperties": false, "properties": {"kind": {"enum": ["run", "form", "form_change", "operation"]}, "session_id": {"type": "string"}, "run_id": {"type": "string"}, "step_key":{"type":"string"}, "after_snapshot_id":{"type":"string"}, "after_cursor": {"type": "integer", "minimum": 0}, "timeout_ms": {"type": "integer", "minimum": 0, "maximum": 25000, "default": 20000}, "expect": {"type": "object", "additionalProperties": false, "properties": {"kind": {"enum": ["window_equals", "element_contains", "element_not_contains", "messages_contains", "messages_empty"]}, "element": {"type": "string"}, "text": {"type": "string"}, "window": {"type": "string"}}, "required": ["kind"]}}, "required": ["kind", "session_id"]}
""");}
 protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{

 var service=TaskSessionService.workspace();String id=p.requireString("session_id"),kind=p.requireString("kind");int timeout=p.getRaw().containsKey("timeout_ms")?Math.toIntExact(p.requireLong("timeout_ms")):20000;if(timeout<0||timeout>25000)throw new IllegalArgumentException("timeout_ms must be 0..25000");
 Map<String,Object> out;
 if(kind.equals("run")) {
  service.assertRun(id,p.requireString("run_id"));long cursor=p.getRaw().containsKey("after_cursor")?p.requireLong("after_cursor"):0;
  out=AgentBrief.run(DevelopmentRunService.getInstance().awaitStatus(p.requireString("run_id"),cursor,timeout),cursor);
  out.put("timed_out",((Number)out.get("latest_cursor")).longValue()==cursor&&!Boolean.TRUE.equals(out.get("terminal"))&&!"interrupted_by_restart".equals(out.get("phase")));
 } else if(kind.equals("form")) {
  long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeout);Map<String,Object> expect=TaskSessionService.object(p.getRaw().get("expect"));
  do {out=service.verify(id,expect);if(Boolean.TRUE.equals(out.get("postcondition_verified"))||System.nanoTime()>=deadline)break;Thread.sleep(Math.min(1000,Math.max(1,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))));}while(true);
  out=new LinkedHashMap<>(out);out.put("timed_out",!Boolean.TRUE.equals(out.get("postcondition_verified")));
 }else if(kind.equals("form_change")||kind.equals("operation")) {
  long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeout);
  do {out=kind.equals("form_change")?service.formChange(id,p.requireString("after_snapshot_id")):service.stepStatus(id,p.requireString("step_key"));
   boolean ready=kind.equals("form_change")?Boolean.TRUE.equals(out.get("changed")):!"running".equals(out.get("state"));
   if(ready||System.nanoTime()>=deadline){out=new LinkedHashMap<>(out);out.put("timed_out",!ready);break;}
   Thread.sleep(Math.min(1000,Math.max(1,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))));
  }while(true);
 }else throw new IllegalArgumentException("Unknown wait kind");return com.codepilot1c.core.edt.development.AgentResponses.success(out,p.getRaw());

 }catch(Exception e){return com.codepilot1c.core.edt.development.AgentErrors.failure("edt_task_wait","execution",e,true);}});}
}
