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
import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.contentmergeviewer.TextMergeViewer;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.compare.structuremergeviewer.ICompareInput;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.OpenEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
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
    private record Side(String name, String text, String icon, String type)
            implements ITypedElement, IEncodedStreamContentAccessor {
        Side(String name, String text) {
            this(name, text, null, "bsl");
        }
        Side(String name, String text, String icon) {
            this(name, text, icon, "bsl");
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
    private record Structure(Map<String, Side> methods, String moduleText) {}

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
        // Eclipse treats its logical left side as the current version. Mirror it
        // so the old snapshot is still displayed on the left of the review.
        configuration.setProperty(CompareConfiguration.MIRRORED, true);
        configuration.setLeftLabel("После изменений");
        configuration.setRightLabel("До изменений");
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
        return Map.of("state", "open", "review_id", id, "file_path", path, "mode", mode, "read_only", true,
                "sections", sections, "editor_id", selected.get());
    }

    private DiffNode buildDiff() {
        String name = bsl ? "Модуль целиком" : "Файл целиком";
        String type = bsl ? "bsl" : "txt";
        var root = new DiffNode(Differencer.CHANGE, null, new Side(name, after, null, type),
                new Side(name, before, null, type));
        if (mode.equals("lines")) return root;
        Structure oldModule = structure(before);
        Structure newModule = structure(after);
        var keys = new LinkedHashSet<>(oldModule.methods().keySet());
        keys.addAll(newModule.methods().keySet());
        var changed = new ArrayList<Map<String, Object>>();
        add(root, changed, new Side("Вне методов", oldModule.moduleText()), new Side("Вне методов", newModule.moduleText()));
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

    private static String methodNames(List<Map.Entry<String, Side>> entries) {
        return String.join("\n", entries.stream().map(entry -> entry.getValue().name()).toList());
    }

    private static void add(DiffNode root, List<Map<String, Object>> changes, Side before, Side after) {
        if (before != null && after != null && before.text().equals(after.text())) return;
        int kind = before == null ? Differencer.ADDITION : after == null ? Differencer.DELETION : Differencer.CHANGE;
        new DiffNode(root, kind, null, after, before);
        changes.add(Map.of("name", Objects.requireNonNullElse(before, after).name(),
                "change", before == null ? "added" : after == null ? "removed" : "changed"));
    }

    private static Structure structure(String text) {
        String code = text.startsWith("\uFEFF") ? text.substring(1) : text;
        if (code.isBlank()) return new Structure(Map.of(), code);
        var parsed = BslCompareUtils.parseBslModuleContent(
                new ByteArrayInputStream(code.getBytes(StandardCharsets.UTF_8)));
        if (parsed == null || parsed.hasSyntaxErrors() || !(parsed.getRootASTElement() instanceof Module module)) {
            throw new IllegalArgumentException("EDT could not parse one of the BSL snapshots. Open line mode to inspect it.");
        }
        var ranges = new ArrayList<MethodRange>();
        for (var method : module.allMethods()) {
            var node = NodeModelUtils.getNode(method);
            if (node == null) throw new IllegalStateException("EDT method range unavailable");
            String icon = method instanceof Function ? BslSharedImages.IMG_FUNCTION : BslSharedImages.IMG_PROCEDURE;
            ranges.add(new MethodRange(method.getName(), node.getOffset(), node.getEndOffset(), icon));
        }
        ranges.sort(Comparator.comparingInt(MethodRange::offset));
        var methods = new LinkedHashMap<String, Side>();
        var occurrences = new LinkedHashMap<String, Integer>();
        var outer = new StringBuilder();
        int previous = 0;
        for (MethodRange range : ranges) {
            if (range.offset() < previous || range.end() > code.length() || range.name() == null) {
                throw new IllegalStateException("EDT returned an invalid BSL method range");
            }
            outer.append(code, previous, range.offset());
            previous = range.end();
            String key = range.name().toLowerCase(Locale.ROOT);
            int occurrence = occurrences.merge(key, 1, Integer::sum);
            String name = occurrence == 1 ? range.name() : range.name() + " [" + occurrence + "]";
            methods.put(key + ":" + occurrence, new Side(name, code.substring(range.offset(), range.end()), range.icon()));
        }
        outer.append(code, previous, code.length());
        return new Structure(methods, outer.toString());
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
            // The underlying compare input uses current then baseline. SWT draws the mirrored order.
            applyLineColors(viewer.getControl(), snapshotText(input.getRight()), snapshotText(input.getLeft()));
        }
        return viewer;
    }

    private static String snapshotText(ITypedElement side) {
        return side instanceof Side snapshot ? normalizeLines(snapshot.text()) : "";
    }

    private static String normalizeLines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static void applyLineColors(Control control, String before, String after) {
        if (control instanceof StyledText text) {
            String key = BslReviewEditor.class.getName() + ".lineColors";
            var colors = (ReviewLineColors) text.getData(key);
            if (colors == null) {
                colors = new ReviewLineColors(text);
                text.setData(key, colors);
            }
            colors.snapshots(before, after);
        } else if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) applyLineColors(child, before, after);
        }
    }

    private static final class ReviewLineColors {
        private static final RGB REMOVED = new RGB(205, 47, 56);
        private static final RGB ADDED = new RGB(37, 151, 69);
        private final StyledText text;
        private String before = "";
        private String after = "";
        private boolean matchPending = true;
        private RGB tint;
        private RGB background;
        private Color color;

        ReviewLineColors(StyledText text) {
            this.text = text;
            text.addModifyListener(event -> matchPending = true);
            text.addLineBackgroundListener(event -> {
                // The native listener decides which lines are different and whether to show them.
                if (event.lineBackground == null) return;
                // The content viewer may install the snapshot after findContentViewer returns.
                if (matchPending) matchSnapshot();
                if (tint != null) event.lineBackground = color();
            });
            text.addDisposeListener(event -> clearColor());
        }

        void snapshots(String before, String after) {
            this.before = before;
            this.after = after;
            matchPending = true;
        }

        private void matchSnapshot() {
            matchPending = false;
            String current = normalizeLines(text.getText());
            RGB next = before.equals(after) ? null : current.equals(before) ? REMOVED : current.equals(after) ? ADDED : null;
            if (!Objects.equals(tint, next)) {
                tint = next;
                clearColor();
            }
        }

        private Color color() {
            RGB current = text.getBackground().getRGB();
            if (color == null || !current.equals(background)) {
                clearColor();
                background = current;
                double weight = (current.red + current.green + current.blue) < 384 ? 0.4 : 0.22;
                color = new Color(text.getDisplay(), blend(current.red, tint.red, weight),
                        blend(current.green, tint.green, weight), blend(current.blue, tint.blue, weight));
            }
            return color;
        }

        private static int blend(int base, int tint, double weight) {
            return (int) Math.round(base + (tint - base) * weight);
        }

        private void clearColor() {
            if (color != null) {
                color.dispose();
                color = null;
            }
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
