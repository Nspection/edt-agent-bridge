// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.util.*;

/** Explicit projections: never forward logs, credentials, code or a whole controller response. */
public final class AgentBrief {
    private AgentBrief() {}
    public static Map<String,Object> select(Map<?,?> input,String... keys) {
        Map<String,Object> out=new LinkedHashMap<>();
        for(String key:keys) if(input.get(key)!=null) out.put(key,input.get(key));
        return out;
    }
    private static Map<?,?> map(Object value) {return value instanceof Map<?,?> m?m:Map.of();}
    private static void limitText(Map<String,Object> output,String key,int limit) {
        if(output.get(key) instanceof String text && text.length()>limit) {
            int end=Character.isHighSurrogate(text.charAt(limit-1))?limit-1:limit;
            output.put(key,text.substring(0,end));output.put(key+"_truncated",true);
        }
    }
    public static Map<String,Object> run(Map<String,Object> input,long after) {
        Map<String,Object> out=select(input,"run_id","project","phase","phase_before_restart","terminal","error","application_migrations_verified","managed_run_id");
        limitText(out,"error",1024);
        long latest=input.get("event_cursor") instanceof Number n?n.longValue():0;
        if(after<0 || after>latest) throw new IllegalArgumentException("after_cursor must be between 0 and latest_cursor");
        List<Map<String,Object>> events=new ArrayList<>();
        if(input.get("events") instanceof List<?> list) for(Object item:list) {
            Map<?,?> event=map(item);
            if(event.get("sequence") instanceof Number n && n.longValue()>after)
                events.add(select(event,"sequence","at","phase"));
        }
        boolean more=events.size()>20;
        List<Map<String,Object>> page=List.copyOf(events.subList(0,Math.min(20,events.size())));
        long next=more?((Number)page.get(page.size()-1).get("sequence")).longValue():latest;
        out.put("events",page);out.put("event_cursor",next);out.put("latest_cursor",latest);out.put("has_more_events",more);
        long first=events.isEmpty()?latest+1:((Number)events.get(0).get("sequence")).longValue();
        out.put("events_gap",first>after+1 && latest>after);
        Map<?,?> managed=map(input.get("managed")),progress=map(managed.get("Progress"));
        var step=select(progress,"Phase");
        Object current=progress.get("CurrentStep");
        if(current instanceof Map<?,?> value) {
            var detail=select(value,"Line","Text");limitText(detail,"Text",320);step.put("CurrentStep",detail);
        } else if(current instanceof String) {step.put("CurrentStep",current);limitText(step,"CurrentStep",320);}
        out.put("progress",step);
        Map<String,Object> checkpoint=select(map(progress.get("Checkpoint")),"Id","RunId","Screenshot","ScreenshotSha256","WaitTimeoutSeconds");
        if(!checkpoint.isEmpty()) out.put("checkpoint",checkpoint);
        out.put("report",select(map(managed.get("Report")),"Tests","Failures","Errors","Skipped"));
        out.put("owned_processes_closed",Boolean.TRUE.equals(managed.get("owned_processes_closed")));
        String phase=String.valueOf(input.get("phase"));
        out.put("next_tool",switch(phase) {
            case "paused_by_debugger" -> "edt_debug_inspect";
            case "awaiting_visual_review" -> "edt_development_run_continue";
            case "interrupted_by_restart" -> "edt_development_run_status";
            default -> "edt_check";
        });
        out.put("details_tool","edt_development_run_status");
        return out;
    }
    public static Map<String,Object> operation(Map<String,Object> input) {
        Map<String,Object> out=select(input,"job_id","kind","state","error");
        limitText(out,"error",1024);
        var progress=select(map(input.get("progress")),"stage","message","percent","updated_at");limitText(progress,"message",512);out.put("progress",progress);
        out.put("result_available",input.containsKey("result"));
        out.put("details_tool","edt_operation_status");
        return out;
    }
}
