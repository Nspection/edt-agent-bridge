// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.util.*;
import java.util.concurrent.Callable;

/** Observation after a proven action: capture failures never replay or reclassify that action. */
public final class NativeUiCapture {
    @FunctionalInterface public interface Capturer { Map<String,Object> capture(long pid,String started,String window) throws Exception; }
    private NativeUiCapture() {}
    public static Map<String,Object> observe(Map<String,Object> client,Map<String,Object> action,Callable<Map<String,Object>> reread) {
        return observe(client,action,reread,(pid,started,window)->ProcessWindowCapture.execute("snapshot",pid,started,window,false,false));
    }
    public static Map<String,Object> observe(Map<String,Object> client,Map<String,Object> action,Callable<Map<String,Object>> reread,Capturer capturer) {
        var proof=new LinkedHashMap<String,Object>();proof.put("success",false);proof.put("observation_only",true);
        try {
            var nativeUi=object(action.get("native_ui"));var target=object(nativeUi.get("capture_target"));
            if(!Boolean.TRUE.equals(action.get("success"))||!Boolean.TRUE.equals(nativeUi.get("header_menu_proven")))throw new IllegalArgumentException("Header menu is not proven");
            long pid=number(client.get("pid"));String started=String.valueOf(client.get("process_started_at"));
            if(number(target.get("process_id"))!=pid||!started.equals(target.get("process_started_at")))throw new IllegalArgumentException("Capture process identity differs");
            if(!(target.get("window_id") instanceof String window)||!window.matches("0x[0-9a-f]{1,16}"))throw new IllegalArgumentException("Invalid menu HWND");
            var image=capturer.capture(pid,started,window);proof.put("image",image);
            for(String key:List.of("window_id","window_class","owner_window_id"))if(!Objects.equals(target.get(key),image.get(key)))throw new IllegalStateException("Captured window identity changed");
            for(String key:List.of("ui_thread_id","left","top","width","height"))if(number(target.get(key))!=number(image.get(key)))throw new IllegalStateException("Captured window scope changed");
            var after=reread.call();var afterUi=object(after.get("native_ui"));proof.put("after_snapshot_id",after.get("snapshot_id"));
            if(!Boolean.TRUE.equals(image.get("success"))||!Boolean.TRUE.equals(after.get("success"))
                    ||!Boolean.TRUE.equals(afterUi.get("header_menu_proven"))
                    ||!(nativeUi.get("reference_sha256") instanceof String hash)||!hash.matches("[a-f0-9]{64}")
                    ||!hash.equals(afterUi.get("reference_sha256"))||!Objects.equals(action.get("window"),after.get("window")))
                throw new IllegalStateException("Menu identity/visibility was not stable across capture");
            proof.put("success",true);proof.put("menu_reference_sha256",hash);proof.put("scope","exact_menu_window_with_stable_semantic_observations");
        } catch(Exception error) {proof.put("reason","menu_capture_unverified");proof.put("error_type",error.getClass().getSimpleName());}
        return proof;
    }
    private static long number(Object value){if(!(value instanceof Number number))throw new IllegalArgumentException("Missing numeric capture identity");return number.longValue();}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){if(!(value instanceof Map<?,?>))throw new IllegalArgumentException("Missing native UI reference");return (Map<String,Object>)value;}
}
