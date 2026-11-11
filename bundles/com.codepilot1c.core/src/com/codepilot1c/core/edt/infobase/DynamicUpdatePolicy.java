// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.codepilot1c.core.edt.development.TaskSessionService;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;

/** Explicit revocable permission, bound to exact project path, branch context and registered base. */
public final class DynamicUpdatePolicy {
    public interface Backend { Map<String,Object> binding(String project) throws Exception; }
    private static final Object LOCK = new Object();
    private static final Gson JSON = new GsonBuilder().create();
    private final Path root;
    private final Backend backend;

    public DynamicUpdatePolicy(Path root, Backend backend) throws Exception {
        this.root = root.toAbsolutePath().normalize(); this.backend = backend;
        Files.createDirectories(this.root); checkRoot();
    }
    public static DynamicUpdatePolicy workspace() throws Exception {
        var root = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath();
        return new DynamicUpdatePolicy(root.resolve(".metadata/.plugins/com.codepilot1c.core/dynamic-update-policies"), p -> {
            var ref = new EdtRuntimeService().resolveDefaultInfobase(p);
            if (ref == null) throw new IllegalStateException("Exact bound base required");
            return new RegisteredInfobaseGateway().describeScope(p, ref.getUuid().toString());
        });
    }
    private void checkRoot() throws Exception {
        if (!root.toRealPath().equals(root) || Files.isSymbolicLink(root)) throw new IllegalStateException("Permission storage moved");
    }
    private Map<String,Object> scope(String project) throws Exception {
        var binding = backend.binding(project);
        var scope = new LinkedHashMap<String,Object>();
        for (String key : List.of("project","project_path","context","base_uuid","target_identity_sha256","primary_uuid")) {
            Object value = binding.get(key);
            if (value == null || (value instanceof String s && s.isBlank())) throw new IllegalStateException("Incomplete permission scope");
            scope.put(key,value);
        }
        if (!project.equals(scope.get("project")) || !scope.get("base_uuid").equals(scope.get("primary_uuid"))
                || !(scope.get("context") instanceof List<?>)
                || !Objects.toString(scope.get("target_identity_sha256")).matches("[a-f0-9]{64}")) {
            throw new IllegalStateException("Permission binding changed");
        }
        UUID.fromString((String)scope.get("base_uuid"));
        return scope;
    }
    private Path file(String id) throws Exception {
        checkRoot();
        if (!id.matches("dynamic-[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid policy identity");
        var file = root.resolve(id+".json");
        if (Files.isSymbolicLink(file)) throw new IllegalStateException("Linked policy file");
        return file;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> read(Map<String,Object> scope) throws Exception {
        String id = "dynamic-"+TaskSessionService.fingerprint(scope); Path file = file(id);
        if (!Files.exists(file,LinkOption.NOFOLLOW_LINKS)) return Map.of("policy_id",id,"policy","ask","revision","none","scope",scope);
        if (!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>65536) throw new IllegalStateException("Invalid permission record");
        Map<String,Object> result = JSON.fromJson(Files.readString(file),LinkedHashMap.class);
        if (!id.equals(result.get("policy_id")) || !TaskSessionService.fingerprint(scope).equals(TaskSessionService.fingerprint(result.get("scope")))
                || !Set.of("ask","allow_dynamic").contains(result.get("policy")) || !(result.get("revision") instanceof String)) {
            throw new IllegalStateException("Permission identity changed");
        }
        return result;
    }
    public Map<String,Object> status(String project) throws Exception {
        var current = scope(project); // Never call the EDT model while holding the file-store lock.
        synchronized(LOCK) { return read(current); }
    }
    private Map<String,Object> previewFor(String project,String policy,Map<String,Object> current) throws Exception {
        if (!Set.of("ask","allow_dynamic").contains(policy)) throw new IllegalArgumentException("policy: ask or allow_dynamic");
        var out = new LinkedHashMap<String,Object>(); out.put("project",project); out.put("before",read(current));
        out.put("policy",policy); out.put("applies_to_pending_owned_questions",policy.equals("allow_dynamic"));
        out.put("starts_new_update",false); out.put("plan_sha256",TaskSessionService.fingerprint(out)); return out;
    }
    public Map<String,Object> preview(String project,String policy) throws Exception {
        var current = scope(project);
        synchronized(LOCK) { return previewFor(project,policy,current); }
    }
    public Map<String,Object> apply(String project, String policy, String expected) throws Exception {
        var current = scope(project);
        synchronized(LOCK) {
        var plan = previewFor(project,policy,current);
        if (!Objects.equals(expected,plan.get("plan_sha256"))) throw new IllegalStateException("STALE_DYNAMIC_PERMISSION_PREVIEW");
        var before = TaskSessionService.object(plan.get("before")); var record = new LinkedHashMap<String,Object>();
        record.put("policy_id",before.get("policy_id")); record.put("scope",before.get("scope"));
        record.put("policy",policy); record.put("revision",UUID.randomUUID().toString());
        save(record); return record;
    } }
    private void save(Map<String,Object> record) throws Exception {
        var target = file((String)record.get("policy_id"));
        try(var files = Files.list(root)) { if (!Files.exists(target) && files.limit(257).count()>=256) throw new IllegalStateException("Permission record capacity reached"); }
        var temp = Files.createTempFile(root,"policy-",".tmp");
        try { Files.writeString(temp,JSON.toJson(record),StandardCharsets.UTF_8); Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temp); }
    }
    @SuppressWarnings("unchecked") private Map<String,Object> byId(String id) throws Exception {
        Path path=file(id);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>65536)throw new IllegalStateException("Permission record unavailable");
        Map<String,Object> record=JSON.fromJson(Files.readString(path),LinkedHashMap.class);
        if(!id.equals(record.get("policy_id"))||!(record.get("scope") instanceof Map<?,?>)||!id.equals("dynamic-"+TaskSessionService.fingerprint(record.get("scope")))||!Set.of("ask","allow_dynamic").contains(record.get("policy")))throw new IllegalStateException("Permission identity changed");
        return record;
    }
    public Map<String,Object> revokePreview(String id) throws Exception { synchronized(LOCK) {
        var out=new LinkedHashMap<String,Object>();out.put("before",byId(id));out.put("policy_id",id);out.put("policy","ask");out.put("plan_sha256",TaskSessionService.fingerprint(out));return out;
    } }
    public Map<String,Object> revoke(String id,String expected) throws Exception { synchronized(LOCK) {
        var plan=revokePreview(id);if(!Objects.equals(expected,plan.get("plan_sha256")))throw new IllegalStateException("STALE_DYNAMIC_PERMISSION_PREVIEW");
        var record=new LinkedHashMap<>(TaskSessionService.object(plan.get("before")));record.put("policy","ask");record.put("revision",UUID.randomUUID().toString());save(record);return record;
    } }
    public Map<String,Object> permission(String project,String baseUuid,String targetHash) throws Exception {
        return permission(project,baseUuid,targetHash,null);
    }
    public Map<String,Object> permission(String project,String baseUuid,String targetHash,String originalScopeHash) throws Exception {
        var current = scope(project);
        if(originalScopeHash!=null&&!originalScopeHash.equals(TaskSessionService.fingerprint(current)))return Map.of();
        synchronized(LOCK) {
        if (!baseUuid.equals(current.get("base_uuid")) || !targetHash.equals(current.get("target_identity_sha256"))) return Map.of();
        var record = read(current); return "allow_dynamic".equals(record.get("policy")) ? record : Map.of();
    } }
}
