// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.contentmergeviewer.TextMergeViewer;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.compare.structuremergeviewer.ICompareInput;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;

import com._1c.g5.v8.dt.bsl.compare.BslCompareUtils;
import com._1c.g5.v8.dt.bsl.model.Module;
import com.codepilot1c.core.edt.editor.EdtEditorService;

/** A read-only Eclipse compare editor backed by immutable BSL text. */
final class BslReviewEditor extends CompareEditorInput {
    private record Side(String name, String text) implements ITypedElement, IEncodedStreamContentAccessor {
        @Override public String getName() { return name; }
        @Override public String getType() { return ITypedElement.TEXT_TYPE; }
        @Override public Image getImage() { return null; }
        @Override public String getCharset() { return StandardCharsets.UTF_8.name(); }
        @Override public InputStream getContents() {
            return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private record MethodRange(String name, int offset, int end) {}
    private record Structure(Map<String, Side> methods, String moduleText) {}

    private final String id;
    private final String path;
    private final String mode;
    private final String location;
    private final String before;
    private final String after;
    private final Runnable onClose;
    private final AtomicBoolean released = new AtomicBoolean();
    private DiffNode diff;
    private List<Map<String, Object>> sections = List.of();
    private IEditorPart editorPart;

    BslReviewEditor(String id, String project, String path, String mode, String before, String after, Runnable onClose) {
        super(config());
        this.id = id;
        this.path = path;
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
                            && id.equals(other.id) && path.equals(other.path) && mode.equals(other.mode)) {
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
        String moduleName = "Модуль целиком";
        var root = new DiffNode(Differencer.CHANGE, null, new Side(moduleName, before), new Side(moduleName, after));
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
        // Eclipse decorates tree rows from its left (target) side. The review reads left to right.
        int kind = before == null ? Differencer.DELETION : after == null ? Differencer.ADDITION : Differencer.CHANGE;
        new DiffNode(root, kind, null, before, after);
        changes.add(Map.of("name", Objects.requireNonNullElse(before, after).name(),
                "change", before == null ? "added" : after == null ? "removed" : "changed"));
    }

    private static Structure structure(String text) {
        String code = text.startsWith("\uFEFF") ? text.substring(1) : text;
        var parsed = BslCompareUtils.parseBslModuleContent(
                new ByteArrayInputStream(code.getBytes(StandardCharsets.UTF_8)));
        if (parsed == null || parsed.hasSyntaxErrors() || !(parsed.getRootASTElement() instanceof Module module)) {
            throw new IllegalArgumentException("EDT could not parse one of the BSL snapshots. Open line mode to inspect it.");
        }
        var ranges = new ArrayList<MethodRange>();
        for (var method : module.allMethods()) {
            var node = NodeModelUtils.getNode(method);
            if (node == null) throw new IllegalStateException("EDT method range unavailable");
            ranges.add(new MethodRange(method.getName(), node.getOffset(), node.getEndOffset()));
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
            methods.put(key + ":" + occurrence, new Side(name, code.substring(range.offset(), range.end())));
        }
        outer.append(code, previous, code.length());
        return new Structure(methods, outer.toString());
    }

    @Override public String getToolTipText() { return location; }

    @Override protected Object prepareInput(IProgressMonitor monitor) { return diff; }

    @Override public boolean canRunAsJob() { return false; }

    @Override public Viewer findContentViewer(Viewer old, ICompareInput input, Composite parent) {
        return old instanceof TextMergeViewer ? old : new TextMergeViewer(parent, SWT.NONE, getCompareConfiguration());
    }

    @Override public Control createContents(Composite parent) {
        var panel = new Composite(parent, SWT.NONE);
        var layout = new GridLayout(1, false);
        layout.marginHeight = 0;
        layout.marginWidth = 0;
        panel.setLayout(layout);
        String other = mode.equals("methods") ? "lines" : "methods";
        Button switchMode = new Button(panel, SWT.PUSH);
        switchMode.setText(other.equals("lines") ? "По строкам" : "По методам");
        switchMode.addListener(SWT.Selection, event -> switchMode(other));
        Control content = super.createContents(panel);
        content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        return panel;
    }

    private void switchMode(String other) {
        var display = PlatformUI.getWorkbench().getDisplay();
        CompletableFuture.runAsync(() -> {
            try {
                new BslReviewService().open(id, path, other);
            } catch (Exception failure) {
                if (!display.isDisposed()) {
                    display.asyncExec(() -> MessageDialog.openInformation(display.getActiveShell(),
                            "Сравнение BSL", failure.getMessage()));
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
