// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.concurrent.TimeUnit;
/** Timings and safe observation flags only: no command line, credentials or window titles. */
public final class StartupTimeline {
 private final LongSupplier clock; private final long began; private long since; private String stage="checking_binding";private boolean running=true;
 private final Map<String,Long> timings=new LinkedHashMap<>();
 public StartupTimeline(){this(System::nanoTime);}
 public StartupTimeline(LongSupplier clock){this.clock=clock;began=since=clock.getAsLong();}
 public void next(String next){long now=clock.getAsLong();timings.merge(stage,TimeUnit.NANOSECONDS.toMillis(Math.max(0,now-since)),Long::sum);since=now;stage=next;}
 public void finish(String finalStage){next(finalStage);running=false;}
 public Map<String,Object> snapshot(){long now=clock.getAsLong();var phases=new LinkedHashMap<>(timings);phases.merge(stage,TimeUnit.NANOSECONDS.toMillis(Math.max(0,now-since)),Long::sum);return describe(stage,TimeUnit.NANOSECONDS.toMillis(Math.max(0,now-began)),phases,running,System.currentTimeMillis());}
 private static Map<String,Object> describe(String stage,long elapsed,Map<String,Long> phases,boolean running,long updated){var out=new LinkedHashMap<String,Object>();out.put("stage",stage);out.put("elapsed_ms",elapsed);out.put("phase_timings_ms",phases);out.put("running",running);out.put("observed_at_ms",updated);out.put("slow_start",elapsed>=10000);out.put("slow_threshold_ms",10000);out.put("slowest_stage",phases.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(stage));return out;}
 public static Map<String,Object> live(Map<String,Object> saved,long now){if(!Boolean.TRUE.equals(saved.get("running")))return saved;String stage=(String)saved.get("stage");long extra=Math.max(0,now-((Number)saved.get("observed_at_ms")).longValue());var phases=new LinkedHashMap<String,Long>();TaskSessionService.object(saved.get("phase_timings_ms")).forEach((k,v)->phases.put(k,((Number)v).longValue()));phases.merge(stage,extra,Long::sum);return describe(stage,((Number)saved.get("elapsed_ms")).longValue()+extra,phases,true,now);}
}
