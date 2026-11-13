// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
import com.google.gson.*;
/** One action/field contract for MCP schema and both direct/orchestrated validation. */
public final class RuntimePreparationContract {
 private static final Map<String,Set<String>> FIELDS=Map.of(
  "prepare",Set.of("project","base_uuid","intent","engine","required_actions","previous_state","idempotency_key","native_path","expected_native_sha256","profile_id","client_kind","readiness_timeout_s"),
  "resume",Set.of("preparation_id"),"status",Set.of("preparation_id"),"details",Set.of("preparation_id"),"cleanup_preview",Set.of("preparation_id"),
  "wait",Set.of("preparation_id","timeout_ms"),"cleanup",Set.of("preparation_id","expected_plan_sha256"));
 private RuntimePreparationContract(){}
 public static Set<String> fields(String action){var fields=FIELDS.get(action);if(fields==null)throw new IllegalArgumentException("Unknown preparation action");return fields;}
 public static void validateFields(String action,Map<String,Object> payload){var fields=fields(action);for(String name:new TreeSet<>(payload.keySet()))if(!fields.contains(name))throw new RejectedField(action,name,fields);}
 public static final class RejectedField extends IllegalArgumentException {
  private final String action,field;private final Set<String> allowed;
  private RejectedField(String action,String field,Set<String> allowed){super("rejected_field="+safe(field)+": field does not apply to preparation action "+action+(field.equals("timeout_ms")?"; timeout_ms is wait-only":""));this.action=action;this.field=safe(field);this.allowed=allowed;}
  private static String safe(String name){return name!=null&&name.matches("[A-Za-z][A-Za-z0-9_]{0,63}")?name:"<unsupported_field>";}
  public JsonObject diagnostic(){var out=new JsonObject();out.addProperty("error_code","PREPARATION_FIELD_NOT_APPLICABLE");out.addProperty("action",action);out.addProperty("rejected_field",field);out.add("allowed_fields",new Gson().toJsonTree(new TreeSet<>(allowed)));return out;}
 }
 public static String schema(String definitions){
  var root=JsonParser.parseString(definitions).getAsJsonObject();var properties=root.getAsJsonObject("properties");
  properties.getAsJsonObject("timeout_ms").addProperty("description","Only action=wait: wait budget. Never pass to prepare; preparation startup uses readiness_timeout_s.");
  properties.getAsJsonObject("readiness_timeout_s").addProperty("description","Only action=prepare: client startup readiness budget in seconds.");
  var choices=new JsonArray();
  for(String action:new TreeSet<>(FIELDS.keySet())){
   var branch=new JsonObject();branch.addProperty("type","object");branch.addProperty("additionalProperties",false);var branchProperties=new JsonObject();var actionProperty=new JsonObject();var values=new JsonArray();values.add(action);actionProperty.add("enum",values);branchProperties.add("action",actionProperty);
   for(String field:new TreeSet<>(fields(action)))branchProperties.add(field,properties.get(field).deepCopy());branch.add("properties",branchProperties);
   var required=new JsonArray();required.add("action");if(action.equals("prepare")){for(String field:List.of("project","base_uuid","intent","idempotency_key"))required.add(field);}else required.add("preparation_id");if(action.equals("cleanup"))required.add("expected_plan_sha256");branch.add("required",required);choices.add(branch);
  }
  root.add("oneOf",choices);return root.toString();
 }
}
