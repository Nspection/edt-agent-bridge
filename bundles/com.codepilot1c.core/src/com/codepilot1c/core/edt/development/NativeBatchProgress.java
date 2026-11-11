// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import com.google.gson.*;
import java.util.Set;
/** Durable append-only progress, acknowledged through the existing Native Complete method. */
final class NativeBatchProgress {
    static boolean isProgress(JsonObject data){
        if(!data.has("progress"))return false;
        if(!data.get("progress").isJsonPrimitive()||!data.getAsJsonPrimitive("progress").isBoolean()||!data.get("progress").getAsBoolean())throw new IllegalArgumentException("progress must be true");
        return true;
    }
    static JsonObject accept(JsonObject request,JsonObject data){
        if(!request.get("context").getAsString().equals("batch")||!request.get("state").getAsString().equals("delivered"))throw new IllegalStateException("Progress requires an executing batch");
        if(data.toString().length()>900000)throw new IllegalArgumentException("Progress too large");
        if(!data.has("sequence")||!data.get("sequence").isJsonPrimitive()||!data.getAsJsonPrimitive("sequence").isNumber())throw new IllegalArgumentException("sequence required");
        var sequence=data.get("sequence").getAsBigDecimal();int seq=sequence.intValueExact();if(seq<1||seq>10000)throw new IllegalArgumentException("Invalid sequence");
        if(!data.has("phase")||!data.get("phase").isJsonPrimitive()||!data.getAsJsonPrimitive("phase").isString()||!Set.of("waiting","step_completed","step_failed").contains(data.get("phase").getAsString()))throw new IllegalArgumentException("Invalid phase");
        if(!data.has("value")||!data.get("value").isJsonArray()||data.getAsJsonArray("value").size()>16)throw new IllegalArgumentException("Step array required");
        JsonObject old=request.has("progress")?request.getAsJsonObject("progress"):null;
        if(old!=null){int previous=old.get("sequence").getAsInt();if(seq==previous){if(!old.equals(data))throw new IllegalStateException("Conflicting progress retry");return request;}
            if(seq!=previous+1)throw new IllegalStateException("Progress sequence skipped");prefix(old,data);
        }else if(seq!=1)throw new IllegalStateException("First progress sequence must be one");
        JsonObject updated=request.deepCopy();updated.add("progress",data.deepCopy());return updated;
    }
    static void prefix(JsonObject old,JsonObject next){
        if(!next.has("value")||!next.get("value").isJsonArray())throw new IllegalArgumentException("Step array required");
        JsonArray a=old.getAsJsonArray("value"),b=next.getAsJsonArray("value");if(b.size()<a.size())throw new IllegalStateException("Progress regressed");
        for(int i=0;i<a.size();i++)if(!a.get(i).equals(b.get(i)))throw new IllegalStateException("Acknowledged step changed");
    }
}
