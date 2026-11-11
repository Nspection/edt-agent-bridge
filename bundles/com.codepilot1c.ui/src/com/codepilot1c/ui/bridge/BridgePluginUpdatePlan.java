// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.ui.bridge;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.equinox.p2.engine.IProfileRegistry;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.operations.Update;
import org.eclipse.equinox.p2.operations.UpdateOperation;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.ui.ProvisioningUI;
import org.osgi.framework.Version;
import com.codepilot1c.core.updates.PluginUpdateCheck;

/** Resolve only the Bridge root from the fixed official site; never install or restart here. */
public final class BridgePluginUpdatePlan {
    private BridgePluginUpdatePlan() { }

    public static List<IInstallableUnit> bridgeRoots(Collection<IInstallableUnit> units) {
        return units.stream().filter(iu -> PluginUpdateCheck.FEATURE_ID.equals(iu.getId())).toList();
    }

    public static Update[] newerBridgeUpdates(Update[] updates, Version running) {
        if (updates == null) return new Update[0];
        return Arrays.stream(updates).filter(u -> u.toUpdate != null && u.replacement != null
                && PluginUpdateCheck.FEATURE_ID.equals(u.toUpdate.getId())
                && PluginUpdateCheck.FEATURE_ID.equals(u.replacement.getId())
                && u.replacement.getVersion().compareTo(u.toUpdate.getVersion()) > 0
                && Version.parseVersion(u.replacement.getVersion().toString()).compareTo(running) > 0)
                .max(Comparator.comparing(u -> u.replacement.getVersion())).map(u -> new Update[] { u })
                .orElseGet(() -> new Update[0]);
    }

    public static UpdateOperation resolve(ProvisioningUI ui, Version running, IProgressMonitor monitor) {
        if (ui == null || ui.getProfileId() == null || ui.hasScheduledOperations()) {
            throw new IllegalStateException("Механизм обновления занят или недоступен. Повторите позже.");
        }
        var registry = ui.getSession().getProvisioningAgent().getService(IProfileRegistry.class);
        var profile = registry == null ? null : registry.getProfile(ui.getProfileId());
        if (profile == null) throw new IllegalStateException("Текущий профиль установки EDT недоступен.");
        var roots = bridgeRoots(profile.query(QueryUtil.createIUQuery(PluginUpdateCheck.FEATURE_ID), monitor).toSet());
        if (roots.size() != 1) throw new IllegalStateException(
                "Bridge установлен вне p2-профиля. Установите его через «Справка → Установить новое ПО» с адреса "
                + PluginUpdateCheck.UPDATE_SITE);
        var operation = ui.getUpdateOperation(roots, new java.net.URI[] { PluginUpdateCheck.UPDATE_SITE });
        operation.getProvisioningContext().setProperty(
                org.eclipse.equinox.p2.engine.ProvisioningContext.FOLLOW_REPOSITORY_REFERENCES, "false");
        IStatus status = operation.resolveModal(monitor);
        if (monitor.isCanceled() || status.matches(IStatus.CANCEL)) return null;
        if (status.matches(IStatus.ERROR)) throw new IllegalStateException("Не удалось подготовить обновление: " + status.getMessage());
        var selected = newerBridgeUpdates(operation.getPossibleUpdates(), running);
        if (selected.length == 0) return null;
        operation.setSelectedUpdates(selected);
        status = operation.resolveModal(monitor);
        if (monitor.isCanceled() || status.matches(IStatus.CANCEL)) return null;
        if (status.matches(IStatus.ERROR)) throw new IllegalStateException("Не удалось подготовить обновление: " + status.getMessage());
        return operation;
    }
}
