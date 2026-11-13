// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class DirectClientLifecycleTest {
 @Test public void stopAfterReservationPreventsLateLaunchAcrossServiceInstances()throws Exception {
  Path client=Files.createTempDirectory("client-lifecycle-");var current=new AtomicReference<Map<String,Object>>(Map.of("state","starting"));var reserved=new CountDownLatch(1);var resume=new CountDownLatch(1);var launches=new AtomicInteger();var failure=new AtomicReference<Throwable>();
  Thread start=new Thread(()->{try{reserved.countDown();resume.await();DirectClientLifecycle.active(client,current::get,()->launches.incrementAndGet());}catch(Throwable e){failure.set(e);}});start.start();assertTrue(reserved.await(2,TimeUnit.SECONDS));
  synchronized(DirectClientLifecycle.lock(client)){current.set(Map.of("state","stopped"));}resume.countDown();start.join(2000);assertFalse(start.isAlive());assertEquals(0,launches.get());assertTrue(failure.get() instanceof DirectClientLifecycle.Stopped);
 }
 @Test public void terminalRecordsCannotBeOverwrittenByLateReadyOrFailure(){
  for(var prior:List.of(Map.<String,Object>of("state","stopped"),Map.<String,Object>of("state","cleaned","cleaned",true)))for(String next:List.of("starting","running","start_failed"))assertThrows(DirectClientLifecycle.Stopped.class,()->DirectClientLifecycle.beforeSave(prior,Map.of("state",next)));
  DirectClientLifecycle.beforeSave(Map.of("state","stopped"),Map.of("state","cleaned","cleaned",true));
 }
 @Test public void processIdentityIsPublishedBeforeStopCanPassLaunchGate()throws Exception {
  var client=Files.createTempDirectory("client-launch-gate-");var current=new AtomicReference<Map<String,Object>>(Map.of("state","starting"));var launched=new CountDownLatch(1);var release=new CountDownLatch(1);var stopped=new AtomicBoolean();
  Thread start=new Thread(()->{try{DirectClientLifecycle.active(client,current::get,()->{launched.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));current.set(Map.of("state","starting","pid",123));return null;});}catch(Exception e){throw new AssertionError(e);}});start.start();assertTrue(launched.await(2,TimeUnit.SECONDS));
  Thread stop=new Thread(()->{synchronized(DirectClientLifecycle.lock(client)){assertEquals(123,current.get().get("pid"));stopped.set(true);current.set(Map.of("state","stopped"));}});stop.start();assertFalse(stopped.get());release.countDown();start.join(2000);stop.join(2000);assertTrue(stopped.get());
 }
 @org.junit.Test public void workerExtractionIncludesPureProtocolDiagnostics()throws Exception {
  var field=DirectTestClientService.class.getDeclaredField("RESOURCES");field.setAccessible(true);
  var resources=(java.util.List<String>)field.get(null);org.junit.Assert.assertTrue(resources.contains("vendor/protocol_diagnostics.py"));
  for(String resource:resources)try(var input=DirectTestClientService.class.getResourceAsStream("/resources/test-client/"+resource)){org.junit.Assert.assertNotNull(resource,input);}
 }

 @Test public void stoppingCannotBeRevivedButStopRetriesAndTerminalSavesAreAllowed(){
  var stopping=Map.<String,Object>of("state","stopping");assertThrows(DirectClientLifecycle.Stopped.class,()->DirectClientLifecycle.requireActive(stopping));
  for(String next:List.of("starting","running","start_failed"))assertThrows(DirectClientLifecycle.Stopped.class,()->DirectClientLifecycle.beforeSave(stopping,Map.of("state",next)));
  for(String next:List.of("stopping","stopped","cleaned"))DirectClientLifecycle.beforeSave(stopping,Map.of("state",next));DirectClientLifecycle.beforeSave(Map.of("state","stopped"),Map.of("state","stopped"));
 }

}
