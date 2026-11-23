// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.ui.PlatformUI;

import com.codepilot1c.core.edt.editor.EdtEditorService;
import com.codepilot1c.core.internal.VibeCorePlugin;

public final class AgentHistoryService {
    public enum Pane {
        MESSAGES("messages", "com.codepilot1c.ui.views.agentMessages"),
        CHANGES("changes", "com.codepilot1c.ui.views.agentChanges");

        public final String key;
        final String view;
        Pane(String key, String view) { this.key = key; this.view = view; }
        public static Pane parse(String key) {
            for (Pane pane : values()) if (pane.key.equals(key)) return pane;
            throw new IllegalArgumentException("Choose messages or changes");
        }
    }

    /** Implemented by the workbench views. Called on the EDT UI thread. */
    public interface HistoryView { void selectTurn(String turnId); }
    public record Turn(String turnId, String title, String time, int count) {}
    public record Message(String id, String text, Target target) {}
    public record Target(String label, String kind, String project, String filePath,
            Integer line, String method, Integer elementId, String mode) {}
    public record Messages(String turnId, String title, String time, List<Message> messages) {}
    record Entry(String id, String revision, Instant modified, boolean protectedEntry) {}

    private static final Object LOCK = new Object();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Pattern ID = Pattern.compile("turn-[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");
    private static final int MAX_TURNS = 100;
    private static final int MAX_ROWS = 100;
    private static final long MAX_BYTES = 4 * 1024 * 1024;

    public static void addListener(Runnable listener) { LISTENERS.add(listener); }
    public static void removeListener(Runnable listener) { LISTENERS.remove(listener); }
    private static void notifyMessages() {
        for (Runnable listener : LISTENERS) {
            try { listener.run(); } catch (RuntimeException ignored) { /* Disposing view. */ }
        }
    }

    static int retentionHours(Pane pane) {
        return InstanceScope.INSTANCE.getNode("com.codepilot1c.core").getInt("agentHistory." + pane.key, 24);
    }

    private static Path folder() throws java.io.IOException {
        Path root = VibeCorePlugin.getDefault().getStateLocation().toFile().toPath();
        Path folder = root.resolve("agent-messages");
        if (Files.exists(folder, NOFOLLOW_LINKS) && (!Files.isDirectory(folder, NOFOLLOW_LINKS)
                || !folder.toRealPath().getParent().equals(root.toRealPath()))) {
            throw new IllegalStateException("Message history must be stored inside the EDT workspace");
        }
        return folder;
    }

    static String checkedId(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid turn_id");
        return id;
    }

    private static List<Path> paths() throws Exception {
        if (!Files.isDirectory(folder(), NOFOLLOW_LINKS)) return List.of();
        try (Stream<Path> files = Files.list(folder())) {
            return files.filter(file -> {
                String name = file.getFileName().toString();
                return name.endsWith(".json") && ID.matcher(name.substring(0, name.length() - 5)).matches()
                        && Files.isRegularFile(file, NOFOLLOW_LINKS);
            }).toList();
        }
    }

    private static Messages read(Path path) throws Exception {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES) {
            throw new IllegalArgumentException("Message turn is unavailable");
        }
        try (var input = Files.newInputStream(path, NOFOLLOW_LINKS);
                var reader = new java.io.InputStreamReader(input, StandardCharsets.UTF_8)) {
            Messages entry = AgentResponses.JSON.fromJson(reader, Messages.class);
            if (entry == null || !(entry.turnId() + ".json").equals(path.getFileName().toString())
                    || entry.title() == null || entry.messages() == null
                    || entry.messages().stream().anyMatch(row -> row == null || row.id() == null || row.text() == null)) {
                throw new IllegalArgumentException("Invalid message history entry");
            }
            Instant.parse(entry.time());
            return entry;
        }
    }

    public Messages messages(String id) throws Exception {
        synchronized (LOCK) { return read(folder().resolve(checkedId(id) + ".json")); }
    }

    public List<Turn> history(Pane pane) throws Exception {
        if (pane == Pane.CHANGES) {
            var titles = new LinkedHashMap<String, String>();
            for (var row : history(Pane.MESSAGES)) titles.put(row.turnId(), row.title());
            return new BslReviewService().turnHistory().stream().map(turn -> new Turn(turn.id(),
                    titles.getOrDefault(turn.id(), turn.files().stream().map(BslReviewService.TurnFile::project)
                            .distinct().limit(3).reduce((a,b) -> a + ", " + b).orElse("Изменения ИИ")),
                    turn.finishedAt(), turn.files().size())).toList();
        }
        synchronized (LOCK) {
            var rows = new ArrayList<Turn>();
            for (Path file : paths()) {
                try {
                    Messages entry = read(file);
                    rows.add(new Turn(entry.turnId(), entry.title(), entry.time(), entry.messages().size()));
                } catch (Exception unreadable) {
                    // Other turns remain available. Clear-all uses file identity even for broken entries.
                }
            }
            rows.sort(Comparator.comparing(Turn::time).reversed().thenComparing(Turn::turnId));
            return rows;
        }
    }

    public Messages publish(String id, String title, List<Message> batch) throws Exception {
        if (batch.isEmpty() || batch.size() > MAX_ROWS) throw new IllegalArgumentException("Provide 1 to 100 messages");
        String turn = id == null ? "turn-" + UUID.randomUUID() : checkedId(id);
        Messages result;
        synchronized (LOCK) {
            Files.createDirectories(folder());
            Path path = folder().resolve(turn + ".json");
            Messages original = Files.exists(path, NOFOLLOW_LINKS) ? read(path) : null;
            if (original == null && paths().size() >= MAX_TURNS) {
                throw new IllegalStateException("Message history is full; clear old turns first");
            }
            var rows = new LinkedHashMap<String, Message>();
            if (original != null) for (Message item : original.messages()) rows.put(item.id(), item);
            int count = rows.size();
            for (Message item : batch) {
                Message previous = rows.putIfAbsent(item.id(), item);
                if (previous != null && !previous.equals(item)) {
                    throw new IllegalArgumentException("message_id already identifies a different message: " + item.id());
                }
            }
            if (rows.size() > MAX_ROWS) throw new IllegalArgumentException("Too many messages in this turn");
            String heading = title != null ? title : original == null ? "Сообщения ИИ" : original.title();
            if (original != null && count == rows.size() && original.title().equals(heading)) return original;
            result = new Messages(turn, heading, Instant.now().toString(), List.copyOf(rows.values()));
            byte[] content = AgentResponses.JSON.toJson(result).getBytes(StandardCharsets.UTF_8);
            if (content.length > MAX_BYTES) throw new IllegalArgumentException("Message turn exceeds the storage limit");
            Path pending = Files.createTempFile(folder(), "messages-", ".tmp");
            try {
                Files.write(pending, content);
                Files.move(pending, path, ATOMIC_MOVE, REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(pending);
            }
        }
        notifyMessages();
        return result;
    }

    private static List<Entry> messageEntries() throws Exception {
        var entries = new ArrayList<Entry>();
        for (Path file : paths()) {
            String name = file.getFileName().toString();
            String id = name.substring(0, name.length() - 5);
            Instant time = Files.getLastModifiedTime(file, NOFOLLOW_LINKS).toInstant();
            entries.add(new Entry(id, time + ":" + Files.size(file), time, BslReviewService.turnActive(id)));
        }
        return entries;
    }

    public Map<String, Object> cleanup(Pane pane, String id, boolean all, String expected) throws Exception {
        if (all == (id != null)) throw new IllegalArgumentException("Choose a turn_id or scope=all");
        if (id != null) checkedId(id);
        if (pane == Pane.CHANGES) return new BslReviewService().cleanupHistory(id, all, expected);
        Map<String, Object> receipt;
        synchronized (LOCK) {
            receipt = cleanupEntries(messageEntries(), id, all, expected,
                    entry -> Files.deleteIfExists(folder().resolve(entry + ".json")));
        }
        if (Boolean.TRUE.equals(receipt.get("applied"))) notifyMessages();
        return receipt;
    }

    private static List<List<Object>> revisions(List<Entry> entries) {
        return entries.stream().map(row -> List.<Object>of(row.id(), row.revision(), row.protectedEntry())).toList();
    }

    interface Removal { boolean remove(String id) throws Exception; }

    static Map<String, Object> cleanupEntries(List<Entry> entries, String id, boolean all,
            String expected, Removal remove) throws Exception {
        var selected = entries.stream().filter(row -> all || row.id().equals(id))
                .sorted(Comparator.comparing(Entry::id)).toList();
        String hash = EdtChangeSetService.hash(AgentResponses.JSON.toJson(revisions(selected)).getBytes(StandardCharsets.UTF_8));
        var eligible = selected.stream().filter(row -> !row.protectedEntry()).toList();
        int count = 0;
        var failed = new ArrayList<String>();
        if (expected != null) {
            if (!hash.equals(expected)) throw new IllegalArgumentException("History changed; preview cleanup again");
            for (Entry row : eligible) {
                try { if (remove.remove(row.id())) count++; }
                catch (java.io.IOException busy) { failed.add(row.id()); }
            }
        }
        return Map.of("preview_sha256", hash, "eligible_count", eligible.size(), "protected_count",
                selected.size() - eligible.size(), "removed_count", count, "failed_turn_ids", failed,
                "applied", expected != null);
    }

    public Map<String, Object> retention(Pane pane, Integer hours, String expected) throws Exception {
        int current = retentionHours(pane);
        if (hours == null) return Map.of("retention_hours", current);
        if (hours < 1 || hours > 8760) throw new IllegalArgumentException("retention_hours must be 1 to 8760");
        synchronized (LOCK) {
            var entries = pane == Pane.MESSAGES ? messageEntries() : new BslReviewService().historyEntries();
            Instant cutoff = Instant.now().minus(hours, ChronoUnit.HOURS);
            var expired = entries.stream().filter(row -> row.modified().isBefore(cutoff) && !row.protectedEntry())
                    .sorted(Comparator.comparing(Entry::id)).toList();
            String hash = EdtChangeSetService.hash(AgentResponses.JSON.toJson(List.of(pane.key, current, hours, revisions(expired)))
                    .getBytes(StandardCharsets.UTF_8));
            if (expected != null) {
                if (!expected.equals(hash)) throw new IllegalArgumentException("Retention preview changed; preview again");
                var prefs = InstanceScope.INSTANCE.getNode("com.codepilot1c.core");
                prefs.putInt("agentHistory." + pane.key, hours);
                prefs.flush();
            }
            return Map.of("retention_hours", expected == null ? current : hours, "requested_hours", hours,
                    "eligible_count", expired.size(), "preview_sha256", hash, "applied", expected != null);
        }
    }

    static void pruneMessages() throws Exception {
        boolean changed = false;
        synchronized (LOCK) {
            Instant cutoff = Instant.now().minus(retentionHours(Pane.MESSAGES), ChronoUnit.HOURS);
            for (Entry entry : messageEntries()) {
                if (!entry.protectedEntry() && entry.modified().isBefore(cutoff)) {
                    changed |= Files.deleteIfExists(folder().resolve(entry.id() + ".json"));
                }
            }
        }
        if (changed) notifyMessages();
    }

    public boolean hasTurn(Pane pane, String id) {
        if (id == null) return false;
        try {
            if (pane == Pane.MESSAGES) messages(id);
            else new BslReviewService().turnSummary(id);
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }

    public Map<String, Object> show(Pane pane, String id) throws Exception {
        if (id != null && !hasTurn(pane, id)) {
            throw new IllegalArgumentException("The selected turn is unavailable in " + pane.key);
        }
        if (!PlatformUI.isWorkbenchRunning()) throw new IllegalStateException("EDT workbench is unavailable");
        var workbench = PlatformUI.getWorkbench();
        workbench.getDisplay().syncExec(() -> {
            var window = workbench.getActiveWorkbenchWindow();
            var page = window == null ? null : window.getActivePage();
            if (page == null) throw new IllegalStateException("EDT workbench has no active page");
            try {
                if (page.showView(pane.view) instanceof HistoryView view) view.selectTurn(id);
                else throw new IllegalStateException("History view is unavailable");
            } catch (org.eclipse.ui.PartInitException ex) {
                throw new IllegalStateException("Could not open history view", ex);
            }
        });
        return id == null ? Map.of("state", "open") : Map.of("state", "open", "turn_id", id);
    }

    public Map<String, Object> openTarget(String turnId, Target target) throws Exception {
        if (target.kind().equals("comparison")) {
            return new BslReviewService().openTurn(turnId, target.project(), target.filePath(),
                    target.mode() == null ? "lines" : target.mode());
        }
        var args = new LinkedHashMap<String, Object>();
        args.put("project", target.project());
        args.put("file_path", target.filePath());
        if (target.line() != null) args.put("line", target.line());
        if (target.method() != null) args.put("method", target.method());
        var editor = EdtEditorService.getInstance();
        var opened = editor.execute("open", args);
        if (target.elementId() == null) return opened;
        var form = Map.<String, Object>of("editor_id", opened.get("editor_id"));
        var current = opened;
        for (int i = 0; !Boolean.TRUE.equals(current.get("ready")) && i < 16; i++) {
            Thread.sleep(250);
            current = editor.execute("tabs", form);
        }
        if (!Boolean.TRUE.equals(current.get("ready"))) {
            throw new IllegalStateException("Форма открывается. Повторите переход после её загрузки.");
        }
        if (!(current.get("tabs") instanceof List<?> tabs) || tabs.stream().noneMatch(
                item -> item instanceof Map<?, ?> tab && "editors.form.pages.main".equals(tab.get("id"))
                        && Boolean.TRUE.equals(tab.get("active")))) {
            editor.execute("select_tab", Map.of("editor_id", opened.get("editor_id"),
                    "tab_id", "editors.form.pages.main"));
        }
        for (int delay : new int[]{0, 250, 500, 1000, 2000}) {
            if (delay != 0) Thread.sleep(delay);
            try {
                var selection = editor.execute("form_preview", Map.of("editor_id", form.get("editor_id"),
                        "mode", "select", "element_id", target.elementId(), "limit", 1));
                if (Set.of("projection_unavailable", "native_rebuild_not_ready", "native_rebuild_busy")
                        .contains(Objects.toString(selection.get("reason"), ""))) continue;
                if (Boolean.TRUE.equals(selection.get("ready")) && selection.get("selected") instanceof List<?> selected
                        && selected.stream().anyMatch(item -> item instanceof Map<?, ?> row
                        && row.get("id") instanceof Number id && id.intValue() == target.elementId())) {
                    return Map.of("ready", true, "element_id", target.elementId(), "editor_id", opened.get("editor_id"));
                }
                break;
            } catch (IllegalStateException loading) {
                if (!"Open a ready Form page first".equals(loading.getMessage())) throw loading;
            }
        }
        throw new IllegalStateException("Форма открыта. EDT не подтвердил выделение элемента.");
    }

    public static Target target(String label, String kind, String project, String file, Integer line,
            String method, Integer element, String mode) {
        if (!Set.of("editor", "comparison").contains(kind)) throw new IllegalArgumentException("Unknown target kind");
        BslReviewService.validateTurnPath(file);
        String lower = file.toLowerCase(Locale.ROOT);
        if (kind.equals("editor") && !(lower.endsWith(".bsl") || lower.endsWith(".form") || lower.endsWith(".mdo"))) {
            throw new IllegalArgumentException("Editor target requires .bsl, .form or .mdo");
        }
        if (line != null && (!kind.equals("editor") || !lower.endsWith(".bsl") || line < 1)
                || method != null && (!kind.equals("editor") || !lower.endsWith(".bsl") || line != null)
                || element != null && (!kind.equals("editor") || !lower.endsWith(".form") || element < 1)
                || mode != null && (!kind.equals("comparison") || !Set.of("lines", "methods").contains(mode)
                        || mode.equals("methods") && !lower.endsWith(".bsl"))) {
            throw new IllegalArgumentException("Target parameters do not match its file type");
        }
        return new Target(label, kind, project, file, line, method, element, mode);
    }
}
