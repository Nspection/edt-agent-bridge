// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.RuntimeFormService;
@ToolMeta(name="edt_form_state",category="qa",mutating=false,tags={"workspace","edt","sensitive"})
public final class EdtFormStateTool extends AbstractTool {
 public String getDescription(){return "Read bounded semantic TestClient form state via Vanessa: elements, values (Gherkin), messages, one element, or windows. Default depth=3; scope large forms by group/table. Text is preserved, not an invented DOM. Use snapshot_id with offset/limit for immutable details. baseline_snapshot_id captures the same scope again and returns an ordered replacement delta preserving duplicate lines; start_line/delete_count/inserted_lines transform the old snapshot. has_more means inserted lines are truncated; inspect the new snapshot for full detail. No base/client launch. Snapshot has no verified EDT project binding. Screenshots remain edt_runtime_observe.";}
 public String getParameterSchema(){return """
{"type":"object","additionalProperties":false,"properties":{"endpoint":{"type":"string"},"view":{"enum":["elements","values","messages","element","windows"]},"element":{"type":"string"},"table":{"type":"string"},"group":{"type":"string"},"max_depth":{"type":"integer","minimum":1,"maximum":20},"only_visible":{"type":"boolean"},"snapshot_id":{"type":"string"},"baseline_snapshot_id":{"type":"string"},"offset":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":100}}}
""";}
 protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{var service=RuntimeFormService.workspace();return ToolResult.success(new Gson().toJson(p.getRaw().containsKey("baseline_snapshot_id")?service.delta(p.getRaw()):p.getRaw().containsKey("snapshot_id")?service.details(p.getRaw()):service.capture(p.getRaw())));}catch(Exception e){return ToolResult.failure("edt_form_state: "+e.getMessage());}});}
}
