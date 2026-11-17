package com.codepilot1c.core.edt.development;
import org.junit.Test;import static org.junit.Assert.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.nio.file.*;
public class ProcessCaptureLeaseTest {
 @Test public void timeoutDoesNotReleaseStillRunningNativeWorkerGate()throws Exception {
  var clock=new AtomicLong();var lease=ProcessCaptureLease.acquire("owned-blocked",clock::get,5);var started=new CountDownLatch(1);var finish=new CountDownLatch(1);var exited=new CountDownLatch(1);var error=new AtomicReference<Throwable>();
  var worker=new Thread(()->{try(lease){started.countDown();finish.await();assertThrows(CancellationException.class,lease::complete);}catch(Throwable problem){error.set(problem);}finally{exited.countDown();}});worker.setDaemon(true);worker.start();
  try{assertTrue(started.await(1,TimeUnit.SECONDS));lease.cancel();assertThrows(IllegalStateException.class,()->ProcessCaptureLease.acquire("owned-blocked",clock::get,5));assertThrows(CancellationException.class,lease::check);}finally{finish.countDown();}
  assertTrue(exited.await(1,TimeUnit.SECONDS));assertNull(error.get());try(var next=ProcessCaptureLease.acquire("owned-blocked",clock::get,5)){next.check();}
 }
 @Test public void lateOrFailedWriterRemovesOnlyItsOwnImages()throws Exception {var folder=Files.createTempDirectory("capture-lease-");var own=folder.resolve("own.png");var retained=folder.resolve("retained.png");Files.writeString(own,"own");Files.writeString(retained,"retained");var clock=new AtomicLong();try(var lease=ProcessCaptureLease.acquire("owned-expired",clock::get,5)){lease.owns(own,folder.resolve("unpublished.png"));clock.set(6);assertThrows(CancellationException.class,lease::check);}assertFalse(Files.exists(own));assertEquals("retained",Files.readString(retained));Files.delete(retained);Files.delete(folder);}

 @Test public void completedFrameNeedsCallerAcceptanceAndSurvivesDeadlineAfterCompletion()throws Exception {
  var dir=Files.createTempDirectory("ready-frame-");var pending=dir.resolve("frame.pending");var image=dir.resolve("frame.png");Files.writeString(pending,"encoded");var clock=new AtomicLong();var lease=ProcessCaptureLease.acquire("pid/start-old",clock::get,5);
  try(var fresh=ProcessCaptureLease.acquire("pid/start-new",clock::get,5)){fresh.complete();}lease.owns(pending,image);clock.set(4);lease.complete();clock.set(6);lease.close();assertTrue(Files.exists(pending));assertFalse(Files.exists(image));lease.accept();assertTrue(Files.exists(image));assertFalse(Files.exists(pending));lease.cancel();assertTrue(Files.exists(image));Files.delete(image);Files.delete(dir);
 }
 @Test public void timeoutAfterWorkerCloseNeverPublishesOrLeaksUnreturnedFrame()throws Exception {
  var dir=Files.createTempDirectory("unreturned-frame-");var pending=dir.resolve("frame.pending");var image=dir.resolve("frame.png");Files.writeString(pending,"encoded");var clock=new AtomicLong();var lease=ProcessCaptureLease.acquire("closed-before-timeout",clock::get,5);lease.owns(pending,image);lease.complete();lease.close();lease.cancel();assertFalse(Files.exists(pending));assertFalse(Files.exists(image));assertThrows(CancellationException.class,lease::accept);Files.delete(dir);
 }
 @Test public void failedPopupImageIsDiscardedWithoutLosingSuccessfulRoot()throws Exception {
  var dir=Files.createTempDirectory("partial-frame-");var root=dir.resolve("root.pending");var bad=dir.resolve("bad.pending");Files.writeString(root,"encoded root");Files.writeString(bad,"partial popup");var clock=new AtomicLong();var lease=ProcessCaptureLease.acquire("partial-snapshot",clock::get,5);lease.owns(root,dir.resolve("root.png"));lease.owns(bad,dir.resolve("bad.png"));lease.discard(bad);lease.complete();lease.close();lease.accept();assertTrue(Files.exists(dir.resolve("root.png")));assertFalse(Files.exists(bad));assertFalse(Files.exists(dir.resolve("bad.png")));Files.delete(dir.resolve("root.png"));Files.delete(dir);
 }

 @Test public void publicationNeverOverwritesAnUnownedTarget()throws Exception {
  var dir=Files.createTempDirectory("collision-frame-");var pending=dir.resolve("frame.pending");var image=dir.resolve("frame.png");Files.writeString(pending,"owned");Files.writeString(image,"foreign");var lease=ProcessCaptureLease.acquire("target-collision",System::nanoTime,TimeUnit.SECONDS.toNanos(5));lease.owns(pending,image);lease.complete();lease.close();assertThrows(java.nio.file.FileAlreadyExistsException.class,lease::accept);assertEquals("foreign",Files.readString(image));assertFalse(Files.exists(pending));Files.delete(image);Files.delete(dir);
 }
}
