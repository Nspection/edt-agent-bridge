// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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
        return "Изменения ИИ. Call begin before edits and finish with its turn_id after saving. Supported Bridge writes are tracked "
                + "automatically on the same MCP connection: edit_file, edt_change_set writes/rollback, mutate_form_model and apply_form_recipe mode=update with form_fqn. "
                + "Before external or other edits, call track with the same turn_id and project. "
                + "Use files for existing paths (including planned deletions), e.g. src/Catalogs/Items/Forms/Item/Form.form. "
                + "Use additions for new project-relative paths, e.g. src/CommonModules/NewModule/Module.bsl. "
                + "Missing files fail before edits; finish reports additions that were never created. "
                + "The panel lists only net changes to tracked paths. Concurrent user edits to those paths are included. "
                + "Latest lists the last completed turn. Show opens the panel on request. Open uses its snapshots and never saves or changes sources.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {"type":"object","additionalProperties":false,"required":["action"],"properties":{
                "action":{"enum":["begin","track","finish","latest","show","open","cleanup"]},
                "turn_id":{"type":"string"},"project":{"type":"string"},"file_path":{"type":"string"},
                 "files":{"type":"array","maxItems":100,"items":{"type":"string"},"description":"Existing paths from the project root, including src/ for EDT sources."},
                "additions":{"type":"array","maxItems":100,"items":{"type":"string"},"description":"Explicit paths of files that will be created, from the project root."},
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
                    case "begin" -> Set.of("action", "project", "files", "additions");
                    case "track" -> Set.of("action", "turn_id", "project", "files", "additions");
                    case "finish", "cleanup" -> Set.of("action", "turn_id");
                    case "open" -> Set.of("action", "turn_id", "project", "file_path", "mode");
                    case "latest" -> Set.of("action");
                    case "show" -> Set.of("action", "turn_id");
                    default -> throw new IllegalArgumentException("Unknown agent review action");
                };
                if (!allowed.containsAll(args.keySet())) throw new IllegalArgumentException("Unexpected field");
                var service = new BslReviewService();
                Object result = switch (action) {
                    case "begin", "track" -> {
                        List<?> files = paths(args, "files");
                        List<?> additions = paths(args, "additions");
                        String id = action.equals("begin") ? null : turnId(args);
                        String project = files.isEmpty() && additions.isEmpty() ? null : required(args, "project");
                        yield service.trackTurn(id, project, files, additions);
                    }
                    case "finish" -> service.finishTurn(turnId(args));
                    case "latest" -> latest(service);
                    case "show" -> new com.codepilot1c.core.edt.development.AgentHistoryService().show(
                            com.codepilot1c.core.edt.development.AgentHistoryService.Pane.CHANGES,
                            args.containsKey("turn_id") ? turnId(args) : null);
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

    private static List<?> paths(Map<String, Object> args, String key) {
        if (!args.containsKey(key)) return List.of();
        if (args.get(key) instanceof List<?> list) return list;
        throw new IllegalArgumentException(key + " must be a list of project-relative paths");
    }

    private static Map<String, Object> latest(BslReviewService service) throws Exception {
        var turn = service.latestTurn();
        if (turn == null) return Map.of("state", "empty");
        var rows = turn.files().stream().map(file -> Map.of("project", file.project(),
                "file_path", file.filePath(), "change", file.change(), "comparison", file.comparison())).toList();
        return Map.of("state", "ready", "turn_id", turn.id(), "finished_at", turn.finishedAt(), "files", rows,
                "unreadable_newer_turns", turn.unreadableTurns());
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
