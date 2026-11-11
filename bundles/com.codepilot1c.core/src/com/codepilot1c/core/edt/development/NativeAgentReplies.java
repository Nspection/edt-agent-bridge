// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
import java.nio.charset.StandardCharsets;
import com.google.gson.*;
/** Read-only views of durable original receipts. Cursors acknowledge presentation, never execution. */
public final class NativeAgentReplies {
    private static final Gson JSON=AgentResponses.JSON;
    private NativeAgentReplies(){}
    private static String hash(Object v)throws Exception{return NativeHostChannel.hash(canonical(JSON.toJsonTree(v)));}
    private static String canonical(JsonElement v){
        if(v.isJsonObject()){var map=new TreeMap<String,String>();for(var e:v.getAsJsonObject().entrySet())map.put(e.getKey(),canonical(e.getValue()));var parts=new ArrayList<String>();map.forEach((k,x)->parts.add(JSON.toJson(k)+":"+x));return "{"+String.join(",",parts)+"}";}
        if(v.isJsonArray()){var values=new ArrayList<String>();v.getAsJsonArray().forEach(x->values.add(canonical(x)));return "["+String.join(",",values)+"]";}
        if(v.isJsonPrimitive()&&v.getAsJsonPrimitive().isNumber())return v.getAsBigDecimal().stripTrailingZeros().toPlainString();return v.toString();
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object v){return v instanceof Map<?,?>?(Map<String,Object>)v:Map.of();}
    private static List<?> list(Object v){return v instanceof List<?> l?l:List.of();}
    private static Map<String,Object> call(String action,String host,String request,Map<String,Object> extra){var args=new LinkedHashMap<String,Object>();args.put("action",action);args.put("host_id",host);args.put("request_id",request);args.putAll(extra);return Map.of("tool","edt.run","arguments",Map.of("op","edt_runtime_host","args",args));}
    private static String text(Object v,int max){String t=Objects.toString(v,"");return t.substring(0,Math.min(max,t.length()));}
    private static Map<String,Object> step(Object value){var source=map(value);var out=new LinkedHashMap<String,Object>();for(String key:List.of("step","kind","success","outcome_unknown","failure_kind","value_omitted","error_truncated","elapsed_ms"))if(source.containsKey(key))out.put(key,source.get(key));if(source.containsKey("error")){String error=Objects.toString(source.get("error"),"");out.put("error",text(error,512));if(error.length()>512)out.put("error_truncated",true);}if(source.containsKey("snapshot"))out.put("snapshot_id",map(source.get("snapshot")).get("snapshot_id"));return out;}
    public static Map<String,Object> view(String host,Map<String,Object> raw,List<Map<String,Object>> images,String cursor,int pageSize)throws Exception{
        if(pageSize<1||pageSize>16)throw new IllegalArgumentException("page_size must be 1..16");
        String request=Objects.toString(raw.get("request_id"),"");var progress=map(raw.get("progress"));var execution=map(raw.get("execution"));
        // Only native batch receipts have host progress. Plain execution/query values are never guessed to be steps.
        boolean batch=Boolean.TRUE.equals(raw.get("_batch"))||raw.containsKey("progress");List<?> steps=batch?list(execution.containsKey("value")?execution.get("value"):progress.get("value")):List.of();
        int offset=0;var seen=new TreeSet<String>();String oldState="";
        var byId=new TreeMap<String,Map<String,Object>>();for(var image:images)byId.put(Objects.toString(image.get("snapshot_id")),image);
        if(cursor!=null){
            try{
                if(cursor.length()>8192)throw new IllegalArgumentException();JsonObject c=JsonParser.parseString(new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8)).getAsJsonObject();
                if(c.get("v").getAsInt()!=1||!host.equals(c.get("host").getAsString())||!request.equals(c.get("request").getAsString()))throw new IllegalArgumentException();offset=c.get("steps").getAsBigDecimal().intValueExact();
                if(offset<0||offset>steps.size()||c.getAsJsonArray("images").size()>64)throw new IllegalArgumentException();
                for(var id:c.getAsJsonArray("images")){String key=id.getAsString();if(!byId.containsKey(key)||!seen.add(key))throw new IllegalArgumentException();}
                if(!hash(steps.subList(0,offset)).equals(c.get("prefix").getAsString())||!hash(seen.stream().map(byId::get).toList()).equals(c.get("image_prefix").getAsString()))throw new IllegalArgumentException();oldState=c.get("state").getAsString();
            }catch(Exception e){throw new IllegalArgumentException("Invalid or stale Native cursor; read the original request without a cursor or use details, never resubmit BSL",e);}
        }
        var state=new LinkedHashMap<String,Object>();for(String key:List.of("state","terminal","outcome_unknown"))state.put(key,raw.get(key));state.put("phase",progress.get("phase"));state.put("current_step",progress.get("current_step"));state.put("steps",steps);state.put("images",images);String fingerprint=hash(state);
        int end=Math.min(steps.size(),offset+pageSize);var newSteps=new ArrayList<Map<String,Object>>();for(Object row:steps.subList(offset,end))newSteps.add(step(row));
        var newImages=new ArrayList<Map<String,Object>>();for(var entry:byId.entrySet())if(!seen.contains(entry.getKey())&&newImages.size()<pageSize){newImages.add(entry.getValue());seen.add(entry.getKey());}
        boolean more=end<steps.size()||seen.size()<images.size(),changed=!fingerprint.equals(oldState)||end>offset||!newImages.isEmpty();
        var token=new LinkedHashMap<String,Object>();token.put("v",1);token.put("host",host);token.put("request",request);token.put("steps",end);token.put("images",seen);token.put("prefix",hash(steps.subList(0,end)));token.put("image_prefix",hash(seen.stream().map(byId::get).toList()));token.put("state",fingerprint);
        String nextCursor=Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.toJson(token).getBytes(StandardCharsets.UTF_8));
        var out=new LinkedHashMap<String,Object>();out.put("host_id",host);out.put("request_id",request);for(String key:List.of("state","terminal","outcome_unknown"))out.put(key,raw.get(key));out.put("view","compact");out.put("execution_success",execution.get("success"));out.put("phase",progress.getOrDefault("phase",raw.get("state")));out.put("reported_steps",steps.size());out.put("artifact_count",images.size());out.put("changed",changed);out.put("has_more",more);out.put("cursor",nextCursor);out.put("new_steps",newSteps);out.put("new_artifacts",newImages);
        for(Object value:steps){var row=map(value);if(Boolean.FALSE.equals(row.get("success"))){out.put("failed_step",row.get("step"));out.put("failure_kind",row.get("failure_kind"));if(row.containsKey("error"))out.put("error",text(row.get("error"),512));break;}}
        if(execution.containsKey("error")&&!out.containsKey("error"))out.put("error",text(execution.get("error"),512));
        if(!batch&&Boolean.TRUE.equals(execution.get("success"))&&execution.containsKey("value")){
            if(JSON.toJson(execution.get("value")).getBytes(StandardCharsets.UTF_8).length<=1024){out.put("value",execution.get("value"));out.put("value_inlined",true);}else out.put("value_in_details",true);
        }
        var details=call("details",host,request,Map.of());out.put("details_call",details);out.put("artifacts_call",call("snapshots",host,request,Map.of()));
        boolean terminal=Boolean.TRUE.equals(raw.get("terminal")),failed=terminal&&!"completed".equals(raw.get("state"));
        if(failed)out.put("next_call",details);else if(more)out.put("next_call",call("result",host,request,Map.of("cursor",nextCursor,"page_size",pageSize)));else if(!terminal)out.put("next_call",call("wait",host,request,Map.of("cursor",nextCursor,"page_size",pageSize,"timeout_ms",20000)));
        return out;
    }
}
