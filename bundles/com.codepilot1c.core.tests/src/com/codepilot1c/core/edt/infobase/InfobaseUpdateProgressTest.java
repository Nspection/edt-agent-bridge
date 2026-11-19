// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.Test;

public class InfobaseUpdateProgressTest {
    private static Job runningJob(String label) {
        return Arrays.stream(Job.getJobManager().find(null))
            .filter(job -> job.getName().equals(label)).findFirst().orElseThrow();
    }

    @Test(timeout = 10000)
    public void visibleJobUsesCallerThreadAndPreservesProgressAndResult() throws Exception {
        String label = "MCP progress test (success)";
        Thread caller = Thread.currentThread();
        var job = new AtomicReference<Job>();
        var parent = new NullProgressMonitor() {
            private int units;

            @Override public void worked(int work) { units += work; }
        };
        assertTrue(InfobaseUpdateProgress.runVisible(label, parent, progress -> {
            job.set(runningJob(label));
            assertFalse(job.get().isSystem());
            assertSame(caller, Thread.currentThread());
            progress.beginTask("Native update", 10);
            progress.worked(5);
            progress.done();
            return true;
        }));
        job.get().join();
        assertTrue(job.get().getResult().isOK());
        assertEquals(5, parent.units);
    }

    @Test(timeout = 10000)
    public void cancelingEclipseJobReachesSameOperation() throws Exception {
        String label = "MCP progress test (cancel)";
        var job = new AtomicReference<Job>();
        assertThrows(OperationCanceledException.class, () ->
            InfobaseUpdateProgress.runVisible(label, new NullProgressMonitor(), progress -> {
                job.set(runningJob(label));
                assertFalse(job.get().cancel());
                assertTrue(progress.isCanceled());
                throw new OperationCanceledException();
            }));
        job.get().join();
        assertEquals(IStatus.CANCEL, job.get().getResult().getSeverity());
    }
}
