// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.observability;

import static org.junit.Assert.assertEquals;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class EdtLogTailServiceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void omitsPlatformLaunchLinesEvenWhenQueriedByTheirContents() throws Exception {
        var root = temp.getRoot().toPath();
        var metadata = Files.createDirectory(root.resolve(".metadata"));
        Files.write(metadata.resolve(".log"), List.of(
                "[INFO] visible diagnostic",
                "!MESSAGE Executing 1C:Enterprise runtime command: designer /N TEST_USER /P TEST_PASSWORD",
                "[ERROR] failed process C:\\Platform\\1cv8.exe DESIGNER /P \"TEST_PASSWORD\""));
        var log = new EdtLogTailService(root);
        assertEquals(List.of("[INFO] visible diagnostic"), log.tail(null).lines().stream().map(EdtLogLine::text).toList());
        assertEquals(0, log.tail(new EdtLogTailService.Request("", "", "TEST_PASSWORD", 0, "", false, 10)).lines().size());
    }
}
