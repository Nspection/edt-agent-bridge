// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
import org.junit.Test;import static org.junit.Assert.*;import java.util.concurrent.*;
public class BackgroundHeavyJobsTest {
 @Test public void nativeJobsSerializeWhileLightweightJobsContinue()throws Exception {
  var registry=BackgroundJobRegistry.createForTest();var firstStarted=new CountDownLatch(1);var releaseFirst=new CountDownLatch(1);var secondStarted=new CountDownLatch(1);var lightStarted=new CountDownLatch(1);
  try{
   String first=registry.startJob("native-host-start",()->{firstStarted.countDown();assertTrue(releaseFirst.await(5,TimeUnit.SECONDS));return "one";});assertTrue(firstStarted.await(2,TimeUnit.SECONDS));
   String second=registry.startJob("infobase-refresh",()->{secondStarted.countDown();return "two";});registry.startJob("light-status",()->{lightStarted.countDown();return "light";});
   assertTrue(lightStarted.await(2,TimeUnit.SECONDS));assertFalse(secondStarted.await(150,TimeUnit.MILLISECONDS));releaseFirst.countDown();assertTrue(secondStarted.await(2,TimeUnit.SECONDS));assertEquals(BackgroundJobRegistry.JobState.DONE,registry.awaitStatus(first,2000).orElseThrow().getState());assertEquals(BackgroundJobRegistry.JobState.DONE,registry.awaitStatus(second,2000).orElseThrow().getState());
  }finally{releaseFirst.countDown();registry.shutdown();}
 }
}
