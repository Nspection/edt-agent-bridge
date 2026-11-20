// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.development.AgentResponses;
import com.codepilot1c.core.edt.development.BslReviewService;
import com.codepilot1c.core.edt.development.RuntimeExecutionPlan;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;

@ToolMeta(name = "edt_bsl_review", category = "workspace", mutating = true, tags = {"workspace", "edt", "compare"})
public final class EdtBslReviewTool extends AbstractTool {
    @Override public String getDescription() {
        return "Show BSL edits in EDT, from any MCP client. Call begin with project and exact src/... .bsl files "
                + "before edits; after saving edits call finish with the same review_id. Snapshots are frozen and kept locally. "
                + "Call open with review_id, file_path and mode=lines or methods to open an EDT read-only comparison or switch the mode in its existing tab. "
                + "If BSL syntax prevents method comparison, use lines. Use status for file hashes and changed flags. "
                + "Call cleanup for the exact review_id to remove only its snapshots. close_views=true closes its own read-only tabs first. "
                + "Automatic cleanup keeps open views and applies a one-day retention and a workspace storage quota. After the reviewed changes have been pushed, use cleanup with that review_id when the snapshots are no longer needed.";
    }

    @Override public String getParameterSchema() {
        return """
                {"type":"object","additionalProperties":false,"required":["action"],"properties":{
                "action":{"enum":["begin","finish","status","open","cleanup"]},"project":{"type":"string"},
                "files":{"type":"array","minItems":1,"maxItems":50,"items":{"type":"string"}},
                "review_id":{"type":"string"},"file_path":{"type":"string"},
                "mode":{"enum":["lines","methods"],"default":"lines"},"close_views":{"type":"boolean","default":false}}}
                """;
    }

    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters parameters) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var args = parameters.getRaw();
                String action = RuntimeExecutionPlan.required(args, "action");
                var fields = switch (action) {
                case "begin" -> Set.of("action", "project", "files");
                case "finish", "status" -> Set.of("action", "review_id");
                case "open" -> Set.of("action", "review_id", "file_path", "mode");
                case "cleanup" -> Set.of("action", "review_id", "close_views");
                default -> throw new IllegalArgumentException("Unknown BSL review action");
                };
                if (!fields.containsAll(args.keySet())) throw new IllegalArgumentException("Unexpected BSL review field");
                var service = new BslReviewService();
                if (action.equals("begin")) {
                    if (!(args.get("files") instanceof List<?> files)) throw new IllegalArgumentException("files are required");
                    return ToolResult.success(AgentResponses.JSON.toJson(
                            service.begin(RuntimeExecutionPlan.required(args, "project"), files)));
                }
                String id = RuntimeExecutionPlan.required(args, "review_id");
                var result = switch (action) {
                case "finish" -> service.finish(id);
                case "status" -> service.status(id);
                case "open" -> {
                    Object mode = args.getOrDefault("mode", "lines");
                    if (!List.of("lines", "methods").contains(mode)) throw new IllegalArgumentException("Choose lines or methods");
                    yield service.open(id, RuntimeExecutionPlan.required(args, "file_path"), (String) mode);
                }
                default -> service.cleanup(id, Boolean.TRUE.equals(args.get("close_views")));
                };
                return ToolResult.success(AgentResponses.JSON.toJson(result));
            } catch (Exception | LinkageError failure) {
                return ToolResult.failure("EDT_BSL_REVIEW: " + failure.getMessage());
            }
        });
    }
}
