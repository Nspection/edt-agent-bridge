// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.mcp.host;
import java.util.*;
import com.codepilot1c.core.edt.development.PythonEnvironmentService;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;

public final class McpRuntimeRouting {
    private McpRuntimeRouting(){}
    private static final Set<String> DIRECT=Set.of("open","close","input","command","wait","table_read","table_select","table_add","table_set","batch","check","uncheck","tab","activate","choose_value","expand","collapse","increase","decrease","set_position","set_date","open_choices");
    public static Map<String,Object> definition(){return Map.of("name","route","description","Read-only runtime selection: Native for code/query/data; direct TestClient for supported UI with managed Python; Native may seed/assert data in the same verified base. Vanessa for unsupported UI or explicit scenarios only. Prefer edt.run op=ui for verified batches and retain/reuse its owned client within a task. No launch, DB update, trust change or mutation retry.","inputSchema",Map.of("type","object","additionalProperties",false,"required",List.of("project","intent"),"properties",Map.of("project",Map.of("type","string"),"intent",Map.of("enum",List.of("code","query","data","ui","scenario")),"engine",Map.of("enum",List.of("auto","native","testclient","vanessa")),"required_actions",Map.of("type","array","maxItems",16,"items",Map.of("type","string")),"previous_state",Map.of("enum",List.of("completed","rejected","unsupported","outcome_unknown","running")))) ,"annotations",Map.of("readOnlyHint",true));}
    public static Map<String,Object> policy(){return Map.of("code_query_data","native","user_interface","testclient when supported Python/actions are available","unsupported_ui_or_explicit_scenario","vanessa","native_helper","seed/assert data alongside UI, same verified base","ui_batch","edt.run op=ui; retain owner with auto_cleanup=false, reuse_from_ui_run_id; cleanup owner at task end","unknown_outcome","inspect original operation; no executor switch/replay","automatic_launch",false);}
    public static Map<String,Object> plan(Map<String,Object> args)throws Exception{
        if(!Set.of("project","intent","engine","required_actions","previous_state").containsAll(args.keySet()))throw new IllegalArgumentException("Unsupported route field");
        String project=McpCompactSurface.text(args,"project",null);if(project==null||project.isBlank())throw new IllegalArgumentException("Explicit project required");
        // Validate intent/previous outcome before any readiness probe.
        validate(args);
        if(Set.of("outcome_unknown","running").contains(McpCompactSurface.text(args,"previous_state","")))return choose(args,false);
        new com.codepilot1c.core.edt.infobase.NativeInfobaseGateway().project(project);
        com._1c.g5.v8.dt.platform.services.model.InfobaseReference base=null;
        try{base=new EdtRuntimeService().resolveDefaultInfobase(project);}catch(EdtRuntimeService.UnboundInfobaseException absent){/* Known missing binding, not a service failure. */}
        if(base==null)return Map.of("state","needs_binding","project",project,"next_call",Map.of("tool","edt.edit","arguments",Map.of("op","edt_infobase_bind","args",Map.of("action","list","project",project))),"side_effects",false);
        Map<String,Object> python=Map.of("source_available",false,"state","not_needed");String intent=(String)args.get("intent");
        if(usesPythonProbe(args)){
            python=PythonEnvironmentService.workspace().availability();

        }
        var result=new LinkedHashMap<>(chooseWithPython(args,python));result.put("project",project);result.put("base_uuid",base.getUuid().toString());result.put("python",python);return result;
    }
    public static Map<String,Object> chooseWithPython(Map<String,Object> args,Map<String,Object> python){
        if(usesPythonProbe(args)&&!Set.of("candidate","ready","needs_setup","python_unavailable").contains(python.get("state")))return Map.of("state","blocked","reason","inspect_python_environment","python",python,"side_effects",false,"executor_switch_allowed",false);
        return choose(args,Boolean.TRUE.equals(python.get("source_available")));
    }
    public static boolean usesPythonProbe(Map<String,Object>a){validate(a);String engine=McpCompactSurface.text(a,"engine","auto");List<?> actions=a.get("required_actions") instanceof List<?> l?l:List.of();return "ui".equals(a.get("intent"))&&!engine.equals("vanessa")&&!engine.equals("native")&&actions.stream().allMatch(DIRECT::contains);}
    private static void validate(Map<String,Object>a){
        String intent=McpCompactSurface.text(a,"intent",null),engine=McpCompactSurface.text(a,"engine","auto"),previous=McpCompactSurface.text(a,"previous_state","");
        if(intent==null||!Set.of("code","query","data","ui","scenario").contains(intent))throw new IllegalArgumentException("intent must be code/query/data/ui/scenario");
        if(!Set.of("auto","native","testclient","vanessa").contains(engine))throw new IllegalArgumentException("Unknown engine");
        if(!Set.of("","completed","rejected","unsupported","outcome_unknown","running").contains(previous))throw new IllegalArgumentException("Unknown previous_state");
        if(a.containsKey("required_actions")&&(!(a.get("required_actions") instanceof List<?> l)||l.size()>16||l.stream().anyMatch(x->!(x instanceof String s)||s.isBlank()||s.length()>128)))throw new IllegalArgumentException("required_actions requires up to16 names");
    }
    public static Map<String,Object> choose(Map<String,Object>a,boolean python){
        validate(a);String previous=McpCompactSurface.text(a,"previous_state","");
        if(Set.of("outcome_unknown","running").contains(previous))return Map.of("state","blocked","reason","inspect_original_operation","side_effects",false,"executor_switch_allowed",false);
        String intent=(String)a.get("intent"),engine=McpCompactSurface.text(a,"engine","auto");List<?> actions=a.get("required_actions") instanceof List<?> l?l:List.of();boolean supported=actions.stream().allMatch(DIRECT::contains);
        if(engine.equals("auto"))engine=Set.of("code","query","data").contains(intent)?"native":intent.equals("scenario")||!python||!supported?"vanessa":"testclient";
        if((engine.equals("native")&&Set.of("ui","scenario").contains(intent))||(engine.equals("testclient")&&(!intent.equals("ui")||!python||!supported)))return Map.of("state","unsupported","engine",engine,"side_effects",false,"reason","requested_engine_cannot_prove_requested_test");
        Map<String,Object> next=switch(engine){
            case "native"->Map.of("tool","edt.help","arguments",Map.of("args",Map.of("action","contract","op","edt_runtime_host")));
            case "testclient"->Map.of("tool","edt.run","arguments",Map.of("op","edt_test_client","args",Map.of("action","environment_status")));
            default->Map.of("tool","edt.run","arguments",Map.of("op","edt_runner_profile","args",Map.of("action","get","project",McpCompactSurface.text(a,"project",""))));
        };
        return Map.of("state","planned","engine",engine,"reason",engine.equals("native")?"code_data_assertions":engine.equals("testclient")?"supported_ui_with_python":"explicit_scenario_or_direct_ui_unavailable","native_helper_allowed",!engine.equals("native"),"next_call",next,"side_effects",false,"automatic_launch",false);
    }
}
