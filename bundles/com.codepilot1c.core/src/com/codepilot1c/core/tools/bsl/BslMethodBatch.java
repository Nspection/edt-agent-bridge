// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.bsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.lang.BslMethodBodyRequest;
import com.codepilot1c.core.edt.lang.BslMethodBodyResult;
import com.codepilot1c.core.edt.lang.BslMethodLookupException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Bounded batch of the existing exact semantic method lookup. */
public final class BslMethodBatch {
    private static final Gson JSON = new Gson();
    private static final Set<String> ITEM_KEYS = Set.of("filePath", "name", "kind", "start_line", "context_lines");
    private BslMethodBatch() { }
    public record Plan(String project, int budget, List<BslMethodBodyRequest> requests) { }
    public static JsonObject read(Map<String, Object> parameters, Function<BslMethodBodyRequest, BslMethodBodyResult> reader) {
        return read(prepare(parameters), reader);
    }
    public static Plan prepare(Map<String, Object> parameters) {
        String project = string(parameters.get("projectName"), "projectName", 256);
        for (String key : parameters.keySet()) if (!Set.of("projectName", "items", "max_chars").contains(key))
            throw new IllegalArgumentException("Batch cannot include singleton or unknown parameter: " + key);
        if (!(parameters.get("items") instanceof List<?> items) || items.isEmpty() || items.size() > 8)
            throw new IllegalArgumentException("items must contain 1..8 method requests");
        int budget = integer(parameters, "max_chars", 16000, 2000, 40000);
        List<BslMethodBodyRequest> requests = new ArrayList<>();
        // Validate every item before dispatching the first read.
        for (Object value : items) {
            if (!(value instanceof Map<?, ?> item)) throw new IllegalArgumentException("Each item must be an object");
            for (Object key : item.keySet()) if (!ITEM_KEYS.contains(key)) throw new IllegalArgumentException("Unknown item field: " + key);
            String path = string(item.get("filePath"), "filePath", 1024).replace('\\', '/');
            if (path.startsWith("src/")) path = path.substring(4);
            if (path.startsWith("/") || path.contains(":") || java.util.Arrays.stream(path.split("/", -1)).anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..")))
                throw new IllegalArgumentException("filePath must be relative to project src");
            String name = string(item.get("name"), "name", 256);
            String kind = item.containsKey("kind") ? string(item.get("kind"), "kind", 16) : "any";
            int context = integer(item, "context_lines", 0, 0, 20);
            Integer line = item.containsKey("start_line") ? integer(item, "start_line", 0, 1, Integer.MAX_VALUE) : null;
            BslMethodBodyRequest request = new BslMethodBodyRequest(project, path, name, kind, context, line);
            request.validate(); requests.add(request);
        }
        return new Plan(project, budget, List.copyOf(requests));
    }
    public static JsonObject read(Plan plan, Function<BslMethodBodyRequest, BslMethodBodyResult> reader) {
        String project = plan.project(); int budget = plan.budget(); var requests = plan.requests();
        JsonArray results = new JsonArray();
        int returned = 0, failed = 0, truncated = 0;
        for (int i = 0; i < requests.size(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Batch interrupted; remaining reads not dispatched");
            BslMethodBodyRequest request = requests.get(i);
            JsonObject item = new JsonObject();
            item.addProperty("index", i); item.addProperty("filePath", request.getFilePath()); item.addProperty("name", request.getName());
            try {
                BslMethodBodyResult body = reader.apply(request);
                if (body == null || body.getText() == null) throw new IllegalStateException("Method reader returned no body");
                JsonObject method = JSON.toJsonTree(body).getAsJsonObject();
                int allowance = (budget - returned) / (requests.size() - i);
                String text = body.getText();
                boolean cut = text.length() > allowance;
                String excerpt = cut ? excerpt(text, allowance) : text;
                method.addProperty("text", excerpt); method.addProperty("text_truncated", cut);
                method.addProperty("original_body_chars", text.length()); method.addProperty("returned_body_chars", excerpt.length());
                if (cut) method.addProperty("excerpt_layout", "head_tail_with_marker_ranges_cover_original");
                item.addProperty("success", true); item.add("method", method);
                returned += excerpt.length(); if (cut) truncated++;
            } catch (Exception e) {
                failed++; item.addProperty("success", false);
                JsonObject error = new JsonObject();
                error.addProperty("code", e instanceof EdtAstException ast ? ast.getCode().name() : "METHOD_READ_FAILED");
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                error.addProperty("message", message.substring(0, Math.min(512, message.length())));
                if (e instanceof BslMethodLookupException lookup) {
                    var candidates = lookup.getCandidates();
                    error.add("candidates", JSON.toJsonTree(candidates.subList(0, Math.min(8, candidates.size()))));
                    error.addProperty("candidates_truncated", candidates.size() > 8);
                }
                item.add("error", error);
            }
            results.add(item);
        }
        JsonObject result = new JsonObject();
        result.addProperty("projectName", project); result.addProperty("terminal", true);
        result.addProperty("source", "edt_semantic"); result.addProperty("atomic_snapshot", false);
        result.addProperty("max_body_chars", budget); result.addProperty("returned_body_chars", returned);
        result.addProperty("failed_items", failed); result.addProperty("truncated_items", truncated);
        result.addProperty("complete", failed == 0 && truncated == 0); result.add("items", results); return result;
    }
    private static String string(Object value, String key, int max) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > max)
            throw new IllegalArgumentException(key + " must be a nonblank bounded string");
        return text.trim();
    }
    private static int integer(Map<?, ?> map, String key, int fallback, int min, int max) {
        if (!map.containsKey(key)) return fallback;
        Object value = map.get(key);
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != n.longValue()
                || n.longValue() < min || n.longValue() > max) throw new IllegalArgumentException(key + " must be an integer in range");
        return n.intValue();
    }
    private static String excerpt(String text, int budget) {
        String marker = "\n... [excerpt: middle omitted] ...\n";
        int head = (budget - marker.length()) / 2;
        int tail = budget - marker.length() - head;
        if (head > 0 && Character.isHighSurrogate(text.charAt(head - 1))) head--;
        int startTail = text.length() - tail;
        if (startTail < text.length() && Character.isLowSurrogate(text.charAt(startTail))) startTail++;
        return text.substring(0, head) + marker + text.substring(startTail);
    }
}
