// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.bsl;

import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.development.AgentResponses;
import com.codepilot1c.core.edt.development.EdtQuickFixService;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;

@ToolMeta(name = "edt_apply_fix", category = "bsl", mutating = true, tags = {"workspace", "edt"})
public final class EdtApplyFixTool extends AbstractTool {
    @Override public String getDescription() {
        return "Preview native EDT fixes for one BSL diagnostic. Apply a supported editor variant using its fresh plan_sha256.";
    }

    @Override public String getParameterSchema() {
        return """
                {"type":"object","additionalProperties":false,"required":["project","path","check_id","line"],"properties":{
                "project":{"type":"string"},"path":{"type":"string"},"check_id":{"type":"string"},
                "line":{"type":"integer","minimum":1},"apply":{"type":"boolean"},
                "variant":{"type":"integer","minimum":0},"plan_sha256":{"type":"string"}},
                "if":{"required":["apply"],"properties":{"apply":{"const":true}}},"then":{"required":["variant","plan_sha256"]}}
                """;
    }

    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p) {
        return doExecute(p, ToolExecutionContext.unscoped());
    }

    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                boolean apply = p.optBoolean("apply", false);
                var result = new EdtQuickFixService().run(p.requireString("project"), p.requireString("path"),
                        p.requireString("check_id"), p.optInt("line", -1), apply, p.optInt("variant", -1),
                        apply ? p.requireString("plan_sha256") : "", context.reviewTurnId());
                return AgentResponses.success(result, p.getRaw());
            } catch (Exception failure) {
                return ToolResult.failure("EDT_QUICK_FIX: " + failure.getMessage());
            }
        });
    }
}
