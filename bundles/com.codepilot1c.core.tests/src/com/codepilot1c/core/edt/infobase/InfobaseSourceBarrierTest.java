// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import com.codepilot1c.core.edt.development.AgentBrief;
import com.codepilot1c.core.edt.development.AgentErrors;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

public class InfobaseSourceBarrierTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
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

    @Test public void warningsProceedToPackageValidationLikeNativeEdt() {
        var monitor = new NullProgressMonitor();
        var warning = new Status(IStatus.WARNING, "edt.export", "Export warning");
        for (var status : List.of(Status.OK_STATUS, Status.info("Informational export status"), warning)) {
            InfobaseSourcePreflight.requireCompletedExport(status, monitor);
        }
        var incompletePackage = assertThrows(InfobaseSourceBarrier.PreflightBlocked.class, () ->
            InfobaseSourcePreflight.verifyPackage(temp.newFolder().toPath(), Map.of()));
        assertTrue(incompletePackage.getMessage().contains("export_manifest_missing"));
        assertThrows(OperationCanceledException.class, () ->
            InfobaseSourcePreflight.requireCompletedExport(Status.CANCEL_STATUS, monitor));
        monitor.setCanceled(true);
        assertThrows(OperationCanceledException.class, () ->
            InfobaseSourcePreflight.requireCompletedExport(warning, monitor));
    }

    @Test public void exportFailureKeepsSafeNestedSdkDetailsInBothReceipts() {
        var cause = new IOException("ZIP output could not be written");
        var failure = new IllegalStateException("launch /P \"fixture password with spaces\"", cause);
        var child = new Status(IStatus.ERROR, "edt.export.xml", 54,
            "Object file does not exist - /Enums/Fixture.xml", failure);
        var status = new MultiStatus("edt.export", 27, new IStatus[] { child }, "Export failed", null);
        var blocked = assertThrows(InfobaseSourceBarrier.PreflightBlocked.class, () ->
            InfobaseSourcePreflight.requireCompletedExport(status, new NullProgressMonitor()));
        assertTrue(blocked.getMessage().contains("native infobase update not dispatched"));
        assertNull(blocked.getCause());

        var details = blocked.sdkExportStatus();
        assertEquals(IStatus.ERROR, details.get("severity"));
        assertEquals("edt.export", details.get("plugin"));
        assertEquals(27, details.get("code"));
        assertEquals("Export failed", details.get("message"));
        var childDetails = (Map<?,?>) ((List<?>) details.get("children")).get(0);
        var exception = (Map<?,?>) childDetails.get("exception");
        assertEquals("[REDACTED]", exception.get("message"));
        assertEquals("ZIP output could not be written", ((Map<?,?>) exception.get("cause")).get("message"));
        assertTrue(childDetails.get("message").toString().contains("/Enums/Fixture.xml"));

        var direct = AgentErrors.describe("update", "update", blocked, false);
        assertEquals(details, direct.get("sdk_export_status"));
        assertEquals("source_preflight", direct.get("stage"));
        var fromMessage = AgentErrors.describe("update", "update", new IllegalStateException(
            blocked.getMessage() + " " + child.getMessage()), false);
        assertEquals("project_update_preflight_blocked", fromMessage.get("reason"));
        assertFalse(fromMessage.containsKey("missing_export_paths"));

        var brief = AgentBrief.operation(Map.of("progress", Map.of("sdk_export_status", details)));
        assertEquals(details, ((Map<?,?>) brief.get("progress")).get("sdk_export_status"));
    }

    @Test public void exportStatusSnapshotBoundsIdsMessagesAndChildren() {
        var child = new Status(IStatus.ERROR, "edt.export", "x".repeat(400));
        var status = new MultiStatus("edt.export", 1, "Export failure", null);
        Collections.nCopies(20, child).forEach(status::add);
        var details = SdkExportStatus.snapshot(status);
        assertTrue((Boolean) details.get("children_truncated"));
        var children = (List<?>) details.get("children");
        assertEquals(7, children.size());
        assertTrue(((Map<?,?>) children.get(0)).get("message").toString().length() < 300);
        var blocked = assertThrows(InfobaseSourceBarrier.PreflightBlocked.class, () ->
            InfobaseSourcePreflight.requireCompletedExport(null, new NullProgressMonitor()));
        assertEquals(Map.of("available", false), blocked.sdkExportStatus());
    }

    @Test public void reportsMissingModelFqnsEvenWhenOtherObjectsArePresent() {
        var failure = assertThrows(IllegalStateException.class,
            () -> InfobaseSourcePreflight.verifyModel(Set.of("Enum.Added", "Constant.Kept"), Set.of("Constant.Kept")));
        assertTrue(failure.getMessage().contains("missing_bm_fqns=[Enum.Added]"));
        assertFalse(failure.getMessage().contains("Constant.Kept"));
    }

    @Test public void staleRemovedObjectAlsoBlocksIncrementalUpdate() {
        var failure = assertThrows(IllegalStateException.class,
            () -> InfobaseSourcePreflight.verifyModel(Set.of("Constant.Kept"),
                Set.of("Constant.Kept", "CommonModule.Removed")));
        assertTrue(failure.getMessage().contains("unexpected_bm_fqns=[CommonModule.Removed]"));
    }

    @Test public void checksPackageManifestAndNestedSubsystemFiles() throws Exception {
        Path root = temp.newFolder().toPath();
        Files.writeString(root.resolve("Configuration.xml"),
            "<MetaDataObject><Configuration><ChildObjects><Subsystem>Parent</Subsystem>"
                + "<Enum>NewOne</Enum></ChildObjects></Configuration></MetaDataObject>");
        Path parent = root.resolve("Subsystems/Parent.xml");
        Path child = root.resolve("Subsystems/Parent/Subsystems/Child.xml");
        Path enumeration = root.resolve("Enums/NewOne.xml");
        Files.createDirectories(child.getParent());
        Files.createDirectories(enumeration.getParent());
        Files.writeString(parent, "<MetaDataObject><Subsystem><ChildObjects><Subsystem>Child</Subsystem>"
            + "</ChildObjects></Subsystem></MetaDataObject>");
        Files.writeString(enumeration, "<MetaDataObject><Enum/></MetaDataObject>");
        Map<String,Path> expected = Map.of("Subsystem.Parent", root.relativize(parent),
            "Subsystem.Parent.Subsystem.Child", root.relativize(child), "Enum.NewOne", root.relativize(enumeration));
        var failure = assertThrows(IllegalStateException.class,
            () -> InfobaseSourcePreflight.verifyPackage(root, expected));
        assertTrue(failure.getMessage().contains("missing_export_fqns=[Subsystem.Parent.Subsystem.Child]"));
        Files.writeString(child, "<MetaDataObject><Subsystem/></MetaDataObject>");
        InfobaseSourcePreflight.verifyPackage(root, expected);
        Files.writeString(root.resolve("Configuration.xml"),
            "<MetaDataObject><Configuration><ChildObjects><Subsystem>Parent</Subsystem>"
                + "<Enum>Different</Enum></ChildObjects></Configuration></MetaDataObject>");
        var stale = assertThrows(IllegalStateException.class,
            () -> InfobaseSourcePreflight.verifyPackage(root, expected));
        assertTrue(stale.getMessage().contains("missing_export_fqns=[Enum.Different, Enum.NewOne]"));
    }
}
