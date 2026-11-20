// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;

public final class BugReproductionPanel {
    // Accessed only from the workbench UI thread.
    private static final Map<String, Shell> OPEN = new HashMap<>();

    private final BugReproductionService service = new BugReproductionService();
    private final String id;
    private final Shell shell;
    private final Label status;
    private final Label feedback;
    private final Text expected;
    private final Text actual;
    private final Button start;
    private final Button mark;
    private final Button snapshot;
    private final Button link;
    private final Button video;
    private String activeVideoId;
    private final Button finish;
    private boolean controlledClient;
    private boolean reading;
    private boolean busy;

    public static String show(String sessionId, Map<String, Object> initial) {
        if (!PlatformUI.isWorkbenchRunning()) {
            return "unavailable";
        }
        Display display = PlatformUI.getWorkbench().getDisplay();
        if (display.isDisposed()) {
            return "unavailable";
        }
        var opened = new CompletableFuture<String>();
        display.asyncExec(() -> {
            try {
                Shell current = OPEN.get(sessionId);
                if (current != null && !current.isDisposed()) {
                    current.setMinimized(false);
                    current.forceActive();
                } else {
                    var panel = new BugReproductionPanel(display, sessionId, initial);
                    OPEN.put(sessionId, panel.shell);
                    panel.shell.open();
                    panel.scheduleRefresh();
                }
                opened.complete("open");
            } catch (RuntimeException failure) {
                opened.complete("unavailable");
            }
        });
        try {
            return opened.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "opening";
        } catch (Exception pending) {
            return "opening";
        }
    }

    private BugReproductionPanel(Display display, String sessionId, Map<String, Object> initial) {
        id = sessionId;
        shell = new Shell(display, SWT.TOOL | SWT.ON_TOP | SWT.TITLE | SWT.CLOSE | SWT.RESIZE);
        shell.setText("Запись воспроизведения");
        shell.setLayout(new GridLayout(1, false));
        shell.addDisposeListener(event -> OPEN.remove(id, shell));

        var heading = new Composite(shell, SWT.NONE);
        heading.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        heading.setLayout(new GridLayout(2, false));
        var base = new Label(heading, SWT.WRAP);
        base.setText(String.valueOf(initial.get("project")) + " · " + initial.get("base_uuid"));
        var baseLayout = new GridData(SWT.FILL, SWT.CENTER, true, false);
        baseLayout.widthHint = 350;
        base.setLayoutData(baseLayout);
        Button collapse = new Button(heading, SWT.PUSH);
        collapse.setText("Свернуть");

        status = new Label(shell, SWT.WRAP);
        var statusLayout = new GridData(SWT.FILL, SWT.CENTER, true, false);
        statusLayout.widthHint = 440;
        status.setLayoutData(statusLayout);

        var notes = new Composite(shell, SWT.NONE);
        notes.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        notes.setLayout(new GridLayout(1, false));
        expected = input(notes, "Что ожидалось");
        actual = input(notes, "Что произошло");
        if (initial.get("mark") instanceof Map<?, ?> saved) {
            expected.setText(String.valueOf(saved.get("expected")));
            actual.setText(String.valueOf(saved.get("actual")));
        }
        collapse.addListener(SWT.Selection, event -> {
            var layout = (GridData) notes.getLayoutData();
            layout.exclude = !layout.exclude;
            notes.setVisible(!layout.exclude);
            collapse.setText(layout.exclude ? "Развернуть" : "Свернуть");
            shell.pack();
        });

        var buttons = new Composite(shell, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        buttons.setLayout(new GridLayout(2, false));
        start = button(buttons, "Начать запись");
        start.addListener(SWT.Selection, event -> perform(() -> service.begin(id), ""));
        mark = button(buttons, "Отметить ошибку");
        snapshot = button(buttons, "Снимок");
        link = button(buttons, "Добавить ссылку");
        link.addListener(SWT.Selection, event -> perform(() -> service.captureLink(id), ""));
        video = button(buttons, "Записать видео");
        video.addListener(SWT.Selection, event -> {
            String active = activeVideoId;
            if (active == null) {
                String key = UUID.randomUUID().toString();
                perform(() -> service.videoStart(id, null, key), "");
            } else {
                perform(() -> service.videoStop(id, active), "");
            }
        });
        finish = button(buttons, "Завершить воспроизведение");
        mark.addListener(SWT.Selection, event -> {
            String wanted = expected.getText(), observed = actual.getText();
            perform(() -> service.mark(id, optionalNote(wanted), optionalNote(observed)), "Отметка сохранена");
        });
        snapshot.addListener(SWT.Selection, event ->
                perform(() -> service.screenshot(id, null), "Снимок сохранён"));
        finish.addListener(SWT.Selection, event -> {
            String wanted = expected.getText(), observed = actual.getText();
            perform(() -> service.finish(id, optionalNote(wanted), optionalNote(observed)), "");
        });
        feedback = new Label(shell, SWT.WRAP);
        var feedbackLayout = new GridData(SWT.FILL, SWT.CENTER, true, false);
        feedbackLayout.widthHint = 440;
        feedback.setLayoutData(feedbackLayout);

        render(initial);
        shell.pack();
        var area = display.getPrimaryMonitor().getClientArea();
        shell.setLocation(Math.max(area.x, area.x + area.width - shell.getSize().x - 24), area.y + 36);
    }

    private static String optionalNote(String value) {
        return value.isEmpty() ? null : value;
    }

    private static Text input(Composite parent, String label) {
        new Label(parent, SWT.NONE).setText(label);
        var text = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        text.setTextLimit(2000);
        var layout = new GridData(SWT.FILL, SWT.TOP, true, false);
        layout.heightHint = 42;
        text.setLayoutData(layout);
        return text;
    }

    private static Button button(Composite parent, String title) {
        var button = new Button(parent, SWT.PUSH);
        button.setText(title);
        button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return button;
    }

    private void scheduleRefresh() {
        shell.getDisplay().timerExec(1000, () -> {
            if (shell.isDisposed()) {
                return;
            }
            if (!reading && !busy) {
                reading = true;
                var display = shell.getDisplay();
                CompletableFuture.supplyAsync(() -> execute(() -> service.status(id)))
                        .thenAccept(result -> display.asyncExec(() -> {
                            reading = false;
                            if (!shell.isDisposed() && !busy) {
                                render(result);
                            }
                        }));
            }
            scheduleRefresh();
        });
    }

    private void perform(Work work, String success) {
        if (busy) {
            return;
        }
        busy = true;
        setActions(false, false, false, false);
        var display = shell.getDisplay();
        CompletableFuture.supplyAsync(() -> execute(work)).thenAccept(result -> display.asyncExec(() -> {
            if (shell.isDisposed()) {
                return;
            }
            busy = false;
            if (result.containsKey("error")) {
                feedback.setText(String.valueOf(result.get("error")));
                // A fresh status read restores only the actions allowed by the session.
            } else {
                feedback.setText(success.isEmpty() ? linkFeedback(result.get("object_link_state")) : success);
                render(result);
            }
            if (!shell.isDisposed()) {
                shell.layout(true, true);
            }
        }));
    }

    private void render(Map<String, Object> result) {
        if (result.containsKey("error")) {
            status.setText("Статус сессии недоступен");
            setActions(false, false, false, false);
            return;
        }
        String state = String.valueOf(result.get("state"));
        if (state.equals("draft_ready")) {
            shell.dispose();
            return;
        }
        String description = switch (state) {
        case "journal_available" -> "Журнал 1С сохранён. Можно завершить воспроизведение.";
        case "awaiting_native_journal" -> "Запустите и сохраните запись штатными кнопками в заголовке 1С.";
        case "client_closed_before_journal" -> "Клиент 1С закрыт. Журнал пока отсутствует.";
        default -> "Проверяется запуск клиента 1С.";
        };
        String recording = String.valueOf(result.get("recording_state"));
        boolean controlled = "testclient".equals(result.get("backend"));
        controlledClient = controlled;
        boolean live = Boolean.TRUE.equals(result.get("client_alive"));
        if (controlled) {
            description = switch (recording) {
            case "ready" -> "Готово к записи";
            case "recording" -> "Запись действий идёт";
            case "journal_saved" -> "Журнал сохранён";
            case "begin_unknown", "finish_unknown" -> "1С не подтвердила состояние записи";
            case "begin_failed" -> "Запись не запустилась";
            case "finish_failed" -> "1С не подтвердила завершение";
            case "journal_unavailable" -> "Запись завершена. Журнал недоступен.";
            case "client_exited" -> "Клиент 1С закрылся до сохранения журнала";
            default -> description;
            };
        }
        if (result.get("recording_elapsed_seconds") instanceof Number elapsed) {
            long seconds = elapsed.longValue();
            description += "\nЗапись " + String.format("%02d:%02d", seconds / 60, seconds % 60);
        } else if (!controlled && result.get("session_elapsed_seconds") instanceof Number elapsed) {
            long seconds = elapsed.longValue();
            description += "\nСессия " + String.format("%02d:%02d", seconds / 60, seconds % 60);
        }
        if (result.containsKey("mark")) {
            description += "\nОшибка отмечена";
        }
        if (result.get("screenshots") instanceof List<?> screenshots && !screenshots.isEmpty()) {
            description += " · Снимков " + screenshots.size();
        }
        if (result.get("object_links") instanceof List<?> links && !links.isEmpty()) {
            description += "\nОбъектов 1С " + links.size();
        }
        activeVideoId = null;
        String videoStatus = "Видео выключено";
        if (result.get("videos") instanceof List<?> clips) {
            long saved = 0;
            boolean failed = false;
            for (Object item : clips) {
                if (!(item instanceof Map<?, ?> clip)) continue;
                switch (String.valueOf(clip.get("state"))) {
                case "starting", "recording", "stopping" -> {
                    activeVideoId = String.valueOf(clip.get("video_id"));
                    long seconds = ((Number) clip.get("duration_ms")).longValue() / 1000;
                    videoStatus = switch (String.valueOf(clip.get("state"))) {
                    case "starting" -> "Видео запускается";
                    case "stopping" -> "Видео сохраняется";
                    default -> "Видео " + String.format("%02d:%02d", seconds / 60, seconds % 60);
                    };
                }
                case "saved" -> saved++;
                case "failed", "interrupted" -> failed = true;
                default -> { }
                }
            }
            if (activeVideoId == null && failed) videoStatus = "Запись видео прервалась";
            if (saved > 0) videoStatus += " · Сохранено " + saved;
        }
        video.setText(activeVideoId == null ? "Записать видео" : "Остановить видео");
        status.setText(description + "\n" + videoStatus);
        setActions(controlled && live && recording.equals("ready"), true, live,
                state.equals("journal_available") || controlled && live && recording.equals("recording"));
        shell.layout(true, true);
    }

    private void setActions(boolean canStart, boolean canMark, boolean canCapture, boolean canFinish) {
        start.setEnabled(canStart);
        mark.setEnabled(canMark);
        snapshot.setEnabled(canCapture);
        link.setEnabled(canCapture && controlledClient);
        video.setEnabled(canCapture || (canMark && activeVideoId != null));
        finish.setEnabled(canFinish);
    }

    private static String linkFeedback(Object state) {
        return switch (String.valueOf(state)) {
        case "saved" -> "Ссылка на объект сохранена";
        case "already_saved" -> "Эта ссылка уже сохранена";
        case "no_object_link" -> "У открытой формы нет ссылки на запись 1С";
        case "limit_reached" -> "Достигнут предел ссылок";
        case "client_unavailable", "observation_unavailable", "link_not_saved" -> "Получить ссылку из 1С не удалось";
        default -> "";
        };
    }

    private static Map<String, Object> execute(Work work) {
        try {
            return work.run();
        } catch (Exception error) {
            return Map.of("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    @FunctionalInterface
    private interface Work {
        Map<String, Object> run() throws Exception;
    }
}
