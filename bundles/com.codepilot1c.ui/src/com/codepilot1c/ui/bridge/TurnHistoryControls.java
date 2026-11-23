// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.ui.bridge;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;

import com.codepilot1c.core.edt.development.AgentHistoryService;
import com.codepilot1c.core.edt.development.AgentHistoryService.Pane;
import com.codepilot1c.core.edt.development.AgentHistoryService.Turn;

final class TurnHistoryControls {
    record Selection(String turnId, boolean followLatest) {}
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss");

    private final AgentHistoryService service = new AgentHistoryService();
    private final Pane pane;
    private final Pane other;
    private final Composite bar;
    private final org.eclipse.swt.widgets.Display display;
    private final Combo turns;
    private final Button latest;
    private final Button cross;
    private final Button cleanup;
    private final Menu cleanupMenu;
    private final MenuItem selectedItem;
    private final Consumer<Selection> onSelect;
    private final Runnable onPending;
    private final Consumer<String> onError;
    private List<Turn> rows = List.of();
    private String fixedId;
    private int generation;

    TurnHistoryControls(Composite parent, Pane pane, Consumer<Selection> onSelect, Runnable onPending,
            Consumer<String> onError) {
        this.pane = pane;
        this.other = pane == Pane.MESSAGES ? Pane.CHANGES : Pane.MESSAGES;
        this.onSelect = onSelect;
        this.onPending = onPending;
        this.onError = onError;
        display = parent.getDisplay();
        bar = new Composite(parent, SWT.NONE);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout layout = new GridLayout(4, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        bar.setLayout(layout);
        turns = new Combo(bar, SWT.READ_ONLY);
        var width = new GridData(SWT.FILL, SWT.CENTER, true, false);
        width.widthHint = 120;
        turns.setLayoutData(width);
        turns.setToolTipText("Выбрать ход агента");
        turns.addListener(SWT.Selection, event -> {
            int index = turns.getSelectionIndex();
            selectTurn(index > 0 ? rows.get(index - 1).turnId() : null);
        });
        latest = new Button(bar, SWT.PUSH);
        var forward = PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_TOOL_FORWARD);
        if (forward == null) latest.setText("»"); else latest.setImage(forward);
        String latestLabel = "К последнему ходу";
        latest.setToolTipText(latestLabel);
        latest.getAccessible().addAccessibleListener(new AccessibleAdapter() {
            @Override public void getName(AccessibleEvent event) { event.result = latestLabel; }
        });
        latest.setEnabled(false);
        latest.addListener(SWT.Selection, event -> selectTurn(null));
        cross = new Button(bar, SWT.PUSH);
        cross.setText(other == Pane.MESSAGES ? "Сообщения" : "Изменения");
        cross.setEnabled(false);
        cross.addListener(SWT.Selection, event -> {
            String id = effectiveId();
            if (id != null) run(() -> service.show(other, id));
        });
        cleanup = new Button(bar, SWT.PUSH);
        cleanup.setText("Очистить ▾");
        cleanup.setEnabled(false);
        cleanupMenu = new Menu(bar);
        selectedItem = new MenuItem(cleanupMenu, SWT.PUSH);
        selectedItem.setText("Очистить выбранный ход");
        selectedItem.addListener(SWT.Selection, event -> {
            String id = effectiveId();
            if (id != null) clear(id, false);
        });
        var all = new MenuItem(cleanupMenu, SWT.PUSH);
        all.setText("Очистить всю историю…");
        all.addListener(SWT.Selection, event -> clear(null, true));
        cleanup.addListener(SWT.Selection, event -> {
            selectedItem.setEnabled(effectiveId() != null);
            Point position = cleanup.toDisplay(0, cleanup.getSize().y);
            cleanupMenu.setLocation(position);
            cleanupMenu.setVisible(true);
        });
    }

    void selectTurn(String id) {
        if (!Objects.equals(fixedId, id)) onPending.run();
        fixedId = id;
        refresh();
    }

    void refresh() {
        int request = ++generation;
        String requestedId = fixedId;
        CompletableFuture.runAsync(() -> {
            try {
                List<Turn> available = service.history(pane);
                String resolved = available.stream().filter(turn -> Objects.equals(turn.turnId(), requestedId))
                        .findFirst().or(() -> available.stream().findFirst()).map(Turn::turnId).orElse(null);
                boolean counterpart = service.hasTurn(other, resolved);
                dispatch(() -> {
                    if (request != generation) return;
                    rows = available;
                    int selected = 0;
                    var labels = new java.util.ArrayList<>(List.of("Последний ход"));
                    for (int i = 0; i < rows.size(); i++) {
                        var row = rows.get(i);
                        String time = TIME.format(Instant.parse(row.time()).atZone(ZoneId.systemDefault()));
                        labels.add(time + "  " + row.title().replaceAll("\\s+", " "));
                        if (Objects.equals(row.turnId(), fixedId)) selected = i + 1;
                    }
                    if (selected == 0) fixedId = null;
                    var items = labels.toArray(String[]::new);
                    if (!java.util.Arrays.equals(turns.getItems(), items)) turns.setItems(items);
                    if (turns.getSelectionIndex() != selected) turns.select(selected);
                    String id = effectiveId();
                    latest.setEnabled(id != null);
                    cross.setEnabled(id != null && counterpart);
                    // Clear-all is also useful for entries unreadable by the list.
                    cleanup.setEnabled(true);
                    onSelect.accept(new Selection(id, fixedId == null));
                });
            } catch (Exception failure) {
                dispatch(() -> {
                    if (request == generation) onError.accept(failure.getMessage());
                });
            }
        });
    }

    private String effectiveId() {
        return fixedId != null ? fixedId : rows.isEmpty() ? null : rows.get(0).turnId();
    }

    private void clear(String id, boolean all) {
        String heading = pane == Pane.MESSAGES ? "Сообщения ИИ" : "Изменения ИИ";
        run(() -> {
            var preview = service.cleanup(pane, id, all, null);
            dispatch(() -> {
                if (all && !MessageDialog.openQuestion(bar.getShell(), heading,
                        "Удалить всю доступную историю? Ходов к удалению — " + preview.get("eligible_count") + ".")) {
                    return;
                }
                run(() -> {
                    Map<String, Object> receipt = service.cleanup(pane, id, all, (String) preview.get("preview_sha256"));
                    dispatch(() -> {
                        refresh();
                        if (!receipt.get("protected_count").equals(0)
                                || !((List<?>) receipt.get("failed_turn_ids")).isEmpty()) {
                            MessageDialog.openInformation(bar.getShell(), heading,
                                    "Удалено — " + receipt.get("removed_count") + ". Активных или открытых ходов — "
                                    + receipt.get("protected_count") + ". Ошибок удаления — "
                                    + ((List<?>) receipt.get("failed_turn_ids")).size() + ".");
                        }
                    });
                });
            });
        });
    }

    @FunctionalInterface private interface Work { void execute() throws Exception; }

    private void run(Work work) {
        CompletableFuture.runAsync(() -> {
            try { work.execute(); }
            catch (Exception failure) {
                dispatch(() -> MessageDialog.openInformation(bar.getShell(), "История ИИ", failure.getMessage()));
            }
        });
    }

    private void dispatch(Runnable work) {
        if (!display.isDisposed()) display.asyncExec(() -> {
            if (!bar.isDisposed()) work.run();
        });
    }
}
