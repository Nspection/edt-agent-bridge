// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;import com.codepilot1c.core.tools.*;import com.codepilot1c.core.edt.development.*;
@ToolMeta(name="edt_agent_metrics",category="workspace",tags={"read-only","workspace","edt"})
public final class EdtAgentMetricsTool extends AbstractTool {
 public String getDescription(){return "Read process-local per-tool call counts, UTF-8 JSON request/response bytes, wall time and failures. No payloads retained. Compare snapshots; bytes are not model tokens. Current metrics request is counted after its response.";}
 public String getParameterSchema(){return AgentResponses.schema("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}");}
 protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.completedFuture(AgentResponses.success(AgentMetrics.snapshot(),p.getRaw()));}
}
