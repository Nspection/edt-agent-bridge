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
}
