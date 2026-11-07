// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.*;
import com.sun.net.httpserver.*;
/** Loopback transport. Poll is an at-most-once claim; losing a response must never replay BSL. */
public final class NativeHostChannel implements AutoCloseable {
    public static final int MAX_BYTES=2*1024*1024;
    private static final Gson JSON=new Gson();
    private final HttpServer server;
    private final ExecutorService workers;
    private final String token;
    private final Path root;
    private final LinkedHashMap<String,JsonObject> requests=new LinkedHashMap<>();
    private JsonObject identity;
    private volatile long lastSeen;
    private boolean stopping;
    public NativeHostChannel(Path root) throws Exception {
        this.root=root;Files.createDirectories(root);
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);token=HexFormat.of().formatHex(bytes);
        server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),8);
        workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"bridge-native-host");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(workers);server.createContext("/",this::handle);server.start();
        // Keep the endpoint private and local to one client instance. Restart never replays requests.
    }
    public int port(){return server.getAddress().getPort();}
    String token(){return token;}
    public synchronized Map<String,Object> status(){
        return Map.of("connected",identity!=null,"identity",identity==null?Map.of():JSON.fromJson(identity,Map.class),"last_seen",lastSeen,"stopping",stopping,"requests",requests.size());
    }
    public synchronized Map<String,Object> submit(String key,String context,String payload,Map<String,Object> parameters) throws Exception {
        if(stopping)throw new IllegalStateException("Host is stopping");
        if(identity==null)throw new IllegalStateException("Host has not reported runtime identity");
        if(!key.matches("[A-Za-z0-9_.:-]{1,128}"))throw new IllegalArgumentException("Invalid idempotency_key");
        if(!Set.of("client","server").contains(context))throw new IllegalArgumentException("context must be client/server");
        JsonObject body=new JsonObject();body.addProperty("context",context);body.addProperty("code",payload);body.add("parameters",JSON.toJsonTree(new TreeMap<>(parameters)));
        if(body.toString().getBytes(StandardCharsets.UTF_8).length>128*1024)throw new IllegalArgumentException("Payload exceeds 128 KiB");
        String keyHash=hash(key),digest=hash(body.toString());Path claim=root.resolve(keyHash+".json");
        if(Files.exists(claim)){
            JsonObject previous=JsonParser.parseString(Files.readString(claim)).getAsJsonObject();
            if(!digest.equals(previous.get("digest").getAsString()))throw new IllegalArgumentException("Idempotency key already used with different payload");
            String id=previous.get("request_id").getAsString();return result(id);
        }
        if(requests.size()>=128)throw new IllegalStateException("Host request limit reached; stop and clean up this host");
        String id=UUID.randomUUID().toString();body.addProperty("request_id",id);body.addProperty("state","queued");body.addProperty("digest",digest);
        persist(claim,body);requests.put(id,body);return result(id);
    }
    public synchronized Map<String,Object> result(String id) throws Exception {
        JsonObject body=requests.get(id);
        if(body==null){
            try(var files=Files.list(root)){
                for(Path p:files.filter(x->x.getFileName().toString().endsWith(".json")).toList()){
                    JsonObject v=JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                    if(v.has("request_id")&&id.equals(v.get("request_id").getAsString())){body=v;break;}
                }
            }
            if(body==null)throw new IllegalArgumentException("Unknown request_id");
            if(!Set.of("completed","failed","cancelled").contains(body.get("state").getAsString()))body.addProperty("state","outcome_unknown");
        }
        var out=new LinkedHashMap<String,Object>();out.put("request_id",id);String state=body.get("state").getAsString();out.put("state",state);out.put("outcome_unknown",state.equals("outcome_unknown"));out.put("terminal",Set.of("completed","failed","cancelled","outcome_unknown").contains(state));
        if(body.has("result"))out.put("execution",JSON.fromJson(body.get("result"),Map.class));return out;
    }
    private synchronized String dispatch(String path,JsonObject data) throws Exception {
        lastSeen=System.currentTimeMillis();
        switch(path){
        case "/hello":
            if(identity!=null&&!identity.equals(data))throw new IllegalStateException("Another client attempted to reuse the host session");
            for(String key:List.of("configuration","user","platform"))if(!data.has(key)||!data.get(key).isJsonPrimitive())throw new IllegalArgumentException("Missing identity "+key);
            identity=data;return "{}";
        case "/poll":
            if(identity==null)throw new IllegalStateException("hello required");
            if(stopping)return "{\"stop\":true}";
            for(JsonObject request:requests.values())if(request.get("state").getAsString().equals("queued")){
                request.addProperty("state","delivered");save(request);return request.toString();
            }
            return "{}";
        case "/result":
            String id=data.get("request_id").getAsString();JsonObject request=requests.get(id);
            if(request==null)throw new IllegalArgumentException("Unknown request_id");
            if(!data.has("success")||!data.get("success").isJsonPrimitive()||!data.get("success").getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("success must be boolean");
            if(request.has("result")){if(!request.get("result").equals(data))throw new IllegalStateException("Conflicting result");return "{}";}
            if(!request.get("state").getAsString().equals("delivered"))throw new IllegalStateException("Request was not delivered");
            request.add("result",data);request.addProperty("state",data.get("success").getAsBoolean()?"completed":"failed");save(request);return "{}";
        default:throw new IllegalArgumentException("Unknown endpoint");
        }
    }
    private void handle(HttpExchange exchange) throws IOException {
        int status=200;String response="{}";
        try{
            String auth=Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"),"");
            if(!MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.US_ASCII),auth.getBytes(StandardCharsets.US_ASCII))){status=403;return;}
            if(!exchange.getRequestMethod().equals("POST")||exchange.getRequestURI().getRawQuery()!=null){status=405;return;}
            byte[] body=exchange.getRequestBody().readNBytes(MAX_BYTES+1);if(body.length>MAX_BYTES){status=413;return;}
            response=dispatch(exchange.getRequestURI().getPath(),JsonParser.parseString(new String(body,StandardCharsets.UTF_8)).getAsJsonObject());
        }catch(Exception e){status=400;response="{\"error\":\"Request rejected\"}";}
        finally{
            byte[] body=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.sendResponseHeaders(status,body.length);try(var stream=exchange.getResponseBody()){stream.write(body);}exchange.close();
        }
    }
    private void save(JsonObject request) throws Exception {
        try(var files=Files.list(root)){
            for(Path p:files.filter(x->x.getFileName().toString().endsWith(".json")).toList()){
                JsonObject v=JsonParser.parseString(Files.readString(p)).getAsJsonObject();if(v.has("request_id")&&v.get("request_id").equals(request.get("request_id"))){persist(p,request);return;}
            }
        }
        throw new IOException("Request claim disappeared");
    }
    private static void persist(Path path,JsonObject body) throws IOException {
        Path temp=path.resolveSibling(path.getFileName()+".tmp");Files.writeString(temp,body.toString(),StandardCharsets.UTF_8);Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    static String hash(String value) throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    public synchronized void requestStop() throws Exception {
        stopping=true;for(JsonObject r:requests.values())if(r.get("state").getAsString().equals("queued")){r.addProperty("state","cancelled");save(r);}
    }
    @Override public void close(){
        server.stop(0);workers.shutdownNow();
        try{if(!workers.awaitTermination(3,TimeUnit.SECONDS))throw new IllegalStateException("Host transport workers did not stop");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted while stopping host transport",e);}
    }
}
