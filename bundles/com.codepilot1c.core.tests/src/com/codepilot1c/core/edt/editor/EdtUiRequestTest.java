package com.codepilot1c.core.edt.editor;
import static org.junit.Assert.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
public class EdtUiRequestTest {
    @Test public void cancelledQueuedActionNeverRunsAfterTimeout() throws Exception {
        AtomicInteger actions=new AtomicInteger();
        try(EdtUiRequest<Integer> r=EdtUiRequest.acquire(20,TimeUnit.MILLISECONDS)) {
            assertThrows(TimeoutException.class,r::await);
            r.dispatch(actions::incrementAndGet);
            assertEquals(0,actions.get());
        }
    }
    @Test public void interruptedWaitCancelsQueuedAction() throws Exception {
        AtomicInteger actions=new AtomicInteger();
        try(EdtUiRequest<Integer> r=EdtUiRequest.acquire(1,TimeUnit.SECONDS)) {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class,r::await);
            r.dispatch(actions::incrementAndGet);
            assertEquals(0,actions.get());
        } finally {Thread.interrupted();}
    }
    @Test public void completedRequestIgnoresLateCallback() throws Exception {
        try(EdtUiRequest<Integer> r=EdtUiRequest.acquire(1,TimeUnit.SECONDS)) {
            r.dispatch(()->r.complete(7));
            r.dispatch(()->fail("Late callback executed"));
            assertEquals(Integer.valueOf(7),r.await());
        }
    }
    @Test public void secondTabSelectionWaitsUntilFirstCaptureFinishes() throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        AtomicInteger tab=new AtomicInteger(); CountDownLatch queued=new CountDownLatch(1);
        Future<Integer> second;
        try {
            try(EdtUiRequest<Integer> first=EdtUiRequest.acquire(2,TimeUnit.SECONDS)) {
                tab.set(1);
                second=worker.submit(()->{
                    queued.countDown();
                    try(EdtUiRequest<Integer> next=EdtUiRequest.acquire(2,TimeUnit.SECONDS)) {
                        next.dispatch(()->{tab.set(2);next.complete(tab.get());});return next.await();
                    }
                });
                assertTrue(queued.await(1,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,()->second.get(50,TimeUnit.MILLISECONDS));
                first.dispatch(()->first.complete(tab.get()));
                assertEquals(Integer.valueOf(1),first.await());
            }
            assertEquals(Integer.valueOf(2),second.get(2,TimeUnit.SECONDS));
        } finally {worker.shutdownNow();}
    }
}
