// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerFilter;
import com._1c.g5.v8.dt.validation.marker.PlainEObjectMarker;
import com._1c.g5.v8.dt.validation.marker.StandardExtraInfo;
import com._1c.g5.wiring.ServiceAccess;
import com.codepilot1c.core.edt.ast.EdtServiceGateway;
import com.codepilot1c.core.internal.VibeCorePlugin;
import com.e1c.g5.v8.dt.check.qfix.IFixManager;

public final class EdtQuickFixService {
    public Map<String, Object> run(String projectName, String path, String checkId, int line,
            boolean apply, int variant, String expectedPlan, String turnId) throws Exception {
        if (!path.startsWith("src/") || !path.endsWith(".bsl") || line < 1) {
            throw new IllegalArgumentException("Specify a project-relative BSL module and the diagnostic line");
        }
        if (!PlatformUI.isWorkbenchRunning()) throw new IllegalStateException("Quick Fix requires the EDT workbench");
        var project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.isOpen()) throw new IllegalArgumentException("EDT project is missing or closed");
        IFile file = EdtChangeSetService.file(project, path);
        var plugin = VibeCorePlugin.getDefault();
        var markers = plugin == null ? null : plugin.getMarkerManager();
        var manager = ServiceAccess.get(IFixManager.class);
        var lookup = ServiceAccess.get(IResourceLookup.class);
        var dtProject = new EdtServiceGateway().getDtProjectManager().getDtProject(project);
        if (markers == null || manager == null || dtProject == null) {
            throw new IllegalStateException("EDT Quick Fix services are not ready");
        }

        var result = new AtomicReference<Map<String, Object>>();
        var error = new AtomicReference<Exception>();
        Runnable operation = () -> {
            try (var candidates = markers.markers(MarkerFilter.createProjectFilter(project)
                    .combine(MarkerFilter.createCheckFilter(checkId)))) {
                var matching = candidates.filter(marker -> project.equals(marker.getProject())
                        && checkId.equals(marker.getCheckId()) && line == markerLine(marker)
                        && file.equals(markerFile(marker, lookup))).limit(2).toList();
                if (matching.size() != 1) throw new IllegalStateException(
                        "Expected one live marker for the exact file, check ID and line; found " + matching.size());
                Marker marker = matching.get(0);
                EdtChangeSetService.ensureNotDirty(file);
                byte[] before = EdtChangeSetService.read(file);
                String sourceSha = EdtChangeSetService.hash(before);
                var handle = manager.prepareFix(marker, dtProject);
                Exception fixFailure = null;
                try {
                    var options = new ArrayList<>(manager.getApplicableFixVariants(handle));
                    var variants = new ArrayList<Map<String, Object>>();
                    for (int i = 0; i < options.size(); i++) {
                        var choice = options.get(i);
                        String hash = EdtChangeSetService.hash(String.join("\0", projectName, path, checkId,
                                Integer.toString(line), marker.getMarkerId(), sourceSha, Integer.toString(i),
                                choice.getDescription(), java.util.Objects.toString(choice.getDetails(), ""))
                                .getBytes(StandardCharsets.UTF_8));
                        manager.selectFixVariant(choice, handle);
                        boolean supported = supported(checkId, manager.getSelectedFixVariant(handle));
                        variants.add(Map.of("variant", i, "title", choice.getDescription(), "plan_sha256", hash,
                                "apply_supported", supported));
                    }
                    if (!apply) {
                        result.set(Map.of("has_fix", !variants.isEmpty(), "variants", variants, "path", path,
                                "line", line, "check_id", checkId));
                        return;
                    }
                    if (variant < 0 || variant >= options.size() || !variants.get(variant).get("plan_sha256").equals(expectedPlan)) {
                        throw new IllegalStateException("QUICK_FIX_PLAN_CHANGED: preview the current marker again");
                    }
                    manager.selectFixVariant(options.get(variant), handle);
                    var selected = manager.getSelectedFixVariant(handle);
                    if (!supported(checkId, selected)) {
                        throw new IllegalStateException("This variant is outside the supported native BSL editor fixes");
                    }
                    EdtChangeSetService.ensureNotDirty(file);
                    if (!java.util.Arrays.equals(before, EdtChangeSetService.read(file))) {
                        throw new IllegalStateException("QUICK_FIX_PLAN_CHANGED: source changed");
                    }
                    BslReviewService.trackWrite(turnId, file);
                    try {
                        manager.executeFix(handle, new NullProgressMonitor());
                        for (var ref : editors(file)) {
                            var editor = ref.getEditor(false);
                            if (editor != null && editor.isDirty()) editor.doSave(new NullProgressMonitor());
                        }
                        EdtChangeSetService.ensureNotDirty(file);
                        var after = EdtChangeSetService.read(file);
                        boolean changed = !java.util.Arrays.equals(before, after);
                        result.set(Map.of("path", path, "check_id", checkId, "line", line,
                                "changed", changed, "state", changed ? "saved" : "no_saved_change_observed",
                                "retry_safe", false));
                    } catch (Exception failure) {
                        throw new IllegalStateException("QUICK_FIX_OUTCOME_UNKNOWN: inspect the editor and disk before any retry", failure);
                    }
                } catch (Exception failure) {
                    fixFailure = failure;
                    throw failure;
                } finally {
                    try {
                        manager.finishFix(handle);
                    } catch (RuntimeException cleanupFailure) {
                        if (fixFailure != null) {
                            fixFailure.addSuppressed(cleanupFailure);
                        } else if (apply && result.get() != null) {
                            var saved = new java.util.LinkedHashMap<>(result.get());
                            saved.put("cleanup_warning", "EDT could not finish the native fix session");
                            result.set(saved);
                        } else {
                            throw cleanupFailure;
                        }
                    }
                }
            } catch (Exception failure) {
                error.set(failure);
            }
        };
        if (Display.getCurrent() == PlatformUI.getWorkbench().getDisplay()) operation.run();
        else PlatformUI.getWorkbench().getDisplay().syncExec(operation);
        if (error.get() != null) throw error.get();
        return result.get();
    }

    private static boolean supported(String checkId, Object variant) {
        return "SU52".equals(checkId) && variant != null
                && "com.e1c.v8codestyle.bsl.ui.qfix.SelfAssignFix".equals(variant.getClass().getName());
    }

    private static List<IEditorReference> editors(IFile file) throws Exception {
        var result = new ArrayList<IEditorReference>();
        for (var window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
            for (var page : window.getPages()) {
                for (var ref : page.getEditorReferences()) {
                    if (file.equals(ref.getEditorInput().getAdapter(IFile.class))) result.add(ref);
                }
            }
        }
        return result;
    }

    private static int markerLine(Marker marker) {
        return marker.getExtraInfo() == null ? -1
                : marker.getExtraInfo().getIntOrDefault(StandardExtraInfo.TEXT_LINE, -1);
    }

    private static IFile markerFile(Marker marker, IResourceLookup lookup) {
        try {
            if (marker instanceof PlainEObjectMarker plain && plain.getURI() != null && plain.getURI().isPlatformResource()) {
                var resource = ResourcesPlugin.getWorkspace().getRoot().findMember(plain.getURI().toPlatformString(true));
                if (resource instanceof IFile file) return file;
            }
            return lookup == null ? null : marker.provideObject(
                    (java.util.function.Function<EObject, IFile>) object -> object == null ? null : lookup.getPlatformResource(object));
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
