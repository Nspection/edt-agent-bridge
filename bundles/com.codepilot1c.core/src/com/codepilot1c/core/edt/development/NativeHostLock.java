// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.concurrent.locks.ReentrantLock;
/** Serializes host mutations while allowing nonblocking observation of a busy lifecycle. */
final class NativeHostLock {
    private final ReentrantLock lock=new ReentrantLock(true);
    Scope hold() throws InterruptedException {lock.lockInterruptibly();return new Scope();}
    Scope tryHold() throws InterruptedException {return lock.tryLock(0,java.util.concurrent.TimeUnit.NANOSECONDS)?new Scope():null;}
    final class Scope implements AutoCloseable {
        private boolean closed;
        public void close(){if(!closed){closed=true;lock.unlock();}}
    }
}
