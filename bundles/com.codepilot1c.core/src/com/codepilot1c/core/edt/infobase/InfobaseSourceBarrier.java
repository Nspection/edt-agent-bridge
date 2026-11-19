// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.ProgressMonitorWrapper;
import com._1c.g5.v8.bm.integration.IBmProjectProvider;
import com._1c.g5.v8.bm.integration.IBmWorkspaceSynchronizationListener;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com._1c.g5.v8.dt.platform.version.Version;

/** Publish external resource changes and join this project's native incremental import pipeline. */
public final class InfobaseSourceBarrier {
    private static final long TIMEOUT_MS = 60_000;
    private static final int MAX_PREFLIGHT_PASSES = 3;

    public static final class PreflightBlocked extends IllegalStateException {
        PreflightBlocked(String message) { super(message); }
    }

    interface Stages {
        void refresh(IProgressMonitor monitor) throws Exception;
        boolean waitDerived(long millis) throws InterruptedException;
        boolean importSettled(long millis);
        long revision();
    }

    public static PreflightBlocked updateRuntimeUnavailable() {
        return InfobaseSourcePreflight.blocked("update_runtime_unavailable");
    }

    static void await(IProject project, IProgressMonitor monitor) throws Exception {
        await(project, monitor, false, null);
    }

    public static void verifyUpdate(IProject project, IProgressMonitor monitor, Version updateVersion) throws Exception {
        await(project, monitor, true, updateVersion);
    }

    private static void await(IProject project, IProgressMonitor monitor, boolean verify, Version updateVersion) throws Exception {
        var gateway = new EdtMetadataGateway();
        var dt = gateway.getDtProjectManager().getDtProject(project);
        var models = gateway.getBmModelManager();
        var model = models.getModel(project);
        if (dt == null || model == null || model.isDisposed()
                || !(model.getProvider(IBmProjectProvider.PROVIDER_ID) instanceof IBmProjectProvider provider)) {
            throw blocked("model_unavailable");
        }
        var derived = gateway.getDerivedDataManagerProvider().get(dt);
        if (derived == null) throw blocked("derived_unavailable");

        // The project provider reports running imports. Workspace changes can already be queued
        // before a worker starts. EDT registers those changes in the derived SYNC segments
        // synchronously while publishing the delta. Join those segments even for an idle provider.
        var revision = new AtomicLong();
        var pending = new java.util.concurrent.atomic.AtomicBoolean();
        var listener = new IBmWorkspaceSynchronizationListener() {
            @Override public void handleInSync(boolean importing, List<IResource> affected) {
                pending.set(importing);
                revision.incrementAndGet();
            }
        };
        model.addResourceSyncListener(listener);
        try {
            var pipeline = new Stages() {
                public void refresh(IProgressMonitor progress) throws Exception {
                    project.refreshLocal(IResource.DEPTH_INFINITE, progress);
                }
                public boolean waitDerived(long millis) throws InterruptedException {
                    // Native incremental phase includes SYNC, AFTER_SYNC and BEFORE_BUILD.
                    return derived.waitImportantDataComputations(millis);
                }
                public boolean importSettled(long millis) {
                    if (model.isDisposed() || models.getModel(project) != model) throw blocked("model_changed");
                    provider.waitProcessing(millis);
                    return !provider.isSyncActive() && !pending.get();
                }
                public long revision() { return revision.get(); }
            };
            for (int pass = 0; pass < MAX_PREFLIGHT_PASSES; pass++) {
                await(pipeline, monitor, TIMEOUT_MS);
                if (!verify) return;
                long checkedRevision = revision.get();
                PreflightBlocked blocked = null;
                try {
                    InfobaseSourcePreflight.verify(project, gateway, monitor, updateVersion);
                } catch (OperationCanceledException cancelled) {
                    throw cancelled;
                } catch (PreflightBlocked failure) {
                    blocked = failure;
                } catch (Exception | LinkageError unavailable) {
                    blocked = InfobaseSourcePreflight.blocked("inventory_verification_failed");
                }
                if (model.isDisposed() || models.getModel(project) != model) {
                    throw InfobaseSourcePreflight.blocked("model_changed_during_preflight");
                }
                if (!pending.get() && !provider.isSyncActive() && revision.get() == checkedRevision) {
                    if (blocked != null) throw blocked;
                    return;
                }
            }
            throw InfobaseSourcePreflight.blocked("model_changed_during_preflight");
        } finally {
            model.removeResourceSyncListener(listener);
        }
    }

    static void await(Stages stages, IProgressMonitor monitor, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        var progress = new ProgressMonitorWrapper(monitor) {
            @Override public boolean isCanceled() {
                return super.isCanceled() || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline;
            }
        };
        remaining(deadline, monitor, "refresh");
        stages.refresh(progress);
        while (true) {
            long version = stages.revision();
            long left = remaining(deadline, monitor, "derived");
            if (stages.waitDerived(Math.min(left, 1000))) {
                left = remaining(deadline, monitor, "import");
                boolean settled = stages.importSettled(Math.min(left, 250));
                remaining(deadline, monitor, "import");
                if (settled && version == stages.revision()) return;
                Thread.sleep(Math.min(50, left));
            }
        }
    }

    private static long remaining(long deadline, IProgressMonitor monitor, String phase) {
        if (monitor.isCanceled() || Thread.currentThread().isInterrupted()) throw new OperationCanceledException();
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw blocked(phase + "_timeout");
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos));
    }

    private static IllegalStateException blocked(String reason) {
        return new IllegalStateException("PROJECT_SOURCE_SYNC_BLOCKED: " + reason + "; infobase operation not started");
    }
}
