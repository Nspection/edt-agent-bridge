// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/** Per-client launch/stop gate. Terminal records are monotonic across all service instances. */
public final class DirectClientLifecycle {
    private static final Map<Path,Object> LOCKS=new ConcurrentHashMap<>();
    private DirectClientLifecycle() {}
    public static Object lock(Path directory){return LOCKS.computeIfAbsent(directory.toAbsolutePath().normalize(),p->new Object());}
    public static final class Stopped extends IllegalStateException {
        Stopped(){super("Original client was stopped or cleaned; startup cannot resume it");}
    }
    public static void requireActive(Map<String,Object> current){
        if(Boolean.TRUE.equals(current.get("cleaned")) || Set.of("stopped","cleaned").contains(current.getOrDefault("state","")))throw new Stopped();
    }
    public static void beforeSave(Map<String,Object> current,Map<String,Object> next){
        if(Boolean.TRUE.equals(current.get("cleaned")) && !Boolean.TRUE.equals(next.get("cleaned")))throw new Stopped();
        if("stopped".equals(current.get("state")) && !Set.of("stopped","cleaned").contains(next.getOrDefault("state","")))throw new Stopped();
    }
    public static <T> T active(Path directory,Callable<Map<String,Object>> current,Callable<T> mutation)throws Exception {
        synchronized(lock(directory)){requireActive(current.call());return mutation.call();}
    }
}
