package com.codepilot1c.core.tools.bsl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import com.codepilot1c.core.edt.lang.BslMethodBodyRequest;
import com.codepilot1c.core.edt.lang.BslSemanticService;

import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.ql.QlIssue;
import com.codepilot1c.core.edt.ql.QlValidationRequest;
import com.codepilot1c.core.edt.ql.QlValidationResult;
import com.codepilot1c.core.edt.ql.QlValidationService;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Validates 1C:Enterprise query-language text against a project's metadata.
 */
@ToolMeta(name = "validate_query", category = "bsl", tags = {"read-only", "workspace", "edt"})
public class ValidateQueryTool extends AbstractTool {

    private static final int MAX_METHOD_CHARS = 250_000;
    private static final int MAX_LITERALS = 8;
    private static final Pattern BSL_TOKEN = Pattern.compile(
            "//[^\\r\\n]*|\"(?:[^\"\\r\\n]++|\"\"|\\r?\\n[ \\t]*\\|)*+\"|[\\p{L}_][\\p{L}\\p{N}_]*|[^\\s]");
    private static final Pattern QUERY_START = Pattern.compile(
            "(?iu)^(?:SELECT|ВЫБРАТЬ)(?![\\p{L}\\p{N}_])");
    private static final Pattern CONTINUATION = Pattern.compile("\\r?\\n[ \\t]*\\|");

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project name"},
                "queryText": {"type": "string", "description": "1C query language text to validate, e.g. ВЫБРАТЬ Ссылка ИЗ Справочник.Номенклатура"},
                "filePath": {"type": "string", "description": "Module path relative to project src/"},
                "name": {"type": "string", "description": "BSL method name when validating queries stored in source"},
                "kind": {"type":"string","enum":["any","procedure","function"]},
                "start_line": {"type":"integer","minimum":1},
                "dcsMode": {"type": "boolean", "description": "Validate as a Data Composition System query (allow {...} blocks and dataset fields). Default false."}
              },
              "required": ["projectName"],
              "oneOf": [
                {"required":["queryText"],"not":{"anyOf":[{"required":["filePath"]},{"required":["name"]},{"required":["kind"]},{"required":["start_line"]}]}},
                {"required":["filePath","name"],"not":{"required":["queryText"]}}
              ]
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Проверяет текст запроса 1С на синтаксические и семантические ошибки в контексте проекта: " //$NON-NLS-1$
                + "разрешает имена таблиц и полей по метаданным конфигурации. Используй перед вставкой запроса в BSL " //$NON-NLS-1$
                + "или в макет СКД (dcsMode=true). Вместо queryText можно указать filePath и name: "
                + "проверяются статические многострочные литералы запроса в одном методе."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String projectName = params.requireString("projectName"); //$NON-NLS-1$
            boolean dcsMode = params.optBoolean("dcsMode", false); //$NON-NLS-1$
            try {
                var validator = new QlValidationService();
                if (!params.getRaw().containsKey("queryText")) {
                    return validateMethod(params, validator, dcsMode);
                }
                if (params.getRaw().keySet().stream().anyMatch(
                        java.util.Set.of("filePath", "name", "kind", "start_line")::contains)) {
                    return ToolResult.failure("Use queryText or filePath and name");
                }
                QlValidationResult result = validator.validate(
                        new QlValidationRequest(projectName, params.requireString("queryText"), dcsMode));
                JsonObject structured = new Gson().toJsonTree(result).getAsJsonObject();
                return ToolResult.success(render(result), ToolResult.ToolResultType.CODE, structured);
            } catch (EdtAstException e) {
                return ToolResult.failure(e.getCode().name() + ": " + e.getMessage()); //$NON-NLS-1$
            } catch (Exception e) {
                return ToolResult.failure("INTERNAL_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private static ToolResult validateMethod(ToolParameters params, QlValidationService validator, boolean dcsMode) {
        var request = BslMethodBodyRequest.fromParameters(params.getRaw());
        request = new BslMethodBodyRequest(request.getProjectName(), request.getFilePath(),
                request.getName(), request.getKind(), 0, request.getStartLine());
        var method = new BslSemanticService().getMethodBody(request);
        if (method.getText().length() > MAX_METHOD_CHARS) {
            return ToolResult.failure("Method too large for automatic query extraction; pass queryText explicitly");
        }

        var queries = queries(method.getText(), method.getStartLine());
        var results = new ArrayList<MethodQueryResult>();
        for (var query : queries.subList(0, Math.min(MAX_LITERALS, queries.size()))) {
            var result = validator.validate(new QlValidationRequest(method.getProjectName(), query.text(), dcsMode));
            results.add(new MethodQueryResult(query.sourceLine(), result));
        }
        var response = new LinkedHashMap<String, Object>();
        response.put("projectName", method.getProjectName());
        response.put("filePath", method.getFilePath());
        response.put("name", method.getName());
        response.put("scope", "static_multiline_query_literals");
        response.put("query_count", queries.size());
        response.put("truncated", queries.size() > MAX_LITERALS);
        response.put("status", results.isEmpty() ? "no_static_queries"
                : results.stream().anyMatch(query -> !query.validation().valid()) ? "errors_found"
                : queries.size() > MAX_LITERALS ? "partial" : "checked");
        response.put("queries", results);
        response.put("coverage", "Single-line literals and concatenated or dynamically assembled query text are outside this check. "
                + "Issue coordinates are relative to each query; sourceLine locates its opening quote.");
        JsonObject json = new Gson().toJsonTree(response).getAsJsonObject();
        return ToolResult.success(json.toString(), ToolResult.ToolResultType.CODE, json);
    }

    record QueryLiteral(int sourceLine, String text) { }
    private record MethodQueryResult(int sourceLine, QlValidationResult validation) { }

    private record SourceToken(String text, int line) { }

    static List<QueryLiteral> queries(String source, int startLine) {
        var matcher = BSL_TOKEN.matcher(source);
        var tokens = new ArrayList<SourceToken>();
        int line = startLine;
        int last = 0;
        while (matcher.find()) {
            for (; last < matcher.start(); last++) if (source.charAt(last) == '\n') line++;
            String text = matcher.group();
            if (!text.startsWith("//")) tokens.add(new SourceToken(text, line));
        }

        var result = new ArrayList<QueryLiteral>();
        for (int position = 0; position < tokens.size(); position++) {
            var token = tokens.get(position);
            String literal = token.text();
            if (!literal.startsWith("\"") || literal.indexOf('\n') < 0
                    || partOfConcatenation(tokens, position)) continue;
            String query = CONTINUATION.matcher(literal.substring(1, literal.length() - 1)).replaceAll("\n")
                    .replace("\"\"", "\"");
            if (QUERY_START.matcher(query.stripLeading()).find()) result.add(new QueryLiteral(token.line(), query));
        }
        return result;
    }

    private static boolean partOfConcatenation(List<SourceToken> tokens, int position) {
        int left = position - 1;
        int right = position + 1;
        while (left >= 0 && right < tokens.size() && tokens.get(left).text().equals("(")
                && tokens.get(right).text().equals(")") && !isCall(tokens, left)) {
            left--;
            right++;
        }
        return (left >= 0 && tokens.get(left).text().equals("+"))
                || (right < tokens.size() && tokens.get(right).text().equals("+"));
    }

    private static boolean isCall(List<SourceToken> tokens, int opening) {
        if (opening == 0) return false;
        String previous = tokens.get(opening - 1).text();
        if (previous.equals(")") || previous.equals("]")) return true;
        if (!Character.isLetter(previous.charAt(0)) && previous.charAt(0) != '_') return false;
        return !java.util.Set.of("возврат", "return", "если", "if", "иначеесли", "elsif",
                "пока", "while", "не", "not", "и", "and", "или", "or")
                .contains(previous.toLowerCase(java.util.Locale.ROOT));
    }

    private static String render(QlValidationResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(result.valid() ? "Запрос корректен." : "Найдены ошибки в запросе.") //$NON-NLS-1$ //$NON-NLS-2$
                .append(" (errors: ").append(result.errorCount()) //$NON-NLS-1$
                .append(", warnings: ").append(result.warningCount()) //$NON-NLS-1$
                .append(", info: ").append(result.infoCount()) //$NON-NLS-1$
                .append(result.dcsMode() ? ", dcsMode" : "").append(")\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (QlIssue issue : result.issues()) {
            sb.append("- [").append(issue.severity()).append("] "); //$NON-NLS-1$ //$NON-NLS-2$
            if (issue.line() > 0) {
                sb.append("line ").append(issue.line()); //$NON-NLS-1$
                if (issue.column() > 0) {
                    sb.append(":").append(issue.column()); //$NON-NLS-1$
                }
                sb.append(" — "); //$NON-NLS-1$
            }
            sb.append(issue.message()).append("\n"); //$NON-NLS-1$
        }
        return sb.toString();
    }
}
