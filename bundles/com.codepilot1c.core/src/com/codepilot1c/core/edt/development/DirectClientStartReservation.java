// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** A capacity slot exists before consent is consumed; no SDK or per-client lock under the gate. */
public final class DirectClientStartReservation implements AutoCloseable {
    private static final Object GATE=new Object();
    private static final int LIMIT=4;
    private final Path directory;
    private final Object fileKey;
    private final Path marker;
    private final String nonce=UUID.randomUUID().toString();
    private boolean committed;

    private DirectClientStartReservation(Path directory)throws Exception {
        this.directory=directory;
        this.fileKey=Files.readAttributes(directory,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
        this.marker=directory.resolve("start-reservation");
        Files.writeString(marker,nonce,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
    }
    static DirectClientStartReservation reserve(Path root)throws Exception {
        root=root.toAbsolutePath().normalize();
        synchronized(GATE){
            if(Files.isSymbolicLink(root)||!root.toRealPath().equals(root))throw new IllegalStateException("Client storage moved");
            var pending=DirectClientStorage.pending(root).stream().filter(path->{try{return !DirectClientStorage.cleaned(path);}catch(Exception unreadable){return true;}}).sorted().toList();
            if(pending.size()>=LIMIT)throw new CapacityBlocked(pending);
            Path directory=Files.createDirectory(root.resolve(UUID.randomUUID().toString().replace("-","")));
            return new DirectClientStartReservation(directory);
        }
    }
    String id(){return directory.getFileName().toString();}
    void commit()throws Exception {verify();Files.delete(marker);committed=true;}
    private void verify()throws Exception {
        var attributes=Files.readAttributes(directory,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isDirectory()||Files.isSymbolicLink(directory)||!directory.toRealPath().equals(directory)||fileKey!=null&&!fileKey.equals(attributes.fileKey())
                ||Files.isSymbolicLink(marker)||!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)||Files.size(marker)!=nonce.length()||!nonce.equals(Files.readString(marker)))
            throw new IllegalStateException("Uncommitted client reservation changed; preserved");
    }
    @Override public void close()throws Exception {
        if(committed)return;
        verify();Files.delete(marker);
        // Both removals target our own names; directory deletion is deliberately nonrecursive.
        Files.delete(directory);
    }
    public static final class CapacityBlocked extends IllegalStateException {
        private final Map<String,Object> result;
        CapacityBlocked(List<Path> pending){
            super("Clean up existing direct clients before starting another");
            var blockers=new ArrayList<Map<String,Object>>();
            for(Path folder:pending.stream().limit(32).toList()){
                String id=folder.getFileName().toString();var row=new LinkedHashMap<String,Object>();
                row.put("client_id",id);row.put("state","unproven");row.put("record_verified",false);row.put("process_liveness_checked",false);
                try{
                    Path receipt=folder.resolve("client.json");
                    if(!Files.isSymbolicLink(folder)&&Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(receipt)&&Files.isRegularFile(receipt,LinkOption.NOFOLLOW_LINKS)&&Files.size(receipt)<=32768){
                        var state=PythonEnvironmentService.JSON.fromJson(Files.readString(receipt),Map.class);
                        if(state!=null&&id.equals(state.get("client_id"))&&Set.of("starting","running","start_failed","stopping","stopped").contains(state.get("state"))){
                            row.put("state",state.get("state"));row.put("record_verified",true);
                            row.put("next_calls",List.of(RuntimeConsentPolicy.call("edt_test_client",Map.of("action","status","client_id",id)),RuntimeConsentPolicy.call("edt_test_client",Map.of("action","cleanup","client_id",id))));
                        }
                    }
                }catch(Exception unavailable){/* The directory still occupies capacity; do not invent a safe cleanup. */}
                blockers.add(Collections.unmodifiableMap(row));
            }
            var out=new LinkedHashMap<String,Object>();
            out.put("success",false);out.put("state","capacity_blocked");out.put("reason","direct_client_cleanup_required");out.put("message",getMessage());
            out.put("process_started",false);out.put("action_dispatched",false);out.put("execution_state","launch_not_dispatched");out.put("outcome_unknown",false);out.put("retry_safe",true);out.put("consent_consumed",false);
            out.put("capacity_limit",LIMIT);out.put("blocking_client_count",pending.size());out.put("blocking_clients",List.copyOf(blockers));out.put("blocking_clients_truncated",pending.size()>blockers.size());
            result=Collections.unmodifiableMap(out);
        }
        public Map<String,Object> result(){return result;}
    }
}
