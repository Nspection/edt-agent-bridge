// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.logging.LogSanitizer;
/** Conservative error receipts. Classification never authorizes replay of a write. */
public final class AgentErrors {
 private AgentErrors(){}
 public static Map<String,Object> describe(String tool,String stage,Throwable failure,boolean readOnly){
  while((failure instanceof java.util.concurrent.CompletionException||failure instanceof java.util.concurrent.ExecutionException||failure instanceof java.lang.reflect.InvocationTargetException)&&failure.getCause()!=null)failure=failure.getCause();
  String text=LogSanitizer.redactSecrets(String.valueOf(failure.getMessage())),lower=text.toLowerCase(Locale.ROOT);
  String reason=lower.contains("outcome_unknown")?"outcome_unknown":lower.contains("already connected")?"already_connected":lower.contains("binding")?"binding_changed":lower.contains("unknown or expired")?"operation_unavailable":failure instanceof IllegalArgumentException?"invalid_arguments":"operation_failed";
  var out=new LinkedHashMap<String,Object>();out.put("state","blocked");out.put("tool",tool);out.put("stage",stage);out.put("reason",reason);out.put("retry_safe",readOnly);out.put("execution_state",readOnly?"read_only":"not_proven");out.put("message",LogSanitizer.truncate(text,1024));out.put("message_truncated",text.length()>1024);return out;
 }
 public static ToolResult failure(String tool,String stage,Throwable e,boolean readOnly){var out=describe(tool,stage,e,readOnly);var json=AgentResponses.JSON.toJsonTree(out).getAsJsonObject();return ToolResult.failure(AgentResponses.JSON.toJson(out),json);}
}
