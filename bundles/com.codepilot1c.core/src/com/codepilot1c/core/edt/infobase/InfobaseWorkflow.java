// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import com.google.gson.Gson;
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
    private Path restoreJournal;
    private String restoreStage;
    private long stageStarted;
    private boolean restoreFinished;
    private static final Gson JSON = new Gson();

    private final Map<String,Object> details=new LinkedHashMap<>();
    public synchronized void details(Map<String,Object> value) {details.putAll(value);}
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
    synchronized void startRestoreJournal(Path journal) throws IOException {
        Files.createFile(journal);
        restoreJournal = journal;
        details.put("restore_journal", journal.toString());
    }

    synchronized void finishRestore(String outcome) {
        if (restoreJournal == null || restoreFinished) return;
        restoreFinished = true;
        writeRestoreEvent("finished", outcome);
    }

    // Build the record from controlled stage/outcome identifiers only. Progress, arguments,
    // native exceptions and EDT access prompts can all contain data outside this journal's scope.
    private void writeRestoreEvent(String event, String outcome) {
        if (restoreJournal == null || restoreStage == null) return;
        var record = new LinkedHashMap<String,Object>();
        record.put("event", event);
        record.put("stage", restoreStage);
        record.put("timestamp", Instant.now().toString());
        if (outcome != null) {
            record.put("outcome", outcome);
            record.put("duration_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stageStarted));
        }
        try {
            Files.writeString(restoreJournal, JSON.toJson(record) + "\n", StandardOpenOption.APPEND,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | SecurityException failure) {
            // Report a diagnostic failure without changing a confirmed or unknown import outcome.
            details.put("restore_journal_write_failed", true);
        }
    }

    public synchronized void stage(String name) {
        if (restoreJournal != null && !restoreFinished) {
            writeRestoreEvent("stage_finished", "advanced");
            restoreStage = name;
            stageStarted = System.nanoTime();
            writeRestoreEvent("stage_started", null);
        }
        history.add(Map.of("stage",name,"started_at",Instant.now().toString()));
        signal(Map.of("stage",name));
    }
    synchronized void signal(Map<String,Object> current) {
        var snapshot=new LinkedHashMap<String,Object>(details);
        snapshot.putAll(current);
        snapshot.put("history",List.copyOf(history));
        progress.accept(snapshot);
    }
    @Override public void close() {
        finishRestore("failed");
        synchronized(ACTIVE) {
            for(String key:keys) {
                Owner owner=ACTIVE.get(key);
                if(owner!=null && owner.thread==Thread.currentThread() && --owner.depth==0) ACTIVE.remove(key);
            }
            keys.clear();
        }
    }
}
