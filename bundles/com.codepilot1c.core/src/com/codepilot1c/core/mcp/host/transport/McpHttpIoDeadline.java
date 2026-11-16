package com.codepilot1c.core.mcp.host.transport;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Interrupts only the worker currently owning this IO phase. Blocking SocketChannel IO
 * closes its channel on interruption. No reflection or JVM-wide HTTP timeout settings. */
final class McpHttpIoDeadline implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final boolean initiallyInterrupted = owner.isInterrupted();
    private ScheduledFuture<?> timer;
    private boolean active = true;
    private boolean expired;

    McpHttpIoDeadline(ScheduledExecutorService scheduler, Duration timeout, Runnable timedOut) {
        timer = scheduler.schedule(() -> {
            synchronized (this) {
                if (!active) return;
                expired = true;
                timedOut.run();
                owner.interrupt();
            }
        }, timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    synchronized boolean expired() { return expired; }

    /** Ends this phase before handing a fully parsed request to business execution. */
    synchronized boolean finish() {
        active = false;
        if (timer != null) timer.cancel(false);
        // Expiry and finish share this monitor: a cancelled timer cannot interrupt the
        // same pool thread after it has moved on to another exchange or an EDT action.
        if (expired && Thread.currentThread() == owner && !initiallyInterrupted) Thread.interrupted();
        return !expired;
    }

    @Override public void close() { finish(); }
}
