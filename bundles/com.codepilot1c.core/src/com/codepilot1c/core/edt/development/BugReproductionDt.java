// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.codepilot1c.core.edt.infobase.InfobasePaths;

final class BugReproductionDt {
    private static final String LOCAL = "database.dt";
    private static final String SHARE = "database-share.json";
    private static final Set<Path> BUSY = ConcurrentHashMap.newKeySet();

    private record Shared(String path, String sha256, long bytes) {}

    private BugReproductionDt() {}

    @FunctionalInterface
    interface Export {
        void run(Path target) throws Exception;
    }

    static void dump(Path directory, Export export) throws Exception {
        acquire(directory);
        try {
            if (Files.isRegularFile(directory.resolve(LOCAL), NOFOLLOW_LINKS)
                    || Files.exists(directory.resolve(SHARE), NOFOLLOW_LINKS)) {
                return;
            }
            Path pending = directory.resolve("database-pending.dt");
            if (Files.exists(pending, NOFOLLOW_LINKS)) {
                throw new IllegalStateException("The previous DT export was interrupted; inspect the pending file");
            }
            try {
                export.run(pending);
                if (!Files.isRegularFile(pending, NOFOLLOW_LINKS) || Files.size(pending) == 0) {
                    throw new IOException("EDT did not produce a complete DT");
                }
                Files.move(pending, directory.resolve(LOCAL));
            } finally {
                Files.deleteIfExists(pending);
            }
        } finally {
            BUSY.remove(directory);
        }
    }

    static void copy(Path directory, String exchangeDirectory) throws Exception {
        acquire(directory);
        try {
            Path exchange = InfobasePaths.absolute(exchangeDirectory);
            if (!Files.isDirectory(exchange, NOFOLLOW_LINKS) || exchange.startsWith(directory)) {
                throw new IllegalArgumentException("Select an existing exchange folder outside the recording session");
            }
            Path local = directory.resolve(LOCAL);
            Path target = exchange.resolve("1c-reproduction-" + directory.getFileName() + ".dt");
            Shared shared = shared(directory);
            boolean localExists = Files.isRegularFile(local, NOFOLLOW_LINKS);
            if (shared != null) {
                if (!shared.path().equals(target.toString())) {
                    throw new IllegalArgumentException("The DT has already been copied to a different folder");
                }
                verify(target, shared);
                if (localExists) {
                    verify(local, shared);
                    Files.delete(local);
                }
                return;
            }
            if (!localExists) throw new IllegalStateException("Export a local DT first");
            shared = new Shared(target.toString(), digest(local), Files.size(local));
            if (!Files.exists(target, NOFOLLOW_LINKS)) {
                Path staging = Files.createTempFile(exchange, ".1c-reproduction-", ".pending");
                try {
                    Files.copy(local, staging, java.nio.file.StandardCopyOption.REPLACE_EXISTING, NOFOLLOW_LINKS);
                    verify(staging, shared);
                    Files.move(staging, target);
                } finally {
                    Files.deleteIfExists(staging);
                }
            }
            verify(target, shared);
            verify(local, shared);
            BugReproductionService.save(directory, SHARE, shared);
            Files.delete(local);
        } finally {
            BUSY.remove(directory);
        }
    }

    static Map<String, Object> receipt(Path directory) throws Exception {
        Path local = directory.resolve(LOCAL);
        boolean localExists = Files.isRegularFile(local, NOFOLLOW_LINKS);
        Shared shared = shared(directory);
        var result = new LinkedHashMap<String, Object>();
        result.put("state", shared == null ? localExists ? "local_ready" : "not_exported"
                : localExists ? "copied_local_retained" : "copied");
        if (localExists) result.put("local_path", local.toString());
        if (shared != null) {
            result.put("exchange_path", shared.path());
            result.put("sha256", shared.sha256());
            result.put("bytes", shared.bytes());
        }
        return result;
    }

    private static Shared shared(Path directory) throws Exception {
        Path path = directory.resolve(SHARE);
        return Files.exists(path, NOFOLLOW_LINKS) ? BugReproductionService.read(path, Shared.class, 16384) : null;
    }

    static boolean busy(Path directory) {
        return BUSY.contains(directory);
    }

    private static void acquire(Path directory) {
        if (!BUSY.add(directory)) throw new IllegalStateException("There is already a DT operation for this session");
    }

    private static void verify(Path path, Shared expected) throws Exception {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) != expected.bytes()
                || !digest(path).equals(expected.sha256())) {
            throw new IOException("DT verification failed");
        }
    }

    private static String digest(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = new DigestInputStream(Files.newInputStream(path, NOFOLLOW_LINKS), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
