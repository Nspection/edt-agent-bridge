package com.codepilot1c.core.tools.development;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
@ToolMeta(name="edt_capabilities",category="workspace",tags={"read-only","workspace","edt"})
public final class EdtCapabilitiesTool extends AbstractTool {
    @Override public String getDescription(){return "Discover the actual registered EDT/QA/metadata capabilities with schemas and short workflows. Use category/name filters before guessing parameters. Tool registration does not guarantee runtime readiness or native initializer coverage.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"category":{"type":"string"},"name_contains":{"type":"string"},"include_schemas":{"type":"boolean","default":false},"offset":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":100,"default":30}}}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){
        Map<String,Object> a=p.getRaw();String category=(String)a.get("category"),filter=(String)a.getOrDefault("name_contains","");
        int offset=((Number)a.getOrDefault("offset",0)).intValue(),limit=Math.min(100,((Number)a.getOrDefault("limit",30)).intValue());
        var tools=ToolRegistry.getInstance().getAllTools().stream().filter(t->!t.getName().startsWith("mcp_") && (category==null || category.equals(t.getCategory())) && t.getName().contains(filter)).sorted(Comparator.comparing(ITool::getName)).toList();
        List<Map<String,Object>> rows=new ArrayList<>();
        for(ITool t:tools.stream().skip(offset).limit(limit).toList()) {
            Map<String,Object> row=new LinkedHashMap<>();row.put("name",t.getName());row.put("category",t.getCategory());row.put("mutating",t.isMutating());row.put("description",t.getDescription());
            if(Boolean.TRUE.equals(a.get("include_schemas"))){row.put("schema",new Gson().fromJson(t.getParameterSchema(),Object.class));}rows.add(row);
        }
        return CompletableFuture.completedFuture(ToolResult.success(new Gson().toJson(Map.of("tools",rows,"total",tools.size(),"offset",offset,"workflows",Map.of("prepare_runner",List.of("edt_runner_profile","edt_runner_prepare","edt_runner_extension","edt_runtime_catalog"),"run_scenario",List.of("edt_runner_profile","edt_development_run_start","edt_development_run_status","edt_development_run_continue"),"runtime_ui",List.of("edt_process_snapshot","edt_runtime_catalog","edt_runtime_observe","edt_runtime_action","edt_operation_status"),"debug",List.of("edt_debug_inspect","edt_exception_breakpoint","edt_run_to_line"),"create_metadata",List.of("edt_creation_defaults","edt_validate_request","create_metadata","get_diagnostics","edt_editor_open"),"delete_metadata",List.of("edt_delete_preview","edt_validate_request","delete_metadata","get_diagnostics"),"create_form",List.of("inspect_form_layout","edt_validate_request","apply_form_recipe","get_diagnostics","edt_editor_snapshot"),"review_changes",List.of("edt_change_set","get_diagnostics"),"inspect_form",List.of("edt_editor_open","edt_editor_select_tab","edt_editor_form_preview","edt_editor_gallery","edt_editor_scroll"),"native_content",List.of("edt_rename_metadata","edt_predefined","edt_spreadsheet"))))));
    }
}
