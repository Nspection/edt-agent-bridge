// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.development.AgentHistoryService;
import com.codepilot1c.core.edt.development.AgentHistoryService.Message;
import com.codepilot1c.core.edt.development.AgentHistoryService.Pane;
import com.codepilot1c.core.edt.development.AgentResponses;
import com.codepilot1c.core.edt.development.BslReviewService;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;

@ToolMeta(name = "edt_agent_history", category = "workspace", mutating = true,
        tags = {"workspace", "edt", "sensitive"})
public final class EdtAgentHistoryTool extends AbstractTool {
    @Override public String getDescription() {
        return "Send concise developer-facing messages to EDT with optional native navigation targets. "
                + "publish uses an existing turn_id from edt_agent_turn_review or returns a new one when omitted. "
                + "Choose a UUID message_id per row and reuse it on retries with the same turn_id. "
                + "For a retryable first publish without a review, choose a turn-<UUID> before publishing and keep it. "
                + "list/read/show select messages or changes by turn_id. show opens only the requested view. "
                + "cleanup requires turn_id or scope=all and first returns an exact preview_sha256. "
                + "retention without hours reads the workspace setting; with hours it previews the new value. "
                + "For cleanup or a setting change, pass expected_preview_sha256 only after user authorization. "
                + "Do not publish credentials, tokens or the full chat transcript.";
    }

    @Override public String getParameterSchema() {
        return """
                {"type":"object","additionalProperties":false,"required":["action"],"properties":{
                "action":{"enum":["publish","list","read","show","cleanup","retention"]},
                "pane":{"enum":["messages","changes"]},"turn_id":{"type":"string"},
                "title":{"type":"string","maxLength":160},"query":{"type":"string","maxLength":160},
                "scope":{"enum":["all"]},"retention_hours":{"type":"integer","minimum":1,"maximum":8760},
                "expected_preview_sha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},
                "messages":{"type":"array","minItems":1,"maxItems":100,"items":{
                  "type":"object","additionalProperties":false,"required":["message_id","text"],"properties":{
                  "message_id":{"type":"string","maxLength":64},"text":{"type":"string","maxLength":4000},
                  "target":{"type":"object","additionalProperties":false,
                    "required":["label","kind","project","file_path"],"properties":{
                    "label":{"type":"string","maxLength":160},"kind":{"enum":["editor","comparison"]},
                    "project":{"type":"string","maxLength":256},"file_path":{"type":"string","maxLength":2048},
                    "line":{"type":"integer","minimum":1},"method":{"type":"string","maxLength":160},
                    "element_id":{"type":"integer","minimum":1},"mode":{"enum":["lines","methods"]}
                  }}
                }}}
                }}
                """;
    }

    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters parameters) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return ToolResult.success(AgentResponses.JSON.toJson(dispatch(parameters.getRaw())));
            } catch (Exception failure) {
                return ToolResult.failure("EDT_AGENT_HISTORY: " + failure.getMessage());
            }
        });
    }

    private static Object dispatch(Map<String, Object> args) throws Exception {
        String action = text(args, "action", 16, true);
        Set<String> allowed = switch (action) {
            case "publish" -> Set.of("action", "turn_id", "title", "messages");
            case "list" -> Set.of("action", "pane", "query");
            case "read", "show" -> Set.of("action", "pane", "turn_id");
            case "cleanup" -> Set.of("action", "pane", "turn_id", "scope", "expected_preview_sha256");
            case "retention" -> Set.of("action", "pane", "retention_hours", "expected_preview_sha256");
            default -> throw new IllegalArgumentException("Unknown history action");
        };
        if (!allowed.containsAll(args.keySet())) throw new IllegalArgumentException("Unexpected history argument");
        var service = new AgentHistoryService();
        String id = text(args, "turn_id", 64, false);
        if ("publish".equals(action)) {
            var result = service.publish(id, text(args, "title", 160, false), messages(args));
            return Map.of("turn_id", result.turnId(), "message_count", result.messages().size());
        }
        Pane pane = Pane.parse(text(args, "pane", 16, true));
        String expected = text(args, "expected_preview_sha256", 64, false);
        return switch (action) {
            case "list" -> {
                String query = text(args, "query", 160, false);
                var rows = service.history(pane).stream().filter(turn -> query == null
                        || turn.title().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))
                        || turn.turnId().contains(query)).toList();
                yield Map.of("turns", rows.stream().map(turn -> Map.of("turn_id", turn.turnId(),
                        "title", turn.title(), "time", turn.time(), "count", turn.count())).toList(), "count", rows.size());
            }
            case "read" -> {
                if (id == null) {
                    var rows = service.history(pane);
                    if (rows.isEmpty()) yield Map.of("state", "empty");
                    id = rows.get(0).turnId();
                }
                yield pane == Pane.MESSAGES ? service.messages(id) : new BslReviewService().turnSummary(id);
            }
            case "show" -> service.show(pane, id);
            case "cleanup" -> {
                String scope = text(args, "scope", 16, false);
                if (scope != null && !"all".equals(scope)) throw new IllegalArgumentException("Unknown cleanup scope");
                yield service.cleanup(pane, id, "all".equals(scope), expected);
            }
            case "retention" -> {
                Integer hours = integer(args, "retention_hours");
                if (hours == null && expected != null) throw new IllegalArgumentException("retention_hours is required");
                yield service.retention(pane, hours, expected);
            }
            default -> throw new IllegalArgumentException("Unknown history action");
        };
    }

    private static List<Message> messages(Map<String, Object> args) {
        if (!(args.get("messages") instanceof List<?> list) || list.isEmpty() || list.size() > 100) {
            throw new IllegalArgumentException("Provide 1 to 100 messages");
        }
        var result = new ArrayList<Message>();
        for (Object value : list) {
            var row = object(value, Set.of("message_id", "text", "target"));
            String id = text(row, "message_id", 64, true);
            if (!id.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid message_id");
            AgentHistoryService.Target target = null;
            if (row.containsKey("target")) {
                var link = object(row.get("target"), Set.of("label", "kind", "project", "file_path",
                        "line", "method", "element_id", "mode"));
                target = AgentHistoryService.target(text(link, "label", 160, true), text(link, "kind", 16, true),
                        text(link, "project", 256, true), text(link, "file_path", 2048, true),
                        integer(link, "line"), text(link, "method", 160, false), integer(link, "element_id"),
                        text(link, "mode", 16, false));
            }
            result.add(new Message(id, text(row, "text", 4000, true), target));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, Set<String> fields) {
        if (!(value instanceof Map<?, ?> map) || !fields.containsAll(map.keySet())) {
            throw new IllegalArgumentException("Invalid message or target fields");
        }
        return (Map<String, Object>) map;
    }

    private static String text(Map<String, Object> args, String key, int limit, boolean required) {
        Object value = args.get(key);
        if (value == null && !required) return null;
        if (!(value instanceof String text) || text.isBlank() || text.length() > limit || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid " + key);
        }
        return text;
    }

    private static Integer integer(Map<String, Object> args, String key) {
        if (!args.containsKey(key)) return null;
        if (!(args.get(key) instanceof Number number)) throw new IllegalArgumentException("Invalid " + key);
        try { return new BigDecimal(number.toString()).intValueExact(); }
        catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new IllegalArgumentException("Invalid " + key);
        }
    }
}
