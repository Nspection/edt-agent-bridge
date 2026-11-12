// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import com.sun.jna.*;

/** Parent-owned restoration after a bounded helper exits, including forced termination. */
public final class StartupVisibilityLease {
    interface Desktop {
        int identity(Map<String,Object> lease); // 0 gone, 1 exact, 2 changed: preserve
        boolean restore(Map<String,Object> lease)throws Exception;
    }
    private StartupVisibilityLease(){}
    static boolean restore(Path file,Map<String,Object> context)throws Exception{return restore(file,context,new Windows());}
    static boolean restore(Path file,Map<String,Object> context,Desktop desktop)throws Exception {
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return true;
        if(Files.isSymbolicLink(file)||Files.isSymbolicLink(file.getParent())||Files.size(file)>4096)throw new IllegalStateException("Visibility lease storage changed");
        byte[] bytes;try{bytes=Files.readAllBytes(file);}catch(NoSuchFileException gone){return true;}
        var lease=TaskSessionService.object(AgentResponses.JSON.fromJson(new String(bytes,java.nio.charset.StandardCharsets.UTF_8),Map.class));
        long pid=((Number)lease.get("pid")).longValue();
        if(pid!=((Number)context.get("pid")).longValue()||!Instant.parse(lease.get("started").toString()).equals(Instant.parse(context.get("started").toString())))throw new IllegalStateException("Visibility lease target differs");
        ProcessHandle process=ProcessHandle.of(pid).orElse(null);
        boolean gone=process==null||!process.isAlive()||!process.info().startInstant().orElseThrow().equals(Instant.parse(context.get("started").toString()));
        boolean restored=gone||Boolean.TRUE.equals(lease.get("was_topmost"));
        if(!restored){int identity=desktop.identity(lease);if(identity==2)return false;restored=identity==0||desktop.restore(lease);}
        if(!restored)return false;
        try{if(!Arrays.equals(bytes,Files.readAllBytes(file)))return false;Files.delete(file);}catch(NoSuchFileException alreadyRestored){return true;}
        return true;
    }
    private interface Visitor extends Callback,AltCallingConvention {int invoke(Pointer window,Pointer ignored);}
    private interface Win extends Library {
        boolean IsWindow(Pointer window);int GetWindowThreadProcessId(Pointer window,int[] pid);
        Pointer GetPropW(Pointer window,WString name);Pointer GetWindow(Pointer window,int relation);
        int GetWindowLongW(Pointer window,int index);boolean SetWindowPos(Pointer window,Pointer after,int x,int y,int width,int height,int flags);
        boolean EnumWindows(Visitor visitor,Pointer ignored);
    }
    private static class Api {static final Win WIN=Native.load("user32",Win.class,Map.of(Library.OPTION_CALLING_CONVENTION,Function.ALT_CONVENTION));}
    private static long handle(Object value){String text=value.toString();return Long.parseUnsignedLong(text.startsWith("0x")?text.substring(2):text,16);}
    private static final class Windows implements Desktop {
        private Pointer window(Map<String,Object> lease){return new Pointer(handle(lease.get("window_id")));}
        private long pid(Pointer window){int[] pid={0};Api.WIN.GetWindowThreadProcessId(window,pid);return Integer.toUnsignedLong(pid[0]);}
        public int identity(Map<String,Object> lease){Pointer window=window(lease);if(!Api.WIN.IsWindow(window))return 0;if(pid(window)!=((Number)lease.get("pid")).longValue())return 0;return Pointer.nativeValue(Api.WIN.GetPropW(window,new WString(lease.get("property").toString())))==handle(lease.get("generation"))?1:2;}
        public boolean restore(Map<String,Object> lease)throws Exception {
            Pointer window=window(lease);long expected=((Number)lease.get("pid")).longValue();
            List<?> owners=(List<?>)lease.get("owners");Pointer owner=Api.WIN.GetWindow(window,4);int index=0;
            while(owner!=null){if(index>=owners.size()||Pointer.nativeValue(owner)!=handle(owners.get(index++))||pid(owner)!=expected||(Api.WIN.GetWindowLongW(owner,-20)&8)!=0)return false;owner=Api.WIN.GetWindow(owner,4);}
            if(index!=owners.size())return false;
            var windows=new ArrayList<Pointer>();Api.WIN.EnumWindows((candidate,ignored)->{windows.add(candidate);return 1;},null);
            var family=new HashSet<Pointer>();family.add(window);boolean added=true;
            while(added){added=false;for(Pointer candidate:windows)if(!family.contains(candidate)&&family.contains(Api.WIN.GetWindow(candidate,4))){if(pid(candidate)!=expected||family.size()>=64)return false;family.add(candidate);added=true;}}
            if(identity(lease)!=1)return false;
            if(!Api.WIN.SetWindowPos(window,new Pointer(-2),0,0,0,0,0x4013))return false;
            for(int attempt=0;attempt<20;attempt++){int identity=identity(lease);if(identity==0)return true;if(identity!=1)return false;if((Api.WIN.GetWindowLongW(window,-20)&8)==0)return true;Thread.sleep(50);}
            return false;
        }
    }
}
