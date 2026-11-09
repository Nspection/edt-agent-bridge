// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.ast.*;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.edt.debug.EdtDebugService;
import com.codepilot1c.core.edt.development.*;

@ToolMeta(name="edt_session_summary",category="workspace",tags={"read-only","workspace","edt"})
public final class EdtSessionSummaryTool extends AbstractTool {
    public String getDescription(){return "Compact read-only overview of one explicit project: model readiness, database synchronization, debugger and saved runner profile; optional run_id. No client launch, connection/update, screenshots, logs or password reads. Partial failures are unknown, never ready. Profile configured is not live readiness. Does not inventory all sessions or detect modal windows; use edt_process_snapshot if blocked.";}
    public String getParameterSchema(){return com.codepilot1c.core.edt.development.AgentResponses.schema("""
        {"type":"object","additionalProperties":false,"properties":{"project":{"type":"string"},"profile_id":{"type":"string"},"run_id":{"type":"string"}},"required":["project"]}
        """);}
    protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{
        try {
            String project=p.requireString("project");Map<String,Object> out=new LinkedHashMap<>();out.put("project",project);
            List<String> unknown=new ArrayList<>();
            try {var gateway=new EdtServiceGateway();var ready=new ProjectReadinessChecker(gateway).check(gateway.resolveProject(project));out.put("model",Map.of("state",ready.getState().name()));}catch(Exception | LinkageError e){unknown.add("model");out.put("model",Map.of("state","unknown","details_tool","edt_capabilities"));}
            try {out.put("database",AgentBrief.select(new EdtRuntimeService().synchronizationState(project),"name","uuid","connected","equality","configuration_synchronized","application_migrations_verified"));}catch(Exception | LinkageError e){unknown.add("database");out.put("database",Map.of("state","unknown","details_tool","edt_infobase_state"));}
            try {var d=EdtDebugService.getInstance().debugStatus(new DebugStatusRequest(project));out.put("debug",Map.of("state",d.state(),"suspended",d.suspended(),"launches",d.launchCount(),"targets",d.debugTargetCount(),"breakpoints",d.breakpointCount()));}catch(Exception | LinkageError e){unknown.add("debug");out.put("debug",Map.of("state","unknown","details_tool","debug_status"));}
            try {out.put("runner",AgentBrief.select(RunnerProfileStore.workspace().get(project,(String)p.getRaw().get("profile_id")),"configured","state","profile_id"));}catch(Exception | LinkageError e){unknown.add("runner");out.put("runner",Map.of("state","unknown","details_tool","edt_runner_profile"));}
            if(p.getRaw().containsKey("run_id")) {
                var run=DevelopmentRunService.getInstance().status(p.requireString("run_id"),0);
                if(!project.equals(run.get("project")))throw new IllegalArgumentException("run_id belongs to a different project");
                out.put("run",AgentBrief.run(run,0));
            }
            out.put("unknown_sections",unknown);out.put("next_tool","edt_check");out.put("live_runner_checked",false);
            return com.codepilot1c.core.edt.development.AgentResponses.success(out,p.getRaw());
        }catch(Exception e){return com.codepilot1c.core.edt.development.AgentErrors.failure("edt_session_summary","execution",e,true);}
    });}
}
