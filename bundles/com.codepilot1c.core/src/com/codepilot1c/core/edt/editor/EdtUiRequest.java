package com.codepilot1c.core.edt.editor;

import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes workbench operations through delayed capture and expires queued callbacks. */
final class EdtUiRequest<T> implements AutoCloseable {
    private static final ReentrantLock WORKBENCH = new ReentrantLock(true);
    private final CompletableFuture<T> result = new CompletableFuture<>();
    private final long deadline;
    private EdtUiRequest(long deadline) {this.deadline=deadline;}
    static <T> EdtUiRequest<T> acquire(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        long deadline=System.nanoTime()+unit.toNanos(timeout);
        if (!WORKBENCH.tryLock(timeout, unit)) { throw new TimeoutException("EDT UI queue is busy"); }
        return new EdtUiRequest<>(deadline);
    }
    void dispatch(Runnable callback) {
        if (result.isDone()) { return; }
        if (System.nanoTime() >= deadline) {
            result.completeExceptionally(new TimeoutException("EDT UI request expired before execution"));
            return;
        }
        try { callback.run(); } catch (Throwable error) { result.completeExceptionally(error); }
    }
    void complete(T value) { dispatch(() -> result.complete(value)); }
    void fail(Throwable error) { result.completeExceptionally(error); }
    T await() throws InterruptedException, ExecutionException, TimeoutException {return awaitStage(result);}
    <U> U awaitStage(CompletableFuture<U> stage)throws InterruptedException,ExecutionException,TimeoutException {
        try{return stage.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
        catch(InterruptedException|TimeoutException error){stage.cancel(false);result.cancel(false);throw error;}
    }
    @Override public void close() { result.cancel(false); WORKBENCH.unlock(); }
}
