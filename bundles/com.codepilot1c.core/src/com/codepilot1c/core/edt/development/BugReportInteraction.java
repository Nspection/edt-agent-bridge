// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
/** Human input stays in the UI bundle; credentials are never MCP arguments. */
public interface BugReportInteraction {
    CompletableFuture<Map<String,Object>> login(String method);
    CompletableFuture<Map<String,Object>> review(String reportId,String payloadHash);
}
