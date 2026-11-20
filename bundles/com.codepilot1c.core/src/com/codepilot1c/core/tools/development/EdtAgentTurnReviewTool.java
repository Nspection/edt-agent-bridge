// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.eclipse.ui.PlatformUI;

import com.codepilot1c.core.edt.development.AgentResponses;
import com.codepilot1c.core.edt.development.BslReviewService;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;

@ToolMeta(name = "edt_agent_turn_review", category = "workspace", mutating = true,
        tags = {"workspace", "edt", "compare"})
public final class EdtAgentTurnReviewTool extends AbstractTool {
    @Override
    public String getDescription() {
        return "Publish the saved changes from one agent edit turn to the EDT Agent changes panel. Begin before the "
                + "first edit with the project and planned files. Track additional paths before their first edit, using "
                + "the same turn_id across projects. Track is safe to repeat and retains the first baseline. "
                + "Include planned additions and deletions. Finish once after all edits are saved. "
                + "The panel lists only net changes to tracked paths. Concurrent user edits to those paths are included. "
                + "Latest lists the last completed turn. Show opens the panel on request. Open uses its snapshots and never saves or changes sources.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {"type":"object","additionalProperties":false,"required":["action"],"properties":{
                "action":{"enum":["begin","track","finish","latest","show","open","cleanup"]},
                "turn_id":{"type":"string"},"project":{"type":"string"},"file_path":{"type":"string"},
                "files":{"type":"array","minItems":1,"maxItems":100,"items":{"type":"string"}},
                "mode":{"enum":["lines","methods"]}}}
                """;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters parameters) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> args = parameters.getRaw();
                String action = required(args, "action");
                Set<String> allowed = switch (action) {
                    case "begin" -> Set.of("action", "project", "files");
                    case "track" -> Set.of("action", "turn_id", "project", "files");
                    case "finish", "cleanup" -> Set.of("action", "turn_id");
                    case "open" -> Set.of("action", "turn_id", "project", "file_path", "mode");
                    case "latest", "show" -> Set.of("action");
                    default -> throw new IllegalArgumentException("Unknown agent review action");
                };
                if (!allowed.containsAll(args.keySet())) throw new IllegalArgumentException("Unexpected field");
                var service = new BslReviewService();
                Object result = switch (action) {
                    case "begin", "track" -> {
                        if (!(args.get("files") instanceof List<?> files)) throw new IllegalArgumentException("files is required");
                        String id = action.equals("begin") ? null : turnId(args);
                        yield service.trackTurn(id, required(args, "project"), files);
                    }
                    case "finish" -> service.finishTurn(turnId(args));
                    case "latest" -> latest(service);
                    case "show" -> show();
                    case "open" -> {
                        String mode = args.containsKey("mode") ? required(args, "mode") : "lines";
                        if (!Set.of("lines", "methods").contains(mode)) throw new IllegalArgumentException("Unknown mode");
                        yield service.openTurn(turnId(args), required(args, "project"), required(args, "file_path"), mode);
                    }
                    default -> service.cleanup(turnId(args), false);
                };
                return ToolResult.success(AgentResponses.JSON.toJson(result));
            } catch (Exception failure) {
                return ToolResult.failure("EDT_AGENT_TURN_REVIEW: " + failure.getMessage());
            }
        });
    }

    private static Map<String, Object> show() {
        if (!PlatformUI.isWorkbenchRunning()) throw new IllegalStateException("EDT workbench is unavailable");
        var workbench = PlatformUI.getWorkbench();
        workbench.getDisplay().syncExec(() -> {
            var window = workbench.getActiveWorkbenchWindow();
            var page = window == null ? null : window.getActivePage();
            if (page == null) throw new IllegalStateException("EDT workbench has no active page");
            try {
                page.showView("com.codepilot1c.ui.views.agentChanges");
            } catch (org.eclipse.ui.PartInitException failure) {
                throw new IllegalStateException("Unable to open Agent changes", failure);
            }
        });
        return Map.of("state", "open");
    }

    private static Map<String, Object> latest(BslReviewService service) throws Exception {
        var turn = service.latestTurn();
        if (turn == null) return Map.of("state", "empty");
        var rows = turn.files().stream().map(file -> Map.of("project", file.project(),
                "file_path", file.filePath(), "change", file.change(), "comparison", file.comparison())).toList();
        return Map.of("state", "ready", "turn_id", turn.id(), "finished_at", turn.finishedAt(), "files", rows);
    }

    private static String turnId(Map<String, Object> args) {
        String id = required(args, "turn_id");
        if (!id.startsWith("turn-")) throw new IllegalArgumentException("Expected an agent turn ID");
        return id;
    }

    private static String required(Map<String, Object> args, String name) {
        if (!(args.get(name) instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return text;
    }
}
