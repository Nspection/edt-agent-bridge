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
        "run","Execute existing runtime operations. op=route returns a read-only native/TestClient/Vanessa plan; it never launches/updates a base or retries a mutation. For code/query/data prefer Native; UI prefers direct TestClient with managed Python; Vanessa covers unsupported UI or explicit scenarios. Unknown outcomes block switching.",
        "debug","Inspect and control the EDT debugger with the original commands and target identity checks.",
        "task","Wait for jobs, read durable results, manage task sessions and cleanup only their owned resources. DONE alone is not business success.",
        "help","Discover compact groups, paged operations or ONE exact operation contract. args action=catalog|operations|contract; group, op, filter, offset, limit. Full legacy tools/list is available with profile=legacy; canonical tool calls remain compatible.");
    public static String group(Map<String,Object> definition){
        String n=(String)definition.get("name");if(n.equals("route"))return "run";
        boolean readOnly=Boolean.TRUE.equals(definition.get("_compact_read_only"))||definition.get("annotations") instanceof Map<?,?> a && Boolean.TRUE.equals(a.get("readOnlyHint"));
        if(n.startsWith("edt_task_")||Set.of("edt_operation_status","edt_result").contains(n))return "task";
        if(n.contains("breakpoint")||n.contains("debug")||n.contains("variables")||n.contains("stack_trace")||n.contains("step_into")||n.contains("step_over")||n.contains("step_out")||n.contains("resume")||n.contains("run_to_line"))return "debug";
        if(n.startsWith("edt_runtime_")||n.startsWith("edt_runner_")||n.startsWith("edt_development_run_")||Set.of("edt_test_client","edt_data_assert","edt_test_data","qa_run","edt_launch_app").contains(n))return "run";
        if(n.contains("compare")||n.startsWith("edt_merge_"))return "diff";
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
            var properties=new LinkedHashMap<String,Object>();properties.put("op",Map.of("type","string","description","Canonical operation name from edt.help; route is a special read-only run plan"));properties.put("args",Map.of("type","object","description",g.equals("help")?"action=catalog|operations|contract, group, op, filter, offset, limit":"Exact arguments from that operation contract"));
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
        String operation=op;var target=legacy.stream().filter(d->operation.equals(d.get("name"))).findFirst().orElseThrow(()->new IllegalArgumentException("Operation is unavailable or not exposed: "+operation));
        if(!group(target).equals(g))throw new IllegalArgumentException("Use edt."+group(target)+" for "+operation);
        return new Invocation(op,args);
    }
    public static Map<String,Object> help(Map<String,Object> args,List<Map<String,Object>> legacy){
        if(!Set.of("action","group","op","filter","offset","limit").containsAll(args.keySet()))throw new IllegalArgumentException("Unknown help field");
        String action=text(args,"action","catalog"),g=text(args,"group",null),filter=text(args,"filter","");
        if(g!=null&&(!GROUPS.contains(g)||g.equals("help")))throw new IllegalArgumentException("Unknown operation group");
        if(action.equals("catalog"))return Map.of("groups",definitions(legacy).stream().map(d->Map.of("name",d.get("name"),"description",d.get("description"))).toList(),"legacy_operation_count",legacy.size(),"routing",McpRuntimeRouting.policy());
        var discoverable=new ArrayList<>(legacy);if(legacy.stream().anyMatch(d->group(d).equals("run")))discoverable.add(McpRuntimeRouting.definition());
        if(action.equals("contract")){
            String op=text(args,"op",null);if(op==null)throw new IllegalArgumentException("op required for a contract");
            var d=discoverable.stream().filter(x->op.equals(x.get("name"))).findFirst().orElseThrow(()->new IllegalArgumentException("Operation unavailable or not exposed"));
            if(g!=null&&!group(d).equals(g))throw new IllegalArgumentException("Operation is in another group");
            var contract=new LinkedHashMap<>(d);contract.remove("_compact_read_only");return Map.of("entry","edt."+group(d),"op",op,"contract",contract);
        }
        if(!action.equals("operations"))throw new IllegalArgumentException("Unknown help action");
        int offset=integer(args,"offset",0,0,Integer.MAX_VALUE),limit=integer(args,"limit",20,1,50);
        var matches=discoverable.stream().filter(d->(g==null||g.equals(group(d)))&&((String)d.get("name")).contains(filter)).sorted(Comparator.comparing(d->(String)d.get("name"))).toList();
        var rows=matches.stream().skip(offset).limit(limit).map(d->{String description=String.valueOf(d.get("description"));return Map.of("entry","edt."+group(d),"op",d.get("name"),"summary",description.substring(0,Math.min(160,description.length())));}).toList();
        return Map.of("operations",rows,"total",matches.size(),"offset",offset,"has_more",(long)offset+rows.size()<matches.size());
    }
    static String text(Map<String,Object>a,String k,String fallback){Object v=a.get(k);if(v==null)return fallback;if(!(v instanceof String s)||s.length()>256)throw new IllegalArgumentException(k+" must be bounded text");return s;}
    static int integer(Map<String,Object>a,String k,int fallback,int min,int max){if(!a.containsKey(k))return fallback;Object v=a.get(k);if(!(v instanceof Number))throw new IllegalArgumentException(k+" must be an integer");try{int n=new java.math.BigDecimal(v.toString()).intValueExact();if(n<min||n>max)throw new ArithmeticException();return n;}catch(ArithmeticException e){throw new IllegalArgumentException(k+" outside bounds");}}
}
