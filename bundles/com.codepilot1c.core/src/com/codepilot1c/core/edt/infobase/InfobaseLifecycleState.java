// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.codepilot1c.core.internal.VibeCorePlugin;

/** A restore invalidates incremental synchronization history, including across EDT restarts. */
public final class InfobaseLifecycleState {
    private InfobaseLifecycleState() {}
    private static Path directory() {
        return VibeCorePlugin.getDefault().getStateLocation().toFile().toPath().resolve("infobase-lifecycle");
    }
    static Path marker(Path directory,InfobaseReference base) {
        return directory.resolve(InfobasePaths.hash(InfobasePaths.connectionIdentity(
            base.getConnectionString().asConnectionString()))+".full-required");
    }
    private static Path legacyMarker(Path directory,InfobaseReference base) {
        return directory.resolve(InfobasePaths.hash(base.getConnectionString().asConnectionString())+".full-required");
    }
    static boolean requiresFull(Path directory,InfobaseReference base) {
        return Files.exists(marker(directory,base)) || Files.exists(legacyMarker(directory,base));
    }
    static void requireFull(Path directory,InfobaseReference base) throws Exception {
        Files.createDirectories(directory);
        Files.writeString(marker(directory,base),"Full native reload required after create/DT restore",StandardCharsets.UTF_8);
    }
    static void fullCompleted(Path directory,InfobaseReference base) throws Exception {
        Files.deleteIfExists(marker(directory,base));Files.deleteIfExists(legacyMarker(directory,base));
    }
    public static boolean requiresFull(InfobaseReference base) {return requiresFull(directory(),base);}
    public static void requireFull(InfobaseReference base) throws Exception {requireFull(directory(),base);}
    public static void fullCompleted(InfobaseReference base) throws Exception {fullCompleted(directory(),base);}
}
