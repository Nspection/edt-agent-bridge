// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
/** Native work retains its process gate until close. Only a caller that received the Future may publish PNGs. */
final class ProcessCaptureLease implements AutoCloseable {
    private static final ConcurrentHashMap<String,ProcessCaptureLease> ACTIVE=new ConcurrentHashMap<>();
    private static final class Frame {final Path pending,target;boolean discarded,published;Frame(Path pending,Path target){this.pending=pending;this.target=target;}}
    private final String key;private final LongSupplier clock;private final long deadline;private final List<Frame> frames=new ArrayList<>();
    private volatile boolean cancelled;private boolean completed,closed,accepting,accepted;
    private ProcessCaptureLease(String key,LongSupplier clock,long duration){this.key=key;this.clock=clock;deadline=clock.getAsLong()+duration;}
    static ProcessCaptureLease acquire(String key,LongSupplier clock,long duration){var lease=new ProcessCaptureLease(key,clock,duration);if(ACTIVE.putIfAbsent(key,lease)!=null)throw new IllegalStateException("A capture for this process is still running; wait for the native worker to drain");return lease;}
    void cancel(){List<Frame> cleanup=null;synchronized(this){if(accepted)return;cancelled=true;if(closed&&!accepting)cleanup=List.copyOf(frames);}if(cleanup!=null)cleanup(cleanup);}
    void check(){if(cancelled || clock.getAsLong()-deadline>=0)throw new CancellationException("Window capture deadline expired");}
    synchronized void owns(Path pending,Path target){if(closed)throw new IllegalStateException("Capture worker is closed");frames.add(new Frame(pending,target));check();}
    void discard(Path pending){Frame frame;synchronized(this){frame=frames.stream().filter(f->f.pending.equals(pending)).findFirst().orElseThrow();frame.discarded=true;}cleanup(List.of(frame));}
    synchronized void complete(){check();completed=true;}
    void accept()throws Exception {
        List<Frame> publish;synchronized(this){if(!closed||!completed||cancelled||accepted)throw new CancellationException("Capture result cannot be published");accepting=true;publish=frames.stream().filter(f->!f.discarded).toList();}
        try {
            for(var frame:publish){if(cancelled)throw new CancellationException("Capture publication cancelled");Files.move(frame.pending,frame.target);frame.published=true;}
            synchronized(this){if(cancelled)throw new CancellationException("Capture publication cancelled");accepted=true;}
        }catch(Exception error){cancelled=true;cleanup(publish);throw error;}
        finally{synchronized(this){accepting=false;}if(cancelled)cleanup(publish);}
    }
    public void close(){List<Frame> cleanup;synchronized(this){closed=true;cleanup=frames.stream().filter(f->!completed||cancelled||f.discarded).toList();}try{cleanup(cleanup);}finally{ACTIVE.remove(key,this);}}
    private static void cleanup(List<Frame> frames){for(var frame:frames){try{Files.deleteIfExists(frame.pending);}catch(Exception retained){/* Retain locked owned evidence; never delete outside the recorded frame. */}if(frame.published)try{Files.deleteIfExists(frame.target);}catch(Exception retained){/* Same owned publication only. */}}}
}
