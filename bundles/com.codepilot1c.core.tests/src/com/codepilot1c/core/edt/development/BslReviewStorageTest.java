// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BslReviewStorageTest {
    @Rule public TemporaryFolder sandbox = new TemporaryFolder();

    @Test public void rejectedSaveKeepsOtherSnapshots() throws Exception {
        var folder = sandbox.getRoot().toPath();
        var old = Files.writeString(folder.resolve("turn-00000000-0000-0000-0000-000000000001.json"), "{}");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        var target = folder.resolve("turn-00000000-0000-0000-0000-000000000002.json");

        assertThrows(IllegalStateException.class, () -> BslReviewService.planPrune(folder, target, 64L * 1024 * 1024 + 1));
        assertTrue(Files.exists(old));
        assertEquals(List.of(old), BslReviewService.planPrune(folder, target, 1));
        assertTrue(Files.exists(old));
    }

    @org.junit.Test public void cleanupPreviewExcludesProtectedTurnsAndRefusesAStaleRevision() throws Exception {
        var time = java.time.Instant.parse("2026-11-23T08:00:00Z");
        var first = new AgentHistoryService.Entry("turn-first", "before", time, false);
        var active = new AgentHistoryService.Entry("turn-open", "same", time, true);
        var rows = java.util.List.of(first, active);
        var preview = AgentHistoryService.cleanupEntries(rows, null, true, null, id -> {
            throw new AssertionError("Preview must not delete anything");
        });
        org.junit.Assert.assertEquals(1, preview.get("eligible_count"));
        org.junit.Assert.assertEquals(1, preview.get("protected_count"));
        var replaced = new AgentHistoryService.Entry(first.id(), "after", time, false);
        String expected = (String) preview.get("preview_sha256");
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () ->
                AgentHistoryService.cleanupEntries(java.util.List.of(replaced, active), null, true, expected, id -> true));
        var removed = new java.util.ArrayList<String>();
        var receipt = AgentHistoryService.cleanupEntries(rows, null, true, expected, removed::add);
        org.junit.Assert.assertEquals(java.util.List.of(first.id()), removed);
        org.junit.Assert.assertEquals(1, receipt.get("removed_count"));
    }

    @org.junit.Test public void messagesAcceptOnlyTypedWorkspaceNavigation() {
        var target = AgentHistoryService.target("code", "editor", "Project", "src/Module.bsl",
                null, "Open", null, null);
        org.junit.Assert.assertEquals("Open", target.method());
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () ->
                AgentHistoryService.target("bad", "editor", "Project", "../Module.bsl", 1, null, null, null));
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () ->
                AgentHistoryService.target("bad", "editor", "Project", "src/Form.form", 1, null, null, null));
    }
}
