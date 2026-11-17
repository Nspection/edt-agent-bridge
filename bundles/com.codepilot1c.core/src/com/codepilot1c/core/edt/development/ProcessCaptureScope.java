// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
/** Inventory scope for separate window images; never implies a composed or atomic desktop frame. */
final class ProcessCaptureScope {
    record Window(long handle,long pid,long thread,long owner,String type,int left,int top,int width,int height,boolean visible,boolean minimized,long style,long extendedStyle) {
        Map<String,Object> describe(){var out=new LinkedHashMap<String,Object>();out.put("window_id",hex(handle));out.put("ui_thread_id",thread);out.put("owner_window_id",hex(owner));out.put("window_class",type);out.put("left",left);out.put("top",top);out.put("width",width);out.put("height",height);out.put("visible",visible);out.put("minimized",minimized);out.put("popup_style",(style&0x80000000L)!=0);out.put("layered",(extendedStyle&0x80000L)!=0);return out;}
    }
    static String hex(long handle){return "0x"+Long.toUnsignedString(handle,16);}
    static List<Window> select(Window root,List<Window> inventory,boolean includePopups) {
        var selected=new ArrayList<Window>();selected.add(root);
        if(includePopups)for(var window:inventory)if(window.handle()!=root.handle() && window.pid()==root.pid() && window.thread()==root.thread()
                && window.visible() && !window.minimized() && (window.style()&0x80000000L)!=0)selected.add(window);
        if(selected.size()>16)throw new IllegalArgumentException("Selected UI thread exceeds 16 capture windows");
        long area=0;for(var window:selected){ProcessWindowCapture.dimensions(window.width(),window.height());area+=(long)window.width()*window.height();}
        if(area>16000000)throw new IllegalArgumentException("Window capture exceeds total 16 million pixel budget");return List.copyOf(selected);
    }
}
