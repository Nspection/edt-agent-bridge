// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import com.codepilot1c.core.edt.development.AgentResponses;
import com.codepilot1c.core.edt.development.BugReproductionService;
import com.codepilot1c.core.edt.development.BugReproductionPanel;
import com.codepilot1c.core.edt.development.RuntimeExecutionPlan;
import com.codepilot1c.core.edt.development.RuntimeConsentPolicy;
import com.codepilot1c.core.mcp.host.McpOperationAuthorization;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;

@ToolMeta(name = "edt_bug_reproduction", category = "qa", mutating = true, tags = {"workspace", "edt", "sensitive"})
public final class EdtBugReproductionTool extends AbstractTool {
    @Override public String getDescription() {
        return "Record a 1C action journal and prepare a local bug draft only when requested. For a manual-only launch use edt_launch_app; for agent-driven form checks use edt_test_client. On the developer's request, start with an "
                + "explicit project, developer-confirmed registered test base_uuid bound in EDT and stable idempotency_key. "
                + "If no test base is available, offer to provision a separate copy from the selected DT with edt_infobase_provision. "
                + "For another bug in the same confirmed base, use repeat with the previous session_id and a fresh idempotency_key. "
                + "It reuses a healthy client after a confirmed stop or starts a new one when the former client has exited. "
                + "Default backend=testclient uses the scoped direct TestClient consent and managed Python environment. "
                + "Use begin and finish from MCP or the shared floating panel after the developer confirms. "
                + "Use status, panel, mark(expected/actual) and screenshot(optional exact window_id) with the same session_id. "
                + "Use link while a record card is open to save its 1C navigation link for the draft description. "
                + "Mark also samples the active card. Optional local video is initially off. "
                + "On request use video_start with a fresh idempotency_key and optional exact window_id; use video_stop with its video_id. "
                + "Video captures the selected 1C window without audio. Finishing the session stops any active clip. "
                + "Only a native start acknowledgment starts the recording timer. The legacy manual backend uses the 1C title bar. "
                + "Optionally use prepare with start's project, base and key to reserve a session without opening 1C. "
                + "If start returns synchronization_required, handle synchronization separately before retrying the same key. "
                + "Supply owned_base_id at prepare/start for a disposable test copy if DT export is needed. The manual backend also requires ownership. On request dump_dt exports that copy locally through EDT. "
                + "After the developer confirms the exchange folder, copy_dt verifies the copied file before deleting the local DT. "
                + "These independent jobs return job_id for edt_operation_status. The verified exchange path is included in the draft description. "
                + "Report recognized actions in order. Materials are kept locally.";
    }

    @Override public String getParameterSchema() {
        return """
                {"type":"object","additionalProperties":false,"required":["action"],"properties":{
                 "action":{"enum":["start","prepare","repeat","status","panel","begin","mark","link","screenshot","video_start","video_stop","dump_dt","copy_dt","finish"]},"project":{"type":"string"},"base_uuid":{"type":"string"},
                 "backend":{"enum":["testclient","manual"],"default":"testclient"},"owned_base_id":{"type":"string"},"idempotency_key":{"type":"string"},
                 "session_id":{"type":"string","pattern":"^[a-f0-9]{64}$"},
                 "exchange_directory":{"type":"string"},
                 "video_id":{"type":"string","pattern":"^[a-f0-9]{64}$"},"window_id":{"type":"string","pattern":"^0x[a-fA-F0-9]+$"},"expected":{"type":"string","maxLength":2000},"actual":{"type":"string","maxLength":2000}}}
                """;
    }

    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters parameters) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var args = parameters.getRaw();
                String action = RuntimeExecutionPlan.required(args, "action");
                var fields = switch (action) {
                case "start" -> Set.of("action", "project", "base_uuid", "owned_base_id", "idempotency_key", "backend");
                case "prepare" -> Set.of("action", "project", "base_uuid", "owned_base_id", "idempotency_key");
                case "repeat" -> Set.of("action", "session_id", "idempotency_key");
                case "status", "panel", "begin", "link", "dump_dt" -> Set.of("action", "session_id");
                case "screenshot" -> Set.of("action", "session_id", "window_id");
                case "copy_dt" -> Set.of("action", "session_id", "exchange_directory");
                case "video_start" -> Set.of("action", "session_id", "window_id", "idempotency_key");
                case "video_stop" -> Set.of("action", "session_id", "video_id");
                case "mark", "finish" -> Set.of("action", "session_id", "expected", "actual");
                default -> throw new IllegalArgumentException("Unknown recording action");
                };
                if (!fields.containsAll(args.keySet())) {
                    throw new IllegalArgumentException("Fields do not apply to this recording action");
                }
                var service = new BugReproductionService();
                if (action.equals("dump_dt") || action.equals("copy_dt")) {
                    McpOperationAuthorization.requireAllowed("edt_bug_reproduction");
                    String session = RuntimeExecutionPlan.required(args, "session_id");
                    String exchange = action.equals("copy_dt")
                            ? RuntimeExecutionPlan.required(args, "exchange_directory") : null;
                    service.requireDtOwnership(session);
                    String job = BackgroundJobRegistry.getInstance().startJob("edt_bug_reproduction_" + action, progress -> {
                        try {
                            if (exchange != null) progress.accept(Map.of("stage", "copy_dt"));
                            return AgentResponses.JSON.toJson(exchange == null ? service.dumpDt(session, progress)
                                    : service.copyDt(session, exchange));
                        } catch (Exception | LinkageError failure) {
                            throw new IllegalStateException("DT operation failed: " + failure.getClass().getSimpleName());
                        }
                    });
                    return ToolResult.success(AgentResponses.JSON.toJson(Map.of("job_id", job, "state", "queued",
                            "next_tool", "edt_operation_status")));
                }
                String ownership = args.containsKey("owned_base_id")
                        ? RuntimeExecutionPlan.required(args, "owned_base_id") : "";
                Map<String, Object> result = switch (action) {
                case "start" -> service.start(RuntimeExecutionPlan.required(args, "project"),
                        RuntimeExecutionPlan.required(args, "base_uuid"), ownership,
                        RuntimeExecutionPlan.required(args, "idempotency_key"),
                        (String) args.getOrDefault("backend", "testclient"));
                case "prepare" -> service.prepare(RuntimeExecutionPlan.required(args, "project"),
                        RuntimeExecutionPlan.required(args, "base_uuid"), ownership,
                        RuntimeExecutionPlan.required(args, "idempotency_key"));
                case "repeat" -> service.repeat(RuntimeExecutionPlan.required(args, "session_id"),
                        RuntimeExecutionPlan.required(args, "idempotency_key"));
                case "status", "panel" -> service.status(RuntimeExecutionPlan.required(args, "session_id"));
                case "begin" -> service.begin(RuntimeExecutionPlan.required(args, "session_id"));
                case "mark" -> service.mark(RuntimeExecutionPlan.required(args, "session_id"),
                        (String) args.get("expected"), (String) args.get("actual"));
                case "link" -> service.captureLink(RuntimeExecutionPlan.required(args, "session_id"));
                case "screenshot" -> service.screenshot(RuntimeExecutionPlan.required(args, "session_id"),
                        (String) args.get("window_id"));
                case "video_start" -> service.videoStart(RuntimeExecutionPlan.required(args, "session_id"),
                        (String) args.get("window_id"), RuntimeExecutionPlan.required(args, "idempotency_key"));
                case "video_stop" -> service.videoStop(RuntimeExecutionPlan.required(args, "session_id"),
                        RuntimeExecutionPlan.required(args, "video_id"));
                default -> service.finish(RuntimeExecutionPlan.required(args, "session_id"),
                        (String) args.get("expected"), (String) args.get("actual"));
                };
                if (Set.of("start", "repeat", "panel").contains(action)
                        && result.get("session_id") instanceof String sessionId
                        && !Set.of("draft_ready", "prepared").contains(result.get("state"))) {
                    result = new LinkedHashMap<>(result);
                    result.put("panel_state", BugReproductionPanel.show(sessionId, result));
                }
                return ToolResult.success(AgentResponses.JSON.toJson(result));
            } catch (RuntimeConsentPolicy.Required required) {
                return ToolResult.success(AgentResponses.JSON.toJson(required.result()));
            } catch (IllegalArgumentException | IllegalStateException error) {
                return ToolResult.failure(error.getMessage());
            } catch (Exception error) {
                return ToolResult.failure("Recording operation failed: " + error.getClass().getSimpleName());
            }
        });
    }
}
