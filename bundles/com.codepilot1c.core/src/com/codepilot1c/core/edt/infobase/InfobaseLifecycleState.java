// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.codepilot1c.core.internal.VibeCorePlugin;

/** A restore invalidates incremental synchronization history, including across EDT restarts. */
public final class InfobaseLifecycleState {
    private InfobaseLifecycleState() {}
    private static Path marker(InfobaseReference base) throws Exception {
        String connection=base.getConnectionString().asConnectionString();
        return VibeCorePlugin.getDefault().getStateLocation().toFile().toPath()
            .resolve("infobase-lifecycle").resolve(InfobasePaths.hash(connection)+".full-required");
    }
    public static boolean requiresFull(InfobaseReference base) throws Exception {return Files.exists(marker(base));}
    public static void requireFull(InfobaseReference base) throws Exception {
        Path p=marker(base);Files.createDirectories(p.getParent());
        Files.writeString(p,"Full native reload required after create/DT restore",StandardCharsets.UTF_8);
    }
    public static void fullCompleted(InfobaseReference base) throws Exception {Files.deleteIfExists(marker(base));}
}
