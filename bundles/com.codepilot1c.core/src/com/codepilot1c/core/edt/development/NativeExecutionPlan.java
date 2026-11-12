// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.google.gson.*;

/** Pure validation and an immutable snapshot of the final Native wire body, before any claim. */
public final class NativeExecutionPlan {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private final String key,wire;
    private NativeExecutionPlan(String key,String wire){this.key=key;this.wire=wire;}
    public String key(){return key;}
    JsonObject body(){return JsonParser.parseString(wire).getAsJsonObject();}
    public static NativeExecutionPlan prepare(Map<String,Object> arguments)throws Exception {
        String key=NativeHostChannel.requireKey(arguments);
        var view=new LinkedHashMap<String,Object>(arguments);view.put("action","execute");NativeHostService.validateViewArguments(view);
        if(Set.of("allow_database_update","expected_plan_sha256","grid","grid_step").stream().anyMatch(arguments::containsKey))throw new IllegalArgumentException("Refresh and snapshot options do not apply to execute");
        Object selected=arguments.getOrDefault("execution_mode","execute");
        if(!(selected instanceof String mode))throw new IllegalArgumentException("execution_mode must be a string");
        Object selectedContext=arguments.getOrDefault("context","server");
        if(!(selectedContext instanceof String context)||!Set.of("client","server").contains(context))throw new IllegalArgumentException("context must be client/server");
        if(mode.equals("query")&&arguments.containsKey("limit"))try{
            int limit=new java.math.BigDecimal(arguments.get("limit").toString()).intValueExact();if(limit<1||limit>1000)throw new ArithmeticException();
        }catch(Exception invalid){throw new IllegalArgumentException("limit must be an integer 1..1000");}
        if(mode.equals("query")&&!context.equals("server"))throw new IllegalArgumentException("Queries require server context");
        if(mode.equals("batch"))return wire(key,"batch",NativeHostBatchPlan.payload(arguments),Map.of());
        Object raw=arguments.getOrDefault("parameters",Map.of());
        if(!(raw instanceof Map<?,?> values)||values.keySet().stream().anyMatch(k->!(k instanceof String)))throw new IllegalArgumentException("parameters must be an object with string keys");
        var payload=new LinkedHashMap<String,Object>(arguments);payload.put("mode",mode);
        @SuppressWarnings("unchecked") Map<String,Object> parameters=(Map<String,Object>)values;
        return wire(key,context,RuntimeExecutionPlan.payload(payload),parameters);
    }
    static NativeExecutionPlan wire(String key,String context,String payload,Map<String,Object> parameters){
        NativeHostChannel.requireKey(Map.of("idempotency_key",key));
        if(!Set.of("client","server","batch").contains(context))throw new IllegalArgumentException("context must be client/server/batch");
        JsonObject body=new JsonObject();body.addProperty("context",context);body.addProperty("code",payload);body.add("parameters",JSON.toJsonTree(new TreeMap<>(parameters)));
        String wire=body.toString();if(wire.getBytes(StandardCharsets.UTF_8).length>128*1024)throw new IllegalArgumentException("Payload exceeds 128 KiB");
        return new NativeExecutionPlan(key,wire);
    }
}
