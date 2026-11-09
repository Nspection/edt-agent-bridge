// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
import com.codepilot1c.core.tools.ITool;
import com.google.gson.*;
/** Stable catalog receipts; an unchanged receipt never means runtime readiness. */
public final class CapabilityCatalog {
 private CapabilityCatalog(){}
 private static final Gson JSON=new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
 public static Map<String,List<String>> workflows(){return WORKFLOWS;}
 private static final Map<String,List<String>> WORKFLOWS=Map.ofEntries(Map.entry("agent_efficiency",List.of("edt_ready","edt_capabilities","edt_operation_status","edt_task_batch","edt_task_session","edt_result","edt_agent_metrics")),Map.entry("task_session",List.of("edt_task_session","edt_task_step","edt_task_batch","edt_task_wait","edt_task_cleanup","edt_task_report")),Map.entry("data_assertions",List.of("edt_data_assert","edt_runtime_host","edt_task_step")),Map.entry("test_data_ledger",List.of("edt_test_data")),Map.entry("quick_check",List.of("edt_session_summary","edt_check")),Map.entry("prepare_runner",List.of("edt_runner_profile","edt_runner_prepare","edt_runner_extension","edt_runtime_catalog")),Map.entry("run_scenario",List.of("edt_runner_profile","edt_development_run_start","edt_development_run_status","edt_development_run_continue")),Map.entry("task_report",List.of("edt_task_report","edt_check","edt_test_data")),Map.entry("runtime_form",List.of("edt_form_state","edt_form_action","edt_operation_status")),Map.entry("runtime_ui",List.of("edt_process_snapshot","edt_runtime_catalog","edt_runtime_observe","edt_runtime_action","edt_operation_status")),Map.entry("debug",List.of("edt_debug_inspect","edt_exception_breakpoint","edt_run_to_line")),Map.entry("create_metadata",List.of("edt_creation_defaults","edt_validate_request","create_metadata","get_diagnostics","edt_editor_open")),Map.entry("delete_metadata",List.of("edt_delete_preview","edt_validate_request","delete_metadata","get_diagnostics")),Map.entry("create_form",List.of("inspect_form_layout","edt_validate_request","apply_form_recipe","get_diagnostics","edt_editor_snapshot")),Map.entry("review_changes",List.of("edt_change_set","get_diagnostics")),Map.entry("inspect_form",List.of("edt_editor_open","edt_editor_select_tab","edt_editor_form_preview","edt_editor_gallery","edt_editor_scroll")),Map.entry("merge_checkpoint",List.of("edt_merge_checkpoint","edt_compare","edt_merge_plan")),Map.entry("native_runtime",List.of("edt_runtime_host","edt_operation_status","edt_process_snapshot")),Map.entry("runtime_code",List.of("edt_runner_profile","edt_runtime_execute","edt_debug_inspect")),Map.entry("external_objects",List.of("edt_validate_request","external_manage","edt_external_build","edt_operation_status","edt_runtime_execute")),Map.entry("native_content",List.of("edt_rename_metadata","edt_predefined","edt_spreadsheet")));
 public static Map<String,Object> describe(Collection<ITool> registered,Map<String,Object>a){
  var all=registered.stream().filter(t->!t.getName().startsWith("mcp_")).sorted(Comparator.comparing(ITool::getName)).toList();
  List<Object> receipt=new ArrayList<>();for(var t:all)receipt.add(List.of(t.getName(),t.getCategory(),t.isMutating(),t.getDescription(),JSON.fromJson(t.getParameterSchema(),Object.class)));
  String hash=TaskSessionService.fingerprint(receipt);Map<String,Object> out=new LinkedHashMap<>();out.put("catalog_sha256",hash);
  if(hash.equals(a.get("if_catalog_sha256"))){out.put("not_modified",true);return out;}out.put("not_modified",false);
  String category=(String)a.get("category"),filter=(String)a.getOrDefault("name_contains","");
  Set<String> names=new LinkedHashSet<>();if(a.containsKey("names")){if(!(a.get("names") instanceof List<?> n)||n.isEmpty()||n.size()>32||n.stream().anyMatch(x->!(x instanceof String)||((String)x).isBlank()))throw new IllegalArgumentException("names requires 1..32 names");for(Object name:n)names.add((String)name);}
  var matches=all.stream().filter(t->(category==null||category.equals(t.getCategory()))&&t.getName().contains(filter)&&(names.isEmpty()||names.contains(t.getName()))).toList();
  int offset=AgentResponses.integer(a,"offset",0,0,Integer.MAX_VALUE),limit=AgentResponses.integer(a,"limit",30,1,100);if(offset<0||limit<1||limit>100)throw new IllegalArgumentException("Invalid pagination");
  List<Map<String,Object>> rows=new ArrayList<>();for(var t:matches.stream().skip(offset).limit(limit).toList()){
   var row=new LinkedHashMap<String,Object>();row.put("name",t.getName());row.put("category",t.getCategory());row.put("mutating",t.isMutating());
   if(!Boolean.FALSE.equals(a.get("include_descriptions")))row.put("description",t.getDescription());if(Boolean.TRUE.equals(a.get("include_schemas")))row.put("schema",JSON.fromJson(t.getParameterSchema(),Object.class));rows.add(row);
  }
  out.put("tools",rows);out.put("total",matches.size());out.put("offset",offset);out.put("has_more",(long)offset+rows.size()<matches.size());
  if(!names.isEmpty()){Set<String> found=new HashSet<>();matches.forEach(t->found.add(t.getName()));out.put("missing_names",names.stream().filter(n->!found.contains(n)).toList());}
  if(Boolean.TRUE.equals(a.get("include_workflows")))out.put("workflows",WORKFLOWS);
  return out;
 }
}
