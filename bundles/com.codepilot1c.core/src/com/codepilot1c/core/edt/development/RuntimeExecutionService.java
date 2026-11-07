package com.codepilot1c.core.edt.development;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.eclipse.core.resources.ResourcesPlugin;
import com.google.gson.*;

/** Durable execution identity; delegates client ownership, cancellation and debugger pause handling to the runner. */
public final class RuntimeExecutionService {
    public interface Runs {
        Map<String,Object> start(Map<String,Object> arguments) throws Exception;
        Map<String,Object> status(String id) throws Exception;
        Map<String,Object> cancel(String id) throws Exception;
    }
    private static final Gson JSON = new Gson();
    private static RuntimeExecutionService instance;
    private final Path storage;
    private final Runs runs;
    public RuntimeExecutionService(Path storage, Runs runs) throws Exception {
        this.storage=storage;this.runs=runs;Files.createDirectories(storage);
    }
    public static synchronized RuntimeExecutionService workspace() throws Exception {
        if(instance==null) {
            Path path=ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath()
                .resolve(".metadata/.plugins/com.codepilot1c.core/runtime-execution");
            instance=new RuntimeExecutionService(path,new Runs() {
                public Map<String,Object> start(Map<String,Object> a) throws Exception {return DevelopmentRunService.getInstance().start(RunnerProfileStore.workspace().resolve(a));}
                public Map<String,Object> status(String id) throws Exception {return DevelopmentRunService.getInstance().status(id,0);}
                public Map<String,Object> cancel(String id) throws Exception {return DevelopmentRunService.getInstance().cancel(id);}
            });
        }
        return instance;
    }
    public synchronized Map<String,Object> start(Map<String,Object> arguments, Map<String,Object> profile) throws Exception {
        String project=RuntimeExecutionPlan.required(arguments,"project");
        String key=RuntimeExecutionPlan.required(arguments,"idempotency_key");
        if(key.length()>200) throw new IllegalArgumentException("idempotency_key exceeds 200 characters");
        String identity=RuntimeExecutionPlan.sha((project+"|"+key).getBytes(StandardCharsets.UTF_8));
        Path directory=storage.resolve(identity),claim=directory.resolve("claim.json");
        String fingerprint=RuntimeExecutionPlan.sha(canonical(JSON.toJsonTree(List.of(arguments,profile))).toString().getBytes(StandardCharsets.UTF_8));
        if(Files.isRegularFile(claim)) {
            JsonObject old=read(claim);
            if(!fingerprint.equals(old.get("fingerprint").getAsString())) throw new IllegalArgumentException("Idempotency key belongs to another request/profile");
            if(old.has("run_id")) {
                String previous=old.get("run_id").getAsString();
                requireRunId(previous);
                Path index=storage.resolve(previous+".json");
                if(!Files.isRegularFile(index)) write(index,old);
                return status(previous);
            }
            throw new IllegalStateException("Execution outcome is unknown after interrupted submission; inspect the base before issuing another key");
        }
        Path result=directory.resolve("result.json"),feature=directory.resolve("execute.feature");
        String scenario=RuntimeExecutionPlan.scenario(arguments,profile,result,identity);
        if(scenario.getBytes(StandardCharsets.UTF_8).length>4*1024*1024) throw new IllegalArgumentException("Generated feature exceeds 4 MiB");
        Files.createDirectories(directory);
        Files.writeString(feature,scenario,StandardCharsets.UTF_8);
        JsonObject record=new JsonObject();record.addProperty("fingerprint",fingerprint);record.addProperty("request_id",identity);
        Files.writeString(claim,JSON.toJson(record),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        Map<String,Object> start=new LinkedHashMap<>();
        start.put("project",project);
        if(arguments.containsKey("profile_id")) start.put("profile_id",arguments.get("profile_id"));
        start.put("feature_paths",List.of(feature.toString()));start.put("idempotency_key","runtime-"+identity);
        start.put("update_db",false);start.put("visual_checkpoint",false);
        start.put("timeout_s",arguments.getOrDefault("timeout_s",300));
        Map<String,Object> accepted=runs.start(start);
        String runId=RuntimeExecutionPlan.required(accepted,"run_id");record.addProperty("run_id",runId);
        write(claim,record);write(storage.resolve(runId+".json"),record);
        return status(runId);
    }
    public synchronized Map<String,Object> status(String id) throws Exception {
        JsonObject record=record(id);
        Map<String,Object> state=new LinkedHashMap<>(runs.status(id));
        state.put("request_id",record.get("request_id").getAsString());
        state.put("execution_context","VAExtension in target TestClient");
        if(Boolean.TRUE.equals(state.get("terminal")) && "completed".equals(state.get("phase"))) {
            Path result=storage.resolve(record.get("request_id").getAsString()).resolve("result.json");
            if(Boolean.TRUE.equals(record.has("cleaned")?record.get("cleaned").getAsBoolean():false)) state.put("result_removed",true);
            else if(!Files.isRegularFile(result)) {state.put("phase","result_missing");state.put("outcome_unknown",true);}
            else {
                try {
                    if(Files.size(result)>2*1024*1024) throw new IllegalStateException("Result exceeds 2 MiB; execution may have completed");
                    JsonObject value=read(result);
                    if(!value.has("request_id") || !value.get("request_id").isJsonPrimitive()
                        || !value.get("request_id").getAsJsonPrimitive().isString()
                        || !record.get("request_id").getAsString().equals(value.get("request_id").getAsString()))
                        throw new IllegalStateException("Result identity mismatch");
                    if(!value.has("success") || !value.get("success").isJsonPrimitive()
                        || !value.get("success").getAsJsonPrimitive().isBoolean())
                        throw new IllegalStateException("Result must contain boolean success");
                    state.put("execution",value);
                    if(!value.get("success").getAsBoolean()) state.put("phase","execution_failed");
                } catch(Exception error) {
                    state.put("phase","result_invalid");state.put("outcome_unknown",true);
                    state.put("error",error instanceof IllegalStateException?error.getMessage():"Runtime result is not valid JSON");
                }
            }
        }
        return state;
    }
    public synchronized Map<String,Object> cancel(String id) throws Exception {record(id);runs.cancel(id);return status(id);}
    public synchronized Map<String,Object> cleanup(String id) throws Exception {
        JsonObject record=record(id);
        if(!Boolean.TRUE.equals(runs.status(id).get("terminal"))) throw new IllegalStateException("Cancel/wait for the run before cleanup");
        Path directory=storage.resolve(record.get("request_id").getAsString());
        for(String name:List.of("execute.feature","result.json")) Files.deleteIfExists(directory.resolve(name));
        record.addProperty("cleaned",true);write(storage.resolve(id+".json"),record);write(directory.resolve("claim.json"),record);
        return Map.of("run_id",id,"cleaned",true,"idempotency_tombstone_retained",true);
    }
    private static void requireRunId(String id) {
        if(id==null || !id.matches("dev-[0-9a-f-]{36}")) throw new IllegalArgumentException("Invalid runtime run_id");
    }
    private JsonObject record(String id) throws Exception {
        requireRunId(id);
        Path index=storage.resolve(id+".json");
        if(!Files.isRegularFile(index)) {
            // Recovery covers a crash between the accepted claim and its secondary index.
            try(var directories=Files.list(storage)) {
                for(Path folder:directories.filter(p->p.getFileName().toString().matches("[0-9a-f]{64}")).toList()) {
                    Path claim=folder.resolve("claim.json");
                    if(!Files.isRegularFile(claim)) continue;
                    JsonObject candidate;
                    try {candidate=read(claim);} catch(Exception invalidClaim) {continue;}
                    if(candidate.has("run_id") && id.equals(candidate.get("run_id").getAsString())) {
                        validateRecord(id,candidate);write(index,candidate);return candidate;
                    }
                }
            }
            throw new IllegalArgumentException("Unknown runtime run_id");
        }
        JsonObject record=read(index);validateRecord(id,record);return record;
    }
    private static void validateRecord(String id,JsonObject record) {
        if(!record.has("run_id") || !id.equals(record.get("run_id").getAsString())
            || !record.has("request_id") || !record.get("request_id").getAsString().matches("[0-9a-f]{64}"))
            throw new IllegalStateException("Runtime record identity mismatch");
    }
    private static JsonElement canonical(JsonElement value) {
        if(value.isJsonObject()) {
            JsonObject sorted=new JsonObject();
            new TreeSet<>(value.getAsJsonObject().keySet()).forEach(key->sorted.add(key,canonical(value.getAsJsonObject().get(key))));
            return sorted;
        }
        if(value.isJsonArray()) {JsonArray result=new JsonArray();value.getAsJsonArray().forEach(item->result.add(canonical(item)));return result;}
        return value;
    }
    private static JsonObject read(Path file) throws Exception {
        String value=Files.readString(file,StandardCharsets.UTF_8);if(value.startsWith("\uFEFF")) value=value.substring(1);
        return JsonParser.parseString(value).getAsJsonObject();
    }
    private static void write(Path file,JsonObject value) throws Exception {
        Path temp=Files.createTempFile(file.getParent(),"runtime-",".json");
        try {Files.writeString(temp,JSON.toJson(value),StandardCharsets.UTF_8);Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        finally {Files.deleteIfExists(temp);}
    }
}
