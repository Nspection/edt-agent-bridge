// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.ui.bridge;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

import com.codepilot1c.core.edt.development.BslReviewService;
import com.codepilot1c.core.edt.development.BslReviewService.TurnFile;
import com.codepilot1c.core.edt.development.BslReviewService.TurnSummary;

public final class AgentChangesView extends ViewPart {
    private record Selection(String turnId, TurnFile file) {}
    private record Group(TreeItem parent, String key) {}

    private final BslReviewService service = new BslReviewService();
    private Display display;
    private Tree tree;
    private Label status;
    private int revision;
    private boolean opening;
    private final Runnable listener = () -> dispatch(this::refresh);

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        parent.setLayout(new GridLayout(1, false));
        status = new Label(parent, SWT.NONE);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText("Загрузка…");
        tree = new Tree(parent, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
        tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        tree.addListener(SWT.DefaultSelection, event -> {
            if (!(event.item instanceof TreeItem item)) return;
            if (item.getData() instanceof Selection row) open(row);
            else item.setExpanded(!item.getExpanded());
        });
        tree.addListener(SWT.MouseMove, event -> {
            TreeItem item = tree.getItem(new Point(event.x, event.y));
            String path = item != null && item.getData() instanceof Selection row ? row.file().filePath() : null;
            if (!java.util.Objects.equals(tree.getToolTipText(), path)) tree.setToolTipText(path);
        });
        getViewSite().getActionBars().getMenuManager().add(new Action("Обновить") {
            @Override public void run() { refresh(); }
        });
        BslReviewService.addTurnListener(listener);
        refresh();
    }

    private void dispatch(Runnable action) {
        if (display == null || display.isDisposed()) return;
        display.asyncExec(() -> {
            if (tree != null && !tree.isDisposed()) action.run();
        });
    }

    private void refresh() {
        int request = ++revision;
        CompletableFuture.runAsync(() -> {
            try {
                TurnSummary turn = service.latestTurn();
                dispatch(() -> {
                    if (request == revision) render(turn);
                });
            } catch (Exception failure) {
                dispatch(() -> {
                    if (request != revision) return;
                    tree.removeAll();
                    status.setText("Список изменений недоступен");
                    status.setToolTipText(failure.getMessage());
                });
            }
        });
    }

    private void render(TurnSummary turn) {
        tree.removeAll();
        status.setToolTipText(null);
        if (turn == null || turn.files().isEmpty()) {
            status.setText(turn == null ? "Нет завершённых ходов с правками" : "В последнем ходе нет итоговых изменений");
            return;
        }
        status.setText("Файлов в последнем ходе — " + turn.files().size());
        var groups = new LinkedHashMap<Group, TreeItem>();
        var images = PlatformUI.getWorkbench().getSharedImages();
        for (TurnFile file : turn.files()) {
            TreeItem parent = folder(groups, images, null, file.project(), file.project());
            String[] parts = file.filePath().split("/");
            boolean source = parts.length > 2 && parts[0].equals("src");
            for (int i = source ? 1 : 0; i < parts.length - 1; i++) {
                String name = parts[i];
                String label = source && i == 1 ? metadataType(name)
                        : source && i >= 3 && i % 2 == 1 ? metadataFolder(name) : name;
                parent = folder(groups, images, parent, name, label);
            }
            var item = new TreeItem(parent, SWT.NONE);
            item.setData(new Selection(turn.id(), file));
            item.setImage(images.getImage(ISharedImages.IMG_OBJ_FILE));
            String filename = parts[parts.length - 1];
            item.setText((source ? sourceName(filename) : filename) + "  ·  " + switch (file.change()) {
                case "added" -> "Добавлен";
                case "removed" -> "Удалён";
                default -> "Изменён";
            } + (file.comparison().equals("binary") ? "  ·  Сравнение недоступно" : ""));
        }
        groups.values().forEach(group -> group.setExpanded(true));
    }

    private TreeItem folder(Map<Group, TreeItem> groups, ISharedImages images,
            TreeItem parent, String key, String label) {
        return groups.computeIfAbsent(new Group(parent, key), ignored -> {
            var item = parent == null ? new TreeItem(tree, SWT.NONE) : new TreeItem(parent, SWT.NONE);
            item.setText(label);
            item.setImage(images.getImage(ISharedImages.IMG_OBJ_FOLDER));
            return item;
        });
    }

    private static String metadataType(String name) {
        return switch (name) {
            case "Configuration" -> "Конфигурация";
            case "Subsystems" -> "Подсистемы";
            case "CommonModules" -> "Общие модули";
            case "CommonForms" -> "Общие формы";
            case "CommonCommands" -> "Общие команды";
            case "CommonPictures" -> "Общие картинки";
            case "CommonTemplates" -> "Общие макеты";
            case "Constants" -> "Константы";
            case "Catalogs" -> "Справочники";
            case "Documents" -> "Документы";
            case "DocumentJournals" -> "Журналы документов";
            case "Enums" -> "Перечисления";
            case "Reports" -> "Отчёты";
            case "DataProcessors" -> "Обработки";
            case "InformationRegisters" -> "Регистры сведений";
            case "AccumulationRegisters" -> "Регистры накопления";
            case "AccountingRegisters" -> "Регистры бухгалтерии";
            case "CalculationRegisters" -> "Регистры расчёта";
            case "ChartsOfCharacteristicTypes" -> "Планы видов характеристик";
            case "ChartsOfAccounts" -> "Планы счетов";
            case "ChartsOfCalculationTypes" -> "Планы видов расчёта";
            case "BusinessProcesses" -> "Бизнес-процессы";
            case "Tasks" -> "Задачи";
            case "ExchangePlans" -> "Планы обмена";
            case "Roles" -> "Роли";
            case "Languages" -> "Языки";
            case "DefinedTypes" -> "Определяемые типы";
            case "EventSubscriptions" -> "Подписки на события";
            case "ScheduledJobs" -> "Регламентные задания";
            case "FunctionalOptions" -> "Функциональные опции";
            case "FunctionalOptionsParameters" -> "Параметры функциональных опций";
            case "SessionParameters" -> "Параметры сеанса";
            case "SettingsStorages" -> "Хранилища настроек";
            case "FilterCriteria" -> "Критерии отбора";
            case "CommandGroups" -> "Группы команд";
            case "Styles" -> "Стили";
            case "StyleItems" -> "Элементы стиля";
            case "WebServices" -> "Web-сервисы";
            case "WebServiceReferences", "WSReferences" -> "WS-ссылки";
            case "HTTPServices" -> "HTTP-сервисы";
            case "IntegrationServices" -> "Сервисы интеграции";
            case "ExternalDataSources" -> "Внешние источники данных";
            case "XDTOPackages" -> "Пакеты XDTO";
            default -> name;
        };
    }

    private static String metadataFolder(String name) {
        return switch (name) {
            case "Forms" -> "Формы";
            case "Commands" -> "Команды";
            case "Templates" -> "Макеты";
            case "Attributes" -> "Реквизиты";
            case "TabularSections" -> "Табличные части";
            case "Predefined" -> "Предопределённые данные";
            default -> name;
        };
    }

    private static String sourceName(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "module.bsl" -> "Модуль";
            case "objectmodule.bsl" -> "Модуль объекта";
            case "managermodule.bsl" -> "Модуль менеджера";
            case "commandmodule.bsl" -> "Модуль команды";
            case "managedapplicationmodule.bsl" -> "Модуль управляемого приложения";
            case "ordinaryapplicationmodule.bsl" -> "Модуль обычного приложения";
            case "sessionmodule.bsl" -> "Модуль сеанса";
            case "externalconnectionmodule.bsl" -> "Модуль внешнего соединения";
            case "recordsetmodule.bsl" -> "Модуль набора записей";
            case "valuemanagermodule.bsl" -> "Модуль менеджера значения";
            case "form.form" -> "Описание формы";
            case "template.mxlx" -> "Табличный макет";
            default -> name.toLowerCase(Locale.ROOT).endsWith(".mdo") ? "Описание объекта" : name;
        };
    }

    private void open(Selection row) {
        if (opening || row.file().comparison().equals("binary")) return;
        opening = true;
        CompletableFuture.runAsync(() -> {
            try {
                service.openTurn(row.turnId(), row.file().project(), row.file().filePath(), "lines");
            } catch (Exception failure) {
                dispatch(() -> MessageDialog.openInformation(tree.getShell(), "Изменения ИИ", failure.getMessage()));
            } finally {
                dispatch(() -> opening = false);
            }
        });
    }

    @Override public void setFocus() {
        if (tree != null && !tree.isDisposed()) tree.setFocus();
    }

    @Override public void dispose() {
        BslReviewService.removeTurnListener(listener);
        super.dispose();
    }
}
