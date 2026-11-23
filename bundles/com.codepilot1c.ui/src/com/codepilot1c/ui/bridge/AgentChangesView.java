// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.ui.bridge;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
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

import com._1c.g5.v8.dt.bsl.ui.BslSharedImages;
import com._1c.g5.v8.dt.md.ui.shared.MdUiSharedImages;
import com.codepilot1c.core.edt.development.AgentHistoryService;
import com.codepilot1c.core.edt.development.AgentHistoryService.HistoryView;
import com.codepilot1c.core.edt.development.AgentHistoryService.Pane;
import com.codepilot1c.core.edt.development.BslReviewService;
import com.codepilot1c.core.edt.development.BslReviewService.TurnFile;
import com.codepilot1c.core.edt.development.BslReviewService.TurnSummary;

public final class AgentChangesView extends ViewPart implements HistoryView {
    private record Selection(String turnId, TurnFile file) {}
    private record Group(TreeItem parent, String key) {}
    private record MetadataNode(String label, String icon) {}

    private final BslReviewService service = new BslReviewService();
    private Display display;
    private Tree tree;
    private Label status;
    private TurnHistoryControls history;
    private int revision;
    private boolean rendered;
    private TurnSummary lastRendered;
    private boolean opening;
    private final Runnable listener = () -> dispatch(this::refresh);

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        parent.setLayout(new GridLayout(1, false));
        history = new TurnHistoryControls(parent, Pane.CHANGES, this::loadTurn, this::historyError);
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
        AgentHistoryService.addListener(listener);
        refresh();
    }

    private void dispatch(Runnable action) {
        if (display == null || display.isDisposed()) return;
        display.asyncExec(() -> {
            if (tree != null && !tree.isDisposed()) action.run();
        });
    }

    private void refresh() { history.refresh(); }

    @Override public void selectTurn(String id) { history.selectTurn(id); }

    private void historyError(String message) {
        rendered = false;
        tree.removeAll();
        status.setText("Список изменений недоступен");
        status.setToolTipText(message);
    }

    private void loadTurn(TurnHistoryControls.Selection selection) {
        int request = ++revision;
        CompletableFuture.runAsync(() -> {
            try {
                TurnSummary turn = selection.followLatest() ? service.latestTurn() : service.turnSummary(selection.turnId());
                dispatch(() -> {
                    if (request == revision) render(turn);
                });
            } catch (Exception failure) {
                dispatch(() -> {
                    if (request != revision) return;
                    historyError(failure.getMessage());
                });
            }
        });
    }

    private void render(TurnSummary turn) {
        if (rendered && java.util.Objects.equals(lastRendered, turn)) return;
        rendered = true;
        lastRendered = turn;
        tree.removeAll();
        tree.setToolTipText(null);
        boolean skipped = turn != null && turn.unreadableTurns() > 0;
        status.setToolTipText(skipped ? "Показан последний доступный ход. Более новые снимки недоступны." : null);
        if (turn == null || turn.files().isEmpty()) {
            status.setText(turn == null ? "Нет завершённых ходов с правками"
                    : skipped ? "В доступном ходе нет изменений · Снимки пропущены" : "В выбранном ходе нет итоговых изменений");
            return;
        }
        status.setText(skipped ? "Файлов в доступном ходе — " + turn.files().size() + " · Снимки пропущены"
                : "Файлов в выбранном ходе — " + turn.files().size());
        var groups = new LinkedHashMap<Group, TreeItem>();
        var images = PlatformUI.getWorkbench().getSharedImages();
        for (TurnFile file : turn.files()) {
            Image folderImage = images.getImage(ISharedImages.IMG_OBJ_FOLDER);
            TreeItem parent = folder(groups, null, file.project(), file.project(),
                    metadataImage(MdUiSharedImages.OBJS_CONFIGURATION, folderImage));
            String[] parts = file.filePath().split("/");
            boolean source = parts.length > 2 && parts[0].equals("src");
            String objectIcon = null;
            for (int i = source ? 1 : 0; i < parts.length - 1; i++) {
                String name = parts[i];
                String label = name;
                if (source && i % 2 == 1) {
                    MetadataNode node = i == 1 ? metadataType(name) : metadataFolder(name);
                    label = node.label();
                    objectIcon = node.icon();
                }
                parent = folder(groups, parent, name, label, metadataImage(objectIcon, folderImage));
            }
            String filename = parts[parts.length - 1];
            var item = new TreeItem(parent, SWT.NONE);
            item.setData(new Selection(turn.id(), file));
            item.setImage(fileImage(filename, objectIcon, images.getImage(ISharedImages.IMG_OBJ_FILE)));
            item.setText((source ? sourceName(filename) : filename) + "  ·  " + switch (file.change()) {
                case "added" -> "Добавлен";
                case "removed" -> "Удалён";
                default -> "Изменён";
            } + (file.comparison().equals("binary") ? "  ·  Сравнение недоступно" : ""));
        }
        groups.values().forEach(group -> group.setExpanded(true));
    }

    private TreeItem folder(Map<Group, TreeItem> groups, TreeItem parent, String key, String label, Image image) {
        return groups.computeIfAbsent(new Group(parent, key), ignored -> {
            var item = parent == null ? new TreeItem(tree, SWT.NONE) : new TreeItem(parent, SWT.NONE);
            item.setText(label);
            item.setImage(image);
            return item;
        });
    }

    private static Image metadataImage(String key, Image fallback) {
        Image image = key == null ? null : MdUiSharedImages.getImage(key);
        return image == null ? fallback : image;
    }

    private static Image fileImage(String name, String objectIcon, Image fallback) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".bsl")) {
            Image image = BslSharedImages.getImage(BslSharedImages.IMG_MODULE);
            return image == null ? fallback : image;
        }
        if (lower.endsWith(".form")) return metadataImage(MdUiSharedImages.OBJS_FORM, fallback);
        if (lower.endsWith(".mxlx")) return metadataImage(MdUiSharedImages.OBJS_SPREADSHEET_DOCUMENT, fallback);
        return lower.endsWith(".mdo") ? metadataImage(objectIcon, fallback) : fallback;
    }

    private static MetadataNode metadataType(String name) {
        return switch (name) {
            case "Configuration" -> new MetadataNode("Конфигурация", MdUiSharedImages.OBJS_CONFIGURATION);
            case "Subsystems" -> new MetadataNode("Подсистемы", MdUiSharedImages.OBJS_SUBSYSTEM);
            case "CommonModules" -> new MetadataNode("Общие модули", MdUiSharedImages.OBJS_COMMON_MODULE);
            case "CommonForms" -> new MetadataNode("Общие формы", MdUiSharedImages.OBJS_COMMON_FORM);
            case "CommonCommands" -> new MetadataNode("Общие команды", MdUiSharedImages.OBJS_COMMON_COMMAND);
            case "CommonPictures" -> new MetadataNode("Общие картинки", MdUiSharedImages.OBJS_COMMON_PICTURE);
            case "CommonTemplates" -> new MetadataNode("Общие макеты", MdUiSharedImages.OBJS_COMMON_TEMPLATE);
            case "Constants" -> new MetadataNode("Константы", MdUiSharedImages.OBJS_CONSTANT);
            case "Catalogs" -> new MetadataNode("Справочники", MdUiSharedImages.OBJS_CATALOG);
            case "Documents" -> new MetadataNode("Документы", MdUiSharedImages.OBJS_DOCUMENT);
            case "DocumentJournals" -> new MetadataNode("Журналы документов", MdUiSharedImages.OBJS_DOCUMENT_JOURNAL);
            case "Enums" -> new MetadataNode("Перечисления", MdUiSharedImages.OBJS_ENUM);
            case "Reports" -> new MetadataNode("Отчёты", MdUiSharedImages.OBJS_REPORT);
            case "DataProcessors" -> new MetadataNode("Обработки", MdUiSharedImages.OBJS_DATA_PROCESSOR);
            case "InformationRegisters" -> new MetadataNode("Регистры сведений", MdUiSharedImages.OBJS_INFORMATION_REGISTER);
            case "AccumulationRegisters" -> new MetadataNode("Регистры накопления", MdUiSharedImages.OBJS_ACCUMULATION_REGISTER);
            case "AccountingRegisters" -> new MetadataNode("Регистры бухгалтерии", MdUiSharedImages.OBJS_ACCOUNTING_REGISTER);
            case "CalculationRegisters" -> new MetadataNode("Регистры расчёта", MdUiSharedImages.OBJS_CALCULATION_REGISTER);
            case "ChartsOfCharacteristicTypes" -> new MetadataNode("Планы видов характеристик", MdUiSharedImages.OBJS_CHART_OF_CHARACTERISTIC_TYPES);
            case "ChartsOfAccounts" -> new MetadataNode("Планы счетов", MdUiSharedImages.OBJS_CHART_OF_ACCOUNTS);
            case "ChartsOfCalculationTypes" -> new MetadataNode("Планы видов расчёта", MdUiSharedImages.OBJS_CHART_OF_CALCULATION_TYPES);
            case "BusinessProcesses" -> new MetadataNode("Бизнес-процессы", MdUiSharedImages.OBJS_BUSINESS_PROCESS);
            case "Tasks" -> new MetadataNode("Задачи", MdUiSharedImages.OBJS_TASK);
            case "ExchangePlans" -> new MetadataNode("Планы обмена", MdUiSharedImages.OBJS_EXCHANGE_PLAN);
            case "Roles" -> new MetadataNode("Роли", MdUiSharedImages.OBJS_ROLE);
            case "Languages" -> new MetadataNode("Языки", MdUiSharedImages.OBJS_LANGUAGE);
            case "DefinedTypes" -> new MetadataNode("Определяемые типы", MdUiSharedImages.OBJS_DEFINED_TYPES);
            case "EventSubscriptions" -> new MetadataNode("Подписки на события", MdUiSharedImages.OBJS_EVENT_SUBSCRIPTION);
            case "ScheduledJobs" -> new MetadataNode("Регламентные задания", MdUiSharedImages.OBJS_SCHEDULED_JOB);
            case "FunctionalOptions" -> new MetadataNode("Функциональные опции", MdUiSharedImages.OBJS_FUNCTIONAL_OPTION);
            case "FunctionalOptionsParameters" -> new MetadataNode("Параметры функциональных опций", MdUiSharedImages.OBJS_FUNCTIONAL_OPTION_PARAMETER);
            case "SessionParameters" -> new MetadataNode("Параметры сеанса", MdUiSharedImages.OBJS_SESSION_PARAMETER);
            case "SettingsStorages" -> new MetadataNode("Хранилища настроек", MdUiSharedImages.OBJS_SETTINGS_STORAGE);
            case "FilterCriteria" -> new MetadataNode("Критерии отбора", MdUiSharedImages.OBJS_FILTER_CRITERION);
            case "CommandGroups" -> new MetadataNode("Группы команд", MdUiSharedImages.OBJS_COMMAND_GROUP);
            case "Styles" -> new MetadataNode("Стили", MdUiSharedImages.OBJS_STYLE);
            case "StyleItems" -> new MetadataNode("Элементы стиля", MdUiSharedImages.OBJS_STYLE_ITEM);
            case "WebServices" -> new MetadataNode("Web-сервисы", MdUiSharedImages.OBJS_WEB_SERVICE);
            case "WebServiceReferences", "WSReferences" -> new MetadataNode("WS-ссылки", MdUiSharedImages.OBJS_WS_REFERENCE);
            case "HTTPServices" -> new MetadataNode("HTTP-сервисы", MdUiSharedImages.OBJS_HTTP);
            case "IntegrationServices" -> new MetadataNode("Сервисы интеграции", MdUiSharedImages.OBJS_INTEGRATION_SERVICE);
            case "ExternalDataSources" -> new MetadataNode("Внешние источники данных", MdUiSharedImages.OBJS_EXTERNAL_DATA_SOURCE);
            case "XDTOPackages" -> new MetadataNode("Пакеты XDTO", MdUiSharedImages.OBJS_XDTO);
            default -> new MetadataNode(name, null);
        };
    }

    private static MetadataNode metadataFolder(String name) {
        return switch (name) {
            case "Forms" -> new MetadataNode("Формы", MdUiSharedImages.OBJS_FORM);
            case "Commands" -> new MetadataNode("Команды", MdUiSharedImages.OBJS_COMMAND);
            case "Templates" -> new MetadataNode("Макеты", MdUiSharedImages.OBJS_TEMPLATE);
            case "Attributes" -> new MetadataNode("Реквизиты", MdUiSharedImages.OBJS_ATTRIBUTE);
            case "TabularSections" -> new MetadataNode("Табличные части", MdUiSharedImages.OBJS_TABULAR_SECTION);
            case "Predefined" -> new MetadataNode("Предопределённые данные", MdUiSharedImages.OBJS_PREDEFINED_FOLDER);
            default -> new MetadataNode(name, null);
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
        AgentHistoryService.removeListener(listener);
        super.dispose();
    }
}
