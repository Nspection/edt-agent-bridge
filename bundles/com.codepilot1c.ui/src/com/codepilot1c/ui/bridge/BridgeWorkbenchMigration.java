// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.ui.bridge;

import java.util.List;
import java.util.Set;
import org.eclipse.ui.*;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.basic.*;
import org.eclipse.e4.ui.model.application.ui.advanced.MPlaceholder;
import org.eclipse.e4.ui.workbench.modeling.EModelService;

/** Removes only retired CodePilot panels, including references in inactive perspectives. */
public final class BridgeWorkbenchMigration {
    private static final Set<String> LEGACY_VIEWS = Set.of(
        "com.codepilot1c.ui.views.ChatView",
        "com.codepilot1c.ui.views.GraphStudioView",
        "com.codepilot1c.ui.views.MemoryInspectorView");

    private BridgeWorkbenchMigration() {}

    public static int closeLegacyViews(IWorkbenchPage... pages) {
        int count = 0;
        for (IWorkbenchPage page : pages) {
            for (IViewReference reference : page.getViewReferences()) {
                if (isLegacy(reference.getId())) {
                    page.hideView(reference);
                    count++;
                }
            }
        }
        return count;
    }

    public static int removeLegacyModelElements(MApplication application, EModelService models) {
        int count = 0;
        List<MPlaceholder> placeholders = models.findElements(application, null, MPlaceholder.class, null, EModelService.ANYWHERE);
        for (MPlaceholder placeholder : List.copyOf(placeholders)) {
            MUIElement target = placeholder.getRef();
            if (isLegacy(placeholder.getElementId())
                    || target != null && isLegacy(target.getElementId())) {
                detach(placeholder);
                count++;
            }
        }
        List<MPart> parts = models.findElements(application, null, MPart.class, null, EModelService.ANYWHERE);
        for (MPart part : List.copyOf(parts)) {
            if (!isLegacy(part.getElementId())) continue;
            part.setVisible(false);
            part.setToBeRendered(false);
            detach(part);
            for (MWindow window : models.findElements(application, null, MWindow.class, null, EModelService.ANYWHERE)) {
                window.getSharedElements().remove(part);
            }
            count++;
        }
        return count;
    }

    private static boolean isLegacy(String id) { return id != null && LEGACY_VIEWS.contains(id); }

    private static void detach(MUIElement element) {
        if (element.getParent() != null) element.getParent().getChildren().remove(element);
    }

    public static void run(IWorkbench workbench) {
        int removed = 0;
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
            removed += closeLegacyViews(window.getPages());
        }
        MApplication application = workbench.getService(MApplication.class);
        EModelService models = workbench.getService(EModelService.class);
        if (application != null && models != null) removed += removeLegacyModelElements(application, models);
        com.codepilot1c.core.internal.VibeCorePlugin.logInfo("EDT Agent Bridge: retired workbench panels removed=" + removed);
    }
}
