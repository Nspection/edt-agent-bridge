// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionArguments;
import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.FrameworkUtil;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;

/** Reports the EDT access question owned by an active provision call. */
public final class ServerRestoreAccessPrompt implements AutoCloseable {
    private static final String DIALOG =
        "com._1c.g5.v8.dt.platform.services.ui.infobases.InfobaseAccessSettingsDialog";
    private static final String REQUESTOR =
        "com._1c.g5.v8.dt.internal.platform.services.ui.infobases.InfobaseAccessSettingsRequestor";
    private static final String DESIGNER_POOL =
        "com._1c.g5.v8.dt.internal.platform.services.core.runtimes.execution.DesignerSessionPool";
    private static final Map<String, ServerRestoreAccessPrompt> ACTIVE = new ConcurrentHashMap<>();

    private final String jobId = BackgroundJobRegistry.currentJobId();
    private final Thread owner = Thread.currentThread();
    private final InfobaseReference ref;
    private final InfobaseWorkflow flow;
    private final String accessStage;
    private final String callerSession;
    private final boolean canCancel;
    private final Display display;
    private final Listener filter;
    private Shell question;
    private String questionId;
    private volatile boolean cancelRequested;
    private volatile boolean closed;
    private boolean inputOpen;
    private boolean externalWait;
    private volatile boolean nativeAccessConfirmed;
    private Grant pendingGrant;
    private volatile Grant submittedGrant;

    ServerRestoreAccessPrompt(InfobaseReference ref, InfobaseWorkflow flow, String accessStage, String callerSession) {
        this.ref = ref;
        this.flow = flow;
        this.accessStage = accessStage;
        this.callerSession = callerSession;
        canCancel = "backup_existing".equals(accessStage) || "verify_restored_access".equals(accessStage);
        filter = event -> {
            if (event.widget instanceof Shell shell) onShow(shell);
        };
        if (jobId == null || FrameworkUtil.getBundle(getClass()) == null || !PlatformUI.isWorkbenchRunning()) {
            display = null;
            return;
        }
        display = PlatformUI.getWorkbench().getDisplay();
        if (ACTIVE.putIfAbsent(jobId, this) != null) throw new IllegalStateException("Access observation already active");
        try {
            display.syncExec(() -> {
                display.addFilter(SWT.Show, filter);
                display.timerExec(750, this::inspectPoolWait);
            });
        } catch (RuntimeException failure) {
            ACTIVE.remove(jobId, this);
            throw failure;
        }
    }

    private void inspectPoolWait() {
        if (closed || display.isDisposed()) return;
        if (question == null) {
            boolean blocked = isWaitingForNativeAccessInPool();
            if (blocked != externalWait) {
                externalWait = blocked;
                flow.signal(blocked
                    ? Map.of("stage", "blocked_by_other_edt_access", "access_stage", accessStage,
                        "access_waiting", true, "access_cancel_available", false, "secure_input_available", false,
                        "message", "Another EDT access dialog holds the Designer session pool. Handle that dialog in EDT and observe this original job.")
                    : Map.of("stage", accessStage, "access_waiting", false, "access_stage", accessStage));
            }
        }
        display.timerExec(750, this::inspectPoolWait);
    }

    private boolean isWaitingForNativeAccessInPool() {
        var threads = ManagementFactory.getThreadMXBean();
        var waiting = threads.getThreadInfo(owner.getId(), 10);
        if (waiting == null || waiting.getThreadState() != Thread.State.BLOCKED || waiting.getLockOwnerId() <= 0
                || Arrays.stream(waiting.getStackTrace()).noneMatch(frame ->
                    frame.getClassName().equals(DESIGNER_POOL) && frame.getMethodName().equals("computeExclusively"))) {
            return false;
        }
        var holder = threads.getThreadInfo(waiting.getLockOwnerId(), 24);
        if (holder == null || !waitingForRequestor(holder.getStackTrace())) {
            return false;
        }
        return Arrays.stream(display.getShells()).anyMatch(shell -> !shell.isDisposed() && shell.isVisible()
            && shell.getData() != null && DIALOG.equals(shell.getData().getClass().getName()));
    }

    private boolean matches(Shell shell) {
        if (shell.isDisposed()) return false;
        Object dialog = shell.getData();
        if (dialog == null || !DIALOG.equals(dialog.getClass().getName())) return false;
        try {
            Object target = dialog.getClass().getMethod("getInfobase").invoke(dialog);
            if (!(target instanceof InfobaseReference base) || base.getConnectionString() == null
                    || !Objects.equals(ref.getUuid(), base.getUuid())) return false;
            return InfobasePaths.connectionIdentity(ref.getConnectionString().asConnectionString())
                .equals(InfobasePaths.connectionIdentity(base.getConnectionString().asConnectionString()));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
    }

    private static boolean waitingForRequestor(StackTraceElement[] stack) {
        return Arrays.stream(stack).anyMatch(frame -> frame.getClassName().equals(REQUESTOR)
            && frame.getMethodName().equals("get"));
    }

    private boolean ownsDispatch() {
        Thread synchronousOwner = display.getSyncThread();
        if (synchronousOwner != null) return owner == synchronousOwner;
        // The workbench dispatches syncExec through its own queue. A native requester
        // must be the callback of the current item, not a nested UI event in its modal loop.
        var frames = Thread.currentThread().getStackTrace();
        int callback = -1;
        for (int i = 0; i < frames.length; i++) {
            if (frames[i].getClassName().equals(REQUESTOR)) {
                callback = i;
                break;
            }
        }
        if (callback < 0) return false;
        int next = callback + 1;
        if (next < frames.length && frames[next].getClassName().startsWith(REQUESTOR + "$$Lambda$")) next++;
        if (next >= frames.length || !frames[next].getClassName().equals("org.eclipse.ui.internal.PendingSyncExec")
                || !frames[next].getMethodName().equals("run")) return false;
        try {
            var synchronizer = display.getSynchronizer();
            if (!synchronizer.getClass().getName().equals("org.eclipse.ui.internal.UISynchronizer")) return false;
            Object listener = workbenchField(synchronizer, "lockListener");
            if (listener == null || !listener.getClass().getName().equals("org.eclipse.ui.internal.UILockListener")) return false;
            Object pending = workbenchField(listener, "currentWork");
            return pending != null && pending.getClass().getName().equals("org.eclipse.ui.internal.PendingSyncExec")
                && pending.getClass().getMethod("getOperationThread").invoke(pending) == owner;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private static Object workbenchField(Object target, String name) throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(name);
        if (!field.trySetAccessible()) throw new IllegalAccessException(name);
        return field.get(target);
    }

    private void onShow(Shell shell) {
        if (closed || question != null || !matches(shell) || !ownsDispatch()) return;
        question = shell;
        questionId = UUID.randomUUID().toString();
        publishWaiting(false);
        shell.addListener(SWT.Dispose, event -> {
            if (question != shell || closed) return;
            if (submittedGrant == null && shell.getData() instanceof Window window) {
                if (window.getReturnCode() == Window.OK) nativeAccessConfirmed = true;
                if (canCancel && window.getReturnCode() == Window.CANCEL) cancelRequested = true;
            }
            pendingGrant = null;
            question = null;
            questionId = null;
            flow.signal(Map.of("stage", cancelRequested ? "access_cancel_requested" : accessStage,
                "access_waiting", false, "access_stage", accessStage));
        });
    }

    private void publishWaiting(boolean credentialsReady) {
        boolean controlled = canCancel && !callerSession.isBlank();
        flow.signal(Map.of("stage", "awaiting_infobase_access", "access_stage", accessStage,
            "access_request_id", questionId, "base_uuid", ref.getUuid().toString(),
            "next_tool", "edt_operation_status", "access_waiting", true, "access_cancel_available", controlled,
            "secure_input_ready", credentialsReady, "secure_input_available", controlled,
            "message", "Complete access to the selected infobase in EDT. Continue the original job."));
    }

    private static ServerRestoreAccessPrompt active(String jobId, String caller) {
        var prompt = ACTIVE.get(jobId);
        if (prompt == null || caller.isBlank() || !prompt.callerSession.equals(caller))
            throw new IllegalStateException("No active access question for the original job in this session");
        return prompt;
    }

    private void checkQuestion(String expected, boolean secure) {
        if (closed || !canCancel || cancelRequested || submittedGrant != null || question == null
                || !Objects.equals(expected, questionId) || !matches(question) || inputOpen
                || (secure && pendingGrant != null)) {
            throw new IllegalStateException("Stale, busy, or unowned infobase access question");
        }
    }

    public static Map<String, Object> cancel(String jobId, String expectedRequest, String caller) {
        var prompt = active(jobId, caller);
        prompt.display.syncExec(() -> {
            prompt.checkQuestion(expectedRequest, false);
            var shell = prompt.question;
            prompt.cancelRequested = true;
            try {
                shell.close();
            } finally {
                if (!shell.isDisposed()) prompt.cancelRequested = false;
            }
            if (!prompt.cancelRequested) throw new IllegalStateException("EDT did not accept access cancellation");
        });
        return Map.of("job_id", jobId, "state", "access_cancel_requested", "terminal", false,
            "next_tool", "edt_operation_status");
    }

    public static Map<String, Object> requestSecureInput(String jobId, String expectedRequest, String caller) {
        var prompt = active(jobId, caller);
        prompt.display.syncExec(() -> {
            prompt.checkQuestion(expectedRequest, true);
            prompt.inputOpen = true;
            prompt.display.asyncExec(() -> prompt.openSecureInput(expectedRequest));
        });
        return Map.of("job_id", jobId, "state", "secure_input_requested", "terminal", false);
    }

    private void openSecureInput(String expectedRequest) {
        try {
            if (closed || !Objects.equals(expectedRequest, questionId) || question == null || !matches(question)) return;
            var dialog = new SecureInputDialog(question, accessStage, ref.getName());
            if (dialog.open() != Window.OK || closed || !Objects.equals(expectedRequest, questionId)
                    || question == null || !matches(question)) return;
            var grant = dialog.grant;
            pendingGrant = grant;
            publishWaiting(true);
            display.timerExec(120000, () -> {
                if (!closed && pendingGrant != null && Objects.equals(questionId, expectedRequest)
                        && System.nanoTime() - pendingGrant.expiresAt >= 0) {
                    pendingGrant = null;
                    publishWaiting(false);
                }
            });
        } finally {
            inputOpen = false;
        }
    }

    public static Map<String, Object> continueAccess(String jobId, String expectedRequest, String caller) {
        var prompt = active(jobId, caller);
        prompt.display.syncExec(() -> {
            prompt.checkQuestion(expectedRequest, false);
            var grant = prompt.pendingGrant;
            if (grant == null || System.nanoTime() - grant.expiresAt >= 0)
                throw new IllegalStateException("No fresh protected input for this request");
            var shell = prompt.question;
            prompt.pendingGrant = null;
            prompt.submittedGrant = grant;
            try {
                shell.close();
            } finally {
                if (!shell.isDisposed()) {
                    prompt.submittedGrant = null;
                    prompt.publishWaiting(false);
                }
            }
            if (!shell.isDisposed()) throw new IllegalStateException("EDT did not release the access dialog");
        });
        return Map.of("job_id", jobId, "state", "access_continuation_requested", "terminal", false,
            "next_tool", "edt_operation_status");
    }

    Grant takeSubmittedGrant() {
        var grant = submittedGrant;
        submittedGrant = null;
        return grant;
    }

    static final class Grant {
        private final String login;
        private final String password;
        private final long expiresAt = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(2);
        final boolean persist;

        private Grant(String login, String password, boolean persist) {
            this.login = login;
            this.password = password;
            this.persist = persist;
        }

        void applyTo(RuntimeExecutionArguments args) {
            args.setAccess(InfobaseAccess.INFOBASE);
            args.setUsername(login);
            args.setPassword(password);
        }
    }

    private static final class SecureInputDialog extends Dialog {
        private final String stage;
        private final String baseName;
        private Text login;
        private Text password;
        private Button persist;
        private Label error;
        private Grant grant;

        SecureInputDialog(Shell parent, String stage, String baseName) {
            super(parent);
            this.stage = stage;
            this.baseName = Objects.toString(baseName, "");
        }

        @Override protected void configureShell(Shell shell) {
            super.configureShell(shell);
            shell.setText("EDT Agent Bridge — подтверждение доступа");
        }

        @Override protected Control createDialogArea(Composite parent) {
            var area = (Composite) super.createDialogArea(parent);
            var context = new Label(area, SWT.WRAP);
            context.setText(("backup_existing".equals(stage) ? "Резервная копия текущей ИБ: " : "Доступ после загрузки DT: ") + baseName);
            new Label(area, SWT.NONE).setText("Пользователь");
            login = new Text(area, SWT.BORDER);
            login.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            login.setTextLimit(256);
            new Label(area, SWT.NONE).setText("Пароль");
            password = new Text(area, SWT.BORDER | SWT.PASSWORD);
            password.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            password.setTextLimit(1024);
            persist = new Button(area, SWT.CHECK);
            persist.setText("Сохранить проверенные реквизиты в EDT");
            new Label(area, SWT.WRAP).setText("Первое FULL-обновление в EDT использует сохранённый доступ.");
            error = new Label(area, SWT.NONE);
            error.setText(" ");
            return area;
        }

        @Override protected void okPressed() {
            String user = login.getText().trim();
            if (user.isEmpty()) {
                error.setText("Введите имя пользователя.");
                error.getParent().layout();
                return;
            }
            grant = new Grant(user, password.getText(), persist.getSelection());
            password.setText("");
            login.setText("");
            super.okPressed();
        }
    }

    boolean nativeAccessConfirmed() {
        return nativeAccessConfirmed;
    }

    boolean cancelRequested() {
        return cancelRequested;
    }

    @Override public void close() {
        closed = true;
        if (display != null) {
            ACTIVE.remove(jobId, this);
            submittedGrant = null;
            if (!display.isDisposed()) display.syncExec(() -> {
                display.removeFilter(SWT.Show, filter);
                pendingGrant = null;
            });
        }
    }
}
