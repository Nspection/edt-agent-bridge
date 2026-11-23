// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.ui.bridge;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.part.ViewPart;

import com.codepilot1c.core.edt.development.AgentHistoryService;
import com.codepilot1c.core.edt.development.AgentHistoryService.HistoryView;
import com.codepilot1c.core.edt.development.AgentHistoryService.Message;
import com.codepilot1c.core.edt.development.AgentHistoryService.Messages;
import com.codepilot1c.core.edt.development.AgentHistoryService.Pane;
import com.codepilot1c.core.edt.development.BslReviewService;

public final class AgentMessagesView extends ViewPart implements HistoryView {
    private final AgentHistoryService service = new AgentHistoryService();
    private Display display;
    private TurnHistoryControls history;
    private final Runnable listener = () -> dispatch(() -> history.refresh());
    private Table table;
    private Label status;
    private Messages rendered;
    private int revision;
    private boolean opening;

    @Override public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        parent.setLayout(new GridLayout(1, false));
        history = new TurnHistoryControls(parent, Pane.MESSAGES, selection -> load(selection.turnId()), this::pending, this::error);
        status = new Label(parent, SWT.NONE);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText("Загрузка…");
        var area = new Composite(parent, SWT.NONE);
        area.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        var layout = new TableColumnLayout();
        area.setLayout(layout);
        table = new Table(area, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setHeaderVisible(true);
        var message = new TableColumn(table, SWT.LEFT);
        message.setText("Сообщение ИИ");
        var link = new TableColumn(table, SWT.LEFT);
        link.setText("Ссылка");
        layout.setColumnData(message, new ColumnWeightData(64, 100));
        layout.setColumnData(link, new ColumnWeightData(36, 60));
        table.addListener(SWT.MouseUp, event -> {
            if (event.button != 1) return;
            var item = table.getItem(new Point(event.x, event.y));
            if (item != null && item.getBounds(1).contains(event.x, event.y)) open(item);
        });
        table.addListener(SWT.KeyDown, event -> {
            if ((event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR) && table.getSelectionCount() == 1) {
                event.doit = false;
                open(table.getSelection()[0]);
            }
        });
        table.addListener(SWT.MouseMove, event -> {
            var item = table.getItem(new Point(event.x, event.y));
            boolean isLink = item != null && item.getData() instanceof Message row && row.target() != null
                    && item.getBounds(1).contains(event.x, event.y);
            table.setCursor(isLink ? display.getSystemCursor(SWT.CURSOR_HAND) : null);
            String text = item != null && item.getData() instanceof Message row
                    ? isLink ? row.target().filePath() : row.text() : null;
            if (!Objects.equals(table.getToolTipText(), text)) table.setToolTipText(text);
        });
        table.addListener(SWT.PaintItem, event -> {
            if (event.index != 1 || !(event.item instanceof TableItem item)
                    || !(item.getData() instanceof Message row) || row.target() == null) return;
            var rect = item.getTextBounds(1);
            int width = Math.min(rect.width, event.gc.textExtent(item.getText(1)).x);
            int baseline = rect.y + (rect.height + event.gc.getFontMetrics().getAscent()) / 2;
            event.gc.drawLine(rect.x, baseline, rect.x + width, baseline);
        });
        AgentHistoryService.addListener(listener);
        BslReviewService.addTurnListener(listener);
        history.refresh();
    }

    @Override public void selectTurn(String id) { history.selectTurn(id); }

    private void pending() {
        revision++;
        table.setEnabled(false);
    }

    private void load(String id) {
        if (rendered != null && !Objects.equals(rendered.turnId(), id)) table.setEnabled(false);
        int request = ++revision;
        CompletableFuture.runAsync(() -> {
            try {
                Messages result = id == null ? null : service.messages(id);
                dispatch(() -> {
                    if (request == revision) render(result);
                });
            } catch (Exception failure) {
                dispatch(() -> {
                    if (request == revision) error(failure.getMessage());
                });
            }
        });
    }

    private void render(Messages result) {
        table.setEnabled(true);
        if (result != null && Objects.equals(rendered, result)) return;
        rendered = result;
        table.removeAll();
        status.setToolTipText(null);
        status.setText(result == null ? "Нет сообщений" : "Сообщений в ходе — " + result.messages().size());
        if (result == null) return;
        for (var row : result.messages()) {
            var item = new TableItem(table, SWT.NONE);
            item.setData(row);
            item.setText(0, row.text().replace('\n', ' ').replace('\r', ' ').replace('\t', ' '));
            if (row.target() != null) {
                item.setText(1, row.target().label());
                item.setForeground(1, display.getSystemColor(SWT.COLOR_LINK_FOREGROUND));
            }
        }
    }

    private void open(TableItem item) {
        if (opening || rendered == null || !(item.getData() instanceof Message row) || row.target() == null) return;
        opening = true;
        String id = rendered.turnId();
        CompletableFuture.runAsync(() -> {
            try { service.openTarget(id, row.target()); }
            catch (Exception failure) {
                dispatch(() -> MessageDialog.openInformation(table.getShell(), "Сообщения ИИ", failure.getMessage()));
            } finally { dispatch(() -> opening = false); }
        });
    }

    private void error(String message) {
        table.setEnabled(true);
        rendered = null;
        table.removeAll();
        status.setText("История сообщений недоступна");
        status.setToolTipText(message);
    }

    private void dispatch(Runnable work) {
        if (display == null || display.isDisposed()) return;
        display.asyncExec(() -> {
            if (table != null && !table.isDisposed()) work.run();
        });
    }

    @Override public void setFocus() { if (table != null && !table.isDisposed()) table.setFocus(); }

    @Override public void dispose() {
        AgentHistoryService.removeListener(listener);
        BslReviewService.removeTurnListener(listener);
        super.dispose();
    }
}
