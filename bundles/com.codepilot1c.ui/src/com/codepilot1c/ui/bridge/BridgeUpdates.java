// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.ui.bridge;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.equinox.p2.ui.ProvisioningUI;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.Version;
import org.osgi.service.prefs.BackingStoreException;
import com.codepilot1c.core.updates.PluginUpdateCheck;

/** Read-only background discovery; the native wizard is opened only after a user click. */
public final class BridgeUpdates {
    private static final BridgeUpdates INSTANCE = new BridgeUpdates();
    private static final String ENABLED = "updates.automatic";
    private static final String LAST_ATTEMPT = "updates.lastAttempt";
    private static final String NOTIFIED = "updates.notifiedVersion";
    private final org.eclipse.core.runtime.preferences.IEclipsePreferences preferences =
            InstanceScope.INSTANCE.getNode("com.codepilot1c.ui");
    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean checking = new AtomicBoolean();
    private final AtomicBoolean updating = new AtomicBoolean();
    private volatile boolean active;
    private volatile long settingsGeneration;
    private volatile long lifecycleGeneration;
    private volatile boolean startupCheck;
    private volatile String status = "Проверка обновлений ещё не выполнялась.";
    private Shell notification;
    private final Job periodic = new Job("Проверка обновлений EDT Agent Bridge") {
        @Override protected IStatus run(IProgressMonitor monitor) {
            boolean force = startupCheck;
            startupCheck = false;
            try { if (active && automatic() && !monitor.isCanceled()) check(force); }
            finally { if (active && automatic()) schedule(PluginUpdateCheck.CHECK_INTERVAL_MS); }
            return Status.OK_STATUS;
        }
    };

    private BridgeUpdates() { periodic.setSystem(true); }
    public static BridgeUpdates getInstance() { return INSTANCE; }
    public Version installedVersion() { return FrameworkUtil.getBundle(BridgeUpdates.class).getVersion(); }
    public boolean automatic() { return preferences.getBoolean(ENABLED, true); }
    public boolean checking() { return checking.get(); }
    public boolean updating() { return updating.get(); }
    public String status() { return status; }

    public synchronized void start() {
        if (active) return;
        active = true;
        startupCheck = true;
        if (automatic()) periodic.schedule(60000);
    }

    public synchronized void stop() {
        active = false;
        lifecycleGeneration++;
        updating.set(false);
        periodic.cancel();
        listeners.clear();
        ui(() -> { if (notification != null && !notification.isDisposed()) notification.dispose(); });
    }

    public synchronized void setAutomatic(boolean value) throws BackingStoreException {
        boolean changed = automatic() != value;
        preferences.putBoolean(ENABLED, value);
        preferences.flush();
        if (!changed) return;
        settingsGeneration++;
        periodic.cancel();
        startupCheck = value;
        if (active && value) periodic.schedule(1000);
        if (!value) ui(() -> { if (notification != null && !notification.isDisposed()) notification.dispose(); });
    }

    public AutoCloseable listen(Consumer<String> listener) {
        listeners.add(listener);
        listener.accept(status);
        return () -> listeners.remove(listener);
    }

    public void checkNow() {
        var job = new Job("Проверка обновлений EDT Agent Bridge") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                if (!monitor.isCanceled()) check(true);
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule();
    }

    private void check(boolean force) {
        long now = System.currentTimeMillis();
        if (!active || (!force && !PluginUpdateCheck.due(automatic(), preferences.getLong(LAST_ATTEMPT, 0), now))
                || !checking.compareAndSet(false, true)) return;
        long generation = settingsGeneration;
        long lifecycle = lifecycleGeneration;
        try {
            publish("Проверка обновлений…");
            preferences.putLong(LAST_ATTEMPT, now);
            preferences.flush();
            var release = PluginUpdateCheck.fetch(installedVersion());
            if (!active || generation != settingsGeneration || lifecycle != lifecycleGeneration) return;
            publish(release.updateAvailable() ? "Доступна версия " + release.available() + "."
                    : "Установлена актуальная версия: " + release.installed() + ".");
            if (automatic() && PluginUpdateCheck.shouldNotify(release, preferences.get(NOTIFIED, ""))) {
                ui(() -> { if (lifecycle == lifecycleGeneration) notifyAvailable(release, generation); });
            }
        } catch (Exception unavailable) {
            if (active && generation == settingsGeneration && lifecycle == lifecycleGeneration) publish(
                    "Не удалось проверить обновления. Проверьте сеть и повторите проверку.");
        } finally {
            checking.set(false);
            if (active) publish(status);
        }
    }

    private void publish(String text) {
        status = text;
        ui(() -> { if (active) for (var listener : listeners) listener.accept(status); });
    }

    private static boolean ui(Runnable work) {
        if (!PlatformUI.isWorkbenchRunning()) return false;
        try {
            var display = PlatformUI.getWorkbench().getDisplay();
            return BridgeUiDispatch.enqueue(display::isDisposed, display::asyncExec, work);
        } catch (IllegalStateException stopped) {
            if (!PlatformUI.isWorkbenchRunning()) return false;
            throw stopped;
        }
    }

    private void notifyAvailable(PluginUpdateCheck.Release release, long generation) {
        if (!active || !automatic() || generation != settingsGeneration
                || !PluginUpdateCheck.shouldNotify(release, preferences.get(NOTIFIED, ""))) return;
        var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null || window.getShell().isDisposed()) return;
        if (notification != null && !notification.isDisposed()) notification.dispose();
        Shell shell = new Shell(window.getShell(), SWT.TOOL | SWT.ON_TOP | SWT.NO_FOCUS);
        notification = shell;
        shell.setText("Обновление EDT Agent Bridge");
        shell.setLayout(new GridLayout(2, false));
        var label = new Label(shell, SWT.WRAP);
        label.setText("Доступна новая версия EDT Agent Bridge: " + release.available());
        label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        Button install = new Button(shell, SWT.PUSH);
        install.setText("Обновить плагин…");
        install.addListener(SWT.Selection, e -> { shell.dispose(); openUpdateWizard(window.getShell()); });
        Button later = new Button(shell, SWT.PUSH);
        later.setText("Позже");
        later.addListener(SWT.Selection, e -> shell.dispose());
        shell.pack();
        var bounds = shell.getMonitor().getClientArea();
        shell.setLocation(bounds.x + Math.max(0, bounds.width - shell.getSize().x - 24),
                bounds.y + Math.max(0, bounds.height - shell.getSize().y - 24));
        shell.setVisible(true);
        preferences.put(NOTIFIED, release.available().toString());
        try { preferences.flush(); } catch (BackingStoreException ignored) { /* No repeated popup this session. */ }
    }

    public void openUpdateWizard(Shell parent) {
        if (!active || parent == null || parent.isDisposed() || !updating.compareAndSet(false, true)) return;
        long lifecycle = lifecycleGeneration;
        publish("Подготовка обновления плагина…");
        var job = new Job("Подготовка обновления EDT Agent Bridge") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                try {
                    ProvisioningUI provisioning = ProvisioningUI.getDefaultUI();
                    var operation = BridgePluginUpdatePlan.resolve(provisioning, installedVersion(), monitor);
                    boolean queued = ui(() -> {
                        try {
                            if (!active || lifecycle != lifecycleGeneration || parent.isDisposed()) return;
                            if (monitor.isCanceled()) { publish("Подготовка обновления отменена."); return; }
                            if (operation == null) publish("Новых обновлений плагина не найдено.");
                            else {
                                provisioning.openUpdateWizard(false, operation, null);
                                publish("Мастер обновления закрыт. Перезапустите EDT, если мастер предложил это после установки.");
                            }
                        } finally { if (lifecycle == lifecycleGeneration) { updating.set(false); if (active) publish(status); } }
                    });
                    if (!queued && lifecycle == lifecycleGeneration) updating.set(false);
                } catch (Exception error) {
                    if (lifecycle != lifecycleGeneration) return Status.CANCEL_STATUS;
                    updating.set(false);
                    publish("Не удалось подготовить обновление плагина.");
                    ui(() -> { if (active && lifecycle == lifecycleGeneration && !parent.isDisposed()) MessageDialog.openInformation(parent,
                            "Обновление EDT Agent Bridge", com.codepilot1c.core.logging.LogSanitizer
                                    .redactSecrets(java.util.Objects.toString(error.getMessage(), "Обновление недоступно."))); });
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }
}
