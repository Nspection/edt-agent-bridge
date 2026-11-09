// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import com.google.gson.Gson;
/** One outstanding UI heartbeat; bounded diagnostics contain names/stacks, never payloads. */
public final class UiLagMonitor implements AutoCloseable {
    private static final Map<Long,String> ACTIVE=new ConcurrentHashMap<>();
    private static final AtomicLong IDS=new AtomicLong();
    private static volatile UiLagMonitor instance;
    private final Thread ui;
    private final Consumer<Runnable> post;
    private final LongSupplier clock;
    private final Consumer<Map<String,Object>> sink;
    private final List<Map<String,Object>> events=new ArrayList<>();
    private ScheduledExecutorService timer;
    private long sentAt,lastAck,maxLag;
    private boolean pending,reported,closed;
    UiLagMonitor(Thread ui,Consumer<Runnable> post,LongSupplier clock,Consumer<Map<String,Object>> sink){this.ui=ui;this.post=post;this.clock=clock;this.sink=sink;}
    public static Runnable track(String name){
        if(name==null||!name.matches("[A-Za-z0-9_.:-]{1,100}"))return ()->{};
        long id=IDS.incrementAndGet();if(ACTIVE.size()>=256)return ()->{};ACTIVE.put(id,name);
        return ()->ACTIVE.remove(id);
    }
    public static synchronized void start(Thread ui,Consumer<Runnable> post,Path report){
        stop();
        UiLagMonitor monitor=new UiLagMonitor(ui,post,System::nanoTime,value->{
            try{Files.createDirectories(report.getParent());Path tmp=report.resolveSibling(report.getFileName()+".tmp");Files.writeString(tmp,new Gson().toJson(value));Files.move(tmp,report,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(Exception ignored){/* Diagnostics must not affect execution. */}
        });
        monitor.timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"bridge-ui-heartbeat");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;});
        instance=monitor;monitor.timer.scheduleWithFixedDelay(()->{try{monitor.sample();}catch(RuntimeException ignored){}},250,250,TimeUnit.MILLISECONDS);
    }
    public static synchronized void stop(){UiLagMonitor old=instance;instance=null;if(old!=null)old.close();}
    public static Map<String,Object> snapshot(){UiLagMonitor current=instance;return current==null?Map.of("enabled",false):current.view();}
    synchronized Map<String,Object> view(){return Map.of("enabled",!closed,"pending_heartbeat",pending,"last_ack_nanos",lastAck,"max_lag_ms",maxLag,"events",List.copyOf(events),"active_operations",List.copyOf(ACTIVE.values()),"payloads_retained",false);}
    void sample(){
        boolean enqueue=false,save=false;
        synchronized(this){
            if(closed)return;long now=clock.getAsLong();
            if(!pending){pending=true;reported=false;sentAt=now;enqueue=true;}
            else {
                long lag=TimeUnit.NANOSECONDS.toMillis(now-sentAt);maxLag=Math.max(maxLag,lag);
                if(lag>=1500&&!reported){
                    reported=true;var event=new LinkedHashMap<String,Object>();event.put("detected_at",java.time.Instant.now().toString());event.put("lag_ms",lag);event.put("active_operations",List.copyOf(ACTIVE.values()));
                    event.put("ui_state",ui.getState().name());event.put("ui_stack",Arrays.stream(ui.getStackTrace()).limit(64).map(StackTraceElement::toString).toList());events.add(Collections.unmodifiableMap(event));if(events.size()>32)events.remove(0);save=true;
                }
            }
        }
        if(enqueue)try{post.accept(this::ack);}catch(RuntimeException error){synchronized(this){pending=false;}}
        if(save)sink.accept(view());
    }
    private synchronized void ack(){if(closed)return;long now=clock.getAsLong();maxLag=Math.max(maxLag,TimeUnit.NANOSECONDS.toMillis(now-sentAt));lastAck=now;pending=false;}
    @Override public synchronized void close(){closed=true;pending=false;if(timer!=null)timer.shutdownNow();}
}
