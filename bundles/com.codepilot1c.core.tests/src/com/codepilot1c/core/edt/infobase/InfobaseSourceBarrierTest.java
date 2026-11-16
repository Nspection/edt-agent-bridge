// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.junit.Test;

public class InfobaseSourceBarrierTest {
    private static class Pipeline implements InfobaseSourceBarrier.Stages {
        final List<String> events = new ArrayList<>();
        int disk = 2, model = 1, queue, revision;
        public void refresh(IProgressMonitor monitor) { events.add("refresh"); queue = disk; }
        public boolean waitDerived(long millis) { events.add("derived"); model = queue; return true; }
        public boolean importSettled(long millis) { events.add("import"); return true; }
        public long revision() { return revision; }
    }

    @Test public void queuedExternalChangeIsAppliedBeforeReadingEqualityOrUpdating() throws Exception {
        var pipeline = new Pipeline();
        assertEquals(1, pipeline.model);
        InfobaseSourceBarrier.await(pipeline, new NullProgressMonitor(), 5000);
        assertEquals(List.of("refresh", "derived", "import"), pipeline.events);
        assertEquals(pipeline.disk, pipeline.model);
    }

    @Test public void changeDuringWaitRequiresSecondDerivedPassWithoutAnotherDiskScan() throws Exception {
        var pipeline = new Pipeline() {
            public boolean importSettled(long millis) {
                if (revision == 0) { disk = queue = 3; revision++; }
                return super.importSettled(millis);
            }
        };
        InfobaseSourceBarrier.await(pipeline, new NullProgressMonitor(), 5000);
        assertEquals(List.of("refresh", "derived", "import", "derived", "import"), pipeline.events);
        assertEquals(pipeline.disk, pipeline.model);
    }

    @Test public void pendingImporterBlocksEvenWhenInitialDerivedWaitCompleted() throws Exception {
        var pipeline = new Pipeline() {
            int checks;
            public boolean importSettled(long millis) { return super.importSettled(millis) && ++checks == 2; }
        };
        InfobaseSourceBarrier.await(pipeline, new NullProgressMonitor(), 5000);
        assertEquals(List.of("refresh", "derived", "import", "derived", "import"), pipeline.events);
    }

    @Test public void timeoutBeforeEqualityDoesNotPublishStaleModel() throws Exception {
        var pipeline = new Pipeline() {
            public boolean waitDerived(long millis) throws RuntimeException {
                try { Thread.sleep(millis + 2); } catch (InterruptedException e) { throw new AssertionError(e); }
                return false;
            }
        };
        var failure = assertThrows(IllegalStateException.class,
                () -> InfobaseSourceBarrier.await(pipeline, new NullProgressMonitor(), 25));
        assertTrue(failure.getMessage().contains("derived_timeout"));
        assertEquals(List.of("refresh"), pipeline.events);
    }

    @Test public void cancellationStopsBeforeRefreshAndBeforeDatabaseDispatch() {
        var monitor = new NullProgressMonitor();
        monitor.setCanceled(true);
        var pipeline = new Pipeline();
        assertThrows(OperationCanceledException.class, () -> InfobaseSourceBarrier.await(pipeline, monitor, 100));
        assertTrue(pipeline.events.isEmpty());
    }
}
