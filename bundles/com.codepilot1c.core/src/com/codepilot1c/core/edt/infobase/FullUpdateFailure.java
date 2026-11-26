// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;

import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;

/** Public diagnostics contain fixed reasons and SDK status numbers only. */
public final class FullUpdateFailure extends IllegalStateException {
    public enum Stage {
        SOURCES(false, "EDT could not prepare the project sources for FULL."),
        PRE_DISPATCH(false, "FULL stopped during target or access preparation before calling EDT reloadInfobase."),
        NATIVE_RELOAD(true, "EDT reloadInfobase did not complete successfully."),
        VERIFY_EQUAL(true, "EDT reloadInfobase returned success, but Bridge could not verify EQUAL.");

        final boolean dispatched;
        final String message;

        Stage(boolean dispatched, String message) {
            this.dispatched = dispatched;
            this.message = message;
        }
    }

    private final Map<String,Object> diagnostic;

    private FullUpdateFailure(Stage stage, String reason, String message, IStatus status) {
        super(message);
        var fields = new LinkedHashMap<String,Object>();
        fields.put("stage", stage.name().toLowerCase(java.util.Locale.ROOT));
        fields.put("reason", reason);
        fields.put("native_update_dispatched", stage.dispatched);
        fields.put("message", message);
        if (status != null) {
            fields.put("sdk_status_code", status.getCode());
            fields.put("sdk_status_severity", status.getSeverity());
            fields.put("sdk_status_children", java.util.Arrays.stream(status.getChildren()).limit(8)
                    .map(child -> Map.of("code", child.getCode(), "severity", child.getSeverity())).toList());
        }
        diagnostic = Map.copyOf(fields);
        BackgroundJobRegistry.currentProgressSink().accept(Map.of("full_update_failure", diagnostic));
    }

    public Map<String,Object> diagnostic() {
        return diagnostic;
    }

    public static FullUpdateFailure forCause(Stage stage, Throwable failure) {
        if (failure instanceof FullUpdateFailure known) return known;
        if (failure instanceof InfobaseWorkflow.ResourceBusy)
            return new FullUpdateFailure(stage, "infobase_operation_busy",
                    "Another Bridge operation already holds the project or infobase for update.", null);
        if (failure instanceof InfobaseSourceBarrier.PreflightBlocked preflight) {
            for (String reason : java.util.List.of("update_runtime_unavailable", "model_unavailable", "derived_unavailable",
                    "model_changed", "model_changed_during_preflight", "inventory_verification_failed",
                    "sdk_export_unavailable", "sdk_export_failed")) {
                if (preflight.getMessage().startsWith("PROJECT_UPDATE_PREFLIGHT_BLOCKED: " + reason + ";"))
                    return new FullUpdateFailure(Stage.SOURCES, reason,
                            Stage.SOURCES.message + " Reason: " + reason + ".", null);
            }
        }
        if (failure instanceof CoreException core)
            return forStatus(stage, core.getStatus());
        if (failure instanceof OperationCanceledException)
            return new FullUpdateFailure(stage, "cancelled", "FULL was cancelled. Inspect the original operation and the current infobase state.", null);
        return switch (stage) {
            case SOURCES -> new FullUpdateFailure(stage, "source_preflight_failed", stage.message, null);
            case PRE_DISPATCH -> new FullUpdateFailure(stage, "full_not_dispatched", stage.message, null);
            case NATIVE_RELOAD -> new FullUpdateFailure(stage, "sdk_reload_exception", stage.message, null);
            case VERIFY_EQUAL -> new FullUpdateFailure(stage, "equal_not_verified", stage.message, null);
        };
    }

    public static FullUpdateFailure forStatus(Stage stage, IStatus status) {
        boolean cancelled = status.matches(IStatus.CANCEL);
        return new FullUpdateFailure(stage, cancelled ? "sdk_cancelled" : "sdk_status_error",
                cancelled ? "EDT reported cancellation during FULL."
                        : stage.message + " SDK status code " + status.getCode() + ", severity " + status.getSeverity() + ".",
                status);
    }

    public static FullUpdateFailure nativeResult(Object result) {
        if (result instanceof IStatus status) return forStatus(Stage.NATIVE_RELOAD, status);
        return Boolean.FALSE.equals(result)
                ? new FullUpdateFailure(Stage.NATIVE_RELOAD, "sdk_returned_false",
                        "EDT reloadInfobase returned false without confirming the update.", null)
                : new FullUpdateFailure(Stage.NATIVE_RELOAD, "sdk_result_unrecognized",
                        "EDT reloadInfobase returned an unrecognized result.", null);
    }
}
