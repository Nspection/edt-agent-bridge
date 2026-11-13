// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.google.gson.*;

/** Durable claim index. A missing receipt is uncertainty, never an unused key.
 * The sibling anchor prevents missing/deleted history from being reinitialised.
 * Legacy receipts remain readable but cannot authorize any new dispatch.
 */
final class NativeRequestStore {
    private static final String INDEX=".claims.json";
    private final Path root;
    NativeRequestStore(Path root,boolean create)throws Exception {
        this.root=root;
        if(create&&!Files.exists(root,LinkOption.NOFOLLOW_LINKS)&&!Files.exists(anchor(root),LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(root.getParent());
            Files.writeString(anchor(root),"native-request-store-v1",StandardOpenOption.CREATE_NEW);
            Files.createDirectory(root);
            var index=new JsonObject();index.addProperty("version",1);index.add("claims",new JsonObject());
            NativeHostChannel.persist(root.resolve(INDEX),index);
        }
    }
    static Path anchor(Path root){return root.resolveSibling(root.getFileName()+".claims-v1");}
    void requireIndexed()throws Exception {if(index().get("version").getAsInt()!=1)throw new IllegalStateException("Initialized Native journal lost its index/anchor; outcome unknown");}
    private JsonObject index()throws Exception {
        if(Files.isSymbolicLink(root)||Files.isSymbolicLink(anchor(root)))throw new IllegalStateException("Native history storage changed");
        Path file=root.resolve(INDEX);
        if(Files.exists(anchor(root),LinkOption.NOFOLLOW_LINKS)) {
            if(!Files.isRegularFile(anchor(root),LinkOption.NOFOLLOW_LINKS)||Files.size(anchor(root))>64||!"native-request-store-v1".equals(Files.readString(anchor(root))))throw new IllegalStateException("Native history anchor changed");
            JsonObject value=read(file,65536);
            if(value.get("version").getAsInt()!=1)throw new IllegalStateException("Unsupported Native journal version");
            return value;
        }
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("Native history anchor missing");
        var legacy=new JsonObject();legacy.addProperty("version",0);var claims=new JsonObject();legacy.add("claims",claims);
        for(Path receipt:files()) {
            JsonObject body=validated(receipt,null);var claim=new JsonObject();
            claim.addProperty("request_id",body.get("request_id").getAsString());claim.addProperty("digest",body.get("digest").getAsString());
            claims.add(key(receipt),claim);
        }
        return legacy;
    }
    private List<Path> files()throws Exception {
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return List.of();
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("Native request directory missing");
        try(var paths=Files.list(root)) {
            var files=paths.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).limit(129).toList();
            if(files.size()>128)throw new IllegalStateException("Native request quota exceeded");return files;
        }
    }
    private static String key(Path p){return p.getFileName().toString().substring(0,64);}
    private JsonObject claims()throws Exception {
        JsonObject claims=index().getAsJsonObject("claims");if(claims.size()>128)throw new IllegalStateException("Native claim quota exceeded");
        Set<String> ids=new HashSet<>(),actual=new HashSet<>();for(Path p:files())actual.add(key(p));
        for(var item:claims.entrySet()) {
            if(!item.getKey().matches("[a-f0-9]{64}"))throw new IllegalStateException("Invalid Native key");
            var claim=item.getValue().getAsJsonObject();String id=claim.get("request_id").getAsString();UUID.fromString(id);
            if(!ids.add(id)||!claim.get("digest").getAsString().matches("[a-f0-9]{64}"))throw new IllegalStateException("Native claim identity duplicated/invalid");
        }
        if(!actual.equals(claims.keySet()))throw new IllegalStateException("Native receipt inventory changed; outcome unknown, no repeat");
        return claims;
    }
    private static JsonObject read(Path file,int max)throws Exception {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>max)throw new IllegalStateException("Native history unavailable");
        return JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private static JsonObject validated(Path file,JsonObject claim)throws Exception {
        JsonObject body=read(file,NativeHostChannel.MAX_RECORD_BYTES),original=new JsonObject();
        for(String field:List.of("context","code","parameters")){if(!body.has(field))throw new IllegalStateException("Incomplete Native request history");original.add(field,body.get(field));}
        String digest=NativeHostChannel.hash(original.toString()),id=body.get("request_id").getAsString();UUID.fromString(id);
        if(!digest.equals(body.get("digest").getAsString()))throw new IllegalStateException("Native request payload changed");
        if(body.has("claim_identity_sha256")&&!NativeHostChannel.hash(key(file)+"\0"+id+"\0"+digest).equals(body.get("claim_identity_sha256").getAsString()))throw new IllegalStateException("Native key/request identity changed");
        if(body.has("result")&&!id.equals(body.getAsJsonObject("result").get("request_id").getAsString()))throw new IllegalStateException("Native result identity changed");
        if(claim!=null&&(!id.equals(claim.get("request_id").getAsString())||!digest.equals(claim.get("digest").getAsString())||!NativeHostChannel.hash(body.toString()).equals(claim.get("record_sha256").getAsString())))throw new IllegalStateException("Native receipt differs from durable claim; outcome unknown");
        return body;
    }
    Optional<JsonObject> previous(NativeExecutionPlan plan)throws Exception {
        String key=NativeHostChannel.hash(plan.key());var claims=claims();if(!claims.has(key)){if(index().get("version").getAsInt()==0&&Files.exists(root))throw new IllegalStateException("Legacy Native history cannot prove an unused key; prepare a new host");return Optional.empty();}
        JsonObject claim=claims.getAsJsonObject(key);
        if(!NativeHostChannel.hash(plan.body().toString()).equals(claim.get("digest").getAsString()))throw new IllegalArgumentException("Idempotency key already used with different payload; inspect original request");
        return Optional.of(validated(root.resolve(key+".json"),index().get("version").getAsInt()==1?claim:null));
    }
    JsonObject result(String id)throws Exception {
        UUID.fromString(id);var claims=claims();
        for(var item:claims.entrySet())if(id.equals(item.getValue().getAsJsonObject().get("request_id").getAsString()))return validated(root.resolve(item.getKey()+".json"),index().get("version").getAsInt()==1?item.getValue().getAsJsonObject():null);
        throw new IllegalArgumentException("Unknown request_id");
    }
    void claim(String key,JsonObject body)throws Exception {
        var index=index();if(index.get("version").getAsInt()!=1)throw new IllegalStateException("Legacy Native history is read-only; prepare a new host for new requests");
        var claims=claims();if(claims.has(key))throw new IllegalStateException("Native key already reserved");
        if(claims.size()>=128)throw new IllegalStateException("Host request limit reached; stop and clean up this host");
        claims.add(key,binding(body));index.add("claims",claims);
        // Reserve before publishing. Failure after this write remains unknown, never replayable.
        NativeHostChannel.persist(root.resolve(INDEX),index);NativeHostChannel.persist(root.resolve(key+".json"),body);
    }
    private static JsonObject binding(JsonObject body)throws Exception {
        var claim=new JsonObject();for(String field:List.of("request_id","digest"))claim.add(field,body.get(field));
        claim.addProperty("record_sha256",NativeHostChannel.hash(body.toString()));return claim;
    }
    void save(Path file,JsonObject body)throws Exception {
        var index=index();var claims=claims();String key=key(file);if(!claims.has(key))throw new IllegalStateException("Native claim missing");
        validated(file,claims.getAsJsonObject(key));
        if(!body.get("request_id").equals(claims.getAsJsonObject(key).get("request_id"))||!body.get("digest").equals(claims.getAsJsonObject(key).get("digest")))throw new IllegalStateException("Native identity changed");
        NativeHostChannel.persist(file,body);claims.add(key,binding(body));index.add("claims",claims);NativeHostChannel.persist(root.resolve(INDEX),index);
    }
    void requireRefreshSafe()throws Exception {
        for(var item:claims().entrySet()) {
            JsonObject body=result(item.getValue().getAsJsonObject().get("request_id").getAsString());
            if(!Set.of("completed","failed","cancelled").contains(body.get("state").getAsString()))throw new IllegalStateException("Refresh blocked by original request "+body.get("request_id").getAsString()+"; inspect it, never replay");
        }
    }
}
