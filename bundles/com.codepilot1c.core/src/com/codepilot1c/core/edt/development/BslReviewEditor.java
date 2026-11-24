// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.CompareViewerPane;
import org.eclipse.compare.CompareViewerSwitchingPane;
import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.Splitter;
import org.eclipse.compare.contentmergeviewer.TextMergeViewer;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.compare.structuremergeviewer.ICompareInput;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.TextPresentation;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.OpenEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IReusableEditor;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;

import com._1c.g5.v8.dt.bsl.compare.BslCompareUtils;
import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.ui.BslSharedImages;
import com.codepilot1c.core.edt.editor.EdtEditorService;

/** A read-only EDT compare editor backed by immutable text. */
final class BslReviewEditor extends CompareEditorInput {
    private static final String OPTIMIZED_ALGORITHM_USED = "OPTIMIZED_ALGORITHM_USED";
    private static final String COMPARE_SOURCE = BslReviewEditor.class.getName() + ".compareSource";
    private static final String REVIEW_COLORS = BslReviewEditor.class.getName() + ".reviewColors";
    private static final String SOURCE_INPUT = BslReviewEditor.class.getName() + ".sourceInput";

    private record SourceSlice(int displayedOffset, int moduleOffset) {}

    private record Side(String name, String text, String icon, String type, List<SourceSlice> sourceSlices)
            implements ITypedElement, IEncodedStreamContentAccessor {
        Side(String name, String text) {
            this(name, text, null, "bsl", List.of());
        }
        @Override public String getName() { return name; }
        @Override public String getType() { return type; }
        @Override public Image getImage() { return icon == null ? null : BslSharedImages.getImage(icon); }
        @Override public String getCharset() { return StandardCharsets.UTF_8.name(); }
        @Override public InputStream getContents() {
            return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private record MethodRange(String name, int offset, int end, String icon) {}
    private record Structure(Map<String, Side> methods, Side outsideMethods, String notice) {}

    private final String id;
    private final String project;
    private final String path;
    private final boolean bsl;
    private final String mode;
    private final String location;
    private final String before;
    private final String after;
    private final Runnable onClose;
    private final AtomicBoolean released = new AtomicBoolean();
    private DiffNode diff;
    private CompareViewerPane structurePane;
    private Viewer structureViewer;
    private List<Map<String, Object>> sections = List.of();
    private IEditorPart editorPart;
    private String methodNotice = "";
    private String methodComparisonState;
    private boolean highlightEnabled = true;

    BslReviewEditor(String id, String project, String path, String mode, String before, String after, Runnable onClose) {
        super(config());
        this.id = id;
        this.project = project;
        this.path = path;
        this.bsl = path.toLowerCase(Locale.ROOT).endsWith(".bsl");
        this.mode = mode;
        this.before = before;
        this.after = after;
        this.onClose = onClose;
        setTitle(path.substring(path.lastIndexOf('/') + 1) + " — " + (mode.equals("methods") ? "По методам" : "По строкам"));
        location = project + " / " + path;
    }

    private static CompareConfiguration config() {
        var configuration = new CompareConfiguration();
        configuration.setLeftEditable(false);
        configuration.setRightEditable(false);
        configuration.setLeftLabel("До изменений");
        configuration.setRightLabel("После изменений");
        return configuration;
    }

    Map<String, Object> open() throws Exception {
        if (!PlatformUI.isWorkbenchRunning()) throw new IllegalStateException("EDT workbench is unavailable");
        var workbench = PlatformUI.getWorkbench();
        var selected = new AtomicReference<String>();
        var failure = new AtomicReference<Exception>();
        diff = buildDiff();
        workbench.getDisplay().syncExec(() -> {
            try {
                var window = workbench.getActiveWorkbenchWindow();
                var page = window == null ? null : window.getActivePage();
                if (page == null) throw new IllegalStateException("EDT workbench has no active page");
                for (var ref : page.getEditorReferences()) {
                    IEditorPart part = ref.getEditor(false);
                    if (part != null && part.getEditorInput() instanceof BslReviewEditor other
                            && id.equals(other.id) && project.equals(other.project) && path.equals(other.path)) {
                        if (!mode.equals(other.mode)) {
                            if (!(part instanceof IReusableEditor reusable)) {
                                throw new IllegalStateException("The EDT comparison tab cannot be reused");
                            }
                            highlightEnabled = other.highlightEnabled;
                            CompareUI.reuseCompareEditor(this, reusable);
                            if (part.getEditorInput() != this) {
                                throw new IllegalStateException("EDT did not switch the comparison view");
                            }
                            editorPart = part;
                        }
                        page.activate(part);
                        selected.set(EdtEditorService.getInstance().register(part));
                        return;
                    }
                }
                CompareUI.openCompareEditorOnPage(this, page);
                var editor = page.findEditor(this);
                if (editor == null) throw new IllegalStateException("EDT did not open the comparison editor");
                editorPart = editor;
                selected.set(EdtEditorService.getInstance().register(editor));
            } catch (Exception error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) throw failure.get();
        var result = new LinkedHashMap<String, Object>(Map.of("state", "open", "review_id", id, "file_path", path,
                "mode", mode, "read_only", true, "sections", sections, "editor_id", selected.get()));
        if (methodComparisonState != null) {
            result.put("method_comparison_state", methodComparisonState);
            result.put("method_notice", methodNotice);
        }
        return result;
    }

    private DiffNode buildDiff() {
        String name = bsl ? "Модуль целиком" : "Файл целиком";
        String type = bsl ? "bsl" : "txt";
        var root = new DiffNode(Differencer.CHANGE, null, new Side(name, before, null, type, List.of(new SourceSlice(0, 0))),
                new Side(name, after, null, type, List.of(new SourceSlice(0, 0))));
        if (mode.equals("lines")) return root;
        Structure oldModule = structure(before);
        Structure newModule = structure(after);
        methodNotice = String.join(" ", List.of(noticeFor("До изменений", oldModule),
                noticeFor("После изменений", newModule))).strip();
        if (oldModule == null || newModule == null) {
            methodComparisonState = "full_module_fallback";
            methodNotice += " Показано сравнение модуля целиком.";
            return root;
        }
        var keys = new LinkedHashSet<>(oldModule.methods().keySet());
        keys.addAll(newModule.methods().keySet());
        if (!methodNotice.isEmpty()) {
            // Recovery can miss a declaration. Do not report it as a real added or removed method.
            keys.retainAll(oldModule.methods().keySet());
            keys.retainAll(newModule.methods().keySet());
            methodComparisonState = "partial";
            methodNotice += " Сопоставлены распознанные методы. Полный текст доступен в режиме «По строкам».";
        }
        var changed = new ArrayList<Map<String, Object>>();
        add(root, changed, oldModule.outsideMethods(), newModule.outsideMethods());
        for (String key : keys) {
            add(root, changed, oldModule.methods().get(key), newModule.methods().get(key));
        }
        var common = new LinkedHashSet<>(oldModule.methods().keySet());
        common.retainAll(newModule.methods().keySet());
        var oldOrder = oldModule.methods().entrySet().stream().filter(entry -> common.contains(entry.getKey())).toList();
        var newOrder = newModule.methods().entrySet().stream().filter(entry -> common.contains(entry.getKey())).toList();
        if (!oldOrder.stream().map(Map.Entry::getKey).toList().equals(newOrder.stream().map(Map.Entry::getKey).toList())) {
            add(root, changed, new Side("Порядок методов", methodNames(oldOrder)),
                    new Side("Порядок методов", methodNames(newOrder)));
        }
        sections = List.copyOf(changed);
        return root;
    }

    private static String noticeFor(String label, Structure structure) {
        if (structure == null) return "«" + label + "» — границы методов установить не удалось.";
        if (structure.notice().isEmpty()) return "";
        return "«" + label + "» — " + structure.notice();
    }

    private static String methodNames(List<Map.Entry<String, Side>> entries) {
        return String.join("\n", entries.stream().map(entry -> entry.getValue().name()).toList());
    }

    private static void add(DiffNode root, List<Map<String, Object>> changes, Side before, Side after) {
        if (before != null && after != null && before.text().equals(after.text())) return;
        int kind = before == null ? Differencer.DELETION : after == null ? Differencer.ADDITION : Differencer.CHANGE;
        new DiffNode(root, kind, null, before, after);
        changes.add(Map.of("name", Objects.requireNonNullElse(before, after).name(),
                "change", before == null ? "added" : after == null ? "removed" : "changed"));
    }

    private static Structure structure(String text) {
        String code = text.startsWith("\uFEFF") ? text.substring(1) : text;
        if (code.isBlank()) return new Structure(Map.of(), new Side("Вне методов", code), "");
        var parsed = BslCompareUtils.parseBslModuleContent(
                new ByteArrayInputStream(code.getBytes(StandardCharsets.UTF_8)));
        if (parsed == null || !(parsed.getRootASTElement() instanceof Module module)) return null;
        String notice = "";
        if (parsed.hasSyntaxErrors()) {
            var errors = parsed.getSyntaxErrors().iterator();
            notice = errors.hasNext()
                    ? "EDT восстановил структуру после ошибки разбора на строке " + errors.next().getStartLine() + "."
                    : "EDT восстановил структуру после ошибки разбора.";
        }
        var ranges = new ArrayList<MethodRange>();
        for (var method : module.allMethods()) {
            var node = NodeModelUtils.getNode(method);
            if (node == null) return null;
            String icon = method instanceof Function ? BslSharedImages.IMG_FUNCTION : BslSharedImages.IMG_PROCEDURE;
            ranges.add(new MethodRange(method.getName(), node.getOffset(), node.getEndOffset(), icon));
        }
        ranges.sort(Comparator.comparingInt(MethodRange::offset));
        var methods = new LinkedHashMap<String, Side>();
        var occurrences = new LinkedHashMap<String, Integer>();
        var outer = new StringBuilder();
        var outerSlices = new ArrayList<SourceSlice>();
        int previous = 0;
        int bomOffset = text.length() - code.length();
        for (MethodRange range : ranges) {
            if (range.offset() < previous || range.end() > code.length() || range.name() == null) return null;
            appendOutsideMethod(code, previous, range.offset(), outer, outerSlices);
            previous = range.end();
            String key = range.name().toLowerCase(Locale.ROOT);
            int occurrence = occurrences.merge(key, 1, Integer::sum);
            String name = occurrence == 1 ? range.name() : range.name() + " [" + occurrence + "]";
            methods.put(key + ":" + occurrence, new Side(name, code.substring(range.offset(), range.end()),
                    range.icon(), "bsl", List.of(new SourceSlice(0, range.offset() + bomOffset))));
        }
        appendOutsideMethod(code, previous, code.length(), outer, outerSlices);
        var slices = outerSlices.stream()
                .map(slice -> new SourceSlice(slice.displayedOffset(), slice.moduleOffset() + bomOffset)).toList();
        return new Structure(methods, new Side("Вне методов", outer.toString(), null, "bsl", slices), notice);
    }

    private static void appendOutsideMethod(String code, int start, int end, StringBuilder outer,
            List<SourceSlice> slices) {
        if (start == end) return;
        slices.add(new SourceSlice(outer.length(), start));
        outer.append(code, start, end);
    }

    @Override public String getToolTipText() { return location; }

    @Override protected Object prepareInput(IProgressMonitor monitor) { return diff; }

    @Override public boolean canRunAsJob() { return false; }

    @Override public Control createContents(Composite parent) {
        if (!bsl) return super.createContents(parent);
        var panel = new Composite(parent, SWT.NONE);
        var layout = new GridLayout(1, false);
        layout.marginHeight = 0;
        layout.marginWidth = 0;
        panel.setLayout(layout);
        String other = mode.equals("methods") ? "lines" : "methods";
        Button switchMode = new Button(panel, SWT.PUSH);
        switchMode.setText(other.equals("lines") ? "По строкам" : "По методам");
        switchMode.addListener(SWT.Selection, event -> {
            switchMode.setEnabled(false);
            switchMode(other, switchMode);
        });
        if (!methodNotice.isEmpty()) {
            var notice = new Label(panel, SWT.WRAP);
            notice.setText(methodNotice);
            var noticeLayout = new GridData(SWT.FILL, SWT.CENTER, true, false);
            noticeLayout.widthHint = 420;
            notice.setLayoutData(noticeLayout);
        }
        Control content = super.createContents(panel);
        content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        if (mode.equals("methods") && structurePane != null && structureViewer != null && diff.hasChildren()) {
            var children = diff.getChildren();
            var first = Arrays.stream(children).filter(node -> node.getKind() == Differencer.CHANGE)
                    .findFirst().orElse(children[0]);
            var selection = new StructuredSelection(first);
            parent.getDisplay().asyncExec(() -> {
                if (!structurePane.isDisposed()) {
                    structurePane.setSelection(selection);
                    structurePane.open(new OpenEvent(structureViewer, selection));
                }
            });
        }
        return panel;
    }

    @Override protected CompareViewerPane createStructureInputPane(Composite parent) {
        structurePane = super.createStructureInputPane(parent);
        return structurePane;
    }

    @Override public Viewer createDiffViewer(Composite parent) {
        structureViewer = super.createDiffViewer(parent);
        return structureViewer;
    }

    @Override public Viewer findContentViewer(Viewer oldViewer, ICompareInput input, Composite parent) {
        Viewer viewer = super.findContentViewer(oldViewer, input, parent);
        if (viewer instanceof TextMergeViewer) {
            configureCompareColors(viewer.getControl(), input);
        }
        return viewer;
    }

    @Override public void registerContextMenu(MenuManager manager, ISelectionProvider selectionProvider) {
        super.registerContextMenu(manager, selectionProvider);
        if (!(selectionProvider instanceof SourceViewer source)) return;
        StyledText text = source.getTextWidget();
        text.setData(COMPARE_SOURCE, source);
        if (!bsl) return;
        int[] clickedLine = {-1};
        text.addMenuDetectListener(event -> {
            clickedLine[0] = -1;
            if (event.detail == SWT.MENU_KEYBOARD) {
                clickedLine[0] = source.widgetLine2ModelLine(text.getLineAtOffset(text.getCaretOffset()));
                return;
            }
            int y = text.toControl(event.x, event.y).y;
            int widgetLine = text.getLineIndex(y);
            if (y >= text.getLinePixel(widgetLine)
                    && y < text.getLinePixel(widgetLine) + text.getLineHeight(text.getOffsetAtLine(widgetLine))) {
                clickedLine[0] = source.widgetLine2ModelLine(widgetLine);
            }
        });
        manager.addMenuListener(menu -> {
            int line = moduleLine(source, clickedLine[0]);
            clickedLine[0] = -1;
            if (line < 1) return;
            menu.add(new Action("Перейти к строке") {
                @Override public void run() {
                    var window = editorPart == null ? null : editorPart.getSite().getWorkbenchWindow();
                    var page = window == null ? null : editorPart.getSite().getPage();
                    if (page == null) return;
                    try {
                        EdtEditorService.getInstance().openBslReviewLine(page, project, path, line, after);
                    } catch (Exception failure) {
                        MessageDialog.openInformation(window.getShell(), "Перейти к строке", failure.getMessage());
                    }
                }
            });
        });
    }

    private int moduleLine(SourceViewer source, int line) {
        if (line < 0 || source.getDocument() == null
                || !(source.getTextWidget().getData(SOURCE_INPUT) instanceof ICompareInput input)
                || !(input.getRight() instanceof Side side) || side.sourceSlices().isEmpty()) return -1;
        String current = normalizeLines(source.getDocument().get());
        if (!current.equals(snapshotText(side)) || current.equals(snapshotText(input.getLeft()))) return -1;
        try {
            int offset = new Document(side.text()).getLineOffset(line);
            SourceSlice found = side.sourceSlices().get(0);
            for (SourceSlice slice : side.sourceSlices()) {
                if (slice.displayedOffset() > offset) break;
                found = slice;
            }
            return new Document(after).getLineOfOffset(found.moduleOffset() + offset - found.displayedOffset()) + 1;
        } catch (BadLocationException invalidLine) {
            return -1;
        }
    }

    @Override protected CompareViewerSwitchingPane createContentViewerSwitchingPane(
            Splitter parent, int style, CompareEditorInput editor) {
        var pane = super.createContentViewerSwitchingPane(parent, style, editor);
        // EDT can clear and rebuild its toolbar on viewer changes and on diff refresh.
        // Top-right belongs to this pane and survives those native toolbar updates.
        var checkbox = new Button(pane, SWT.CHECK);
        checkbox.setText("Подсветка MCP");
        checkbox.setToolTipText("Удалённые строки красным, добавленные зелёным");
        checkbox.setSelection(highlightEnabled);
        checkbox.addListener(SWT.Selection, event -> {
            highlightEnabled = checkbox.getSelection();
            if (pane.getViewer() instanceof TextMergeViewer viewer) {
                Control content = viewer.getControl();
                refreshCompareColors(content);
                var size = content.getSize();
                content.redraw(0, 0, size.x, size.y, true);
            }
        });
        pane.setTopRight(checkbox);
        if (pane.getTopLeft() instanceof Composite header) {
            for (Control child : header.getChildren()) {
                if (!(child instanceof Link link)) continue;
                var display = pane.getDisplay();
                link.addListener(SWT.Show, event -> display.asyncExec(() -> {
                    if (!link.isDisposed() && link.getVisible()
                            && Boolean.TRUE.equals(getCompareConfiguration().getProperty(OPTIMIZED_ALGORITHM_USED))) {
                        // EDT owns the exact recomputation and restores its settings after the run.
                        link.notifyListeners(SWT.Selection, new Event());
                    }
                }));
            }
        }
        return pane;
    }

    private static String snapshotText(ITypedElement side) {
        return side instanceof Side snapshot ? normalizeLines(snapshot.text()) : "";
    }

    private static String normalizeLines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private void configureCompareColors(Control control, ICompareInput input) {
        if (control instanceof StyledText text) {
            if (text.getData(COMPARE_SOURCE) instanceof SourceViewer source && text.getData(REVIEW_COLORS) == null) {
                text.setData(REVIEW_COLORS, new ReviewColors(source));
            }
            if (bsl) text.setData(SOURCE_INPUT, input);
        } else if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) configureCompareColors(child, input);
        }
    }

    private static void refreshCompareColors(Control control) {
        if (control instanceof StyledText text && text.getData(REVIEW_COLORS) instanceof ReviewColors colors) {
            colors.source.invalidateTextPresentation();
        } else if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) refreshCompareColors(child);
        }
    }

    private final class ReviewColors {
        private static final RGB EDT_ADDITION_DEFAULT = new RGB(0, 255, 0);
        private static final RGB EDT_DELETION_DEFAULT = new RGB(255, 0, 0);
        private final SourceViewer source;
        private final StyledText text;
        private final Map<RGB, Color> colors = new LinkedHashMap<>();

        ReviewColors(SourceViewer source) {
            this.source = source;
            this.text = source.getTextWidget();
            // EDT registers both of its highlighters during construction, before this hook.
            text.addLineBackgroundListener(event -> {
                if (highlightEnabled && event.lineBackground != null) {
                    event.lineBackground = swap(event.lineBackground, 0.9);
                }
            });
            source.addTextPresentationListener(this::swapTokens);
            text.addDisposeListener(event -> {
                colors.values().forEach(Color::dispose);
                colors.clear();
            });
        }

        private void swapTokens(TextPresentation presentation) {
            if (!highlightEnabled) return;
            var ranges = presentation.getAllStyleRangeIterator();
            var replacements = new ArrayList<StyleRange>();
            while (ranges.hasNext()) {
                StyleRange range = ranges.next();
                if (range.background == null) continue;
                Color background = swap(range.background, 0.8);
                if (background != range.background) {
                    replacements.add(new StyleRange(range.start, range.length, null, background));
                }
            }
            if (!replacements.isEmpty()) presentation.mergeStyleRanges(replacements.toArray(StyleRange[]::new));
        }

        private Color swap(Color background, double backgroundWeight) {
            var registry = JFaceResources.getColorRegistry();
            RGB base = text.getBackground().getRGB();
            RGB addition = Objects.requireNonNullElse(registry.getRGB("ADDITION_COLOR"), EDT_ADDITION_DEFAULT);
            RGB deletion = Objects.requireNonNullElse(registry.getRGB("DELETION_COLOR"), EDT_DELETION_DEFAULT);
            RGB green = fill(base, addition, backgroundWeight);
            RGB red = fill(base, deletion, backgroundWeight);
            RGB current = background.getRGB();
            if (green.equals(red) || current.equals(base)) return background;
            RGB swapped = current.equals(green) ? red : current.equals(red) ? green : null;
            return swapped == null ? background : colors.computeIfAbsent(swapped, key -> new Color(text.getDisplay(), key));
        }

        // Eclipse Compare computes its line and token backgrounds from the same palette at different weights.
        private static RGB fill(RGB base, RGB tint, double backgroundWeight) {
            double weight = 1 - backgroundWeight;
            return new RGB((int) (weight * tint.red + backgroundWeight * base.red),
                    (int) (weight * tint.green + backgroundWeight * base.green),
                    (int) (weight * tint.blue + backgroundWeight * base.blue));
        }
    }

    private void switchMode(String other, Button button) {
        var display = PlatformUI.getWorkbench().getDisplay();
        CompletableFuture.runAsync(() -> {
            try {
                var service = new BslReviewService();
                if (id.startsWith("turn-")) service.openTurn(id, project, path, other);
                else service.open(id, path, other);
            } catch (Exception failure) {
                if (!display.isDisposed()) {
                    display.asyncExec(() -> MessageDialog.openInformation(display.getActiveShell(),
                            "Сравнение BSL", failure.getMessage()));
                }
            } finally {
                if (!display.isDisposed()) {
                    display.asyncExec(() -> {
                        if (!button.isDisposed()) button.setEnabled(true);
                    });
                }
            }
        });
    }

    boolean retained() { return editorPart != null; }

    void release() {
        if (released.compareAndSet(false, true)) onClose.run();
    }

    @Override protected void handleDispose() {
        try {
            super.handleDispose();
        } finally {
            if (editorPart != null) EdtEditorService.getInstance().forget(editorPart);
            release();
        }
    }

    static void close(String id) {
        if (!PlatformUI.isWorkbenchRunning()) return;
        var workbench = PlatformUI.getWorkbench();
        workbench.getDisplay().syncExec(() -> {
            for (var window : workbench.getWorkbenchWindows()) {
                for (var page : window.getPages()) {
                    for (var ref : page.getEditorReferences()) {
                        var part = ref.getEditor(false);
                        if (part != null && !part.isDirty() && part.getEditorInput() instanceof BslReviewEditor input
                                && id.equals(input.id)) {
                            page.closeEditor(part, false);
                        }
                    }
                }
            }
        });
    }
}
