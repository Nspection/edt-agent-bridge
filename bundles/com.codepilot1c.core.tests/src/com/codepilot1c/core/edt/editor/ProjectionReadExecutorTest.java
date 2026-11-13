// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.editor;
import static org.junit.Assert.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import com.codepilot1c.core.edt.development.RuntimeProcessLifecycle;
public class ProjectionReadExecutorTest {
 @Test public void timeoutCancelsReaderPreventsLateRenderAndReleasesUiGate()throws Exception{
  var life=new RuntimeProcessLifecycle();var reads=new ProjectionReadExecutor(life);var release=new CountDownLatch(1);var started=new CountDownLatch(1);var render=new AtomicInteger();
  try{
   try(var request=EdtUiRequest.<Integer>acquire(100,TimeUnit.MILLISECONDS)){
    var stage=reads.submit(life.epoch(),()->{started.countDown();try{release.await();}catch(InterruptedException ignored){}return 1;});assertTrue(started.await(1,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->request.awaitStage(stage));assertTrue(stage.isCancelled());release.countDown();request.dispatch(render::incrementAndGet);assertEquals(0,render.get());
   }
   try(var next=EdtUiRequest.<Integer>acquire(1,TimeUnit.SECONDS)){next.complete(2);assertEquals(Integer.valueOf(2),next.await());}
  }finally{release.countDown();life.shutdown();}
 }
 @Test public void stoppedEpochCannotDeliverUncooperativeReadAndNewEpochWorks()throws Exception{
  var life=new RuntimeProcessLifecycle();var reads=new ProjectionReadExecutor(life);var release=new CountDownLatch(1);var started=new CountDownLatch(1);long old=life.epoch();
  var stage=reads.submit(old,()->{started.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}return 1;});assertTrue(started.await(1,TimeUnit.SECONDS));life.shutdown();life.begin();release.countDown();assertThrows(ExecutionException.class,()->stage.get(1,TimeUnit.SECONDS));assertThrows(IllegalStateException.class,()->reads.submit(old,()->2));assertEquals(Integer.valueOf(3),reads.submit(life.epoch(),()->3).get(1,TimeUnit.SECONDS));life.shutdown();
 }
 @Test public void readerPoolHasBoundedQueueAndFailsClosedWhenSaturated()throws Exception{
  var life=new RuntimeProcessLifecycle();var reads=new ProjectionReadExecutor(life);var release=new CountDownLatch(1);var started=new CountDownLatch(2);
  try{for(int i=0;i<2;i++)reads.submit(life.epoch(),()->{started.countDown();release.await();return 1;});assertTrue(started.await(1,TimeUnit.SECONDS));for(int i=0;i<2;i++)reads.submit(life.epoch(),()->1);assertThrows(FormProjectionGeneration.Unverified.class,()->reads.submit(life.epoch(),()->1));}finally{release.countDown();life.shutdown();}
 }
 @Test public void sourceStagesShareOneDeadline()throws Exception{
  try(var request=EdtUiRequest.<Integer>acquire(80,TimeUnit.MILLISECONDS)){
   var first=new CompletableFuture<Integer>();var thread=new Thread(()->{try{Thread.sleep(50);first.complete(1);}catch(InterruptedException ignored){}});thread.start();assertEquals(Integer.valueOf(1),request.awaitStage(first));long start=System.nanoTime();assertThrows(TimeoutException.class,()->request.awaitStage(new CompletableFuture<Integer>()));assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<70);thread.join();
  }
 }
}
