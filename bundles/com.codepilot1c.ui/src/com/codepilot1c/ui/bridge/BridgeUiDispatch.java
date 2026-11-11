// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.ui.bridge;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;

/** Closing a display is a normal lifecycle event, not a failed background operation. */
public final class BridgeUiDispatch {
    private BridgeUiDispatch() { }

    public static boolean enqueue(BooleanSupplier disposed, Consumer<Runnable> queue, Runnable work) {
        if (disposed.getAsBoolean()) return false;
        try {
            queue.accept(() -> { if (!disposed.getAsBoolean()) work.run(); });
            return true;
        } catch (SWTException closed) {
            if (closed.code == SWT.ERROR_DEVICE_DISPOSED) return false;
            throw closed;
        }
    }
}
