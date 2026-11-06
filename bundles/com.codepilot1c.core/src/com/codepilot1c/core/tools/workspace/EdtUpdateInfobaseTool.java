package com.codepilot1c.core.tools.workspace;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.io.File;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.codepilot1c.core.edt.runtime.EdtProjectResolver;
import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.codepilot1c.core.edt.runtime.EdtToolException;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Updates an EDT project's associated infobase.
 */
@ToolMeta(
        name = "edt_update_infobase",
        category = "diagnostics",
        surfaceCategory = "smoke_runtime_recovery",
        mutating = true,
        tags = {"workspace", "edt"})
public class EdtUpdateInfobaseTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtUpdateInfobaseTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project_name": {
                  "type": "string",
                  "description": "EDT project name"
                },
                "full_reload": {"type":"boolean","description":"Use native reloadInfobase (full configuration load), required for the first update after DT restore; default false"},
                "keep_connected": {
                  "type": "boolean",
                  "description": "Keep infobase connected after EDT update (default: true)"
                },
                "dry_run": {
                  "type": "boolean",
                  "description": "Resolve project and infobase without updating"
                },
                "async": {
                  "type": "boolean",
                  "description": "Fire-and-forget; returns jobId to poll via update_infobase_status (default: false)"
                }
              },
              "required": ["project_name"]
            }
            """; //$NON-NLS-1$

    private final EdtProjectResolver projectResolver;
    private final EdtRuntimeService runtimeService;

    public EdtUpdateInfobaseTool() {
        this(new EdtProjectResolver(), new EdtRuntimeService());
    }

    public EdtUpdateInfobaseTool(EdtProjectResolver projectResolver, EdtRuntimeService runtimeService) {
        this.projectResolver = projectResolver;
        this.runtimeService = runtimeService;
    }

    @Override
    public String getDescription() {
        return "Update the project's associated infobase through native EDT. full_reload=true uses reloadInfobase instead of incremental updateInfobase; pending create/DT restore forces full reload automatically. Results report the effective update_mode.";
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        Map<String, Object> parameters = params.getRaw();
        String opId = LogSanitizer.newId("edt-update"); //$NON-NLS-1$
        File workspaceRoot = getWorkspaceRoot();
        String projectName = asString(parameters == null ? null : parameters.get("project_name")); //$NON-NLS-1$
        // Lenient: callers reaching this tool through edt_diagnostics may ship
        // these as strings (the parent schema doesn't advertise their types).
        boolean keepConnected = asBoolean(parameters == null ? null : parameters.get("keep_connected"), true); //$NON-NLS-1$
        boolean dryRun = asBoolean(parameters == null ? null : parameters.get("dry_run"), false); //$NON-NLS-1$
        boolean fullReload = asBoolean(parameters == null ? null : parameters.get("full_reload"), false);
        boolean async = asBoolean(parameters == null ? null : parameters.get("async"), false); //$NON-NLS-1$

        if (async && dryRun) {
            // Dry-run is fast and deterministic; running it synchronously avoids
            // roundtripping through the background registry. Surface an explicit
            // flag so the caller knows the async request was intentionally ignored.
            LOG.info("[%s] edt_update_infobase async+dry_run: running sync (async_ignored)", opId); //$NON-NLS-1$
        } else if (async) {
            try {
                String jobId = BackgroundJobRegistry.getInstance().startJob(
                        "edt_update_infobase", //$NON-NLS-1$
                        () -> runUpdateAndRenderResult(opId, projectName, keepConnected, workspaceRoot, fullReload));
                LOG.info("[%s] edt_update_infobase scheduled async job=%s", opId, jobId); //$NON-NLS-1$
                JsonObject accepted = basePayload(opId, "scheduled", projectName, false, workspaceRoot); //$NON-NLS-1$
                accepted.addProperty("async", true); //$NON-NLS-1$
                accepted.addProperty("job_id", jobId); //$NON-NLS-1$
                accepted.addProperty("state", BackgroundJobRegistry.JobState.RUNNING.name());
                accepted.addProperty("updated", false); //$NON-NLS-1$
                accepted.add("details", new JsonObject()); //$NON-NLS-1$
                return CompletableFuture.completedFuture(
                        ToolResult.success(pretty(accepted), ToolResult.ToolResultType.CODE));
            } catch (RejectedExecutionException e) {
                LOG.warn("[%s] edt_update_infobase async rejected: %s", opId, e.getMessage()); //$NON-NLS-1$
                JsonObject rejected = basePayload(opId, "error", projectName, false, workspaceRoot); //$NON-NLS-1$
                rejected.addProperty("updated", false); //$NON-NLS-1$
                rejected.addProperty("error", "queue_saturated"); //$NON-NLS-1$ //$NON-NLS-2$
                rejected.addProperty("message", "Background job queue full; retry later"); //$NON-NLS-1$ //$NON-NLS-2$
                rejected.add("details", new JsonObject()); //$NON-NLS-1$
                return CompletableFuture.completedFuture(
                        ToolResult.failure(pretty(rejected)));
            }
        }

        final boolean asyncIgnored = async && dryRun;

        return CompletableFuture.supplyAsync(() -> {
            LOG.info("[%s] START edt_update_infobase", opId); //$NON-NLS-1$
            try {
                InfobaseReference infobase = projectResolver.resolveInfobase(projectName, workspaceRoot);
                JsonObject result = basePayload(opId, dryRun ? "dry_run" : "updated", projectName, dryRun, //$NON-NLS-1$ //$NON-NLS-2$
                        workspaceRoot);
                if (asyncIgnored) {
                    result.addProperty("async_ignored", true); //$NON-NLS-1$
                    result.addProperty("async_ignored_reason", //$NON-NLS-1$
                            "dry_run completes synchronously"); //$NON-NLS-1$
                }
                JsonObject details = new JsonObject();
                if (infobase != null && infobase.getConnectionString() != null) {
                    details.addProperty("infobase_connection",
                            infobase.getConnectionString().asConnectionString()); //$NON-NLS-1$
                }
                result.add("details", details); //$NON-NLS-1$
                if (dryRun) {
                    result.addProperty("updated", false); //$NON-NLS-1$
                    return ToolResult.success(pretty(result), ToolResult.ToolResultType.CODE);
                }

                boolean updated = updateWithMode(projectName, infobase, keepConnected, fullReload, result);
                if (!updated) {
                    throw new EdtToolException(EdtToolErrorCode.UPDATE_FAILED,
                            "EDT update returned false for project: " + projectName); //$NON-NLS-1$
                }
                return ToolResult.success(pretty(result), ToolResult.ToolResultType.CODE);
            } catch (EdtToolException e) {
                return ToolResult.failure(pretty(errorPayload(opId, projectName, workspaceRoot, e.getCode(), e.getMessage())));
            } catch (Exception e) {
                return ToolResult.failure(pretty(errorPayload(opId, projectName, workspaceRoot,
                        EdtToolErrorCode.UPDATE_FAILED, e.getMessage())));
            }
        });
    }

    /**
     * Runs the non-dry update synchronously and renders a pretty JSON payload.
     * Used by the background job path so the job result mirrors the regular
     * synchronous output.
     */
    private String runUpdateAndRenderResult(String opId, String projectName, boolean keepConnected,
            File workspaceRoot, boolean fullReload) {
        LOG.info("[%s] START edt_update_infobase (async)", opId); //$NON-NLS-1$
        try {
            InfobaseReference infobase = projectResolver.resolveInfobase(projectName, workspaceRoot);
            JsonObject result = basePayload(opId, "updated", projectName, false, workspaceRoot); //$NON-NLS-1$
            JsonObject details = new JsonObject();
            if (infobase != null && infobase.getConnectionString() != null) {
                details.addProperty("infobase_connection", //$NON-NLS-1$
                        infobase.getConnectionString().asConnectionString());
            }
            result.add("details", details); //$NON-NLS-1$

                boolean updated = updateWithMode(projectName, infobase, keepConnected, fullReload, result);
            if (!updated) {
                JsonObject error = errorPayload(opId, projectName, workspaceRoot,
                        EdtToolErrorCode.UPDATE_FAILED,
                        "EDT update returned false for project: " + projectName); //$NON-NLS-1$
                throw new IllegalStateException(pretty(error));
            }
            return pretty(result);
        } catch (EdtToolException e) {
            throw new IllegalStateException(pretty(errorPayload(opId, projectName, workspaceRoot, e.getCode(), e.getMessage())));
        } catch (Exception e) {
            throw new IllegalStateException(pretty(errorPayload(opId, projectName, workspaceRoot,
                    EdtToolErrorCode.UPDATE_FAILED, e.getMessage())));
        }
    }

    private boolean updateWithMode(String projectName, InfobaseReference infobase, boolean keepConnected,
            boolean fullReload, JsonObject result) throws Exception {
        try (var operation = new com.codepilot1c.core.edt.infobase.InfobaseWorkflow(
                com.codepilot1c.core.edt.infobase.InfobaseWorkflow.resourceKeys(projectName, infobase), p -> {})) {
            boolean effectiveFullReload = fullReload || runtimeService.requiresFullReload(projectName);
            boolean updated = effectiveFullReload
                    ? runtimeService.updateInfobase(projectName, keepConnected, new NullProgressMonitor(), true)
                    : runtimeService.updateInfobase(projectName, keepConnected, new NullProgressMonitor());
            result.addProperty("updated", updated);
            result.addProperty("update_mode", effectiveFullReload ? "full" : "incremental");
            return updated;
        }
    }

    private static JsonObject basePayload(String opId, String status, String projectName, boolean dryRun,
            File workspaceRoot) {
        JsonObject result = new JsonObject();
        result.addProperty("op_id", opId); //$NON-NLS-1$
        result.addProperty("status", status); //$NON-NLS-1$
        result.addProperty("project_name", projectName); //$NON-NLS-1$
        result.addProperty("dry_run", dryRun); //$NON-NLS-1$
        result.addProperty("workspace_root", workspaceRoot == null ? "" : workspaceRoot.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
        return result;
    }

    private static JsonObject errorPayload(String opId, String projectName, File workspaceRoot,
            EdtToolErrorCode code, String message) {
        JsonObject result = basePayload(opId, "error", projectName, false, workspaceRoot); //$NON-NLS-1$
        result.addProperty("updated", false); //$NON-NLS-1$
        result.addProperty("error_code", code.name()); //$NON-NLS-1$
        result.addProperty("message", message == null ? "" : message); //$NON-NLS-1$ //$NON-NLS-2$
        result.add("details", new JsonObject()); //$NON-NLS-1$
        return result;
    }

    protected File getWorkspaceRoot() {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace() == null ? null : ResourcesPlugin.getWorkspace().getRoot();
        return root == null || root.getLocation() == null ? null : root.getLocation().toFile();
    }

    private static String pretty(JsonObject object) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(object);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    /**
     * Coerces a JSON-decoded value into a boolean. Accepts {@link Boolean},
     * canonical strings ({@code "true"}, {@code "false"}, case-insensitive,
     * with surrounding whitespace ignored), and {@link Number} (non-zero ->
     * true). Falls back to {@code defaultValue} on null/unrecognized input.
     */
    static boolean asBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b.booleanValue();
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0d;
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) { //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        }
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) { //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
        return defaultValue;
    }
}
