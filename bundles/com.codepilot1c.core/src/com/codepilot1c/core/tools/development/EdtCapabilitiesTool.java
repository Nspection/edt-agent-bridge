// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.*;
@ToolMeta(name="edt_capabilities",category="workspace",tags={"read-only","workspace","edt"})
public final class EdtCapabilitiesTool extends AbstractTool {
 public String getDescription(){return "Discover registered commands. Filter by names (1..32) or name_contains. Schemas and workflows are opt-in; cache catalog_sha256 with if_catalog_sha256. A matching catalog receipt is not runtime readiness; use edt_ready. Optional response selects summary/fields/size; edt_result reads omitted details.";}
 public String getParameterSchema(){return AgentResponses.schema("""
 {"type":"object","additionalProperties":false,"properties":{"category":{"type":"string"},"name_contains":{"type":"string"},"names":{"type":"array","minItems":1,"maxItems":32,"items":{"type":"string"}},"include_schemas":{"type":"boolean","default":false},"include_descriptions":{"type":"boolean","default":true},"include_workflows":{"type":"boolean","default":false},"if_catalog_sha256":{"type":"string"},"offset":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":100,"default":30}}}
 """);}
 protected CompletableFuture<ToolResult> doExecute(ToolParameters p){try{return CompletableFuture.completedFuture(AgentResponses.success(CapabilityCatalog.describe(ToolRegistry.getInstance().getAllTools().stream().filter(tool->ToolRegistry.getInstance().isToolModelFacing(tool.getName())).toList(),p.getRaw()),p.getRaw()));}catch(Exception e){return CompletableFuture.completedFuture(AgentErrors.failure(getName(),"discovery",e,false));}}
}
