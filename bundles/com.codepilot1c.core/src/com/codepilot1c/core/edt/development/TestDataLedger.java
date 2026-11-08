// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import com.google.gson.*;

/** Local ledger only. A declaration is not proof of ownership and never permits deletion. */
public final class TestDataLedger {
    private static final Gson JSON=new Gson();
    private static final int MAX_RECORDS=1000;
    private static final Pattern FQN=Pattern.compile("(?:Catalog|Document)\\.[\\p{L}_][\\p{L}\\p{N}_]{0,127}");
    private static final Pattern UUID_TEXT=Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Map<Path,Object> LOCKS=new ConcurrentHashMap<>();
    private final Path root;
    public record Target(String project,String baseUuid) {
        public Target {project=text(project,"project",256);baseUuid=uuid(baseUuid);}
    }
    @FunctionalInterface private interface Operation<T> {T run() throws Exception;}
    public TestDataLedger(Path root) throws IOException {this.root=root.toAbsolutePath().normalize();Files.createDirectories(this.root);}
    private <T> T locked(Operation<T> operation) throws Exception {
        synchronized(LOCKS.computeIfAbsent(root,key->new Object())) {
            try(var channel=FileChannel.open(root.resolve(".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()) {return operation.run();}
        }
    }
    public JsonObject begin(Target target,String request,String label) throws Exception {
        text(request,"idempotency_key",200);text(label,"label",200);
        String id="data-"+hash(JSON.toJson(List.of(target.project(),target.baseUuid(),request)));
        return locked(()->{
            if(Files.exists(file(id))) {
                JsonObject old=read(id,target);
                if(!old.get("label").getAsString().equals(label))throw new IllegalArgumentException("Idempotency key already used with another label");
                return summary(old);
            }
            JsonObject doc=new JsonObject();doc.addProperty("schema",1);doc.addProperty("run_id",id);doc.addProperty("project",target.project());doc.addProperty("base_uuid",target.baseUuid());doc.addProperty("label",label);doc.addProperty("state","open");doc.addProperty("revision",0);doc.addProperty("created_at",Instant.now().toString());doc.add("records",new JsonArray());write(id,doc);return summary(doc);
        });
    }
    public JsonObject register(String id,Target target,long expectedRevision,List<Map<String,Object>> entries) throws Exception {
        if(expectedRevision<0)throw new IllegalArgumentException("expected_revision must be nonnegative");
        if(entries==null || entries.isEmpty() || entries.size()>100)throw new IllegalArgumentException("Provide 1..100 records");
        List<JsonObject> parsed=new ArrayList<>();for(var entry:entries)parsed.add(record(entry));
        return locked(()->{
            JsonObject doc=read(id,target);ensureOpen(doc);ensureNoActiveCreation(doc);Map<String,JsonObject> known=new LinkedHashMap<>();
            for(JsonElement item:doc.getAsJsonArray("records")){JsonObject entry=item.getAsJsonObject();known.put(entry.get("record_id").getAsString(),entry);}
            List<JsonObject> added=new ArrayList<>();
            for(JsonObject entry:parsed) {
                String key=entry.get("record_id").getAsString();JsonObject prior=known.get(key);
                if(prior!=null) {if(!prior.equals(entry))throw new IllegalArgumentException("Existing record differs; original version/provenance preserved");}
                else {known.put(key,entry);added.add(entry);}
            }
            if(known.size()>MAX_RECORDS)throw new IllegalArgumentException("Ledger is limited to 1000 records");
            if(!added.isEmpty()) {
                if(expectedRevision!=doc.get("revision").getAsLong())throw new IllegalStateException("Stale revision; reread the ledger");
                added.forEach(doc.getAsJsonArray("records")::add);doc.addProperty("revision",Math.addExact(expectedRevision,1));write(id,doc);
            }
            JsonObject result=summary(doc);result.addProperty("added",added.size());return result;
        });
    }
    public JsonObject list(String id,Target target,int offset,int limit) throws Exception {
        pageArguments(offset,limit);return locked(()->page(read(id,target),offset,limit));
    }
    public JsonObject evidence(String id,Target target) throws Exception {
        return locked(()->{
            JsonObject doc=read(id,target),out=summary(doc);Map<String,Integer> outcomes=new TreeMap<>();
            for(JsonElement item:cleanups(doc)){JsonObject op=item.getAsJsonObject();String outcome=op.has("outcome")?op.get("outcome").getAsString():op.get("state").getAsString();outcomes.merge(outcome,1,Integer::sum);}
            out.add("cleanup_outcomes",new Gson().toJsonTree(outcomes));out.addProperty("details_tool","edt_test_data");return out;
        });
    }
    public JsonObject preview(String id,Target target,int offset,int limit) throws Exception {
        pageArguments(offset,limit);return locked(()->{
            JsonObject doc=read(id,target);JsonObject result=page(doc,offset,limit);
            result.addProperty("plan_sha256",planHash(doc));result.addProperty("mode","plan_only");result.addProperty("writes_database",false);result.addProperty("physical_delete_allowed",false);result.addProperty("requires_runtime_verification",true);
            result.addProperty("next_step","cleanup can mark ONE native-confirmed unchanged record for deletion using this plan hash. No physical deletion or automatic removal of references.");result.addProperty("cleanup_mode","mark_deletion");return result;
        });
    }
    public JsonObject close(String id,Target target,String expectedPlan) throws Exception {
        return locked(()->{
            JsonObject doc=read(id,target);
            if(!doc.get("state").getAsString().equals("closed")) {
                ensureNoActiveCreation(doc);
                if(expectedPlan==null || !expectedPlan.equals(planHash(doc)))throw new IllegalStateException("Stale cleanup plan; preview again");
                doc.addProperty("state","closed");doc.addProperty("closed_at",Instant.now().toString());doc.addProperty("revision",Math.addExact(doc.get("revision").getAsLong(),1));write(id,doc);
            }
            return summary(doc);
        });
    }
    JsonObject findCreation(String id,Target target,String key,String host,JsonObject spec) throws Exception {
        String creationId=creationId(key),fingerprint=creationFingerprint(host,spec);
        return locked(()->{JsonObject doc=read(id,target);JsonObject op=find(doc,creationId);if(op==null)return null;if(!fingerprint.equals(op.get("arguments_sha256").getAsString()))throw new IllegalArgumentException("Creation key already used with different arguments/host");return op.deepCopy();});
    }
    JsonObject reserveCreation(String id,Target target,long expectedRevision,String key,String host,JsonObject spec) throws Exception {
        if(host==null || !host.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid host_id");
        String creationId=creationId(key),fingerprint=creationFingerprint(host,spec);
        return locked(()->{
            JsonObject doc=read(id,target);JsonObject old=find(doc,creationId);
            if(old!=null){if(!fingerprint.equals(old.get("arguments_sha256").getAsString()))throw new IllegalArgumentException("Creation key already used with different arguments/host");return old.deepCopy();}
            ensureOpen(doc);ensureNoActiveCreation(doc);
            if(expectedRevision!=doc.get("revision").getAsLong())throw new IllegalStateException("Stale revision; reread ledger");
            if(doc.getAsJsonArray("records").size()>=MAX_RECORDS || creations(doc).size()>=MAX_RECORDS)throw new IllegalStateException("Ledger capacity reached");
            JsonObject op=new JsonObject();op.addProperty("creation_id",creationId);op.addProperty("host_id",host);op.addProperty("arguments_sha256",fingerprint);op.addProperty("metadata_fqn",spec.get("metadata_fqn").getAsString());op.addProperty("uuid",UUID.randomUUID().toString());op.addProperty("nonce",UUID.randomUUID().toString());op.addProperty("state","prepared");op.add("specification",spec.deepCopy());op.addProperty("host_request_key","td-"+hash(JSON.toJson(List.of(id,creationId))));
            creations(doc).add(op);advance(doc);if(JSON.toJson(doc).getBytes(StandardCharsets.UTF_8).length+65536>8*1024*1024)throw new IllegalStateException("Not enough journal space for a creation receipt");write(id,doc);return op.deepCopy();
        });
    }
    JsonObject claimCreation(String id,Target target,String creationId) throws Exception {
        return locked(()->{JsonObject doc=read(id,target),op=requireCreation(doc,creationId);boolean acquired="prepared".equals(op.get("state").getAsString());if(acquired){ensureOpen(doc);op.addProperty("state","submitting");advance(doc);write(id,doc);}JsonObject out=op.deepCopy();out.addProperty("claim_acquired",acquired);return out;});
    }
    JsonObject attachCreationRequest(String id,Target target,String creationId,String requestId) throws Exception {
        uuid(requestId);
        return locked(()->{JsonObject doc=read(id,target),op=requireCreation(doc,creationId);if(!"submitting".equals(op.get("state").getAsString()))throw new IllegalStateException("Creation is not awaiting a request receipt");op.addProperty("request_id",requestId);op.addProperty("state","submitted");advance(doc);write(id,doc);return creationView(doc,op);});
    }
    JsonObject existingCreation(String id,Target target,String creationId)throws Exception{return locked(()->{if(creationId==null||!creationId.matches("create-[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid creation_id");JsonObject op=find(read(id,target),creationId);return op==null?null:op.deepCopy();});}
    JsonObject cleanupForRecord(String id,Target target,String recordId)throws Exception{return locked(()->{JsonObject doc=read(id,target);for(JsonElement e:cleanups(doc)){JsonObject op=e.getAsJsonObject();if(recordId.equals(op.get("record_id").getAsString()))return op.deepCopy();}return null;});}
    JsonObject existingCleanup(String id,Target target,String key,String host,String record)throws Exception{return locked(()->{JsonObject doc=read(id,target);String cid="cleanup-"+hash(text(key,"idempotency_key",200));for(JsonElement e:cleanups(doc)){JsonObject op=e.getAsJsonObject();if(cid.equals(op.get("cleanup_id").getAsString())){if(!host.equals(op.get("host_id").getAsString())||!record.equals(op.get("record_id").getAsString()))throw new IllegalArgumentException("Cleanup key already used for another record/host");return op.deepCopy();}}return null;});}
    JsonObject creation(String id,Target target,String creationId) throws Exception {return locked(()->requireCreation(read(id,target),creationId).deepCopy());}
    JsonObject creationView(String id,Target target,String creationId) throws Exception {return locked(()->{JsonObject doc=read(id,target);return creationView(doc,requireCreation(doc,creationId));});}
    JsonObject unknownCreation(String id,Target target,String creationId,String reason) throws Exception {
        return locked(()->{JsonObject doc=read(id,target),op=requireCreation(doc,creationId);if(!Set.of("completed","failed").contains(op.get("state").getAsString())){op.addProperty("state","outcome_unknown");op.addProperty("reason",text(reason,"reason",200));advance(doc);write(id,doc);}return creationView(doc,op);});
    }
    JsonObject finishCreation(String id,Target target,String creationId,String requestId,JsonObject receipt) throws Exception {
        return locked(()->{
            JsonObject doc=read(id,target),op=requireCreation(doc,creationId);
            if("completed".equals(op.get("state").getAsString()))return creationView(doc,op);
            if(!op.has("request_id") || !requestId.equals(op.get("request_id").getAsString()))throw new IllegalArgumentException("Native request identity mismatch");
            validateReceipt(op,receipt);
            if(!receipt.get("created").getAsBoolean()) {
                if(!receipt.has("rolled_back") || !receipt.get("rolled_back").isJsonPrimitive() || !receipt.get("rolled_back").getAsJsonPrimitive().isBoolean() || !receipt.get("rolled_back").getAsBoolean())throw new IllegalArgumentException("Missing explicit rollback proof");
                op.addProperty("state","failed");op.addProperty("reason","creation_rolled_back");op.add("receipt",compactReceipt(receipt));
            } else {
                text(receipt.get("expected_version").getAsString(),"expected_version",4096);
                JsonObject record=confirmedRecord(op,receipt);String recordId=record.get("record_id").getAsString();
                for(JsonElement e:doc.getAsJsonArray("records"))if(e.getAsJsonObject().get("record_id").getAsString().equals(recordId))throw new IllegalStateException("Reserved reference already registered; keep for review");
                doc.getAsJsonArray("records").add(record);op.addProperty("state","completed");op.add("record",record.deepCopy());op.add("receipt",compactReceipt(receipt));
            }
            advance(doc);write(id,doc);return creationView(doc,op);
        });
    }
    JsonObject reserveCleanup(String id,Target target,String key,String host,String recordId,String expectedPlan) throws Exception {
        if(host==null || !host.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid host_id");
        String cleanupId="cleanup-"+hash(text(key,"idempotency_key",200));
        return locked(()->{
            JsonObject doc=read(id,target);
            for(JsonElement e:cleanups(doc)) {JsonObject op=e.getAsJsonObject();if(cleanupId.equals(op.get("cleanup_id").getAsString())) {if(!host.equals(op.get("host_id").getAsString()) || !recordId.equals(op.get("record_id").getAsString()))throw new IllegalArgumentException("Cleanup key already used for another record/host");return op.deepCopy();}}
            ensureOpen(doc);ensureNoActiveCreation(doc);
            if(expectedPlan==null || !expectedPlan.equals(planHash(doc)))throw new IllegalStateException("Stale cleanup plan; preview again");
            JsonObject record=null;for(JsonElement e:doc.getAsJsonArray("records"))if(recordId.equals(e.getAsJsonObject().get("record_id").getAsString()))record=e.getAsJsonObject();
            if(record==null || !"native_host_confirmed".equals(record.get("ownership").getAsString()))throw new IllegalArgumentException("Cleanup requires a native-confirmed creation record");
            for(JsonElement e:cleanups(doc))if(recordId.equals(e.getAsJsonObject().get("record_id").getAsString()))throw new IllegalStateException("Record already has cleanup intent; inspect that intent instead of replaying");
            if(cleanups(doc).size()>=MAX_RECORDS)throw new IllegalStateException("Cleanup capacity reached");
            JsonObject op=new JsonObject();op.addProperty("cleanup_id",cleanupId);op.addProperty("host_id",host);op.addProperty("record_id",recordId);op.add("record",record.deepCopy());op.addProperty("nonce",UUID.randomUUID().toString());op.addProperty("state","prepared");op.addProperty("host_request_key","tc-"+hash(JSON.toJson(List.of(id,cleanupId))));cleanups(doc).add(op);advance(doc);
            if(JSON.toJson(doc).getBytes(StandardCharsets.UTF_8).length+65536>8*1024*1024)throw new IllegalStateException("Not enough journal space for cleanup receipt");write(id,doc);return op.deepCopy();
        });
    }
    JsonObject cleanup(String id,Target target,String cleanupId) throws Exception {return locked(()->requireCleanup(read(id,target),cleanupId).deepCopy());}
    JsonObject claimCleanup(String id,Target target,String cleanupId) throws Exception {
        return locked(()->{JsonObject doc=read(id,target),op=requireCleanup(doc,cleanupId);boolean acquired="prepared".equals(op.get("state").getAsString());if(acquired){ensureOpen(doc);op.addProperty("state","submitting");advance(doc);write(id,doc);}JsonObject out=op.deepCopy();out.addProperty("claim_acquired",acquired);return out;});
    }
    JsonObject cleanupView(String id,Target target,String cleanupId) throws Exception {return locked(()->cleanupView(read(id,target),cleanupId));}
    JsonObject cleanupRequest(String id,Target target,String cleanupId,String requestId) throws Exception {
        uuid(requestId);return locked(()->{JsonObject doc=read(id,target),op=requireCleanup(doc,cleanupId);if(!"submitting".equals(op.get("state").getAsString()))throw new IllegalStateException("Cleanup is not awaiting request receipt");op.addProperty("request_id",requestId);op.addProperty("state","submitted");advance(doc);write(id,doc);return cleanupView(doc,cleanupId);});
    }
    JsonObject unknownCleanup(String id,Target target,String cleanupId,String reason) throws Exception {
        return locked(()->{JsonObject doc=read(id,target),op=requireCleanup(doc,cleanupId);if(!Set.of("completed","skipped","failed").contains(op.get("state").getAsString())){op.addProperty("state","outcome_unknown");op.addProperty("reason",text(reason,"reason",200));advance(doc);write(id,doc);}return cleanupView(doc,cleanupId);});
    }
    JsonObject finishCleanup(String id,Target target,String cleanupId,String requestId,JsonObject receipt) throws Exception {
        return locked(()->{JsonObject doc=read(id,target),op=requireCleanup(doc,cleanupId);
            if(Set.of("completed","skipped","failed").contains(op.get("state").getAsString()))return cleanupView(doc,cleanupId);
            if(!requestId.equals(op.get("request_id").getAsString()))throw new IllegalArgumentException("Cleanup native request mismatch");
            validateCleanupReceipt(op,receipt);String outcome=receipt.get("outcome").getAsString();
            JsonObject compact=new JsonObject();for(String field:List.of("protocol","cleanup_id","nonce","record_id","uuid","expected_version","outcome"))compact.add(field,receipt.get(field).deepCopy());
            op.add("receipt",compact);op.addProperty("state",outcome.equals("marked_for_deletion") || outcome.equals("already_absent")?"completed":outcome.equals("rolled_back")?"failed":"skipped");op.addProperty("outcome",outcome);advance(doc);write(id,doc);return cleanupView(doc,cleanupId);
        });
    }
    private static JsonArray cleanups(JsonObject doc){if(!doc.has("cleanups"))doc.add("cleanups",new JsonArray());return doc.getAsJsonArray("cleanups");}
    private static JsonObject requireCleanup(JsonObject doc,String id){if(id==null || !id.matches("cleanup-[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid cleanup_id");for(JsonElement e:cleanups(doc))if(id.equals(e.getAsJsonObject().get("cleanup_id").getAsString()))return e.getAsJsonObject();throw new IllegalArgumentException("Unknown cleanup_id");}
    private void validateCleanupReceipt(JsonObject op,JsonObject receipt){
        if(receipt==null || !receipt.has("protocol") || !"edt-test-data-cleanup-v1".equals(receipt.get("protocol").getAsString()))throw new IllegalArgumentException("Invalid cleanup protocol");
        for(String field:List.of("cleanup_id","nonce","record_id"))if(!op.get(field).equals(receipt.get(field)))throw new IllegalArgumentException("Cleanup receipt identity mismatch");
        for(String field:List.of("uuid","expected_version"))if(!op.getAsJsonObject("record").get(field).equals(receipt.get(field)))throw new IllegalArgumentException("Cleanup receipt reference/version mismatch");
        if(!receipt.has("outcome") || !Set.of("marked_for_deletion","already_absent","version_changed","predefined","posted","already_marked","rolled_back").contains(receipt.get("outcome").getAsString()))throw new IllegalArgumentException("Invalid cleanup outcome");
    }
    private JsonObject cleanupView(JsonObject doc,String id){JsonObject op=requireCleanup(doc,id),out=summary(doc);for(String field:List.of("cleanup_id","host_id","record_id","request_id","outcome","reason"))if(op.has(field))out.add(field,op.get(field).deepCopy());String state=op.get("state").getAsString();if(state.equals("submitting")&&!op.has("request_id"))state="outcome_unknown";out.addProperty("ledger_state",doc.get("state").getAsString());out.addProperty("state",state);out.addProperty("terminal",Set.of("completed","skipped","failed","outcome_unknown").contains(state));out.addProperty("physical_delete_performed",false);out.addProperty("marked_for_deletion",op.has("outcome")&&"marked_for_deletion".equals(op.get("outcome").getAsString()));out.addProperty("next_tool","edt_test_data");return out;}
    private JsonObject compactReceipt(JsonObject receipt) {
        JsonObject copy=new JsonObject();for(String name:List.of("protocol","creation_id","nonce","metadata_fqn","uuid","created","rolled_back","expected_version"))if(receipt.has(name))copy.add(name,receipt.get(name).deepCopy());return copy;
    }
    private JsonObject confirmedRecord(JsonObject op,JsonObject receipt) {
        JsonObject entry=record(Map.of("metadata_fqn",op.get("metadata_fqn").getAsString(),"uuid",op.get("uuid").getAsString(),"expected_version",receipt.get("expected_version").getAsString(),"source_request_id",op.get("request_id").getAsString()));
        entry.addProperty("ownership","native_host_confirmed");entry.addProperty("creation_id",op.get("creation_id").getAsString());entry.addProperty("host_id",op.get("host_id").getAsString());return entry;
    }
    private void validateReceipt(JsonObject op,JsonObject receipt) {
        if(receipt==null || !receipt.has("protocol") || !"edt-test-data-create-v1".equals(receipt.get("protocol").getAsString()))throw new IllegalArgumentException("Unknown receipt protocol");
        for(String field:List.of("creation_id","nonce","metadata_fqn","uuid"))if(!receipt.has(field) || !receipt.get(field).isJsonPrimitive() || !receipt.get(field).getAsJsonPrimitive().isString() || !op.get(field).equals(receipt.get(field)))throw new IllegalArgumentException("Creation receipt does not match reserved intent");
        if(!receipt.has("created") || !receipt.get("created").isJsonPrimitive() || !receipt.get("created").getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("Creation receipt missing boolean outcome");
        if(receipt.get("created").getAsBoolean() && (!receipt.has("expected_version") || !receipt.get("expected_version").isJsonPrimitive() || !receipt.get("expected_version").getAsJsonPrimitive().isString()))throw new IllegalArgumentException("Creation receipt missing version");
    }
    private JsonObject creationView(JsonObject doc,JsonObject op) {
        JsonObject out=summary(doc);for(String field:List.of("creation_id","host_id","metadata_fqn","uuid","request_id","reason","record"))if(op.has(field))out.add(field,op.get(field).deepCopy());
        String state=op.get("state").getAsString();if(state.equals("submitting")&&!op.has("request_id"))state="outcome_unknown";
        out.addProperty("ledger_state",doc.get("state").getAsString());out.addProperty("state",state);out.addProperty("terminal",Set.of("completed","failed","outcome_unknown").contains(state));out.addProperty("confirmed",state.equals("completed"));out.addProperty("next_tool","edt_test_data");return out;
    }
    private static String creationId(String key){return "create-"+hash(text(key,"idempotency_key",200));}
    private static String creationFingerprint(String host,JsonObject spec){JsonObject value=new JsonObject();value.addProperty("host_id",host);value.add("specification",spec);return hash(JSON.toJson(value));}
    private static JsonArray creations(JsonObject doc){if(!doc.has("creations"))doc.add("creations",new JsonArray());return doc.getAsJsonArray("creations");}
    private static JsonObject find(JsonObject doc,String id){for(JsonElement e:creations(doc))if(id.equals(e.getAsJsonObject().get("creation_id").getAsString()))return e.getAsJsonObject();return null;}
    private static JsonObject requireCreation(JsonObject doc,String id){if(id==null || !id.matches("create-[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid creation_id");JsonObject op=find(doc,id);if(op==null)throw new IllegalArgumentException("Unknown creation_id");return op;}
    private static void advance(JsonObject doc){doc.addProperty("revision",Math.addExact(doc.get("revision").getAsLong(),1));}
    private static void ensureNoActiveCreation(JsonObject doc){for(JsonElement e:cleanups(doc))if(!Set.of("completed","skipped","failed").contains(e.getAsJsonObject().get("state").getAsString()))throw new IllegalStateException("Resolve pending/unknown cleanup before editing or closing ledger");for(JsonElement e:creations(doc))if(!Set.of("completed","failed").contains(e.getAsJsonObject().get("state").getAsString()))throw new IllegalStateException("Resolve pending/unknown creation before editing or closing ledger");}
    private JsonObject record(Map<String,Object> values) {
        if(values==null)throw new IllegalArgumentException("Record must be an object");
        Set<String> extra=new TreeSet<>(values.keySet());extra.removeAll(Set.of("metadata_fqn","uuid","expected_version","source_request_id"));
        if(!extra.isEmpty())throw new IllegalArgumentException("Unsupported record fields: "+extra);
        String fqn=text(values.get("metadata_fqn"),"metadata_fqn",140);
        if(!FQN.matcher(fqn).matches())throw new IllegalArgumentException("Only Catalog.Name or Document.Name references are supported");
        String uuid=uuid(values.get("uuid"));JsonObject result=new JsonObject();result.addProperty("metadata_fqn",fqn);result.addProperty("uuid",uuid);result.addProperty("record_id",hash(fqn.toLowerCase(Locale.ROOT)+"|"+uuid));result.addProperty("ownership","reported_by_agent");
        for(String name:List.of("expected_version","source_request_id"))if(values.containsKey(name))result.addProperty(name,text(values.get(name),name,name.equals("expected_version")?4096:200));
        return result;
    }
    private JsonObject read(String id,Target target) throws Exception {
        Path file=file(id);if(!Files.isRegularFile(file)||Files.isSymbolicLink(file))throw new IllegalArgumentException("Unknown ledger or unsupported symbolic link");
        if(Files.size(file)>8*1024*1024)throw new IllegalStateException("Ledger exceeds size limit");
        JsonObject doc=JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject();
        if(doc.get("schema").getAsBigDecimal().intValueExact()!=1 || !id.equals(doc.get("run_id").getAsString()))throw new IllegalStateException("Ledger identity/schema mismatch");
        if(!target.project().equals(doc.get("project").getAsString()) || !target.baseUuid().equals(doc.get("base_uuid").getAsString()))throw new IllegalStateException("Project or default infobase changed; original ledger preserved");
        if(!Set.of("open","closed").contains(doc.get("state").getAsString()) || doc.get("revision").getAsBigDecimal().longValueExact()<0)throw new IllegalStateException("Invalid ledger state");
        if(doc.getAsJsonArray("records").size()>MAX_RECORDS)throw new IllegalStateException("Too many stored records");
        Set<String> creationIds=new HashSet<>();
        if(creations(doc).size()>MAX_RECORDS)throw new IllegalStateException("Too many creation intents");
        for(JsonElement item:creations(doc)) {
            JsonObject op=item.getAsJsonObject();String creationId=op.get("creation_id").getAsString();
            if(!creationId.matches("create-[a-f0-9]{64}") || !creationIds.add(creationId) || !op.get("host_id").getAsString().matches("[a-f0-9]{32}"))throw new IllegalStateException("Invalid creation identity");
            uuid(op.get("uuid").getAsString());uuid(op.get("nonce").getAsString());
            if(!Set.of("prepared","submitting","submitted","completed","failed","outcome_unknown").contains(op.get("state").getAsString()))throw new IllegalStateException("Invalid creation state");
            Map<String,Object> fields=TestDataCreatePlan.fieldValues(op.getAsJsonObject("specification").getAsJsonObject("fields"));
            JsonObject normalized=TestDataCreatePlan.normalizeLinked(op.get("metadata_fqn").getAsString(),fields);
            if(!normalized.equals(op.get("specification")) || !creationFingerprint(op.get("host_id").getAsString(),normalized).equals(op.get("arguments_sha256").getAsString()) || !("td-"+hash(JSON.toJson(List.of(id,creationId)))).equals(op.get("host_request_key").getAsString()))throw new IllegalStateException("Creation intent was modified");
            if(op.has("request_id"))uuid(op.get("request_id").getAsString());
        }
        Map<String,JsonObject> confirmed=new HashMap<>();
        for(JsonElement item:creations(doc)) {
            JsonObject op=item.getAsJsonObject();String state=op.get("state").getAsString();
            if(state.equals("completed")) {
                validateReceipt(op,op.getAsJsonObject("receipt"));if(!op.getAsJsonObject("receipt").get("created").getAsBoolean())throw new IllegalStateException("Completed intent has no creation proof");
                JsonObject expected=confirmedRecord(op,op.getAsJsonObject("receipt"));
                if(!expected.equals(op.get("record")) || confirmed.put(expected.get("record_id").getAsString(),expected)!=null)throw new IllegalStateException("Creation proof/record mismatch");
            }
        }
        Set<String> ids=new HashSet<>();
        for(JsonElement item:doc.getAsJsonArray("records")) {
            JsonObject entry=item.getAsJsonObject();String ownership=entry.get("ownership").getAsString();if(!Set.of("reported_by_agent","native_host_confirmed").contains(ownership))throw new IllegalStateException("Unsupported ownership assertion");
            Map<String,Object> raw=new LinkedHashMap<>();for(String key:List.of("metadata_fqn","uuid","expected_version","source_request_id"))if(entry.has(key))raw.put(key,entry.get(key).getAsString());
            JsonObject expected=record(raw);
            if(ownership.equals("native_host_confirmed")) {
                JsonObject op=requireCreation(doc,entry.get("creation_id").getAsString());
                if(!"completed".equals(op.get("state").getAsString()))throw new IllegalStateException("Confirmed record has no completed creation");
                validateReceipt(op,op.getAsJsonObject("receipt"));expected=confirmedRecord(op,op.getAsJsonObject("receipt"));
            }
            String recordId=expected.get("record_id").getAsString();
            if(!expected.equals(entry) || !ids.add(recordId))throw new IllegalStateException("Invalid or duplicate stored record");
            if(ownership.equals("native_host_confirmed"))confirmed.remove(recordId);
        }
        if(!confirmed.isEmpty())throw new IllegalStateException("Completed creation is missing its ledger record");
        Set<String> cleanupIds=new HashSet<>(),cleanupRecords=new HashSet<>();if(cleanups(doc).size()>MAX_RECORDS)throw new IllegalStateException("Too many cleanup intents");
        for(JsonElement e:cleanups(doc)){
            JsonObject op=e.getAsJsonObject();String cid=op.get("cleanup_id").getAsString(),rid=op.get("record_id").getAsString(),state=op.get("state").getAsString();
            if(!cid.matches("cleanup-[a-f0-9]{64}") || !cleanupIds.add(cid) || !cleanupRecords.add(rid) || !op.get("host_id").getAsString().matches("[a-f0-9]{32}"))throw new IllegalStateException("Invalid cleanup identity");
            if(!Set.of("prepared","submitting","submitted","completed","skipped","failed","outcome_unknown").contains(state))throw new IllegalStateException("Invalid cleanup state");uuid(op.get("nonce").getAsString());if(op.has("request_id"))uuid(op.get("request_id").getAsString());
            JsonObject owned=null;for(JsonElement record:doc.getAsJsonArray("records"))if(rid.equals(record.getAsJsonObject().get("record_id").getAsString()))owned=record.getAsJsonObject();
            if(owned==null || !"native_host_confirmed".equals(owned.get("ownership").getAsString()) || !owned.equals(op.get("record")) || !("tc-"+hash(JSON.toJson(List.of(id,cid)))).equals(op.get("host_request_key").getAsString()))throw new IllegalStateException("Cleanup record/request was modified");
            if(Set.of("completed","skipped","failed").contains(state)){
                validateCleanupReceipt(op,op.getAsJsonObject("receipt"));String outcome=op.getAsJsonObject("receipt").get("outcome").getAsString();String expected=Set.of("marked_for_deletion","already_absent").contains(outcome)?"completed":outcome.equals("rolled_back")?"failed":"skipped";
                if(!expected.equals(state) || !outcome.equals(op.get("outcome").getAsString()) || !op.has("request_id"))throw new IllegalStateException("Cleanup proof/state mismatch");
            }
        }

        return doc;
    }
    private JsonObject page(JsonObject doc,int offset,int limit) {
        JsonObject result=summary(doc);JsonArray all=doc.getAsJsonArray("records"),items=new JsonArray();
        for(int i=Math.min(offset,all.size());i<Math.min((long)offset+limit,all.size());i++)items.add(all.get(i).deepCopy());
        result.add("records",items);result.addProperty("offset",offset);result.addProperty("has_more",(long)offset+items.size()<all.size());return result;
    }
    private JsonObject summary(JsonObject doc) {
        JsonObject out=new JsonObject();for(String key:List.of("run_id","project","base_uuid","label","state","revision"))out.add(key,doc.get(key).deepCopy());out.add("data_run_id",out.remove("run_id"));out.addProperty("total",doc.getAsJsonArray("records").size());out.addProperty("pending_creations",activeCreations(doc));out.addProperty("pending_cleanups",activeCleanups(doc));out.addProperty("database_cleanup_verified",false);out.addProperty("data_may_remain",doc.getAsJsonArray("records").size()>0 || activeCreations(doc)>0 || activeCleanups(doc)>0);return out;
    }
    private static int activeCleanups(JsonObject doc){int count=0;for(JsonElement e:cleanups(doc))if(!Set.of("completed","skipped","failed").contains(e.getAsJsonObject().get("state").getAsString()))count++;return count;}
    private static int activeCreations(JsonObject doc){int count=0;for(JsonElement e:creations(doc))if(!Set.of("completed","failed").contains(e.getAsJsonObject().get("state").getAsString()))count++;return count;}
    private String planHash(JsonObject doc) {JsonObject plan=summary(doc);plan.add("records",doc.get("records").deepCopy());plan.add("cleanups",cleanups(doc).deepCopy());return hash(JSON.toJson(plan));}
    private void ensureOpen(JsonObject doc) {if(!"open".equals(doc.get("state").getAsString()))throw new IllegalStateException("Ledger is closed; records retained");}
    private Path file(String id) {if(id==null || !id.matches("data-[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid data_run_id");return root.resolve(id+".json");}
    private void write(String id,JsonObject doc) throws Exception {
        Path file=file(id);if(Files.isSymbolicLink(file))throw new IllegalStateException("Refusing to replace a symbolic link");
        byte[] bytes=JSON.toJson(doc).getBytes(StandardCharsets.UTF_8);if(bytes.length>8*1024*1024)throw new IllegalArgumentException("Ledger would exceed 8 MiB");
        Path temporary=Files.createTempFile(root,"ledger-",".json");
        try {Files.write(temporary,bytes);try{Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temporary);}
    }
    private static void pageArguments(int offset,int limit) {if(offset<0 || limit<1 || limit>100)throw new IllegalArgumentException("offset >= 0 and limit 1..100 required");}
    private static String text(Object value,String name,int max) {if(!(value instanceof String s) || s.isBlank() || s.length()>max || s.indexOf('\u0000')>=0)throw new IllegalArgumentException(name+" must be a nonblank string up to "+max+" characters");return s;}
    private static String uuid(Object value) {String s=text(value,"uuid",36);if(!UUID_TEXT.matcher(s).matches())throw new IllegalArgumentException("Canonical UUID required");String id=UUID.fromString(s).toString();if(id.equals("00000000-0000-0000-0000-000000000000"))throw new IllegalArgumentException("Empty reference is not test data");return id;}
    private static String hash(String text) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
