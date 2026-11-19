// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
import java.util.regex.Pattern;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.logging.LogSanitizer;
/** Conservative error receipts. Classification never authorizes replay of a write. */
public final class AgentErrors {
 private static final Pattern MISSING_NATIVE_XML = Pattern.compile(
  "(?:файл объекта не существует|object file does not exist)\\s*[-:]\\s*(/[\\p{L}\\p{N}_/]+\\.xml)",
  Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
 private static final Map<String,String> EXPORTED_KINDS = Map.of("Enums","Enum","DefinedTypes","DefinedType",
  "Constants","Constant","Subsystems","Subsystem","CommonModules","CommonModule","Catalogs","Catalog",
  "Documents","Document");
 private AgentErrors(){}
 public static Map<String,Object> describe(String tool,String stage,Throwable failure,boolean readOnly){
  while((failure instanceof java.util.concurrent.CompletionException||failure instanceof java.util.concurrent.ExecutionException||failure instanceof java.lang.reflect.InvocationTargetException)&&failure.getCause()!=null)failure=failure.getCause();
  String text=LogSanitizer.redactSecrets(String.valueOf(failure.getMessage())),lower=text.toLowerCase(Locale.ROOT);
  var missingPaths = new LinkedHashSet<String>();
  var match = MISSING_NATIVE_XML.matcher(text);
  while (missingPaths.size() < 12 && match.find()) missingPaths.add(match.group(1));
  String reason=!missingPaths.isEmpty()?"native_export_package_incomplete":lower.contains("project_update_preflight_blocked:")?"project_update_preflight_blocked":lower.contains("outcome_unknown")?"outcome_unknown":lower.contains("already connected")?"already_connected":lower.contains("binding")?"binding_changed":lower.contains("unknown or expired")?"operation_unavailable":failure instanceof IllegalArgumentException?"invalid_arguments":"operation_failed";
  var out=new LinkedHashMap<String,Object>();out.put("state","blocked");out.put("tool",tool);out.put("stage",reason.equals("project_update_preflight_blocked")?"source_preflight":stage);out.put("reason",reason);out.put("retry_safe",readOnly);out.put("execution_state",readOnly?"read_only":"not_proven");out.put("message",LogSanitizer.truncate(text,1024));out.put("message_truncated",text.length()>1024);
  if (!missingPaths.isEmpty()) {
   out.put("missing_export_paths", List.copyOf(missingPaths));
   var fqns = missingPaths.stream().map(AgentErrors::exportFqn).filter(Objects::nonNull).toList();
   if (!fqns.isEmpty()) out.put("missing_export_fqns", fqns);
  }
  return out;
 }
 private static String exportFqn(String path) {
  String[] parts = path.substring(1, path.length() - 4).split("/");
  if (parts.length % 2 != 0) return null;
  var fqn = new StringJoiner(".");
  for (int i = 0; i < parts.length; i += 2) {
   String type = EXPORTED_KINDS.get(parts[i]);
   if (type == null || (i > 0 && !"Subsystem".equals(type))) return null;
   fqn.add(type).add(parts[i + 1]);
  }
  return fqn.toString();
 }

 public static ToolResult failure(String tool,String stage,Throwable e,boolean readOnly){var out=describe(tool,stage,e,readOnly);var json=AgentResponses.JSON.toJsonTree(out).getAsJsonObject();return ToolResult.failure(AgentResponses.JSON.toJson(out),json);}
}
