// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.mcp.host;

import java.util.*;

/** Small discovery surface; canonical calls still pass through the original MCP dispatcher. */
public final class McpCompactSurface {
    private McpCompactSurface() {}
    public static final List<String> GROUPS=List.of("status","search","inspect","edit","diff","check","run","debug","task","help");
    private static final Map<String,String> DESCRIPTIONS=Map.of(
        "status","Read readiness and status. Empty call reads edt_bridge_status. Use help for one operation contract.",
        "search","Search code, symbols and metadata. Request operations/contracts from edt.help; pass canonical op and its args.",
        "inspect","Read EDT models, forms and source structure; schemas are loaded through edt.help only when needed.",
        "edit","Change sources/metadata through existing EDT operations. Original validation tokens, previews and authorization still apply.",
        "diff","Compare and merge using existing native plans. Applying a merge still requires its exact saved plan/hash.",
        "check","Run existing checks and diagnostics. Read the chosen operation contract before starting checks that update/run a database.",
        "run","Verified UI batches: op=ui returns compact outcome and next_call/details_call. Follow next_call wait on the same run; no separate job-poll/status or repeated contract lookup. op=prepare supports the same wait/details pattern. For several batches in one task retain first client with auto_cleanup=false; later starts use reuse_from_ui_run_id of that original owner, then cleanup owner at task end. UUID/EQUAL/live identity rechecked; no cold replacement or replay. op=prepare prepares explicit project/base_uuid; op=route plans only. Prefer Native for BSL/query/data, direct TestClient with managed Python for supported UI; Native may seed/assert the same verified base. Vanessa for unsupported UI or explicit scenarios. Unknown outcomes block switching.",
        "debug","Inspect and control the EDT debugger with the original commands and target identity checks.",
        "task","Wait for jobs, read durable results, manage task sessions and cleanup only their owned resources. DONE alone is not business success.",
        "help","Discover compact groups, paged operations or ONE exact operation contract. args action=catalog|operations|contract|guide (topic=testclient|editor); group, op, filter, offset, limit. Full legacy tools/list is available with profile=legacy; canonical tool calls remain compatible.");
    public static String group(Map<String,Object> definition){
        String n=(String)definition.get("name");if(n.equals("route"))return "run";
        boolean readOnly=Boolean.TRUE.equals(definition.get("_compact_read_only"))||definition.get("annotations") instanceof Map<?,?> a && Boolean.TRUE.equals(a.get("readOnlyHint"));
        if(n.startsWith("edt_task_")||Set.of("edt_operation_status","edt_result").contains(n))return "task";
        if(n.contains("breakpoint")||n.contains("debug")||n.contains("variables")||n.contains("stack_trace")||n.contains("step_into")||n.contains("step_over")||n.contains("step_out")||n.contains("resume")||n.contains("run_to_line"))return "debug";
        if(n.startsWith("edt_runtime_")||n.startsWith("edt_runner_")||n.startsWith("edt_development_run_")||Set.of("edt_update_control","edt_ui_run","edt_bug_reproduction","edt_test_client","edt_data_assert","edt_test_data","qa_run","edt_launch_app").contains(n))return "run";
        if(n.contains("compare")||n.equals("edt_bsl_review")||n.equals("edt_agent_turn_review")||n.startsWith("edt_merge_"))return "diff";
        if(n.contains("check")||n.contains("diagnostic")||n.contains("validate")||n.equals("get_problem_summary"))return "check";
        if(readOnly&&(n.contains("status")||n.endsWith("_state")||n.equals("edt_ready")||n.equals("edt_session_summary")||n.equals("edt_agent_metrics")))return "status";
        if(readOnly&&(n.contains("search")||n.contains("find")||n.equals("grep")||n.equals("glob")||n.contains("references")))return "search";
        return readOnly?"inspect":"edit";
    }
    public static boolean isFacade(String name){return name!=null&&name.startsWith("edt.")&&GROUPS.contains(name.substring(4));}
    public static List<Map<String,Object>> definitions(List<Map<String,Object>> legacy){
        var result=new ArrayList<Map<String,Object>>();
        for(String g:GROUPS){
            var members=legacy.stream().filter(d->group(d).equals(g)).toList();
            if(!g.equals("help")&&members.isEmpty())continue;
            if(g.equals("help")&&legacy.isEmpty())continue;
            boolean readOnly=g.equals("help")||members.stream().allMatch(d->Boolean.TRUE.equals(d.get("_compact_read_only"))||d.get("annotations") instanceof Map<?,?> a&&Boolean.TRUE.equals(a.get("readOnlyHint")));
            var properties=new LinkedHashMap<String,Object>();properties.put("op",Map.of("type","string","description","Canonical operation name from edt.help; route is a special read-only run plan"));properties.put("args",Map.of("type","object","description",g.equals("help")?"action=catalog|operations|contract|guide (topic=testclient|editor), group, op, filter, offset, limit":"Exact arguments from that operation contract"));
            result.add(Map.of("name","edt."+g,"description",DESCRIPTIONS.get(g),"inputSchema",Map.of("type","object","additionalProperties",false,"properties",properties),"annotations",Map.of("readOnlyHint",readOnly,"destructiveHint",!readOnly,"openWorldHint",true)));
        }
        return result;
    }
    public record Invocation(String operation,Map<String,Object> arguments){}
    @SuppressWarnings("unchecked") public static Invocation decode(String facade,Map<String,Object> request,List<Map<String,Object>> legacy){
        if(!isFacade(facade)||!Set.of("op","args").containsAll(request.keySet()))throw new IllegalArgumentException("Unknown compact entry or field");
        String g=facade.substring(4);Object raw=request.get("args");if(raw!=null&&!(raw instanceof Map<?,?>))throw new IllegalArgumentException("args must be an object");
        Map<String,Object> args=raw==null?Map.of():new LinkedHashMap<>((Map<String,Object>)raw);
        Object value=request.get("op");if(value!=null&&!(value instanceof String))throw new IllegalArgumentException("op must be text");
        String op=(String)value;
        if(g.equals("help")){if(op!=null)throw new IllegalArgumentException("edt.help uses args.op for contracts");return new Invocation("@help",args);}
        if(op==null&&g.equals("status"))op="edt_bridge_status";
        if(op==null){if(!args.isEmpty())throw new IllegalArgumentException("Select op before passing args");return new Invocation("@help",Map.of("action","operations","group",g));}
        if(g.equals("run")&&op.equals("route"))return new Invocation("@route",args);
        if(g.equals("run")&&op.equals("ui"))op="edt_ui_run";
        if(g.equals("run")&&op.equals("prepare"))op="edt_runtime_prepare";
        String operation=op;var target=legacy.stream().filter(d->operation.equals(d.get("name"))).findFirst().orElseThrow(()->new IllegalArgumentException("Operation is unavailable or not exposed: "+operation));
        if(!group(target).equals(g))throw new IllegalArgumentException("Use edt."+group(target)+" for "+operation);
        return new Invocation(op,args);
    }
    public static Map<String,Object> help(Map<String,Object> args,List<Map<String,Object>> legacy){
        if(!Set.of("action","group","op","filter","offset","limit","topic").containsAll(args.keySet()))throw new IllegalArgumentException("Unknown help field");
        String action=text(args,"action","catalog"),g=text(args,"group",null),filter=text(args,"filter","");
        if(action.equals("guide")){
            if(!args.keySet().equals(Set.of("action","topic")))
                throw new IllegalArgumentException("Use action=guide with a topic");
            return switch(text(args,"topic","")){
                case "testclient" -> testClientGuide(legacy);
                case "editor" -> editorGuide(legacy);
                default -> throw new IllegalArgumentException("Choose topic=testclient or editor");
            };
        }
        if(args.containsKey("topic"))throw new IllegalArgumentException("topic applies only to action=guide");
        if(g!=null&&(!GROUPS.contains(g)||g.equals("help")))throw new IllegalArgumentException("Unknown operation group");
        if(action.equals("catalog"))return Map.of("groups",definitions(legacy).stream().map(d->Map.of("name",d.get("name"),"description",d.get("description"))).toList(),"legacy_operation_count",legacy.size(),"routing",McpRuntimeRouting.policy());
        var discoverable=new ArrayList<>(legacy);if(legacy.stream().anyMatch(d->group(d).equals("run")))discoverable.add(McpRuntimeRouting.definition());
        if(action.equals("contract")){
            String op=text(args,"op",null);if("prepare".equals(op))op="edt_runtime_prepare";if("ui".equals(op))op="edt_ui_run";String selected=op;if(op==null)throw new IllegalArgumentException("op required for a contract");
            var d=discoverable.stream().filter(x->selected.equals(x.get("name"))).findFirst().orElseThrow(()->new IllegalArgumentException("Operation unavailable or not exposed"));
            if(g!=null&&!group(d).equals(g))throw new IllegalArgumentException("Operation is in another group");
            var contract=new LinkedHashMap<>(d);contract.remove("_compact_read_only");
            String entry = "edt." + group(d);
            if ("edt_agent_turn_review".equals(op)) {
                return Map.of("entry", entry, "op", op, "contract", contract, "examples", List.of(
                        Map.of("action", "begin"),
                        Map.of("action", "track", "turn_id", "<begin.turn_id>", "project", "MyProject",
                                "files", List.of("src/Catalogs/Items/Forms/Item/Form.form")),
                        Map.of("action", "track", "turn_id", "<begin.turn_id>", "project", "MyProject",
                                "additions", List.of("src/CommonModules/NewModule/Module.bsl")),
                        Map.of("action", "finish", "turn_id", "<begin.turn_id>")));
            }
            return Map.of("entry",entry,"op",op,"contract",contract);
        }
        if(!action.equals("operations"))throw new IllegalArgumentException("Unknown help action");
        int offset=integer(args,"offset",0,0,Integer.MAX_VALUE),limit=integer(args,"limit",20,1,50);
        var matches=discoverable.stream().filter(d->(g==null||g.equals(group(d)))&&((String)d.get("name")).contains(filter)).sorted(Comparator.comparing(d->(String)d.get("name"))).toList();
        var rows=matches.stream().skip(offset).limit(limit).map(d->{String description=String.valueOf(d.get("description"));return Map.of("entry","edt."+group(d),"op",d.get("name"),"summary",description.substring(0,Math.min(160,description.length())));}).toList();
        return Map.of("operations",rows,"total",matches.size(),"offset",offset,"has_more",(long)offset+rows.size()<matches.size());
    }
    private static Map<String,Object> editorGuide(List<Map<String,Object>> legacy) {
        String op="edt_editor_open";
        var tool=legacy.stream().filter(row->op.equals(row.get("name"))).findFirst()
                .orElseThrow(()->new IllegalArgumentException("EDT editor navigation is unavailable or not exposed"));
        String entry="edt."+group(tool);
        var example=Map.of("project","My EDT project","file_path","src/Documents/Example/ObjectModule.bsl","line",216);
        return Map.of("topic","editor","method","Call this operation directly when the project, BSL path and line are known.",
            "chat_links","Ordinary file links are handled by the chat client. If a click opens the chat editor or the client does not support an EDT action, call the MCP operation.",
            "entry",entry,"op",op,"example_args",example,
            "example_call",Map.of("tool",entry,"arguments",Map.of("op",op,"args",example)),
            "line","Optional 1-based line. Confirm the exact project and project-relative module path.",
            "code_changes","When reporting BSL changes, cite the relevant file and line. When asked to show the code in EDT, call this operation for that location. selected_line confirms navigation; navigation_state reports whether EDT is in front.",
            "codex_open_in","For manual navigation, Codex can add 1C:EDT to its Open in menu. See agent-bridge/editor-lifecycle.md and tools/codex-open-in-edt.py in the Bridge release source.");
    }

    private static Map<String,Object> testClientGuide(List<Map<String,Object>> legacy) {
        var exposed=legacy.stream().map(tool->tool.get("name")).collect(java.util.stream.Collectors.toSet());
        List<Map<String,Object>> choices=new ArrayList<>();
        addGuide(choices,exposed,"edt_launch_app","manual",
            "Open 1C so the developer can reproduce the issue themselves without recording.",
            "Confirm the exact registered EDT application and its test base. Use the launch contract.");
        addGuide(choices,exposed,"edt_test_client","agent_controls_forms",
            "Use for TestClient launch, observation and supported agent-driven form actions.",
            "Use the existing permitted project-bound EQUAL base. Check environment_status, follow managed preparation if needed, then start and poll the original job. Confirm ui_ready=true before form calls with transport=testclient.");
        addGuide(choices,exposed,"edt_bug_reproduction","record_actions",
            "Use when explicitly asked to record actions and prepare a bug draft. Video is optional.",
            "Use an existing registered test base bound in EDT and confirmed by the developer. An owned copy is needed for DT export. Confirm the exact project/base before start, then use begin and finish with the same session_id or the floating panel.");
        return Map.of("topic","testclient","routes",choices,
            "existing_base","Having a test base does not require a recording. Only request a recording when asked for a journal or draft.",
            "manual_launch","For manual work, use the normal launch from EDT Applications if the profile does not expose a manual launch command.",
            "startup_recovery","Check the original client status. If awaiting_manual_startup, have the developer inspect that existing 1C window and resume the same client. An unknown outcome must be investigated before any retry.");
    }

    private static void addGuide(List<Map<String,Object>> choices,Set<Object> exposed,String op,String intent,
            String useWhen,String firstStep) {
        if(exposed.contains(op))choices.add(Map.of("intent",intent,"op",op,"use_when",useWhen,
            "first_steps",firstStep,"contract_call",Map.of("tool","edt.help","arguments",Map.of("args",Map.of("action","contract","op",op)))));
    }

    static String text(Map<String,Object>a,String k,String fallback){Object v=a.get(k);if(v==null)return fallback;if(!(v instanceof String s)||s.length()>256)throw new IllegalArgumentException(k+" must be bounded text");return s;}
    static int integer(Map<String,Object>a,String k,int fallback,int min,int max){if(!a.containsKey(k))return fallback;Object v=a.get(k);if(!(v instanceof Number))throw new IllegalArgumentException(k+" must be an integer");try{int n=new java.math.BigDecimal(v.toString()).intValueExact();if(n<min||n>max)throw new ArithmeticException();return n;}catch(ArithmeticException e){throw new IllegalArgumentException(k+" outside bounds");}}
}
