// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.updates;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.junit.Test;
import com.codepilot1c.ui.bridge.BridgeUiDispatch;
import static org.junit.Assert.*;

public class BridgeUiDispatchTest {
    @Test public void alreadyClosedDisplayDoesNotQueueWork() {
        assertFalse(BridgeUiDispatch.enqueue(() -> true, work -> fail("No queue after close"), () -> fail("No UI work")));
    }
    @Test public void disposalBetweenCheckAndEnqueueIsNotAnError() {
        var disposed = new AtomicBoolean();
        assertFalse(BridgeUiDispatch.enqueue(disposed::get, work -> {
            disposed.set(true); throw new SWTException(SWT.ERROR_DEVICE_DISPOSED);
        }, () -> fail("No UI work")));
    }
    @Test public void queuedCallbackRechecksDisposal() {
        var disposed = new AtomicBoolean();
        var callback = new AtomicReference<Runnable>();
        assertTrue(BridgeUiDispatch.enqueue(disposed::get, callback::set, () -> fail("No UI work after close")));
        disposed.set(true); callback.get().run();
    }
    @Test public void liveCallbackRunsAndOtherFailuresRemainVisible() {
        var ran = new AtomicBoolean();
        assertTrue(BridgeUiDispatch.enqueue(() -> false, Runnable::run, () -> ran.set(true)));
        assertTrue(ran.get());
        assertThrows(SWTException.class, () -> BridgeUiDispatch.enqueue(() -> false,
                work -> { throw new SWTException(SWT.ERROR_THREAD_INVALID_ACCESS); }, () -> { }));
    }
}
