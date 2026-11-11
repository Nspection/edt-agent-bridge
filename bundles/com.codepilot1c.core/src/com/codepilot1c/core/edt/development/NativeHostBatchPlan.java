// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
import com.google.gson.Gson;
/** Explicit, bounded batch. Each UI-changing step yields before its optional screenshot. */
final class NativeHostBatchPlan {
    static void requireCapabilities(Map<String,Object> input,Map<?,?> identity){
        if(input.get("steps") instanceof List<?> values&&values.stream().anyMatch(item->item instanceof Map<?,?> step&&(step.containsKey("kind")||step.containsKey("select")||Boolean.TRUE.equals(step.get("screenshot_on_failure"))))){
            if(!(identity.get("host_features") instanceof List<?> features)||!features.contains("batch_controls_v1"))throw new IllegalStateException("Host template lacks batch_controls_v1; prepare a new owned host, do not replay previous work");
        }
        if(input.get("steps") instanceof List<?> steps&&steps.stream().anyMatch(step->step instanceof Map<?,?> values&&(Boolean.TRUE.equals(values.get("screenshot"))||Boolean.TRUE.equals(values.get("screenshot_on_failure"))))){
            if(!(identity.get("native_features") instanceof List<?> features)||!features.contains("screenshot_png"))throw new IllegalStateException("Native component lacks screenshot_png; use the matching release ZIP before submitting a screenshot batch");
        }
    }
    private static int number(Map<?,?> step,String name,int fallback,int min,int max){
        if(!step.containsKey(name))return fallback;
        Object value=step.get(name);if(!(value instanceof Number n))throw new IllegalArgumentException(name+" must be an integer");
        double d=n.doubleValue();if(!Double.isFinite(d)||d!=Math.rint(d)||d<min||d>max)throw new IllegalArgumentException(name+" is out of range");return (int)d;
    }
    static String payload(Map<String,Object> input){
        if(!(input.get("steps") instanceof List<?> raw)||raw.isEmpty()||raw.size()>16)throw new IllegalArgumentException("Batch requires 1..16 steps");
        var steps=new ArrayList<Map<String,Object>>();
        for(Object item:raw){
            if(!(item instanceof Map<?,?> step))throw new IllegalArgumentException("Invalid batch step");
            if(!Set.of("code","context","parameters","caption","screenshot","kind","timeout_ms","interval_ms","select","screenshot_on_failure").containsAll(step.keySet()))throw new IllegalArgumentException("Unknown batch step field");
            Object code=step.get("code");if(!(code instanceof String text)||text.isBlank()||text.length()>8192)throw new IllegalArgumentException("Step code required, up to 8192 characters");
            Object context=step.containsKey("context")?step.get("context"):"client";if(!Set.of("client","server").contains(context))throw new IllegalArgumentException("Step context must be client/server");
            Object caption=step.containsKey("caption")?step.get("caption"):"";if(!(caption instanceof String title)||title.length()>200)throw new IllegalArgumentException("Caption exceeds 200 characters");
            Object capture=step.containsKey("screenshot")?step.get("screenshot"):false;if(!(capture instanceof Boolean))throw new IllegalArgumentException("screenshot must be boolean");
            Object parameters=step.containsKey("parameters")?step.get("parameters"):Map.of();if(!(parameters instanceof Map<?,?>))throw new IllegalArgumentException("Step parameters must be an object");
            Object kind=step.containsKey("kind")?step.get("kind"):"execute";
            if(!Set.of("execute","wait","assert","read").contains(kind))throw new IllegalArgumentException("kind must be execute/wait/assert/read");
            int timeout=number(step,"timeout_ms",60000,1000,300000),interval=number(step,"interval_ms",1000,1000,10000);
            if((step.containsKey("timeout_ms")||step.containsKey("interval_ms"))&&!kind.equals("wait"))throw new IllegalArgumentException("Wait timing only applies to kind=wait");
            Object failure=step.containsKey("screenshot_on_failure")?step.get("screenshot_on_failure"):false;if(!(failure instanceof Boolean))throw new IllegalArgumentException("screenshot_on_failure must be boolean");
            Object selection=step.containsKey("select")?step.get("select"):List.of();
            if(!(selection instanceof List<?> fields)||fields.size()>32||(!fields.isEmpty()&&!kind.equals("read")))throw new IllegalArgumentException("select requires kind=read, at most 32 paths");
            for(Object field:fields)if(!(field instanceof String path)||path.length()>200||!path.matches("[\\p{L}\\p{N}_]+(\\.[\\p{L}\\p{N}_]+)*"))throw new IllegalArgumentException("Invalid select path");
            if(new HashSet<>(fields).size()!=fields.size())throw new IllegalArgumentException("Duplicate select path");
            var value=new LinkedHashMap<String,Object>();value.put("code",code);value.put("context",context);value.put("caption",caption);value.put("screenshot",capture);value.put("parameters",parameters);
            if(step.keySet().stream().anyMatch(k->Set.of("kind","timeout_ms","interval_ms","select","screenshot_on_failure").contains(k))){
                value.put("kind",kind);value.put("timeout_ms",timeout);value.put("interval_ms",interval);value.put("select",selection);value.put("screenshot_on_failure",failure);
            }steps.add(value);
        }
        return new Gson().toJson(Map.of("steps",steps));
    }
}
