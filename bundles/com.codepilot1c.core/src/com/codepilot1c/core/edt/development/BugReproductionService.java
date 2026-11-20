// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.function.Consumer;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder.ThickClientMode;
import com._1c.g5.v8.dt.platform.services.model.ClientType;
import com.codepilot1c.core.edt.infobase.InfobasePaths;
import com.codepilot1c.core.edt.infobase.InfobaseWorkflow;
import com.codepilot1c.core.edt.infobase.NativeInfobaseGateway;
import com.codepilot1c.core.edt.infobase.OwnedFileBaseService;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.mcp.host.McpOperationAuthorization;

public final class BugReproductionService {
    private static final Object LOCK = new Object();
    private static final int MAX_JOURNAL_BYTES = 4 * 1024 * 1024;
    private static final int MAX_SCREENSHOTS = 8;
    private static final Pattern OBJECT_LINK = Pattern.compile("e1cib/data/[\\p{L}_][\\p{L}\\p{N}_]*\\.[\\p{L}_][\\p{L}\\p{N}_]*[?]ref=[a-fA-F0-9]{32}");
    private static final String TOOL = "edt_bug_reproduction";

    private record Session(String id, String project, String baseUuid, String lease, long pid, String started,
            String clientId, String backend) {
        boolean controlled() { return "testclient".equals(backend); }
        boolean prepared() { return pid == 0 && clientId == null && "prepared".equals(started); }
    }
    private record Recorder(String state, String startedAt, String stoppedAt, String operationId) {}
    private record ObjectLink(String url, String caption) {}
    private record Mark(String markedAt, String expected, String actual) {}
    private record Screenshot(String windowId, String capturedAt, String name, String sha256) {}
    private record Draft(String expected, String actual, String journalSha256, List<BugReproductionJournal.Step> steps,
            List<ObjectLink> objectLinks, String description) {}

    private final Path root = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath()
            .resolve(".metadata/.plugins/com.codepilot1c.core/bug-reproduction");

    public Map<String, Object> start(String project, String baseUuid, String lease, String key, String backend) throws Exception {
        return start(project, baseUuid, lease, key, backend, false, null);
    }

    public Map<String, Object> prepare(String project, String baseUuid, String lease, String key) throws Exception {
        return start(project, baseUuid, lease, key, "testclient", true, null);
    }

    public Map<String, Object> repeat(String previousId, String key) throws Exception {
        synchronized (LOCK) {
            Path priorDirectory = directory(previousId);
            Session previous = load(priorDirectory, previousId);
            if (previous.prepared() || previous.pid() == 0) {
                throw new IllegalStateException("The previous session has no confirmed client");
            }
            if (alive(previous) && (!previous.controlled()
                    || !Files.isRegularFile(priorDirectory.resolve("draft.json"), NOFOLLOW_LINKS))) {
                throw new IllegalStateException("Finish the previous recording before reusing its live client");
            }
            return start(previous.project(), previous.baseUuid(), previous.lease(), key, "testclient",
                    false, previous);
        }
    }

    private Map<String, Object> start(String project, String baseUuid, String lease, String key, String backend,
            boolean prepareOnly, Session previous) throws Exception {
        if (!key.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid idempotency_key");
        }
        if (!List.of("testclient", "manual").contains(backend)) {
            throw new IllegalArgumentException("Unknown recording backend");
        }
        McpOperationAuthorization.requireAllowed(TOOL);
        String id = InfobasePaths.hash(key);
        var lifecycle = RuntimeProcessLifecycle.global();
        long epoch = lifecycle.epoch();
        synchronized (LOCK) {
            Path directory = directory(id);
            boolean prepared = false;
            if (Files.exists(directory, NOFOLLOW_LINKS)) {
                Session session = load(directory, id);
                if (!session.project().equals(project) || !session.baseUuid().equals(baseUuid)
                        || !session.lease().equals(lease) || session.controlled() != backend.equals("testclient")) {
                    throw new IllegalArgumentException("Recording key is already bound to a different test base");
                }
                prepared = session.prepared();
                if (!prepared || prepareOnly) {
                    return receipt(session, directory);
                }
                if (BugReproductionDt.busy(directory)) {
                    throw new IllegalStateException("Wait for the DT job before opening the client");
                }
            }
            var runtime = new EdtRuntimeService();
            var base = runtime.resolveDefaultInfobase(project);
            if (base == null || !baseUuid.equals(base.getUuid().toString())) {
                throw new IllegalStateException("EDT is bound to a different database");
            }
            if (backend.equals("manual") || !lease.isEmpty()) {
                requireOwnedBase(lease, project, baseUuid, base.getConnectionString().asConnectionString());
            }
            if (prepareOnly) {
                Files.createDirectories(root);
                Files.createDirectory(directory);
                var session = new Session(id, project, baseUuid, lease, 0, "prepared", null, backend);
                save(directory, "session.json", session);
                return receipt(session, directory);
            }
            if (previous != null && alive(previous)) {
                var client = new DirectTestClientService();
                String clientId = previous.clientId();
                boolean directoryExists = prepared;
                client.transferRecorder(clientId, previous.id(), id, baseUuid, state -> {
                    try {
                        if (!directoryExists) {
                            Files.createDirectory(directory);
                        }
                        // A reservation is persisted before changing the client binding.
                        save(directory, "session.json", new Session(id, project, baseUuid, lease, 0, "", clientId, backend));
                    } catch (Exception error) {
                        throw new IllegalStateException("Recording session reservation could not be saved", error);
                    }
                });
                var reused = new Session(id, project, baseUuid, lease, previous.pid(), previous.started(), clientId, backend);
                save(directory, "session.json", reused);
                return receipt(reused, directory);
            }
            if (backend.equals("testclient")) {
                if (!Boolean.TRUE.equals(runtime.synchronizationState(project).get("configuration_synchronized"))) {
                    return Map.of("state", "synchronization_required", "project", project, "base_uuid", baseUuid,
                            "message", "Update the confirmed test database separately until EDT reports native EQUAL, then retry recording.");
                }
                return startControlled(directory, id, project, baseUuid, lease, prepared);
            }
            var info = runtime.resolveThickClientInfo(base);
            var executable = info.component().getFile().toPath().resolveSibling("1cv8c.exe").toRealPath();
            var builder = new RuntimeExecutionCommandBuilder(executable.toFile(), ThickClientMode.ENTERPRISE);
            builder.forInfobase(base.getConnectionString(), false);
            builder.clientType(ClientType.THIN_CLIENT);
            var access = runtime.resolveAccessSettings(base);
            if (access.isInfobaseAuthentication()) {
                if (access.getUserName() != null) builder.userName(access.getUserName());
                if (access.getPassword() != null) builder.userPassword(access.getPassword());
            } else {
                builder.osAuthentication(true);
            }
            var command = builder.toProcessBuilder();
            command.command().addAll(List.of("/UILogRecorder", "-File", directory.resolve("actions.xml").toString()));
            command.directory(directory.toFile());
            command.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Files.createDirectories(root);
            Files.createDirectory(directory);
            save(directory, "session.json", new Session(id, project, baseUuid, lease, 0, "", null, "manual"));
            return lifecycle.launch(epoch, () -> {
                McpOperationAuthorization.requireAllowed(TOOL);
                Process child = command.start();
                lifecycle.ownProcess(epoch, directory.toString(), child);
                child.onExit().thenRun(() -> lifecycle.forget(directory.toString()));
                var session = new Session(id, project, baseUuid, lease, child.pid(),
                        child.info().startInstant().orElseThrow().toString(), null, "manual");
                save(directory, "session.json", session);
                return receipt(session, directory);
            });
        }
    }

    private static Map<String,Object> startControlled(Path directory, String id, String project,
            String baseUuid, String lease, boolean prepared) throws Exception {
        var client = new DirectTestClientService();
        client.requireRecorderConsent(project, baseUuid);
        // Startup is reserved by the dedicated TestClient before it can launch.
        var result = client.startRecorder(project, baseUuid, id, clientId -> {
            try {
                Files.createDirectories(directory.getParent());
                if (!prepared) {
                    Files.createDirectory(directory);
                }
                save(directory, "session.json", new Session(id, project, baseUuid, lease, 0, "", clientId, "testclient"));
            } catch (Exception error) {
                throw new IllegalStateException("Recording session reservation could not be saved", error);
            }
        });
        String clientId = (String) result.get("client_id");
        var session = new Session(id, project, baseUuid, lease,
                result.get("pid") instanceof Number pid ? pid.longValue() : 0,
                (String) result.get("process_started_at"), clientId, "testclient");
        save(directory, "session.json", session);
        return receipt(session, directory);
    }

    public Map<String,Object> begin(String id) throws Exception {
        McpOperationAuthorization.requireAllowed(TOOL);
        synchronized (LOCK) {
            Path directory = directory(id);
            Session session = load(directory, id);
            requireDraftOpen(directory);
            if (!session.controlled()) {
                throw new IllegalStateException("Use the native title bar controls for this manual session");
            }
            if (recorder(directory) == null) {
                var result = new DirectTestClientService().record(session.clientId(), id, session.baseUuid(), false);
                boolean started = Boolean.TRUE.equals(result.get("success"));
                String state = started ? "recording"
                        : Boolean.TRUE.equals(result.get("outcome_unknown")) ? "begin_unknown" : "begin_failed";
                save(directory, "recording.json", new Recorder(state,
                        started ? String.valueOf(result.get("acknowledged_at")) : null,
                        null, (String) result.get("operation_id")));
            }
            return receipt(session, directory);
        }
    }

    public Map<String, Object> status(String id) throws Exception {
        synchronized (LOCK) {
            Path directory = directory(id);
            return receipt(load(directory, id), directory);
        }
    }

    public Map<String, Object> mark(String id, String expected, String actual) throws Exception {
        McpOperationAuthorization.requireAllowed(TOOL);
        validateNotes(expected, actual);
        synchronized (LOCK) {
            Path directory = directory(id);
            Session session = load(directory, id);
            requireDraftOpen(directory);
            Mark previous = mark(directory);
            var updated = new Mark(previous == null ? Instant.now().toString() : previous.markedAt(),
                    expected == null ? previous == null ? "" : previous.expected() : expected,
                    actual == null ? previous == null ? "" : previous.actual() : actual);
            if (!updated.equals(previous)) {
                save(directory, "mark.json", updated);
            }
            String linkState;
            try {
                linkState = observeObjectLink(session, directory);
            } catch (Exception failure) {
                linkState = "link_not_saved";
            }
            var result = receipt(session, directory);
            result.put("object_link_state", linkState);
            return result;
        }
    }

    public Map<String, Object> captureLink(String id) throws Exception {
        McpOperationAuthorization.requireAllowed(TOOL);
        synchronized (LOCK) {
            Path directory = directory(id);
            Session session = load(directory, id);
            requireDraftOpen(directory);
            String outcome = observeObjectLink(session, directory);
            var result = receipt(session, directory);
            result.put("object_link_state", outcome);
            return result;
        }
    }

    static boolean isObjectLink(String value) {
        return value != null && value.length() <= 512 && OBJECT_LINK.matcher(value).matches()
                && !value.endsWith("00000000000000000000000000000000");
    }

    private static String observeObjectLink(Session session, Path directory) throws Exception {
        if (!session.controlled() || !alive(session)) {
            return "client_unavailable";
        }
        Map<String,Object> result;
        try {
            result = new DirectTestClientService().observe(Map.of("client_id", session.clientId(),
                    "view", "windows", "operation_timeout_s", 5, "wait_timeout_ms", 0));
        } catch (Exception unavailable) {
            return "observation_unavailable";
        }
        if (!Boolean.TRUE.equals(result.get("success")) || !(result.get("window") instanceof Map<?,?> window)) {
            return "observation_unavailable";
        }
        if (!(window.get("url") instanceof String url) || !isObjectLink(url)) {
            return "no_object_link";
        }
        List<ObjectLink> links = new ArrayList<>(objectLinks(directory));
        String reference = url.substring(url.indexOf("?ref=") + 5);
        url = url.substring(0, url.indexOf("?ref=") + 5) + reference.toLowerCase(java.util.Locale.ROOT);
        final String normalized = url;
        if (links.stream().anyMatch(link -> link.url().equals(normalized))) {
            return "already_saved";
        }
        if (links.size() >= 64) {
            return "limit_reached";
        }
        String caption = window.get("title") instanceof String title ? title.substring(0, Math.min(256, title.length())) : "";
        links.add(new ObjectLink(normalized, caption));
        save(directory, "object-links.json", links.toArray(ObjectLink[]::new));
        return "saved";
    }

    private static List<ObjectLink> objectLinks(Path directory) throws Exception {
        Path path = directory.resolve("object-links.json");
        return Files.exists(path, NOFOLLOW_LINKS) ? List.of(read(path, ObjectLink[].class, 128 * 1024)) : List.of();
    }

    private static void requireOwnershipLease(String lease) {
        if (lease.isEmpty()) {
            throw new IllegalArgumentException("This operation requires a disposable file base and its owned_base_id");
        }
    }

    private static void requireOwnedBase(String lease, String project, String baseUuid, String connectionString)
            throws Exception {
        requireOwnershipLease(lease);
        var ownership = new OwnedFileBaseService().preview(lease, project, baseUuid);
        String connection = InfobasePaths.connectionIdentity(connectionString);
        if (Boolean.TRUE.equals(ownership.get("cleaned")) || Boolean.TRUE.equals(ownership.get("delete_started"))
                || !connection.equalsIgnoreCase("file:" + ownership.get("database_path"))) {
            throw new IllegalStateException("The confirmed disposable file base is unavailable");
        }
    }

    public void requireDtOwnership(String id) throws Exception {
        requireOwnershipLease(load(directory(id), id).lease());
    }

    public Map<String, Object> dumpDt(String id, Consumer<Map<String, Object>> progress) throws Exception {
        Path directory = directory(id);
        Session session = load(directory, id);
        requireOwnershipLease(session.lease());
        try {
            BugReproductionDt.dump(directory, target -> {
                var runtime = new EdtRuntimeService();
                var base = runtime.resolveDefaultInfobase(session.project());
                if (base == null || !session.baseUuid().equals(base.getUuid().toString())) {
                    throw new IllegalStateException("EDT is bound to a different database");
                }
                try (var workflow = new InfobaseWorkflow(InfobaseWorkflow.resourceKeys(session.project(), base), progress)) {
                    workflow.stage("verify_recorded_base");
                    requireOwnedBase(session.lease(), session.project(), session.baseUuid(),
                            base.getConnectionString().asConnectionString());
                    workflow.stage("dump_dt");
                    new NativeInfobaseGateway().dumpRegistered(base, target, new NullProgressMonitor());
                }
            });
        } catch (Exception failure) {
            if (!exclusiveLock(failure)) throw failure;
            var result = status(id);
            result.put("dt_dump_error", "infobase_in_use");
            result.put("dt_dump_hint", "Close sessions for this disposable base before exporting. Bridge will not close them.");
            return result;
        }
        return status(id);
    }

    private static boolean exclusiveLock(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String details = java.util.Objects.toString(cause.getMessage(), "").toLowerCase(java.util.Locale.ROOT);
            if (details.contains("исключительной блокировк") || details.contains("exclusive lock")) return true;
        }
        return false;
    }

    public Map<String, Object> copyDt(String id, String exchangeDirectory) throws Exception {
        Path directory = directory(id);
        requireOwnershipLease(load(directory, id).lease());
        try {
            BugReproductionDt.copy(directory, exchangeDirectory);
        } catch (Exception failure) {
            var result = status(id);
            result.put("dt_copy_error", failure instanceof IllegalArgumentException || failure instanceof IllegalStateException
                    ? failure.getMessage() : "Could not verify or complete DT transfer");
            return result;
        }
        return status(id);
    }

    public Map<String, Object> videoStart(String id, String windowId, String key) throws Exception {
        McpOperationAuthorization.requireAllowed(TOOL);
        synchronized (LOCK) {
            Path directory = directory(id);
            Session session = load(directory, id);
            if (BugReproductionVideo.exists(directory, key)) {
                BugReproductionVideo.start(directory, session.pid(), session.started(), windowId, key);
                return receipt(session, directory);
            }
            requireDraftOpen(directory);
            if (!alive(session)) {
                throw new IllegalStateException("The original 1C client is unavailable");
            }
            BugReproductionVideo.start(directory, session.pid(), session.started(), selectWindow(session, windowId), key);
            return receipt(session, directory);
        }
    }

    public Map<String, Object> videoStop(String id, String videoId) throws Exception {
        McpOperationAuthorization.requireAllowed(TOOL);
        synchronized (LOCK) {
            Path directory = directory(id);
            Session session = load(directory, id);
            BugReproductionVideo.stop(directory, videoId);
            return receipt(session, directory);
        }
    }

    public Map<String, Object> screenshot(String id, String windowId) throws Exception {
        McpOperationAuthorization.requireAllowed(TOOL);
        synchronized (LOCK) {
            Path directory = directory(id);
            Session session = load(directory, id);
            requireDraftOpen(directory);
            if (!alive(session)) {
                throw new IllegalStateException("The original 1C client is unavailable");
            }
            List<Screenshot> saved = new ArrayList<>(screenshots(directory));
            if (saved.size() >= MAX_SCREENSHOTS) {
                throw new IllegalStateException("The recording session already contains eight screenshots");
            }
            String target = selectWindow(session, windowId);
            var image = ProcessWindowCapture.execute("snapshot", session.pid(), session.started(), target, false, false);
            Path source = Path.of((String) image.get("image_path"));
            try {
                if (!Boolean.TRUE.equals(image.get("success")) || !Boolean.TRUE.equals(image.get("content_informative"))
                        || !alive(session)) {
                    throw new IllegalStateException("The original 1C window could not be captured");
                }
                byte[] bytes;
                try (var input = Files.newInputStream(source, NOFOLLOW_LINKS)) {
                    bytes = input.readNBytes(8 * 1024 * 1024 + 1);
                }
                if (bytes.length > 8 * 1024 * 1024) {
                    throw new IllegalStateException("The screenshot exceeds 8 MiB");
                }
                String name = "screenshot-" + (saved.size() + 1) + ".png";
                saveBytes(directory, name, bytes);
                saved.add(new Screenshot(target, (String) image.get("captured_at"), name, sha256(bytes)));
                save(directory, "screenshots.json", saved.toArray(Screenshot[]::new));
                return receipt(session, directory);
            } finally {
                Files.deleteIfExists(source);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static String selectWindow(Session session, String requested) throws Exception {
        var inventory = ProcessWindowCapture.execute("list", session.pid(), null, null);
        var processes = (List<Map<String, Object>>) inventory.get("processes");
        if (processes.size() != 1 || !session.started().equals(processes.get(0).get("process_started_at"))) {
            throw new IllegalStateException("The original 1C process changed");
        }
        var windows = (List<Map<String, Object>>) processes.get(0).get("windows");
        var candidates = windows.stream().filter(window ->
                Boolean.TRUE.equals(window.get("visible")) && !Boolean.TRUE.equals(window.get("minimized"))
                && ((String) window.get("window_class")).startsWith("V8")
                && ((String) window.get("title")).length() > 0
                && (requested == null ? "0x0".equals(window.get("owner_window_id"))
                        && !Boolean.TRUE.equals(window.get("popup_style")) : requested.equals(window.get("window_id"))))
                .toList();
        if (candidates.size() != 1) {
            throw new IllegalStateException("The selected 1C window is absent or ambiguous");
        }
        return (String) candidates.get(0).get("window_id");
    }

    public Map<String, Object> finish(String id, String expected, String actual) throws Exception {
        McpOperationAuthorization.requireAllowed(TOOL);
        validateNotes(expected, actual);
        synchronized (LOCK) {
            Path directory = directory(id);
            Session session = load(directory, id);
            Mark mark = mark(directory);
            expected = expected == null ? mark == null ? "" : mark.expected() : expected;
            actual = actual == null ? mark == null ? "" : mark.actual() : actual;
            Path journal = directory.resolve("actions.xml");
            Path draftPath = directory.resolve("draft.json");
            if (Files.exists(draftPath, NOFOLLOW_LINKS)) {
                Draft recorded = read(draftPath, Draft.class, 2 * MAX_JOURNAL_BYTES);
                if (!recorded.expected().equals(expected) || !recorded.actual().equals(actual)) {
                    throw new IllegalArgumentException("A draft with different notes already exists");
                }
                return receipt(session, directory);
            }
            BugReproductionVideo.stopActive(directory);
            if (session.controlled() && !Files.isRegularFile(journal, NOFOLLOW_LINKS)) {
                stopControlled(session, directory, journal);
            }
            if (Files.isRegularFile(journal, NOFOLLOW_LINKS)) {
                try (var input = Files.newInputStream(journal, NOFOLLOW_LINKS)) {
                    byte[] bytes = input.readNBytes(MAX_JOURNAL_BYTES + 1);
                    var steps = BugReproductionJournal.read(bytes);
                    String digest = sha256(bytes);
                    saveBytes(directory, "journal-evidence.xml", bytes);
                    var links = objectLinks(directory);
                    save(directory, "draft.json", new Draft(expected, actual, digest, steps, links,
                            description(expected, actual, steps, links)));
                }
            }
            return receipt(session, directory);
        }
    }

    private static String description(String expected, String actual, List<BugReproductionJournal.Step> steps,
            List<ObjectLink> links) {
        var text = new StringBuilder("## Ожидаемый результат\n").append(expected)
                .append("\n\n## Фактический результат\n").append(actual);
        if (!steps.isEmpty()) {
            text.append("\n\n## Шаги воспроизведения\n");
            for (var step : steps) {
                String title = step.elementTitle().isBlank() ? step.element() : step.elementTitle();
                text.append(step.number()).append(". ");
                if (!step.form().isBlank()) {
                    text.append(step.form()).append(" — ");
                }
                text.append(title).append('\n');
            }
        }
        if (!links.isEmpty()) {
            text.append("\n\n## Объекты 1С\n");
            for (var link : links) {
                text.append("- ");
                if (!link.caption().isBlank()) {
                    text.append(link.caption().replaceAll("\\p{Cntrl}", " ")).append(" — ");
                }
                text.append(link.url()).append('\n');
            }
        }
        return text.toString();
    }

    private static void stopControlled(Session session, Path directory, Path journal) throws Exception {
        Recorder original = recorder(directory);
        if (original == null || !"recording".equals(original.state())) {
            return;
        }
        var client = new DirectTestClientService();
        var result = client.record(session.clientId(), session.id(), session.baseUuid(), true);
        String operationId = (String) result.get("operation_id");
        if (!Boolean.TRUE.equals(result.get("success"))) {
            String state = Boolean.TRUE.equals(result.get("outcome_unknown")) ? "finish_unknown"
                    : Boolean.TRUE.equals(result.get("recording_acknowledged")) ? "journal_unavailable" : "finish_failed";
            save(directory, "recording.json", new Recorder(state, original.startedAt(), null, operationId));
            return;
        }
        byte[] xml = client.recordingJournal(session.clientId(), result);
        saveBytes(directory, journal.getFileName().toString(), xml);
        save(directory, "recording.json", new Recorder("journal_saved", original.startedAt(),
                String.valueOf(result.get("acknowledged_at")), operationId));
    }

    private static Recorder recorder(Path directory) throws Exception {
        Path path = directory.resolve("recording.json");
        return Files.exists(path, NOFOLLOW_LINKS) ? read(path, Recorder.class, 8192) : null;
    }

    private static Map<String, Object> receipt(Session session, Path directory) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("session_id", session.id());
        result.put("project", session.project());
        result.put("base_uuid", session.baseUuid());
        result.put("backend", session.controlled() ? "testclient" : "manual");
        if (session.clientId() != null) {
            result.put("client_id", session.clientId());
        }
        Recorder recorder = recorder(directory);
        if (recorder != null) {
            result.put("recording_operation_id", recorder.operationId());
            if (recorder.startedAt() != null && !"null".equals(recorder.startedAt())) {
                result.put("recording_started_at", recorder.startedAt());
                Instant until = recorder.stoppedAt() == null ? Instant.now() : Instant.parse(recorder.stoppedAt());
                if (alive(session) && "recording".equals(recorder.state()) || recorder.stoppedAt() != null) {
                    result.put("recording_elapsed_seconds", Math.max(0, Duration.between(Instant.parse(recorder.startedAt()), until).toSeconds()));
                }
            }
        }
        boolean running = alive(session);
        result.put("client_alive", running);
        Mark mark = mark(directory);
        if (mark != null) {
            result.put("mark", Map.of("marked_at", mark.markedAt(), "expected", mark.expected(), "actual", mark.actual()));
        }
        var attachments = screenshots(directory).stream().map(image -> Map.of(
                "window_id", image.windowId(), "captured_at", image.capturedAt(), "sha256", image.sha256(),
                "path", directory.resolve(image.name()).toString())).toList();
        result.put("screenshots", attachments);
        result.put("object_links", objectLinks(directory));
        result.put("videos", BugReproductionVideo.receipts(directory));
        var dt = BugReproductionDt.receipt(directory);
        result.put("dt", dt);
        Path draft = directory.resolve("draft.json"), journal = directory.resolve("actions.xml");
        if (Files.isRegularFile(draft, NOFOLLOW_LINKS)) {
            result.put("state", "draft_ready");
            Draft saved = read(draft, Draft.class, 2 * MAX_JOURNAL_BYTES);
            if (dt.get("exchange_path") instanceof String exchangePath) {
                saved = new Draft(saved.expected(), saved.actual(), saved.journalSha256(), saved.steps(),
                        saved.objectLinks(), java.util.Objects.toString(saved.description(), "")
                        + "\n\n## Выгрузка базы 1С\n" + exchangePath);
            }
            result.put("draft", saved);
            result.put("journal_path", directory.resolve("journal-evidence.xml").toString());
            result.put("journal_scope", "recognized_button_clicks");
            result.put("recording_state", "journal_saved");
        } else if (Files.isRegularFile(journal, NOFOLLOW_LINKS)) {
            result.put("state", "journal_available");
            result.put("recording_state", "journal_saved");
            result.put("next_call", call("finish", session.id()));
        } else {
            result.put("state", session.prepared() ? "prepared" : session.pid() == 0 ? "launch_outcome_unknown"
                    : running ? "awaiting_native_journal" : "client_closed_before_journal");
            String recordingState = recorder == null ? session.controlled() ? "ready" : "unconfirmed" : recorder.state();
            result.put("recording_state", session.controlled() && !running && session.pid() > 0
                    ? "client_exited" : recordingState);
            if (running) {
                result.put("session_elapsed_seconds", Math.max(0, Duration.between(Instant.parse(session.started()), Instant.now()).toSeconds()));
                if (!session.controlled()) {
                    result.put("user_action", "Start and save the journal using the recorder buttons in the 1C title bar.");
                    result.put("next_call", call("status", session.id()));
                } else if (recorder == null) {
                    result.put("next_call", call("begin", session.id()));
                } else if ("recording".equals(recorder.state())) {
                    result.put("next_call", call("finish", session.id()));
                }
            }
        }
        return result;
    }

    private static boolean alive(Session session) {
        return session.pid() > 0 && ProcessHandle.of(session.pid()).filter(ProcessHandle::isAlive)
                .flatMap(handle -> handle.info().startInstant()).map(Object::toString)
                .filter(session.started()::equals).isPresent();
    }

    private static void validateNotes(String expected, String actual) {
        if (expected != null && expected.length() > 2000 || actual != null && actual.length() > 2000) {
            throw new IllegalArgumentException("Each note is limited to 2000 characters");
        }
    }

    private static void requireDraftOpen(Path directory) {
        if (Files.exists(directory.resolve("draft.json"), NOFOLLOW_LINKS)) {
            throw new IllegalStateException("The recording draft is already prepared");
        }
    }

    private static Mark mark(Path directory) throws Exception {
        Path path = directory.resolve("mark.json");
        return Files.exists(path, NOFOLLOW_LINKS) ? read(path, Mark.class, 32_768) : null;
    }

    private static List<Screenshot> screenshots(Path directory) throws Exception {
        Path path = directory.resolve("screenshots.json");
        return Files.exists(path, NOFOLLOW_LINKS) ? List.of(read(path, Screenshot[].class, 8192)) : List.of();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Map<String, Object> call(String action, String sessionId) {
        return Map.of("tool", "edt.run", "arguments",
                Map.of("op", TOOL, "args", Map.of("action", action, "session_id", sessionId)));
    }

    private Path directory(String id) throws Exception {
        if (!id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid session_id");
        return InfobasePaths.absolute(root.resolve(id).toString());
    }

    private static Session load(Path directory, String id) throws Exception {
        var session = read(directory.resolve("session.json"), Session.class, 8192);
        if (!id.equals(session.id())) throw new IllegalStateException("Recording session identity changed");
        return session;
    }

    static <T> T read(Path path, Class<T> type, int limit) throws Exception {
        try (var input = Files.newInputStream(path, NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IllegalStateException("Recording state exceeds size limit");
            return AgentResponses.JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), type);
        }
    }

    static void save(Path directory, String name, Object value) throws Exception {
        saveBytes(directory, name, AgentResponses.JSON.toJson(value).getBytes(StandardCharsets.UTF_8));
    }

    private static void saveBytes(Path directory, String name, byte[] data) throws Exception {
        Path temp = Files.createTempFile(directory, "record-", ".tmp");
        try {
            Files.write(temp, data);
            Files.move(temp, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
