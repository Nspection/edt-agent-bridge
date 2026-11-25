// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.bsl;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.development.AgentResponses;
import com.codepilot1c.core.edt.development.BslMethodEditService;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;

@ToolMeta(name = "edt_bsl_method_edit", category = "bsl", mutating = true, tags = {"workspace", "edt"})
public final class EdtBslMethodEditTool extends AbstractTool {
    @Override public String getDescription() {
        return "Read a saved BSL method and source_sha256. Preview or apply a replacement using that hash. "
                + "Apply tracks changes in the current agent turn and rejects stale source or dirty editors.";
    }

    @Override public String getParameterSchema() {
        return """
                {"type":"object","additionalProperties":false,"required":["action","project","path","name"],"properties":{
                "action":{"enum":["read","preview","apply"]},"project":{"type":"string"},"path":{"type":"string","description":"Project-relative src/.../*.bsl"},
                "name":{"type":"string"},"expected_sha256":{"type":"string","pattern":"^[A-Fa-f0-9]{64}$"},
                "replacement":{"type":"string","minLength":1,"maxLength":250000}},
                "if":{"properties":{"action":{"enum":["preview","apply"]}}},"then":{"required":["expected_sha256","replacement"]}}
                """;
    }

    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return doExecute(params, ToolExecutionContext.unscoped());
    }

    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters params, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String action = params.requireString("action");
                if (!Set.of("read", "preview", "apply").contains(action)) throw new IllegalArgumentException("Unknown action");
                String hash = action.equals("read") ? "" : params.requireString("expected_sha256");
                String replacement = action.equals("read") ? "" : params.requireString("replacement");
                if (replacement.length() > 250000) throw new IllegalArgumentException("Replacement is too large");
                var result = new BslMethodEditService().execute(action, params.requireString("project"),
                        params.requireString("path"), params.requireString("name"), hash, replacement, context.reviewTurnId());
                return AgentResponses.success(result, params.getRaw());
            } catch (Exception failure) {
                return ToolResult.failure("BSL_METHOD_EDIT: " + failure.getMessage());
            }
        });
    }
}
