// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import java.util.concurrent.CompletableFuture;import com.codepilot1c.core.tools.*;import com.codepilot1c.core.edt.development.*;
@ToolMeta(name="edt_result",category="workspace",tags={"read-only","workspace","edt","sensitive"})
public final class EdtResultTool extends AbstractTool {
 public String getDescription(){return "Read omitted response details by opaque details_id, as immutable paginated JSON text. Cache expires after 30 minutes or restart; no tool is re-executed. Follow next_offset; partial text does not prove business results.";}
 public String getParameterSchema(){return """
 {"type":"object","additionalProperties":false,"properties":{"details_id":{"type":"string"},"offset":{"type":"integer","minimum":0,"default":0},"limit":{"type":"integer","minimum":2,"maximum":8000,"default":4000}},"required":["details_id"]}
 """;}
 protected CompletableFuture<ToolResult> doExecute(ToolParameters p){try{return CompletableFuture.completedFuture(AgentResponses.success(AgentResponses.read(p.requireString("details_id"),AgentResponses.integer(p.getRaw(),"offset",0,0,Integer.MAX_VALUE),AgentResponses.integer(p.getRaw(),"limit",4000,2,8000)),java.util.Map.of()));}catch(Exception e){return CompletableFuture.completedFuture(AgentErrors.failure(getName(),"details",e,true));}}
}
