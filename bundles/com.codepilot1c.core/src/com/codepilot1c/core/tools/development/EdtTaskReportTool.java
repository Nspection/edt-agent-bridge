// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.TaskReportService;
@ToolMeta(name="edt_task_report",category="qa",mutating=false,tags={"workspace","edt","sensitive"})
public final class EdtTaskReportTool extends AbstractTool {
 public String getDescription(){return "Compact read-only task report: current edt_check changes, optional baseline/run, explicit test-data journal summaries and form snapshot evidence. Does not run tests, advance sets or clean data. Historical scenario/source matching, actual visual review, snapshot-to-project binding and whole-database cleanup are NOT certified. Missing supplied evidence is unknown. Get full details with original tools.";}
 public String getParameterSchema(){return """
 {"type":"object","additionalProperties":false,"required":["project"],"properties":{"project":{"type":"string"},"files":{"type":"array","maxItems":2000,"items":{"type":"string"}},"baseline_id":{"type":"string"},"run_id":{"type":"string"},"data_run_ids":{"type":"array","maxItems":10,"uniqueItems":true,"items":{"type":"string"}},"form_snapshot_ids":{"type":"array","maxItems":10,"uniqueItems":true,"items":{"type":"string"}}}}
 """;}
 private static List<String> strings(ToolParameters p,String key){Object raw=p.getRaw().get(key);if(raw==null)return List.of();if(!(raw instanceof List<?>list)||list.stream().anyMatch(x->!(x instanceof String)))throw new IllegalArgumentException(key+" must be strings");return list.stream().map(x->(String)x).toList();}
 protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{for(String k:p.getRaw().keySet())if(!Set.of("project","files","baseline_id","run_id","data_run_ids","form_snapshot_ids").contains(k))throw new IllegalArgumentException("Unexpected parameter: "+k);var result=new TaskReportService(new TaskReportService.NativeBackend()).report(p.requireString("project"),p.getRaw().containsKey("files")?strings(p,"files"):null,(String)p.getRaw().get("baseline_id"),(String)p.getRaw().get("run_id"),strings(p,"data_run_ids"),strings(p,"form_snapshot_ids"));return ToolResult.success(new Gson().toJson(result));}catch(Exception e){return ToolResult.failure("edt_task_report: "+e.getMessage());}});}
}
