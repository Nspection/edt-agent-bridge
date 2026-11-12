// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.bsl;
import java.util.Map;
import java.util.function.Function;
import com.codepilot1c.core.edt.lang.BslMethodBodyRequest;
import com.codepilot1c.core.edt.lang.BslMethodBodyResult;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Slow semantic batches continue through the original background job, never the UI thread. */
public final class BslMethodBatchJobs {
    private static final Gson JSON = new Gson();
    private final BackgroundJobRegistry jobs;
    public BslMethodBatchJobs(BackgroundJobRegistry jobs) { this.jobs = jobs; }
    public JsonObject read(Map<String,Object> args, Function<BslMethodBodyRequest,BslMethodBodyResult> reader, int waitMillis) throws Exception {
        var plan = BslMethodBatch.prepare(args); // frozen validated requests before submission
        String id = jobs.startJob("bsl_method_batch", () -> {
            JsonObject result = BslMethodBatch.read(plan, reader);
            result.addProperty("state", "completed");
            return JSON.toJson(result);
        });
        var status = jobs.awaitStatus(id, waitMillis).orElseThrow(() -> new IllegalStateException("Batch job missing"));
        if (status.getState().name().equals("DONE")) {
            JsonObject result = JsonParser.parseString(status.getResult()).getAsJsonObject();
            result.addProperty("job_id", id); return result;
        }
        if (status.getState().name().equals("FAILED")) throw new IllegalStateException("Batch job failed: " + status.getError());
        JsonObject result = new JsonObject(); result.addProperty("terminal", false);
        result.addProperty("state", "reading"); result.addProperty("job_id", id);
        result.add("next_call", JSON.toJsonTree(Map.of("tool", "edt.task", "arguments", Map.of("op", "edt_operation_status", "args", Map.of("job_id", id, "timeout_ms", 20000)))));
        return result;
    }
}
