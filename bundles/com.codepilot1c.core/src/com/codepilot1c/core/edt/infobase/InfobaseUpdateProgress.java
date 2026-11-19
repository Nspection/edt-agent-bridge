// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.ProgressMonitorWrapper;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.ui.PlatformUI;

/** Registers the workbench progress of an update whose resource leases belong to its caller. */
public final class InfobaseUpdateProgress {
    private static final IStatus FAILED = new Status(IStatus.ERROR, "com.codepilot1c.core", "Infobase update failed");
    @FunctionalInterface
    public interface Update {
        boolean run(IProgressMonitor monitor) throws Exception;
    }

    private InfobaseUpdateProgress() {}

    public static boolean run(String name, IProgressMonitor caller, Update update) throws Exception {
        IProgressMonitor parent = caller == null ? new NullProgressMonitor() : caller;
        return PlatformUI.isWorkbenchRunning() ? runVisible(name, parent, update) : update.run(parent);
    }

    static boolean runVisible(String name, IProgressMonitor parent, Update update) throws Exception {
        var ready = new CompletableFuture<IProgressMonitor>();
        var finished = new CompletableFuture<IStatus>();
        // The database work and callbacks stay on the caller thread. This Eclipse job supplies
        // the workbench monitor and remains visible until the caller has a terminal result.
        Job job = Job.create(name, (IProgressMonitor eclipseMonitor) -> {
            ready.complete(eclipseMonitor);
            return finished.join();
        });
        job.addJobChangeListener(new JobChangeAdapter() {
            @Override public void done(IJobChangeEvent event) {
                // A Job can be canceled while it is still queued, before there is a monitor.
                ready.completeExceptionally(new OperationCanceledException());
            }
        });
        job.schedule();
        IStatus outcome = Status.CANCEL_STATUS;
        try {
            var monitor = new UpdateMonitor(ready.get(), parent);
            if (monitor.isCanceled()) throw new OperationCanceledException();
            boolean updated = update.run(monitor);
            outcome = updated ? Status.OK_STATUS : monitor.isCanceled() ? Status.CANCEL_STATUS : FAILED;
            return updated;
        } catch (ExecutionException canceledBeforeStart) {
            throw new OperationCanceledException();
        } catch (OperationCanceledException | InterruptedException canceled) {
            throw canceled;
        } catch (Exception | Error failure) {
            outcome = FAILED;
            throw failure;
        } finally {
            finished.complete(outcome);
            if (!ready.isDone()) job.cancel();
        }
    }

    private static final class UpdateMonitor extends ProgressMonitorWrapper {
        private final IProgressMonitor parent;

        UpdateMonitor(IProgressMonitor visible, IProgressMonitor parent) {
            super(visible);
            this.parent = parent;
        }

        @Override public boolean isCanceled() {
            return super.isCanceled() || parent.isCanceled();
        }

        @Override public void setCanceled(boolean canceled) {
            super.setCanceled(canceled);
            parent.setCanceled(canceled);
        }

        @Override public void beginTask(String name, int totalWork) {
            super.beginTask(name, totalWork);
            parent.beginTask(name, totalWork);
        }

        @Override public void subTask(String name) {
            super.subTask(name);
            parent.subTask(name);
        }

        @Override public void setTaskName(String name) {
            super.setTaskName(name);
            parent.setTaskName(name);
        }

        @Override public void worked(int work) {
            super.worked(work);
            parent.worked(work);
        }

        @Override public void internalWorked(double work) {
            super.internalWorked(work);
            parent.internalWorked(work);
        }

        @Override public void setBlocked(IStatus reason) {
            super.setBlocked(reason);
            parent.setBlocked(reason);
        }

        @Override public void clearBlocked() {
            super.clearBlocked();
            parent.clearBlocked();
        }

        @Override public void done() {
            super.done();
            parent.done();
        }
    }
}
