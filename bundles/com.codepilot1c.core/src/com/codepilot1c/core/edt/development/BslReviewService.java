// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import com.codepilot1c.core.edt.infobase.InfobasePaths;
import com.codepilot1c.core.internal.VibeCorePlugin;

/** Stores only immutable review snapshots. The change-set rollback records live separately. */
public final class BslReviewService {
    private static final Object LOCK = new Object();
    private static final Pattern ID = Pattern.compile("review-[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");
    private static final long MAX_CAPTURE = 8L * 1024 * 1024;
    private static final long MAX_CACHE = 64L * 1024 * 1024;
    private static final int MAX_REVIEWS = 40;
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final long CLEAN_INTERVAL = Duration.ofHours(1).toMillis();

    private record Version(String text, String sha256) {}
    private record Pair(Version before, Version after) {}
    private record Review(String id, String project, String projectLocation, String startedAt, String finishedAt,
            Map<String, Pair> files) {}
    private record Stored(Path path, long size, Instant modified) {}

    private static final Map<String, Integer> OPEN = new ConcurrentHashMap<>();
    private static Job housekeeping;

    public static synchronized void startHousekeeping() {
        if (housekeeping != null) return;
        housekeeping = new Job("Очистка снимков сравнения BSL") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                try {
                    synchronized (LOCK) {
                        Path root = root();
                        if (Files.isDirectory(root, NOFOLLOW_LINKS)) prune(root, null, 0);
                    }
                } catch (Exception failure) {
                    return new Status(IStatus.WARNING, "com.codepilot1c.core", "BSL review cleanup could not finish", failure);
                } finally {
                    synchronized (BslReviewService.class) {
                        if (housekeeping == this && !monitor.isCanceled()) schedule(CLEAN_INTERVAL);
                    }
                }
                return Status.OK_STATUS;
            }
        };
        housekeeping.setSystem(true);
        housekeeping.schedule(Duration.ofMinutes(1).toMillis());
    }

    public static synchronized void stopHousekeeping() {
        if (housekeeping == null) return;
        Job job = housekeeping;
        housekeeping = null;
        job.cancel();
    }

    public Map<String, Object> begin(String projectName, List<?> requested) throws Exception {
        if (requested.isEmpty() || requested.size() > 50) {
            throw new IllegalArgumentException("Choose 1 to 50 existing BSL modules");
        }
        IProject project = project(projectName);
        var files = new LinkedHashMap<String, Pair>();
        long size = 0;
        for (Object raw : requested) {
            if (!(raw instanceof String path) || !path.startsWith("src/")
                    || !path.toLowerCase(Locale.ROOT).endsWith(".bsl") || files.containsKey(path)) {
                throw new IllegalArgumentException("Use unique project-relative src/... .bsl paths");
            }
            byte[] bytes = capture(project, path);
            size = checkSize(size, bytes);
            files.put(path, new Pair(version(bytes), null));
        }
        Review review = new Review("review-" + UUID.randomUUID(), projectName, location(project),
                Instant.now().toString(), null, files);
        synchronized (LOCK) {
            save(review);
        }
        return describe(review);
    }

    public Map<String, Object> finish(String id) throws Exception {
        Review original;
        synchronized (LOCK) {
            original = load(id);
        }
        if (original.finishedAt() != null) return describe(original);
        IProject project = project(original.project());
        if (!location(project).equals(original.projectLocation())) {
            throw new IllegalStateException("The review's EDT project location changed");
        }
        var files = new LinkedHashMap<String, Pair>();
        long size = 0;
        for (var row : original.files().entrySet()) {
            byte[] bytes = capture(project, row.getKey());
            size = checkSize(size, bytes);
            files.put(row.getKey(), new Pair(row.getValue().before(), version(bytes)));
        }
        synchronized (LOCK) {
            Review current = load(id);
            if (current.finishedAt() != null) return describe(current);
            Review finished = new Review(id, original.project(), original.projectLocation(), original.startedAt(),
                    Instant.now().toString(), files);
            save(finished);
            return describe(finished);
        }
    }

    public Map<String, Object> status(String id) throws Exception {
        synchronized (LOCK) {
            return describe(load(id));
        }
    }

    public Map<String, Object> open(String id, String filePath, String mode) throws Exception {
        Pair file;
        Review review;
        synchronized (LOCK) {
            review = load(id);
            if (review.finishedAt() == null) throw new IllegalStateException("Finish capturing the review first");
            file = review.files().get(filePath);
            if (file == null) throw new IllegalArgumentException("Module is absent from this review");
            if (file.before().sha256().equals(file.after().sha256())) {
                return Map.of("state", "unchanged", "review_id", id, "file_path", filePath);
            }
            OPEN.merge(id, 1, Integer::sum);
        }
        var input = new BslReviewEditor(id, review.project(), filePath, mode, file.before().text(),
                file.after().text(), () -> OPEN.computeIfPresent(id, (key, count) -> count == 1 ? null : count - 1));
        try {
            return input.open();
        } finally {
            if (!input.retained()) input.release();
        }
    }

    public Map<String, Object> cleanup(String id, boolean closeViews) throws Exception {
        Path path = path(id);
        if (closeViews) BslReviewEditor.close(id);
        synchronized (LOCK) {
            if (OPEN.containsKey(id)) return Map.of("review_id", id, "state", "open", "removed", false);
            if (Files.exists(path, NOFOLLOW_LINKS)) {
                load(id); // Confirm this is a Bridge review before deleting it.
                Files.delete(path);
            }
            return Map.of("review_id", id, "state", "cleaned", "removed", true);
        }
    }

    private static Map<String, Object> describe(Review review) {
        var result = new LinkedHashMap<String, Object>();
        result.put("review_id", review.id());
        result.put("project", review.project());
        result.put("state", review.finishedAt() == null ? "capturing" : "ready");
        result.put("started_at", review.startedAt());
        if (review.finishedAt() != null) result.put("finished_at", review.finishedAt());
        var files = new ArrayList<Map<String, Object>>();
        review.files().forEach((name, pair) -> {
            var file = new LinkedHashMap<String, Object>();
            file.put("file_path", name);
            file.put("before_sha256", pair.before().sha256());
            if (pair.after() != null) {
                file.put("after_sha256", pair.after().sha256());
                file.put("changed", !pair.before().sha256().equals(pair.after().sha256()));
            }
            files.add(file);
        });
        result.put("files", files);
        result.put("open_views", OPEN.getOrDefault(review.id(), 0));
        return result;
    }

    private static IProject project(String name) {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        if (!project.isOpen() || project.getLocation() == null) throw new IllegalArgumentException("Open local EDT project required");
        return project;
    }

    private static String location(IProject project) throws Exception {
        return project.getLocation().toFile().toPath().toRealPath().toString();
    }

    private static byte[] capture(IProject project, String path) throws Exception {
        var file = EdtChangeSetService.file(project, path);
        EdtChangeSetService.ensureNotDirty(file);
        return EdtChangeSetService.read(file);
    }

    private static long checkSize(long total, byte[] bytes) {
        long size = total + bytes.length;
        if (size > MAX_CAPTURE) throw new IllegalArgumentException("Review snapshot exceeds 8 MiB");
        return size;
    }

    private static Version version(byte[] bytes) throws Exception {
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        return new Version(text, EdtChangeSetService.hash(bytes));
    }

    private static Path root() throws Exception {
        return InfobasePaths.absolute(VibeCorePlugin.getDefault().getStateLocation().toFile().toPath()
                .resolve("bsl-reviews").toString());
    }

    private static Path path(String id) throws Exception {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid review_id");
        return root().resolve(id + ".json");
    }

    private static Review load(String id) throws Exception {
        Path file = path(id);
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) > MAX_CACHE) {
            throw new IllegalArgumentException("Review is absent, expired or unavailable");
        }
        try (var input = Files.newInputStream(file, NOFOLLOW_LINKS);
                var reader = new java.io.InputStreamReader(input, StandardCharsets.UTF_8)) {
            Review review = AgentResponses.JSON.fromJson(reader, Review.class);
            if (review == null || !id.equals(review.id()) || review.files() == null) {
                throw new IllegalStateException("Review receipt identity changed");
            }
            return review;
        }
    }

    private static void save(Review review) throws Exception {
        Path target = path(review.id());
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "capture-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                AgentResponses.JSON.toJson(review, writer);
            }
            prune(target.getParent(), target, Files.size(temporary));
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void prune(Path folder, Path replacing, long added) throws Exception {
        var files = new ArrayList<Stored>();
        Instant cutoff = Instant.now().minus(RETENTION);
        try (var entries = Files.list(folder)) {
            for (Path entry : entries.toList()) {
                if (!Files.isRegularFile(entry, NOFOLLOW_LINKS) || entry.equals(replacing)) continue;
                String name = entry.getFileName().toString();
                Instant modified = Files.getLastModifiedTime(entry, NOFOLLOW_LINKS).toInstant();
                if (name.matches("capture-[0-9]+\\.tmp") && modified.isBefore(cutoff)) {
                    Files.deleteIfExists(entry);
                } else if (name.endsWith(".json") && ID.matcher(name.substring(0, name.length() - 5)).matches()) {
                    files.add(new Stored(entry, Files.size(entry), modified));
                }
            }
        }
        files.sort(Comparator.comparing(Stored::modified));
        long size = added + files.stream().mapToLong(Stored::size).sum();
        int count = files.size() + (replacing == null ? 0 : 1);
        for (Stored file : files) {
            String name = file.path().getFileName().toString();
            if (!OPEN.containsKey(name.substring(0, name.length() - 5))
                    && (file.modified().isBefore(cutoff) || size > MAX_CACHE || count > MAX_REVIEWS)) {
                Files.deleteIfExists(file.path());
                size -= file.size();
                count--;
            }
        }
        if (replacing != null && (size > MAX_CACHE || count > MAX_REVIEWS)) {
            throw new IllegalStateException("Close old BSL comparisons to make review storage available");
        }
    }
}
