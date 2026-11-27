// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.forms;

import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.development.AgentResponses;
import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;

@ToolMeta(name = "edt_form_property_reference", category = "forms", tags = {"read-only", "workspace", "edt"})
public final class EdtFormPropertyReferenceTool extends AbstractTool {
    @Override public String getDescription() {
        return "Read writable scalar properties and current values for one managed form or a specific item_id. "
                + "Russian EDT labels (label_ru) are included when available; filter by key or label. model_is_set is EMF state, not XML presence. Use returned keys in mutate_form_model, with the usual validation token.";
    }

    @Override public String getParameterSchema() {
        return """
                {"type":"object","additionalProperties":false,"required":["project","form_fqn"],"properties":{
                "project":{"type":"string"},"form_fqn":{"type":"string"},"item_id":{"type":"integer","minimum":1},
                "filter":{"type":"string"},"limit":{"type":"integer","minimum":1,"maximum":100}}}
                """;
    }

    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Integer id = params.getRaw().containsKey("item_id") ? params.optInt("item_id", -1) : null;
                if (id != null && id <= 0) throw new IllegalArgumentException("item_id must be positive");
                var result = new EdtMetadataService().inspectFormProperties(params.requireString("project"),
                        params.requireString("form_fqn"), id, params.optString("filter", ""),
                        Math.max(1, Math.min(100, params.optInt("limit", 25))));
                return AgentResponses.success(result, params.getRaw());
            } catch (Exception failure) {
                return ToolResult.failure("FORM_PROPERTY_REFERENCE: " + failure.getMessage());
            }
        });
    }
}
