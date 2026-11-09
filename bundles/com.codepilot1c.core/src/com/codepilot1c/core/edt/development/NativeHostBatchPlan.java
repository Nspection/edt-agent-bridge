// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
import com.google.gson.Gson;
/** Explicit, bounded batch. Each UI-changing step yields before its optional screenshot. */
final class NativeHostBatchPlan {
    static void requireCapabilities(Map<String,Object> input,Map<?,?> identity){
        if(input.get("steps") instanceof List<?> steps&&steps.stream().anyMatch(step->step instanceof Map<?,?> values&&Boolean.TRUE.equals(values.get("screenshot")))){
            if(!(identity.get("native_features") instanceof List<?> features)||!features.contains("screenshot_png"))throw new IllegalStateException("Native component lacks screenshot_png; use the matching release ZIP before submitting a screenshot batch");
        }
    }
    static String payload(Map<String,Object> input){
        if(!(input.get("steps") instanceof List<?> raw)||raw.isEmpty()||raw.size()>16)throw new IllegalArgumentException("Batch requires 1..16 steps");
        var steps=new ArrayList<Map<String,Object>>();
        for(Object item:raw){
            if(!(item instanceof Map<?,?> step))throw new IllegalArgumentException("Invalid batch step");
            if(!Set.of("code","context","parameters","caption","screenshot").containsAll(step.keySet()))throw new IllegalArgumentException("Unknown batch step field");
            Object code=step.get("code");if(!(code instanceof String text)||text.isBlank()||text.length()>8192)throw new IllegalArgumentException("Step code required, up to 8192 characters");
            Object context=step.containsKey("context")?step.get("context"):"client";if(!Set.of("client","server").contains(context))throw new IllegalArgumentException("Step context must be client/server");
            Object caption=step.containsKey("caption")?step.get("caption"):"";if(!(caption instanceof String title)||title.length()>200)throw new IllegalArgumentException("Caption exceeds 200 characters");
            Object capture=step.containsKey("screenshot")?step.get("screenshot"):false;if(!(capture instanceof Boolean))throw new IllegalArgumentException("screenshot must be boolean");
            Object parameters=step.containsKey("parameters")?step.get("parameters"):Map.of();if(!(parameters instanceof Map<?,?>))throw new IllegalArgumentException("Step parameters must be an object");
            steps.add(Map.of("code",code,"context",context,"caption",caption,"screenshot",capture,"parameters",parameters));
        }
        return new Gson().toJson(Map.of("steps",steps));
    }
}
