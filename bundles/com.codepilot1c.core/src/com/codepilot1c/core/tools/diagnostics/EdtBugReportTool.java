// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.diagnostics;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.*;
@ToolMeta(name="edt_bug_report",category="qa",mutating=true,tags={"workspace","edt","sensitive"})
public final class EdtBugReportTool extends AbstractTool {
 public String getDescription(){return "Prepare a sanitized technical bug report for Nspection/edt-agent-bridge GitHub Issues. Local status/prepare do not post. One-time GitHub connection and sharing policy are set only by the user in EDT preferences; MCP cannot enable sharing or open login. prepare with client_id+operation_id collects an existing local TestClient journal without replay; otherwise diagnostics are explicitly agent_reported. Strict allowlist discards arbitrary text, paths, project/base identity, code, screenshots, stacks and credentials. Returned preview includes exact public title/body and payload_sha256. submit requires that hash and user ask approval or explicit auto_sanitized opt-in. Durable claim/dedup/quota precede POST; sending/delivery_unknown never retries. reconcile reads GitHub; absent acknowledgement remains unknown. Does not report reporter errors recursively. Never submit business data through another tool as a workaround.";}
 public String getParameterSchema(){return """
 {"type":"object","additionalProperties":false,"required":["action"],"properties":{"action":{"enum":["status","prepare","submit","reconcile"]},"component":{"enum":["testclient","native","editor","mcp"]},"diagnostics":{"type":"object","description":"Only known symbolic diagnostics and bounded counters survive; arbitrary values are discarded."},"client_id":{"type":"string","pattern":"^[a-f0-9]{32}$"},"operation_id":{"type":"string","pattern":"^[a-f0-9]{64}$"},"report_id":{"type":"string","pattern":"^[a-f0-9]{64}$"},"expected_payload_sha256":{"type":"string","pattern":"^[a-f0-9]{64}$"}}}
 """;}
 @SuppressWarnings("unchecked") protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{
  var args=p.getRaw();String action=RuntimeExecutionPlan.required(args,"action");var service=BugReportService.workspace();
  Set<String> allowed=switch(action){case "status","reconcile"->Set.of("action","report_id");case "submit"->Set.of("action","report_id","expected_payload_sha256");case "prepare"->Set.of("action","component","diagnostics","client_id","operation_id");default->throw new IllegalArgumentException("Unsupported report action");};if(!allowed.containsAll(args.keySet()))throw new IllegalArgumentException("Fields do not apply to this report action");
  Object result;
  if(action.equals("prepare")){
   boolean verified=args.containsKey("client_id")||args.containsKey("operation_id");Map<String,Object> diagnostics;String component=RuntimeExecutionPlan.required(args,"component");
   if(verified){if(!component.equals("testclient")||args.containsKey("diagnostics"))throw new IllegalArgumentException("Journal reports require component=testclient and no manual diagnostics");diagnostics=new DirectTestClientService().result(RuntimeExecutionPlan.required(args,"client_id"),RuntimeExecutionPlan.required(args,"operation_id"));}
   else diagnostics=args.get("diagnostics") instanceof Map<?,?> map?(Map<String,Object>)map:Map.of();
   result=service.prepare(component,diagnostics,verified);
  }else if(action.equals("submit"))result=service.submit(RuntimeExecutionPlan.required(args,"report_id"),RuntimeExecutionPlan.required(args,"expected_payload_sha256"));
  else if(action.equals("reconcile"))result=service.reconcile(RuntimeExecutionPlan.required(args,"report_id"));
  else result=args.containsKey("report_id")?service.result(RuntimeExecutionPlan.required(args,"report_id")):service.status();
  return ToolResult.success(AgentResponses.JSON.toJson(result));
 }catch(Exception failure){return ToolResult.failure("BUG_REPORT_UNAVAILABLE: "+failure.getClass().getSimpleName()+". Check GitHub connection, current preview and reporting policy in EDT preferences; no automatic retry.");}});}
}
