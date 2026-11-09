// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.*;
@ToolMeta(name="edt_task_session",category="qa",mutating=true,tags={"workspace","edt","sensitive"})
public final class EdtTaskSessionTool extends AbstractTool {
 public String getDescription(){return "Open/status/close persistent task context pinned to project path, base UUID, connection and runner profile hash. Drift blocks steps. No client launch or credentials stored. Endpoint fixed for the task; verify_client correlates active Vanessa profile with a live local 1C process and explicit base arguments; unavailable PID/arguments fails verification. New tool sessions require verification before form steps by default. close refuses pending tracked resources. Keep original step tools for manual operations.";}
 public String getParameterSchema(){return """
{"type": "object", "additionalProperties": false, "properties": {"action": {"enum": ["open", "status", "close", "attach", "verify_client"]}, "project": {"type": "string"}, "profile_id": {"type": "string"}, "endpoint": {"type": "string"}, "idempotency_key": {"type": "string"}, "session_id": {"type": "string"}, "require_client_binding": {"type":"boolean","default":true}, "resource_kind": {"enum": ["run", "host", "data_set", "data_journal", "import", "database", "audit_project"]}, "resource_id": {"type": "string"}}, "required": ["action"]}
""";}
 protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{

 var s=TaskSessionService.workspace();String action=p.requireString("action");Map<String,Object> out=switch(action){
 case "open"->s.open(p.requireString("project"),(String)p.getRaw().get("profile_id"),(String)p.getRaw().get("endpoint"),p.requireString("idempotency_key"),!Boolean.FALSE.equals(p.getRaw().get("require_client_binding")));
 case "verify_client"->s.verifyClient(p.requireString("session_id"));
 case "status"->s.status(p.requireString("session_id"));case "close"->s.close(p.requireString("session_id"));
 case "attach"->s.attach(p.requireString("session_id"),p.requireString("resource_kind"),p.requireString("resource_id"));default->throw new IllegalArgumentException("Unknown action");};return ToolResult.success(new Gson().toJson(out));

 }catch(Exception e){return ToolResult.failure("edt_task_session: "+e.getMessage());}});}
}
