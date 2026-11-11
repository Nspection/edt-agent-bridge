// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import com.google.gson.*;

/** Persist the claim before dispatch. A missing result never grants permission to retry. */
public final class DirectClientJournal {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
    private final Path root;
    public DirectClientJournal(Path root) throws Exception { this.root=root; Files.createDirectories(root); }
    public static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    public record Claim(boolean created, Map<String,Object> record) {}
    private static Object canonical(Object value){
        if(value instanceof Map<?,?> map){var out=new TreeMap<String,Object>();map.forEach((k,v)->out.put(String.valueOf(k),canonical(v)));return out;}
        if(value instanceof List<?> list)return list.stream().map(DirectClientJournal::canonical).toList();
        return value;
    }
    public synchronized Optional<Map<String,Object>> existing(String key,Map<String,Object> request)throws Exception {
        if(key==null||!key.matches("[A-Za-z0-9_.:-]{1,128}"))throw new IllegalArgumentException("Invalid idempotency_key");String id=hash(key);if(!Files.exists(root.resolve(id+".json")))return Optional.empty();var prior=read(id);if(!hash(JSON.toJson(canonical(request))).equals(prior.get("digest")))throw new IllegalArgumentException("Idempotency key has another payload");return Optional.of(prior);
    }
    public synchronized Claim claim(String key, Map<String,Object> request) throws Exception {
        if(key==null || !key.matches("[A-Za-z0-9_.:-]{1,128}"))throw new IllegalArgumentException("Invalid idempotency_key");
        String id=hash(key), digest=hash(JSON.toJson(canonical(request))); Path path=root.resolve(id+".json");
        if(Files.exists(path)){
            var prior=read(id);
            if(!digest.equals(prior.get("digest")))throw new IllegalArgumentException("Idempotency key has another payload");
            return new Claim(false,prior);
        }
        var blocked=blockingOperation();if(blocked.isPresent())throw new IllegalStateException("Previous client operation is active or unknown; inspect original operation "+blocked.get());
        try(var files=Files.list(root)){if(files.filter(p->p.toString().endsWith(".json")).count()>=128)throw new IllegalStateException("Client operation limit reached");}
        var claim=new LinkedHashMap<String,Object>();claim.put("operation_id",id);claim.put("digest",digest);claim.put("state","claimed");write(id,claim);return new Claim(true,claim);
    }
    public synchronized Optional<String> blockingOperation()throws Exception {
        try(var files=Files.list(root)){for(Path file:files.filter(p->p.toString().endsWith(".json")).limit(129).toList()){
            String id=file.getFileName().toString().replace(".json","");var record=read(id);if("outcome_unknown".equals(record.get("state"))||!(record.get("result") instanceof Map<?,?>))return Optional.of(id);
        }}return Optional.empty();
    }
    @SuppressWarnings("unchecked") public synchronized Map<String,Object> read(String id)throws Exception {
        if(id==null || !id.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid operation_id");
        Path path=root.resolve(id+".json");if(Files.isSymbolicLink(path)||Files.size(path)>512*1024)throw new IllegalStateException("Invalid operation record");
        return JSON.fromJson(Files.readString(path,StandardCharsets.UTF_8),LinkedHashMap.class);
    }
    public synchronized void write(String id,Map<String,Object> value)throws Exception {
        if(!id.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid operation_id");
        Path path=root.resolve(id+".json"),tmp=root.resolve(id+".tmp");if(Files.isSymbolicLink(root)||Files.isSymbolicLink(path)||Files.isSymbolicLink(tmp))throw new IllegalStateException("Operation storage changed");
        Files.writeString(tmp,JSON.toJson(value),StandardCharsets.UTF_8);Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    public synchronized Map<String,Object> result(String id)throws Exception {
        var record=read(id);var out=new LinkedHashMap<String,Object>();out.put("operation_id",id);
        if(record.get("result") instanceof Map<?,?> result){result.forEach((k,v)->out.put(String.valueOf(k),v));out.put("state",record.get("state"));out.put("outcome_unknown","outcome_unknown".equals(record.get("state")));}
        else {out.put("success",false);out.put("state","outcome_unknown");out.put("outcome_unknown",true);out.put("message","Claim exists without a proven result; inspect the original operation");}
        out.put("terminal",true);return out;
    }
}
