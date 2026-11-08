// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;

import java.time.Instant;
import java.util.*;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.util.function.Consumer;

/** Bounded job progress plus fail-closed coordination for operations on the same resources. */
public final class InfobaseWorkflow implements AutoCloseable {
    private static final Map<String,Owner> ACTIVE=new HashMap<>();
    private static final class Owner {
        final Thread thread=Thread.currentThread(); int depth=1;
    }
    public static List<String> resourceKeys(String project,InfobaseReference base) {
        var keys=new ArrayList<String>();
        if(project!=null) keys.add("project:"+project);
        if(base!=null && base.getConnectionString()!=null) {
            String connection=base.getConnectionString().asConnectionString();
            keys.add("infobase:"+InfobasePaths.hash(InfobasePaths.connectionIdentity(connection)));
        }
        return keys;
    }
    private final List<String> keys;
    private final List<Map<String,Object>> history=new ArrayList<>();
    private final Consumer<Map<String,Object>> progress;
    private final Map<String,Object> details=new LinkedHashMap<>();
    public void details(Map<String,Object> value) {details.putAll(value);}
    public InfobaseWorkflow(List<String> keys, Consumer<Map<String,Object>> progress) {
        this.keys=new ArrayList<>();this.progress=progress;
        synchronized(ACTIVE) {
            for(String key:keys) {
                Owner owner=ACTIVE.get(key);
                if(owner!=null && owner.thread!=Thread.currentThread()) {
                    close();throw new IllegalStateException("Another infobase operation owns "+key);
                }
                if(owner==null) ACTIVE.put(key,new Owner()); else owner.depth++;
                this.keys.add(key);
            }
        }
    }
    public void stage(String name) {
        history.add(Map.of("stage",name,"started_at",Instant.now().toString()));
        var snapshot=new LinkedHashMap<String,Object>(details);snapshot.put("stage",name);snapshot.put("history",List.copyOf(history));progress.accept(snapshot);
    }
    @Override public void close() {
        synchronized(ACTIVE) {
            for(String key:keys) {
                Owner owner=ACTIVE.get(key);
                if(owner!=null && owner.thread==Thread.currentThread() && --owner.depth==0) ACTIVE.remove(key);
            }
            keys.clear();
        }
    }
}
