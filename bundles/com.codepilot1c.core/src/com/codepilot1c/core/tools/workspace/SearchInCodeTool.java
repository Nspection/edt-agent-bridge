package com.codepilot1c.core.tools.workspace;

import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.codepilot1c.core.edt.runtime.EdtToolException;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.metadata.EdtProjectAnalysisSupport;
import com.google.gson.JsonObject;

@ToolMeta(
        name = "edt_search_in_code",
        category = "workspace",
        mutating = false,
        tags = {"workspace", "read-only", "edt", "search", "bsl"})
public class SearchInCodeTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(SearchInCodeTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project name"},
                "query": {"type": "string", "description": "Text or regular expression to search"},
                "searchType": {"type": "string", "enum": ["text", "regex", "bm25", "auto"], "description": "Default text preserves legacy. Prefer auto for discovery: identifier literal text, otherwise BM25; explicit modes always available"},
                "top_k": {"type":"integer","minimum":1,"maximum":20,"description":"BM25/auto only, default 5"},
                "rebuild": {"type":"boolean","description":"BM25 or auto routed to BM25 only: explicitly rebuild saved-file index, normally unnecessary"},
                "scope": {"type": "string", "enum": ["all", "modules", "queries"], "description": "Search scope"}
              },
              "required": ["projectName", "query"]
            }
            """; //$NON-NLS-1$

    private final EdtProjectAnalysisSupport support;

    public SearchInCodeTool() {
        this(new EdtMetadataGateway());
    }

    public SearchInCodeTool(EdtMetadataGateway gateway) {
        this.support = new EdtProjectAnalysisSupport(gateway);
    }

    @Override
    public String getDescription() {
        return "Ищет текст/regex (прежнее поведение) либо searchType=bm25: локальный ранжированный поиск по методам BSL, именам объектов, синонимам и комментариям. BM25 обновляет индекс сохранённых src-файлов, возвращает top_k, file:line, компактный snippet, matched_terms и явную полноту/свежесть. searchType=auto: идентификатор ищется как точная подстрока, иначе BM25; явные text/regex/bm25 сохранены. Это лексический поиск, не semantic embeddings. Без сети, Python, запуска базы или изменения проекта."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("edt-search-code"); //$NON-NLS-1$
            String projectName = ""; //$NON-NLS-1$
            LOG.info("[%s] START edt_search_in_code", opId); //$NON-NLS-1$
            try {
                projectName = params.requireString("projectName").trim(); //$NON-NLS-1$
                String query = params.requireString("query");
                var route = com.codepilot1c.core.search.SearchRouting.select(params.optString("searchType", "text"), query);
                boolean ranked = "bm25".equals(route.selected());
                boolean automatic = "auto".equals(route.requested());
                if (!ranked && !automatic && params.getRaw().containsKey("top_k")) throw new IllegalArgumentException("top_k applies only to BM25/auto");
                if (params.getRaw().containsKey("rebuild") && (!(params.getRaw().get("rebuild") instanceof Boolean) || !ranked)) throw new IllegalArgumentException("rebuild requires a BM25 route and must be boolean");
                int limit = params.getRaw().containsKey("top_k") ? Math.toIntExact(params.requireLong("top_k")) : 5;
                if ((ranked || automatic) && (limit < 1 || limit > 20)) throw new IllegalArgumentException("top_k must be 1..20");
                JsonObject result = ranked
                        ? support.searchRanked(projectName, query, params.optString("scope", "all"), limit, Boolean.TRUE.equals(params.getRaw().get("rebuild")))
                        : automatic ? support.searchLiteralBounded(projectName, query, params.optString("scope", "all"), limit)
                        : support.searchInCode(projectName, query, route.selected(), params.optString("scope", "all"));
                if (automatic) {
                    result.addProperty("requested_search_type", "auto");
                    result.addProperty("selected_search_type", route.selected());
                    result.addProperty("route_reason", route.reason());
                    result.addProperty("explicit_modes_available", true);
                }
                return ToolResult.success(EdtProjectAnalysisSupport.pretty(result), ToolResult.ToolResultType.SEARCH_RESULTS, result);
            } catch (ToolParameters.ToolParameterException e) {
                return failure(projectName, EdtToolErrorCode.INVALID_ARGUMENT.name(), e.getMessage());
            } catch (EdtToolException e) {
                return failure(projectName, e.getCode().name(), e.getMessage());
            } catch (Exception e) {
                LOG.error("[" + opId + "] edt_search_in_code failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return failure(projectName, "SEARCH_IN_CODE_FAILED", e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private ToolResult failure(String projectName, String code, String message) {
        JsonObject error = EdtProjectAnalysisSupport.errorPayload(projectName, code, message);
        return ToolResult.failure(EdtProjectAnalysisSupport.pretty(error));
    }
}
