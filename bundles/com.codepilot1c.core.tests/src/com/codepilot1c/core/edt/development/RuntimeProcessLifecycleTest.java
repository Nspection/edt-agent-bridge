// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import static org.junit.Assert.*;
import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class RuntimeProcessLifecycleTest {
 @Test public void shutdownRejectsLateLaunchAndOldEpochCannotReturnAfterRestart()throws Exception {
  var lifecycle=new RuntimeProcessLifecycle();long old=lifecycle.epoch();var launches=new AtomicInteger();lifecycle.shutdown();assertThrows(IllegalStateException.class,()->lifecycle.launch(old,launches::incrementAndGet));lifecycle.begin();assertThrows(IllegalStateException.class,()->lifecycle.launch(old,launches::incrementAndGet));lifecycle.launch(lifecycle.epoch(),launches::incrementAndGet);assertEquals(1,launches.get());
 }
 @Test public void alreadyOwnedResourcesCloseExactlyOnceOutsideLaunchGate()throws Exception {
  var lifecycle=new RuntimeProcessLifecycle();var closes=new AtomicInteger();lifecycle.launch(lifecycle.epoch(),()->{lifecycle.own(lifecycle.epoch(),"channel",()->{var observed=new AtomicBoolean();Thread another=new Thread(()->{lifecycle.epoch();observed.set(true);});another.start();another.join(2000);assertTrue(observed.get());closes.incrementAndGet();});return null;});assertTrue(lifecycle.shutdown().isEmpty());assertTrue(lifecycle.shutdown().isEmpty());assertEquals(1,closes.get());
 }
 @Test public void registrationAndLaunchAreAtomicWithShutdown()throws Exception {
  var lifecycle=new RuntimeProcessLifecycle();long epoch=lifecycle.epoch();var starting=new CountDownLatch(1);var release=new CountDownLatch(1);var closed=new AtomicInteger();var error=new AtomicReference<Throwable>();
  Thread launch=new Thread(()->{try{lifecycle.launch(epoch,()->{starting.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));lifecycle.own(epoch,"own-child",closed::incrementAndGet);return null;});}catch(Throwable e){error.set(e);}});launch.start();assertTrue(starting.await(2,TimeUnit.SECONDS));Thread stop=new Thread(lifecycle::shutdown);stop.start();release.countDown();launch.join(2000);stop.join(2000);assertFalse(launch.isAlive());assertFalse(stop.isAlive());assertNull(error.get());assertEquals(1,closed.get());
 }
 @Test public void failedCloseRetainsHandleForAnotherCleanupAttempt()throws Exception {
  var lifecycle=new RuntimeProcessLifecycle();var attempts=new AtomicInteger();lifecycle.own(lifecycle.epoch(),"own-child",()->{if(attempts.incrementAndGet()==1)throw new IllegalStateException("busy");});assertEquals(1,lifecycle.shutdown().size());assertTrue(lifecycle.shutdown().isEmpty());assertEquals(2,attempts.get());
 }
 @Test public void acquiredChannelIsTrackedBeforeLaterManifestFailure()throws Exception{var lifecycle=new RuntimeProcessLifecycle();var closed=new AtomicInteger();lifecycle.create(lifecycle.epoch(),"channel",()->(AutoCloseable)closed::incrementAndGet);try{throw new java.io.IOException("manifest denied");}catch(java.io.IOException expected){lifecycle.closeOwned("channel");}assertEquals(1,closed.get());assertTrue(lifecycle.shutdown().isEmpty());assertEquals(1,closed.get());}
 @Test public void helperClosesBeforeVisibilityAndClient()throws Exception{var lifecycle=new RuntimeProcessLifecycle();var order=new java.util.ArrayList<String>();for(String name:java.util.List.of("client","visibility","helper"))lifecycle.own(lifecycle.epoch(),name,()->order.add(name));assertTrue(lifecycle.shutdown().isEmpty());assertEquals(java.util.List.of("helper","visibility","client"),order);}
}
