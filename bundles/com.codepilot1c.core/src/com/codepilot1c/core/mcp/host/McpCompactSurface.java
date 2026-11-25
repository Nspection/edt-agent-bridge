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
        "edit","Change sources/metadata. For BSL and edit-turn examples, use edt.help guide bsl. Original previews and authorization apply.",
        "diff","Compare and merge using existing native plans. Applying a merge still requires its exact saved plan/hash.",
        "check","Run existing checks and diagnostics. Read the chosen operation contract before starting checks that update/run a database.",
        "run","Plan or run 1C tasks. Read edt.help guide testclient before a client workflow. Follow next_call; resolve unknown outcomes before retrying or switching.",
        "debug","Inspect and control the EDT debugger with the original commands and target identity checks.",
        "task","Wait for jobs, read durable results, manage task sessions and cleanup only their owned resources. DONE alone is not business success.",
        "help","Discover compact groups, paged operations or ONE exact operation contract. args action=catalog|operations|contract|guide (topic=bsl|testclient|editor|agent_history|authoring); group, op, filter, offset, limit. Full legacy tools/list is available with profile=legacy; canonical tool calls remain compatible.");
    public static String group(Map<String,Object> definition){
        String n=(String)definition.get("name");if(n.equals("route"))return "run";
        boolean readOnly=Boolean.TRUE.equals(definition.get("_compact_read_only"))||definition.get("annotations") instanceof Map<?,?> a && Boolean.TRUE.equals(a.get("readOnlyHint"));
        if(n.startsWith("edt_task_")||Set.of("edt_operation_status","edt_result").contains(n))return "task";
        if(n.contains("breakpoint")||n.contains("debug")||n.contains("variables")||n.contains("stack_trace")||n.contains("step_into")||n.contains("step_over")||n.contains("step_out")||n.contains("resume")||n.contains("run_to_line"))return "debug";
        if(n.startsWith("edt_runtime_")||n.startsWith("edt_runner_")||n.startsWith("edt_development_run_")||Set.of("edt_update_control","edt_ui_run","edt_bug_reproduction","edt_test_client","edt_data_assert","edt_test_data","qa_run","edt_launch_app").contains(n))return "run";
        if(n.contains("compare")||n.equals("edt_bsl_review")||n.equals("edt_agent_turn_review")||n.equals("edt_agent_history")||n.startsWith("edt_merge_"))return "diff";
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
            var properties=new LinkedHashMap<String,Object>();properties.put("op",Map.of("type","string","description",g.equals("run")?"Operation name or route, prepare, ui.":"Operation name from edt.help."));properties.put("args",Map.of("type","object","description",g.equals("help")?"action=catalog|operations|contract|guide (topic=bsl|testclient|editor|agent_history|authoring), group, op, filter, offset, limit":"Exact arguments from that operation contract"));
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
                case "bsl" -> bslGuide(legacy);
                case "testclient" -> testClientGuide(legacy);
                case "editor" -> editorGuide(legacy);
                case "agent_history" -> agentHistoryGuide(legacy);
                case "authoring" -> authoringGuide(legacy);
                default -> throw new IllegalArgumentException("Choose topic=bsl, testclient, editor, agent_history or authoring");
            };
        }
        if(args.containsKey("topic"))throw new IllegalArgumentException("topic applies only to action=guide");
        if(g!=null&&(!GROUPS.contains(g)||g.equals("help")))throw new IllegalArgumentException("Unknown operation group");
        if(action.equals("catalog")){
            var catalog=new LinkedHashMap<String,Object>();
            catalog.put("groups",definitions(legacy).stream().map(d->Map.of("name",d.get("name"),"description",d.get("description"))).toList());
            catalog.put("legacy_operation_count",legacy.size());
            catalog.put("routing",McpRuntimeRouting.policy());
            if (legacy.stream().anyMatch(row -> BSL_OPERATIONS.contains(row.get("name")))) {
                catalog.put("bsl", Map.of("hint", "Поиск, чтение и правка BSL",
                        "help_args", Map.of("action", "guide", "topic", "bsl")));
            }
            if (legacy.stream().anyMatch(row -> agentHistoryOperation((String) row.get("name")))) {
                catalog.put("agent_history", Map.of("hint", "Сообщения ИИ / Изменения ИИ · EDT turn history",
                        "help_args", Map.of("action", "guide", "topic", "agent_history")));
            }
            return catalog;
        }
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
        var matches=discoverable.stream().filter(d->(g==null||g.equals(group(d)))&&matchesFilter((String)d.get("name"),filter)).sorted(Comparator.comparing(d->(String)d.get("name"))).toList();
        var rows=matches.stream().skip(offset).limit(limit).map(d->{String description=String.valueOf(d.get("description"));return Map.of("entry","edt."+group(d),"op",d.get("name"),"summary",description.substring(0,Math.min(160,description.length())));}).toList();
        return Map.of("operations",rows,"total",matches.size(),"offset",offset,"has_more",(long)offset+rows.size()<matches.size());
    }
    private static final Set<String> BSL_OPERATIONS = Set.of(
            "edt_search_in_code", "bsl_get_method_body", "edit_file");
    private static final Map<String, Set<String>> BSL_FILTER_ALIASES = Map.of(
            "код", BSL_OPERATIONS, "код bsl", BSL_OPERATIONS, "бсл", BSL_OPERATIONS,
            "поиск", Set.of("edt_search_in_code", "edt_find_references"),
            "метод", Set.of("bsl_get_method_body", "bsl_list_methods", "bsl_analyze_method",
                    "edt_get_method_call_hierarchy"),
            "правка", Set.of("edit_file"), "диагностика", Set.of("edt_check"));

    private static final String HISTORY = "edt_agent_history";
    private static final String TURN_REVIEW = "edt_agent_turn_review";
    private static final String FORM = "src/Catalogs/Items/Forms/Item/Form.form";
    private static final String MODULE = "src/Catalogs/Items/Forms/Item/Module.bsl";

    private static boolean agentHistoryOperation(String name) {
        return HISTORY.equals(name) || TURN_REVIEW.equals(name);
    }

    private static boolean matchesFilter(String name, String filter) {
        String term = filter.strip().toLowerCase(Locale.ROOT);
        if (name.contains(term) || BSL_FILTER_ALIASES.getOrDefault(term, Set.of()).contains(name)) return true;
        if (!agentHistoryOperation(name)) return false;
        String aliases = HISTORY.equals(name)
                ? "messages panel changes review история панель сообщения ии изменения ии"
                : "changes panel review панель изменения ии";
        return aliases.contains(term);
    }

    private static Map<String,Object> agentHistoryGuide(List<Map<String,Object>> legacy) {
        boolean messages = legacy.stream().anyMatch(row -> HISTORY.equals(row.get("name")));
        boolean changes = legacy.stream().anyMatch(row -> TURN_REVIEW.equals(row.get("name")));
        if (!messages && !changes) throw new IllegalArgumentException("Agent history is unavailable or not exposed");
        var guide = new LinkedHashMap<String,Object>();
        guide.put("topic", "agent_history");
        var steps = new ArrayList<Map<String,Object>>();
        String turn = "<begin.turn_id>";
        if (changes) {
            steps.add(historyCall(TURN_REVIEW, Map.of("action", "begin")));
            steps.add(historyCall(TURN_REVIEW, Map.of("action", "track", "turn_id", turn, "project", "MyProject",
                    "files", List.of(FORM))));
            steps.add(historyCall(TURN_REVIEW, Map.of("action", "finish", "turn_id", turn)));
        }
        if (messages) {
            var form = Map.of("kind", "editor", "label", "Поле формы", "project", "MyProject",
                    "file_path", FORM, "element_id", 16);
            if (changes) {
                steps.add(historyCall(HISTORY, Map.of("action", "publish", "turn_id", turn,
                        "messages", List.of(Map.of("message_id", "form-check",
                                "text", "Проверка формы пройдена.", "target", form)))));
                steps.add(historyCall(HISTORY, Map.of("action", "show", "pane", "messages", "turn_id", turn)));
            }
            guide.put("standalone", historyCall(HISTORY, Map.of("action", "publish",
                    "turn_id", "turn-<your UUID>", "title", "Проверка формы",
                    "messages", List.of(Map.of("message_id", "form-check", "text", "Поле найдено.", "target", form)))));
            var targets = new LinkedHashMap<String,Object>();
            targets.put("bsl_method", Map.of("kind", "editor", "label", "Проверить адрес", "project", "MyProject",
                    "file_path", MODULE, "method", "ПроверитьАдрес"));
            targets.put("form_element", form);
            if (changes) targets.put("form_comparison", Map.of("kind", "comparison", "label", "Сравнение формы",
                    "project", "MyProject", "file_path", FORM));
            guide.put("targets", targets);
            guide.put("list_example", historyCall(HISTORY, Map.of("action", "list", "pane", "messages")));
            guide.put("history_actions", "Use list(pane, optional query) without turn_id; read/show(pane, optional turn_id). "
                    + "If a turn has no messages, publish with its turn_id before showing messages. "
                    + "Retry standalone publish with the same caller-generated turn UUID and message_id.");
        }
        if (changes) {
            guide.put("edit_turn", steps);
            guide.put("show_changes", historyCall(TURN_REVIEW, Map.of("action", "show", "turn_id", turn)));
            guide.put("edit_rules", "Track before external edits; supported Bridge writes are tracked on the same "
                    + "connection. Finish after saving."
                    + (messages ? " Finish records changes; a note appears only after publish." : ""));
        }
        guide.put("navigation", (messages ? "In Сообщения ИИ an editor target uses element_id to select a form item. " : "")
                + (changes ? "In Изменения ИИ open the Form.form row to compare snapshots. " : "")
                + "Codex Open with 1C:EDT is a separate local handler that maps Form.form source lines when possible.");
        return guide;
    }

    private static Map<String,Object> historyCall(String op, Map<String,Object> args) {
        return Map.of("tool", "edt.diff", "arguments", Map.of("op", op, "args", args));
    }

    private static Map<String,Object> authoringGuide(List<Map<String,Object>> legacy) {
        var calls = new ArrayList<Map<String,Object>>();
        String project = "<EDT project name>";
        String path = "src/CommonModules/Example/Module.bsl";
        String method = "<procedure or function name>";
        addBslStep(calls, legacy, "edt_bsl_method_edit",
                Map.of("action", "read", "project", project, "path", path, "name", method), null);
        addBslStep(calls, legacy, "edt_bsl_method_edit", Map.of("action", "apply", "project", project,
                "path", path, "name", method, "expected_sha256", "<read.source_sha256>", "replacement", "<full method>"),
                "Begin the agent turn before apply and finish after saving. For a preview use action=preview with the same arguments.");
        addBslStep(calls, legacy, "edt_search_queries", Map.of("project", project, "query", "<query fragment>"), null);
        addBslStep(calls, legacy, "edt_form_property_reference",
                Map.of("project", project, "form_fqn", "<form FQN>", "item_id", 16),
                "Use a real item_id from inspect_form_layout. Omit item_id to inspect the form root.");
        return Map.of("topic", "authoring", "calls", calls);
    }

    private static Map<String,Object> bslGuide(List<Map<String,Object>> legacy) {
        if (legacy.stream().noneMatch(row -> BSL_OPERATIONS.contains(row.get("name")))) {
            throw new IllegalArgumentException("BSL workflow is unavailable or not exposed");
        }
        var steps = new ArrayList<Map<String,Object>>();
        String project = "<EDT project name>";
        String modulePath = "CommonModules/Example/Module.bsl";
        String path = "src/" + modulePath;
        String historyId = "<begin.turn_id>";
        addBslStep(steps, legacy, "edt_search_in_code", Map.of("projectName", project, "query", "<method or text>",
                "searchType", "auto", "scope", "modules", "top_k", 5), null);
        addBslStep(steps, legacy, "bsl_get_method_body",
                Map.of("projectName", project, "filePath", modulePath, "name", "<exact method name>"),
                "Use the actual path relative to src/. If truncated, request the needed range before editing.");
        addBslStep(steps, legacy, "edt_check", Map.of("action", "baseline", "project", project, "files", List.of(path)),
                "Capture existing diagnostics before a change if you plan to compare them.");
        addBslStep(steps, legacy, TURN_REVIEW, Map.of("action", "begin"), "Before the first source edit.");
        addBslStep(steps, legacy, TURN_REVIEW,
                Map.of("action", "track", "turn_id", historyId, "project", project, "files", List.of(path)),
                "Before external writes only. Supported Bridge writes snapshot automatically on the same MCP connection. Use additions for new files.");
        addBslStep(steps, legacy, "edit_file",
                Map.of("path", project + "/" + path, "old_text", "<exact saved text>",
                        "new_text", "<replacement>"), "Use the workspace-relative path starting with the registered EDT project name, then src/. Use a uniquely matching fragment from the actual file.");
        addBslStep(steps, legacy, TURN_REVIEW, Map.of("action", "finish", "turn_id", historyId),
                "After saving, once for the whole edit turn.");
        addBslStep(steps, legacy, "edt_check",
                Map.of("action", "changes", "project", project, "files", List.of(path),
                        "baseline_id", "<baseline.baseline_id>"),
                "Read the diagnostic delta after saving. Use details for pages; a pending build is not a clean result.");
        var guide = new LinkedHashMap<String,Object>();
        guide.put("topic", "bsl");
        guide.put("usage", "The examples are complete calls for the shown steps. Fill in the placeholders and call the tool directly. "
                + "Request the full edt.help contract only when the example lacks an operation or an argument you need, "
                + "or when a validation error calls for more detail.");
        guide.put("scope", "Search and read for inspection; use the later steps only for edits. Diagnostics do not launch or update 1C.");
        var optional = new ArrayList<Map<String,Object>>();
        addBslStep(optional, legacy, "edt_platform_help", Map.of("query", "ТаблицаЗначений Добавить"),
                "Check a platform API signature. Read a hit by path and bundle.");
        addBslStep(optional, legacy, "validate_query",
                Map.of("projectName", project, "filePath", modulePath, "name", "<exact method name>"),
                "Check static multiline query literals after changing a method that contains queries.");
        if (!optional.isEmpty()) guide.put("optional", optional);
        guide.put("steps", steps);
        return guide;
    }

    private static void addBslStep(List<Map<String,Object>> steps, List<Map<String,Object>> legacy,
            String operation, Map<String,Object> args, String when) {
        legacy.stream().filter(row -> operation.equals(row.get("name"))).findFirst().ifPresent(row -> {
            var step = new LinkedHashMap<String,Object>();
            step.put("tool", "edt." + group(row));
            step.put("arguments", Map.of("op", operation, "args", args));
            if (when != null) step.put("when", when);
            steps.add(step);
        });
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
            "codex_open_in",legacy.stream().anyMatch(row -> "edt_setup_offer".equals(row.get("name")))
                    ? "Only when a Codex user asks about opening code from chat, check edt_setup_offer with topic=codex_open_in_edt, and offer setup only if suggested=true."
                    : "For manual navigation from Codex, see agent-bridge/editor-lifecycle.md.");
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
            "runtime_routing","Use edt.run op=route to plan. Prefer Native for BSL or data, direct TestClient for supported UI and Vanessa for unsupported UI or explicit scenarios.",
            "managed_batches","When exposed, edt.run op=prepare takes explicit project/base_uuid and op=ui executes a verified batch. Follow next_call and use details_call only as needed. To reuse a verified client, retain the owner with auto_cleanup=false, pass reuse_from_ui_run_id for later starts, and clean up the owner at task end.",
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
