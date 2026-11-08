// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.RuntimeFormService;
@ToolMeta(name="edt_form_action",category="qa",mutating=true,tags={"workspace","edt","sensitive"})
public final class EdtFormActionTool extends AbstractTool {
 public String getDescription(){return "Perform ONE semantic TestClient action by element name: input text, choose_value from selection, command, tab, row criteria, check/uncheck. Requires exact expected_window from observation; checked in worker before dispatch. Returns job_id; poll edt_operation_status, then re-read edt_form_state to verify business result. No coordinate/keyboard/clipboard actions. Same key protects retries; unknown outcome must be investigated.";}
 public String getParameterSchema(){return """
{"type":"object","additionalProperties":false,"required":["action","expected_window","idempotency_key"],"properties":{"endpoint":{"type":"string"},"action":{"enum":["input","choose_value","command","tab","row","check","uncheck"]},"expected_window":{"type":"string"},"idempotency_key":{"type":"string","maxLength":200},"element":{"type":"string"},"table":{"type":"string"},"value":{"type":"string","maxLength":4096},"row_criteria":{"type":"string"}}}
""";}
 protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{var service=RuntimeFormService.workspace();return ToolResult.success(new Gson().toJson(service.act(p.getRaw())));}catch(Exception e){return ToolResult.failure("edt_form_action: "+e.getMessage());}});}
}
