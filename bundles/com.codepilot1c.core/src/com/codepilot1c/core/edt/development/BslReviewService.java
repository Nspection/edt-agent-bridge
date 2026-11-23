// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.HexFormat;
import java.util.concurrent.CopyOnWriteArrayList;
import java.security.MessageDigest;
import java.nio.charset.CharacterCodingException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import com.codepilot1c.core.edt.infobase.InfobasePaths;
import com.codepilot1c.core.internal.VibeCorePlugin;
import com.google.gson.JsonParseException;

/** Stores only immutable review snapshots. The change-set rollback records live separately. */
public final class BslReviewService {
    private static final Object LOCK = new Object();
    private static final Pattern ID = Pattern.compile("(?:review|turn)-[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");
    private static final long MAX_CAPTURE = 8L * 1024 * 1024;
    private static final long MAX_CACHE = 64L * 1024 * 1024;
    private static final int MAX_REVIEWS = 40;
    private static final Duration RETENTION = Duration.ofDays(1);
    private static final long CLEAN_INTERVAL = Duration.ofHours(1).toMillis();

    private record Version(String text, String sha256) {}
    private record Pair(Version before, Version after, boolean plannedAddition) {
        Pair(Version before, Version after) {
            this(before, after, false);
        }
    }
    private record Review(String id, String project, String projectLocation, String startedAt, String finishedAt,
            Map<String, Pair> files, Map<String, ProjectFiles> turnProjects) {}
    private record ProjectFiles(String location, Map<String, Pair> files) {}
    public record TurnFile(String project, String filePath, String change, String comparison) {}
    public record TurnSummary(String id, String finishedAt, List<TurnFile> files, int unreadableTurns) {}

    private static final int MAX_TURN_FILES = 100;
    private static final int MAX_TEXT_BYTES = (int) MAX_CAPTURE;
    private static final long MAX_BINARY_BYTES = 64L * 1024 * 1024;
    private static final List<Runnable> TURN_LISTENERS = new CopyOnWriteArrayList<>();
    private record Stored(Path path, long size, Instant modified) {}

    private static final Map<String, Integer> OPEN = new ConcurrentHashMap<>();
    private static final java.util.Set<String> ACTIVE_TURNS = ConcurrentHashMap.newKeySet();
    private static Job housekeeping;

    public static void setTurnActive(String id, boolean active) {
        if (id.isEmpty()) return;
        if (active) ACTIVE_TURNS.add(id); else ACTIVE_TURNS.remove(id);
    }

    static boolean turnActive(String id) { return ACTIVE_TURNS.contains(id); }

    public static synchronized void startHousekeeping() {
        if (housekeeping != null) return;
        housekeeping = new Job("Очистка снимков сравнения BSL") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                try {
                    synchronized (LOCK) {
                        Path root = root();
                        if (Files.isDirectory(root, NOFOLLOW_LINKS)) removeExpired(planPrune(root, null, 0, historyRetention()));
                    }
                    notifyTurns();
                    AgentHistoryService.pruneMessages();
                } catch (Exception failure) {
                    return new Status(IStatus.WARNING, "com.codepilot1c.core", "Review cleanup could not finish", failure);
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
                Instant.now().toString(), null, files, null);
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
        if (original.turnProjects() != null) throw new IllegalArgumentException("Use the agent turn review operation");
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
                    Instant.now().toString(), files, null);
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
            if (review.turnProjects() != null) throw new IllegalArgumentException("Use the agent turn review operation");
            if (review.finishedAt() == null) throw new IllegalStateException("Finish capturing the review first");
            file = review.files().get(filePath);
            if (file == null) throw new IllegalArgumentException("Module is absent from this review");
            if (file.before().sha256().equals(file.after().sha256())) {
                return Map.of("state", "unchanged", "review_id", id, "file_path", filePath);
            }
            OPEN.merge(id, 1, Integer::sum);
        }
        return openPinned(id, review.project(), filePath, mode, file);
    }

    private static Map<String, Object> openPinned(String id, String project, String filePath, String mode, Pair file)
            throws Exception {
        var input = new BslReviewEditor(id, project, filePath, mode, text(file.before()), text(file.after()),
                () -> OPEN.computeIfPresent(id, (key, count) -> count == 1 ? null : count - 1));
        try {
            return input.open();
        } finally {
            if (!input.retained()) input.release();
        }
    }

    private static String text(Version version) {
        return version == null ? "" : version.text();
    }

    public static void addTurnListener(Runnable listener) {
        TURN_LISTENERS.add(listener);
    }

    public static void removeTurnListener(Runnable listener) {
        TURN_LISTENERS.remove(listener);
    }

    public Map<String, Object> trackTurn(String id, String projectName, List<?> files, List<?> additions) throws Exception {
        return trackTurn(id, projectName, files, additions, false);
    }

    public static void trackWrite(String turnId, org.eclipse.core.resources.IFile... files) {
        if (turnId.isEmpty() || files.length == 0) return;
        IProject project = files[0].getProject();
        var paths = new ArrayList<String>();
        for (var file : files) {
            if (!project.equals(file.getProject())) throw new IllegalArgumentException("Expected one EDT project");
            paths.add(file.getProjectRelativePath().toPortableString());
        }
        try {
            new BslReviewService().trackTurn(turnId, project.getName(), paths, List.of(), true);
        } catch (Exception error) {
            throw new IllegalStateException("Could not save the pre-edit snapshot: " + error.getMessage(), error);
        }
    }

    private Map<String, Object> trackTurn(String id, String projectName, List<?> requested,
            List<?> additions, boolean automatic) throws Exception {
        int requestedCount = requested.size() + additions.size();
        if (requestedCount == 0) {
            if (id != null) throw new IllegalArgumentException("Provide at least one file or addition");
            synchronized (LOCK) {
                var turn = new Review("turn-" + UUID.randomUUID(), null, null, Instant.now().toString(), null,
                        Map.of(), Map.of());
                save(turn);
                return Map.of("turn_id", turn.id(), "state", "capturing", "tracked_count", 0);
            }
        }
        if (requestedCount > MAX_TURN_FILES) throw new IllegalArgumentException("Choose at most 100 project-relative paths");
        var project = project(projectName);
        String projectLocation = location(project);
        synchronized (LOCK) {
            Review original = id == null
                    ? new Review("turn-" + UUID.randomUUID(), null, null, Instant.now().toString(), null,
                            Map.of(), Map.of())
                    : loadTurn(id);
            if (original.finishedAt() != null) throw new IllegalStateException("This agent turn is already finished");
            var projects = new LinkedHashMap<>(original.turnProjects());
            ProjectFiles previous = projects.get(projectName);
            if (previous != null && !previous.location().equals(projectLocation)) {
                throw new IllegalStateException("The agent turn's EDT project location changed");
            }
            var files = previous == null ? new LinkedHashMap<String, Pair>() : new LinkedHashMap<>(previous.files());
            int count = projects.values().stream().mapToInt(row -> row.files().size()).sum();
            int originalCount = count;
            long size = recordedTextSize(projects);
            var paths = new ArrayList<Object>(requested);
            paths.addAll(additions);
            var expectations = new LinkedHashMap<Path, Boolean>();
            for (int i = 0; i < paths.size(); i++) {
                if (!(paths.get(i) instanceof String path)) throw new IllegalArgumentException("File path must be text");
                validateTurnPath(path);
                boolean planned = i >= requested.size();
                Boolean previousExpectation = expectations.putIfAbsent(Path.of(path), planned);
                if (previousExpectation != null && previousExpectation != planned) {
                    throw new IllegalArgumentException("Use either files or additions for " + path);
                }
                if (files.keySet().stream().anyMatch(key -> Path.of(key).equals(Path.of(path)))) continue;
                if (++count > MAX_TURN_FILES) throw new IllegalArgumentException("Agent turn exceeds 100 tracked files");
                Version before = captureTurnFile(project, projectLocation, path);
                if (!automatic && planned && before != null) {
                    throw new IllegalArgumentException("Addition already exists: " + path + ". Use files.");
                }
                if (!automatic && !planned && before == null) {
                    String alternative = "src/" + path;
                    boolean existsUnderSrc = !path.startsWith("src/")
                            && Files.isRegularFile(Path.of(projectLocation).resolve(alternative), NOFOLLOW_LINKS);
                    throw new IllegalArgumentException("File does not exist: " + path
                            + (existsUnderSrc ? ". Use " + alternative : ". Check the path or declare it in additions."));
                }
                size = checkVersionSize(size, before);
                files.put(path, new Pair(before, null, planned));
            }
            if (count != originalCount) {
                projects.put(projectName, new ProjectFiles(projectLocation, files));
                save(new Review(original.id(), null, null, original.startedAt(), null, Map.of(), projects));
            }
            return Map.of("turn_id", original.id(), "state", "capturing", "tracked_count", count);
        }
    }

    public Map<String, Object> finishTurn(String id) throws Exception {
        Map<String, Object> receipt;
        synchronized (LOCK) {
            Review original = loadTurn(id);
            if (original.finishedAt() != null) return turnResult(original);
            var projects = new LinkedHashMap<String, ProjectFiles>();
            long size = 0;
            for (var row : original.turnProjects().entrySet()) {
                var project = project(row.getKey());
                ProjectFiles before = row.getValue();
                if (!location(project).equals(before.location())) {
                    throw new IllegalStateException("The agent turn's EDT project location changed");
                }
                var files = new LinkedHashMap<String, Pair>();
                for (var file : before.files().entrySet()) {
                    Version after = captureTurnFile(project, before.location(), file.getKey());
                    size = checkVersionSize(size, after);
                    files.put(file.getKey(), new Pair(file.getValue().before(), after, file.getValue().plannedAddition()));
                }
                projects.put(row.getKey(), new ProjectFiles(before.location(), files));
            }
            var finished = new Review(id, null, null, original.startedAt(), Instant.now().toString(), Map.of(), projects);
            save(finished);
            receipt = turnResult(finished);
        }
        notifyTurns();
        return receipt;
    }

    private static void notifyTurns() {
        for (Runnable listener : TURN_LISTENERS) {
            try { listener.run(); }
            catch (RuntimeException ignored) { /* A closing view must not invalidate a saved turn. */ }
        }
    }

    List<AgentHistoryService.Entry> historyEntries() throws Exception {
        synchronized (LOCK) {
            Path folder = root();
            if (!Files.isDirectory(folder, NOFOLLOW_LINKS)) return List.of();
            var result = new ArrayList<AgentHistoryService.Entry>();
            try (var files = Files.list(folder)) {
                for (Path file : files.toList()) {
                    String name = file.getFileName().toString();
                    if (!name.startsWith("turn-") || !name.endsWith(".json")
                            || !Files.isRegularFile(file, NOFOLLOW_LINKS)) continue;
                    String id = name.substring(0, name.length() - 5);
                    if (!ID.matcher(id).matches()) continue;
                    Instant modified = Files.getLastModifiedTime(file, NOFOLLOW_LINKS).toInstant();
                    result.add(new AgentHistoryService.Entry(id, modified + ":" + Files.size(file),
                            modified, OPEN.containsKey(id) || turnActive(id)));
                }
            }
            result.sort(Comparator.comparing(AgentHistoryService.Entry::modified).reversed()
                    .thenComparing(AgentHistoryService.Entry::id));
            return result;
        }
    }

    public List<TurnSummary> turnHistory() throws Exception {
        synchronized (LOCK) {
            var rows = new ArrayList<TurnSummary>();
            for (var entry : historyEntries()) {
                try {
                    Review review = loadTurn(entry.id());
                    if (review.finishedAt() != null) rows.add(summarizeTurn(review));
                } catch (IOException | JsonParseException | IllegalArgumentException | IllegalStateException invalid) {
                    // Preserve readable older turns.
                }
            }
            return rows;
        }
    }

    public TurnSummary turnSummary(String id) throws Exception {
        synchronized (LOCK) {
            Review review = loadTurn(AgentHistoryService.checkedId(id));
            if (review.finishedAt() == null) throw new IllegalArgumentException("Turn is still capturing");
            return summarizeTurn(review);
        }
    }

    Map<String, Object> cleanupHistory(String id, boolean all, String expected) throws Exception {
        Map<String, Object> receipt;
        synchronized (LOCK) {
            receipt = AgentHistoryService.cleanupEntries(historyEntries(), id, all, expected,
                    entry -> Files.deleteIfExists(path(entry)));
        }
        if (Boolean.TRUE.equals(receipt.get("applied"))) notifyTurns();
        return receipt;
    }

    public TurnSummary latestTurn() throws Exception {
        synchronized (LOCK) {
            Path folder = root();
            if (!Files.isDirectory(folder, NOFOLLOW_LINKS)) return null;
            var candidates = new ArrayList<Stored>();
            try (var files = Files.list(folder)) {
                for (Path entry : files.toList()) {
                    String name = entry.getFileName().toString();
                    if (name.startsWith("turn-") && name.endsWith(".json")
                            && ID.matcher(name.substring(0, name.length() - 5)).matches()
                            && Files.isRegularFile(entry, NOFOLLOW_LINKS)) {
                        candidates.add(new Stored(entry, Files.size(entry), Files.getLastModifiedTime(entry).toInstant()));
                    }
                }
            }
            candidates.sort(Comparator.comparing(Stored::modified).reversed());
            int unreadable = 0;
            for (Stored item : candidates) {
                String name = item.path().getFileName().toString();
                try {
                    Review turn = loadTurn(name.substring(0, name.length() - 5));
                    if (turn.finishedAt() != null) {
                        TurnSummary summary = summarizeTurn(turn);
                        return new TurnSummary(summary.id(), summary.finishedAt(), summary.files(), unreadable);
                    }
                } catch (IOException | JsonParseException | IllegalArgumentException | IllegalStateException invalid) {
                    unreadable++;
                }
            }
            if (unreadable > 0) throw new IllegalStateException("No readable completed turn in review storage");
            return null;
        }
    }

    public Map<String, Object> openTurn(String id, String project, String path, String mode) throws Exception {
        Pair file;
        synchronized (LOCK) {
            Review review = loadTurn(id);
            if (review.finishedAt() == null) throw new IllegalStateException("Finish the agent turn first");
            var files = review.turnProjects().get(project);
            file = files == null ? null : files.files().get(path);
            if (file == null || !changed(file)) throw new IllegalArgumentException("File has no change in this turn");
            String kind = comparison(path, file);
            if (kind.equals("binary")) throw new IllegalArgumentException("Text comparison is unavailable for this file");
            if (mode.equals("methods") && !kind.equals("bsl")) throw new IllegalArgumentException("Methods require BSL");
            OPEN.merge(id, 1, Integer::sum);
        }
        return openPinned(id, project, path, mode, file);
    }

    private static Review loadTurn(String id) throws Exception {
        Review turn = load(id);
        if (turn.turnProjects() == null
                || turn.turnProjects().values().stream().anyMatch(group -> group == null || group.files() == null
                        || group.files().isEmpty() || group.files().containsValue(null))) {
            throw new IllegalArgumentException("Invalid agent turn snapshot");
        }
        return turn;
    }

    private static Map<String, Object> turnResult(Review review) {
        var result = new LinkedHashMap<String, Object>();
        result.put("turn_id", review.id());
        result.put("state", "ready");
        result.put("changed_count", summarizeTurn(review).files().size());
        var notCreated = new ArrayList<Map<String, String>>();
        int unchanged = 0;
        for (var group : review.turnProjects().entrySet()) {
            for (var entry : group.getValue().files().entrySet()) {
                Pair pair = entry.getValue();
                if (pair.plannedAddition() && pair.after() == null) {
                    notCreated.add(Map.of("project", group.getKey(), "file_path", entry.getKey()));
                } else if (pair.before() != null && !changed(pair)) {
                    unchanged++;
                }
            }
        }
        result.put("unchanged_count", unchanged);
        if (!notCreated.isEmpty()) result.put("additions_not_created", notCreated);
        return result;
    }

    private static TurnSummary summarizeTurn(Review review) {
        var rows = new ArrayList<TurnFile>();
        review.turnProjects().forEach((project, group) -> group.files().forEach((path, file) -> {
            if (changed(file)) {
                String change = file.before() == null ? "added" : file.after() == null ? "removed" : "changed";
                rows.add(new TurnFile(project, path, change, comparison(path, file)));
            }
        }));
        rows.sort(Comparator.comparing(TurnFile::project).thenComparing(TurnFile::filePath));
        return new TurnSummary(review.id(), review.finishedAt(), List.copyOf(rows), 0);
    }

    private static boolean changed(Pair file) {
        return !Objects.equals(file.before() == null ? null : file.before().sha256(),
                file.after() == null ? null : file.after().sha256());
    }

    private static String comparison(String path, Pair file) {
        if (text(file.before()) == null || text(file.after()) == null) return "binary";
        return path.toLowerCase(Locale.ROOT).endsWith(".bsl") ? "bsl" : "text";
    }

    static void validateTurnPath(String path) {
        if (path.isBlank() || path.length() > 2048 || path.startsWith("/") || path.indexOf('\\') >= 0
                || path.indexOf(':') >= 0 || path.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Use a project-relative file path");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.startsWith(".")) {
                throw new IllegalArgumentException("Use a source path without hidden or relative segments");
            }
        }
    }

    private static Version captureTurnFile(IProject project, String location, String path) throws Exception {
        Path root = Path.of(location);
        var file = project.getFile(path);
        if (file.getLocation() == null) throw new IllegalArgumentException("Source is not a local file: " + path);
        Path resolved = file.getLocation().toFile().toPath().toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) throw new IllegalArgumentException("Source escapes the project: " + path);
        Path ancestor = resolved;
        while (Files.notExists(ancestor, NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        if (!ancestor.toRealPath().startsWith(root)) throw new IllegalArgumentException("Source escapes the project: " + path);
        EdtChangeSetService.ensureNotDirty(file);
        if (Files.notExists(resolved, NOFOLLOW_LINKS)) return null;
        var identity = Files.readAttributes(resolved, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!identity.isRegularFile()) throw new IllegalArgumentException("Expected ordinary file: " + path);
        try (var stream = Files.newInputStream(resolved, NOFOLLOW_LINKS)) {
            byte[] prefix = stream.readNBytes(MAX_TEXT_BYTES + 1);
            if (prefix.length <= MAX_TEXT_BYTES) {
                verifyCaptureIdentity(resolved, identity, prefix.length, path);
                return new Version(decodeText(prefix), EdtChangeSetService.hash(prefix));
            }
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(prefix);
            byte[] buffer = new byte[8192];
            long total = prefix.length;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BINARY_BYTES) throw new IllegalArgumentException("File exceeds 64 MiB: " + path);
                digest.update(buffer, 0, read);
            }
            verifyCaptureIdentity(resolved, identity, total, path);
            return new Version(null, HexFormat.of().formatHex(digest.digest()));
        }
    }

    private static void verifyCaptureIdentity(Path file, BasicFileAttributes before, long bytesRead, String relative)
            throws IOException {
        var after = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!after.isRegularFile() || before.size() != bytesRead || after.size() != bytesRead
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) {
            throw new IllegalStateException("File changed during snapshot: " + relative);
        }
    }

    private static String decodeText(byte[] bytes) {
        var charset = StandardCharsets.UTF_8;
        int offset = 0;
        if (bytes.length >= 2 && (bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe
                || bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff)) {
            charset = bytes[0] == (byte) 0xff ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE;
            offset = 2;
        }
        try {
            String text = charset.newDecoder().decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
            return text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')
                    ? null : text;
        } catch (CharacterCodingException binary) {
            return null;
        }
    }

    private static long recordedTextSize(Map<String, ProjectFiles> projects) {
        long size = 0;
        for (ProjectFiles group : projects.values()) {
            for (Pair pair : group.files().values()) size = checkVersionSize(size, pair.before());
        }
        return size;
    }

    private static long checkVersionSize(long size, Version version) {
        return version == null || version.text() == null ? size
                : checkSize(size, version.text().getBytes(StandardCharsets.UTF_8));
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
        }
        if (id.startsWith("turn-")) notifyTurns();
        return Map.of("review_id", id, "state", "cleaned", "removed", true);
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
            var expired = planPrune(target.getParent(), target, Files.size(temporary), historyRetention());
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING);
            removeExpired(expired);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Duration historyRetention() {
        return Duration.ofHours(AgentHistoryService.retentionHours(AgentHistoryService.Pane.CHANGES));
    }

    static List<Path> planPrune(Path folder, Path replacing, long added) throws IOException {
        return planPrune(folder, replacing, added, RETENTION);
    }

    private static List<Path> planPrune(Path folder, Path replacing, long added, Duration turnRetention)
            throws IOException {
        var files = new ArrayList<Stored>();
        var expired = new ArrayList<Path>();
        Instant cutoff = Instant.now().minus(RETENTION);
        try (var entries = Files.list(folder)) {
            for (Path entry : entries.toList()) {
                if (!Files.isRegularFile(entry, NOFOLLOW_LINKS) || entry.equals(replacing)) continue;
                String name = entry.getFileName().toString();
                Instant modified = Files.getLastModifiedTime(entry, NOFOLLOW_LINKS).toInstant();
                if (name.matches("capture-[0-9]+\\.tmp") && modified.isBefore(cutoff)) {
                    expired.add(entry);
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
            String id = name.substring(0, name.length() - 5);
            Instant expiry = name.startsWith("turn-") ? Instant.now().minus(turnRetention) : cutoff;
            if (!OPEN.containsKey(id) && !turnActive(id)
                    && (file.modified().isBefore(expiry) || size > MAX_CACHE || count > MAX_REVIEWS)) {
                expired.add(file.path());
                size -= file.size();
                count--;
            }
        }
        if (replacing != null && (size > MAX_CACHE || count > MAX_REVIEWS)) {
            throw new IllegalStateException("Close old comparisons to make review storage available");
        }
        return expired;
    }

    private static void removeExpired(List<Path> expired) {
        for (Path path : expired) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException unavailable) {
                // The new snapshot is committed. Housekeeping can retry this removal later.
                Platform.getLog(VibeCorePlugin.getDefault().getBundle())
                        .warn("Could not remove an old review snapshot", unavailable);
            }
        }
    }
}
