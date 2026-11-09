// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;import java.util.concurrent.atomic.AtomicLong;
public class UiLagMonitorTest {
 @Test public void onePendingProbeDoesNotFloodBlockedUiAndCapturesActiveNames(){
  var clock=new AtomicLong();var posted=new ArrayList<Runnable>();var reports=new ArrayList<Map<String,Object>>();var monitor=new UiLagMonitor(Thread.currentThread(),posted::add,clock::get,reports::add);Runnable finish=UiLagMonitor.track("edt_runtime_host");
  try{monitor.sample();for(int i=0;i<10;i++){clock.addAndGet(250_000_000L);monitor.sample();}assertEquals(1,posted.size());assertEquals(1,reports.size());assertTrue(reports.get(0).toString().contains("edt_runtime_host"));posted.remove(0).run();monitor.sample();assertEquals(1,posted.size());assertEquals(2500L,monitor.view().get("max_lag_ms"));}
  finally{finish.run();monitor.close();}
 }
 @Test public void responsiveUiAndClosedMonitorDoNotReportStalls(){
  var clock=new AtomicLong();var posted=new ArrayList<Runnable>();var reports=new ArrayList<Map<String,Object>>();var monitor=new UiLagMonitor(Thread.currentThread(),posted::add,clock::get,reports::add);
  for(int i=0;i<20;i++){monitor.sample();clock.addAndGet(5_000_000L);posted.remove(0).run();}assertTrue(reports.isEmpty());monitor.close();clock.addAndGet(5_000_000_000L);monitor.sample();assertTrue(posted.isEmpty());
 }
 @Test public void diagnosticHistoryIsBounded(){
  var clock=new AtomicLong();var posted=new ArrayList<Runnable>();var monitor=new UiLagMonitor(Thread.currentThread(),posted::add,clock::get,x->{});
  for(int i=0;i<40;i++){monitor.sample();clock.addAndGet(2_000_000_000L);monitor.sample();posted.remove(0).run();}assertEquals(32,((List<?>)monitor.view().get("events")).size());monitor.close();
 }
}
