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
    private static final Gson JSON=new GsonBuilder().serializeNulls().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
    private final HttpServer server;
    private final ExecutorService workers;
    private final String token;
    private final Path root;
    private final LinkedHashMap<String,JsonObject> requests=new LinkedHashMap<>();
    private JsonObject identity;
    private volatile long lastSeen;
    private boolean stopping;
    private long revision;
    public synchronized long revision(){return revision;}
    private void changed(){revision++;notifyAll();}
    public synchronized void awaitRevision(long before,long timeoutMillis)throws InterruptedException{
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while(revision==before){long left=deadline-System.nanoTime();if(left<=0)return;TimeUnit.NANOSECONDS.timedWait(this,left);}
    }
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
        if(!Set.of("client","server","batch").contains(context))throw new IllegalArgumentException("context must be client/server");
        JsonObject body=new JsonObject();body.addProperty("context",context);body.addProperty("code",payload);body.add("parameters",JSON.toJsonTree(new TreeMap<>(parameters)));
        if(body.toString().getBytes(StandardCharsets.UTF_8).length>128*1024)throw new IllegalArgumentException("Payload exceeds 128 KiB");
        String keyHash=hash(key),digest=hash(body.toString());Path claim=root.resolve(keyHash+".json");
        if(Files.exists(claim)){
            JsonObject previous=JsonParser.parseString(Files.readString(claim)).getAsJsonObject();
            if(!digest.equals(previous.get("digest").getAsString()))throw new IllegalArgumentException("Idempotency key already used with different payload");
            String id=previous.get("request_id").getAsString();return result(id);
        }
        try(var claims=Files.list(root)){
            if(claims.filter(x->x.getFileName().toString().matches("[a-f0-9]{64}\\.json")).count()>=128)throw new IllegalStateException("Host request limit reached; stop and clean up this host");
        }
        String id=UUID.randomUUID().toString();body.addProperty("request_id",id);body.addProperty("state","queued");body.addProperty("digest",digest);
        persist(claim,body);requests.put(id,body);changed();return result(id);
    }
    public synchronized Map<String,Object> result(String id) throws Exception {
        JsonObject body=requests.get(id);
        return body==null?persistedResult(root,id):resultView(body,false);
    }
    public synchronized boolean batch(String id)throws Exception {JsonObject body=requests.get(id);return body==null?persistedBatch(root,id):"batch".equals(body.get("context").getAsString());}
    public static boolean persistedBatch(Path root,String id)throws Exception{return "batch".equals(persistedBody(root,id).get("context").getAsString());}
    public static Map<String,Object> persistedResult(Path root,String id)throws Exception{return resultView(persistedBody(root,id),true);}
    private static JsonObject persistedBody(Path root,String id) throws Exception {
        if(Files.isDirectory(root))try(var files=Files.list(root)){
            for(Path p:files.filter(x->x.getFileName().toString().endsWith(".json")).toList()){
                JsonObject body=JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                if(body.has("request_id")&&id.equals(body.get("request_id").getAsString()))return body;
            }
        }
        throw new IllegalArgumentException("Unknown request_id");
    }
    private static Map<String,Object> resultView(JsonObject body,boolean recovered){
        String state=body.get("state").getAsString();
        if(recovered&&!Set.of("completed","failed","cancelled").contains(state))state="outcome_unknown";
        var out=new LinkedHashMap<String,Object>();out.put("request_id",body.get("request_id").getAsString());out.put("state",state);out.put("outcome_unknown",state.equals("outcome_unknown"));out.put("terminal",Set.of("completed","failed","cancelled","outcome_unknown").contains(state));
        if(body.has("progress")){
            JsonObject progress=body.getAsJsonObject("progress").deepCopy();
            if(body.has("result")){progress.addProperty("reported_steps",progress.getAsJsonArray("value").size());progress.remove("value");}
            out.put("progress",JSON.fromJson(progress,Map.class));
        }
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
            if(requests.values().stream().anyMatch(r->r.get("context").getAsString().equals("batch")&&r.get("state").getAsString().equals("delivered")))return "{}";
            for(JsonObject request:requests.values())if(request.get("state").getAsString().equals("queued")){
                request.addProperty("state","delivered");save(request);changed();return request.toString();
            }
            return "{}";
        case "/result":
            String id=data.get("request_id").getAsString();JsonObject request=requests.get(id);
            if(request==null)throw new IllegalArgumentException("Unknown request_id");
            if(NativeBatchProgress.isProgress(data)){
                JsonObject updated=NativeBatchProgress.accept(request,data);if(updated!=request){save(updated);requests.put(id,updated);changed();}return "{}";
            }
            if(!data.has("success")||!data.get("success").isJsonPrimitive()||!data.get("success").getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("success must be boolean");
            if(request.has("result")){if(!request.get("result").equals(data))throw new IllegalStateException("Conflicting result");return "{}";}
            if(!request.get("state").getAsString().equals("delivered"))throw new IllegalStateException("Request was not delivered");
            boolean unknown=false;
            if(data.has("outcome_unknown")){
                if(!data.get("outcome_unknown").isJsonPrimitive()||!data.get("outcome_unknown").getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("outcome_unknown must be boolean");
                unknown=data.get("outcome_unknown").getAsBoolean();
                if(unknown&&data.get("success").getAsBoolean())throw new IllegalArgumentException("Unknown outcome cannot be successful");
            }
            if(request.has("progress"))NativeBatchProgress.prefix(request.getAsJsonObject("progress"),data);
            JsonObject completed=request.deepCopy();completed.add("result",data.deepCopy());completed.addProperty("state",unknown?"outcome_unknown":data.get("success").getAsBoolean()?"completed":"failed");save(completed);requests.put(id,completed);changed();return "{}";
        default:throw new IllegalArgumentException("Unknown endpoint");
        }
    }
    private synchronized String snapshot(byte[] png)throws Exception {
        if(identity==null||stopping)throw new IllegalStateException("Connected client required");
        var delivered=requests.values().stream().filter(r->r.get("state").getAsString().equals("delivered")).toList();
        if(delivered.size()!=1)throw new IllegalStateException("Screenshot requires exactly one executing request");
        if(png.length<24||!Arrays.equals(Arrays.copyOf(png,8),new byte[]{(byte)137,80,78,71,13,10,26,10})||!Arrays.equals(Arrays.copyOfRange(png,12,16),new byte[]{73,72,68,82}))throw new IllegalArgumentException("PNG required");
        var header=java.nio.ByteBuffer.wrap(png);int width=header.getInt(16),height=header.getInt(20);
        if(width<1||height<1||width>8192||height>8192||(long)width*height>24*1024*1024)throw new IllegalArgumentException("Image dimensions exceed limit");
        Path folder=root.resolve("snapshots");if(Files.isSymbolicLink(root)||Files.isSymbolicLink(folder))throw new IllegalStateException("Snapshot directory changed");Files.createDirectories(folder);long total=0;int count=0;
        try(var files=Files.list(folder)){for(Path file:files.toList()){if(Files.isSymbolicLink(file))throw new IllegalStateException("Snapshot directory changed");if(file.toString().endsWith(".png")){count++;total+=Files.size(file);}}}
        if(count>=64||total+png.length>128*1024*1024)throw new IllegalStateException("Snapshot quota reached");
        String id=UUID.randomUUID().toString();Path image=folder.resolve(id+".png");Files.write(image,png,StandardOpenOption.CREATE_NEW);
        JsonObject value=new JsonObject();value.addProperty("snapshot_id",id);value.addProperty("image_path",image.toString());value.addProperty("mime_type","image/png");value.addProperty("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)));value.addProperty("width",width);value.addProperty("height",height);value.addProperty("request_id",delivered.get(0).get("request_id").getAsString());value.addProperty("captured_at",Instant.now().toString());
        persist(folder.resolve(id+".json"),value);changed();lastSeen=System.currentTimeMillis();return value.toString();
    }
    public static List<Map<String,Object>> snapshots(Path root)throws Exception {
        Path folder=root.resolve("snapshots");if(Files.isSymbolicLink(root)||Files.isSymbolicLink(folder))throw new IllegalStateException("Snapshot directory changed");var values=new ArrayList<Map<String,Object>>();if(!Files.isDirectory(folder))return values;
        try(var files=Files.list(folder)){for(Path file:files.filter(p->p.getFileName().toString().matches("[a-f0-9-]{36}\\.json")).sorted().toList()){
            if(Files.isSymbolicLink(file)||Files.size(file)>4096)throw new IllegalStateException("Snapshot manifest changed");
            JsonObject item=JsonParser.parseString(Files.readString(file)).getAsJsonObject();Path image=folder.resolve(file.getFileName().toString().replace(".json",".png"));
            if(Files.isSymbolicLink(image)||!Files.isRegularFile(image)||Files.size(image)>16*1024*1024||!image.toString().equals(item.get("image_path").getAsString())||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image))).equals(item.get("sha256").getAsString()))throw new IllegalStateException("Snapshot bytes changed");
            @SuppressWarnings("unchecked") Map<String,Object> value=JSON.fromJson(item,Map.class);values.add(value);
        }}return values;
    }
    public static List<Map<String,Object>> snapshotReferences(Path root,String requestId)throws Exception{
        Path folder=root.resolve("snapshots");if(Files.isSymbolicLink(root)||Files.isSymbolicLink(folder))throw new IllegalStateException("Snapshot directory changed");
        var out=new ArrayList<Map<String,Object>>();if(!Files.isDirectory(folder))return out;
        try(var paths=Files.list(folder)){
            var files=paths.filter(p->p.getFileName().toString().matches("[a-f0-9-]{36}\\.json")).sorted().toList();if(files.size()>64)throw new IllegalStateException("Snapshot manifest quota exceeded");
            for(Path file:files){
                if(Files.isSymbolicLink(file)||Files.size(file)>4096)throw new IllegalStateException("Snapshot manifest changed");
                JsonObject value=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if(!requestId.equals(value.get("request_id").getAsString()))continue;
                String id=value.get("snapshot_id").getAsString();if(!file.getFileName().toString().equals(id+".json")||!value.get("sha256").getAsString().matches("[a-f0-9]{64}"))throw new IllegalStateException("Invalid snapshot receipt");
                var row=new LinkedHashMap<String,Object>();for(String key:List.of("snapshot_id","request_id","sha256","width","height","captured_at"))row.put(key,JSON.fromJson(value.get(key),Object.class));
                row.put("verification","capture_receipt_only");out.add(row);
            }
        }return out;
    }
    private void handle(HttpExchange exchange) throws IOException {
        int status=200;String response="{}";
        try{
            String auth=Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"),"");
            if(!MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.US_ASCII),auth.getBytes(StandardCharsets.US_ASCII))){status=403;return;}
            if(!exchange.getRequestMethod().equals("POST")||exchange.getRequestURI().getRawQuery()!=null){status=405;return;}
            boolean capture=exchange.getRequestURI().getPath().equals("/snapshot");int max=capture?16*1024*1024:MAX_BYTES;
            byte[] body=exchange.getRequestBody().readNBytes(max+1);if(body.length>max){status=413;return;}
            response=capture?snapshot(body):dispatch(exchange.getRequestURI().getPath(),JsonParser.parseString(new String(body,StandardCharsets.UTF_8)).getAsJsonObject());
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
    public synchronized void freezeForRefresh()throws Exception {
        requireRefreshSafe(root);stopping=true;changed();
    }
    public static void requireRefreshSafe(Path root)throws Exception {
        if(!Files.isDirectory(root))return;
        try(var files=Files.list(root)){
            for(Path file:files.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).toList()){
                JsonObject body=JsonParser.parseString(Files.readString(file)).getAsJsonObject();String state=body.get("state").getAsString();
                if(!Set.of("completed","failed","cancelled").contains(state))throw new IllegalStateException("Refresh blocked by original request "+body.get("request_id").getAsString()+" ("+state+"); inspect its result, never replay it");
            }
        }
    }
    public synchronized void requestStop() throws Exception {
        stopping=true;changed();for(JsonObject r:requests.values())if(r.get("state").getAsString().equals("queued")){r.addProperty("state","cancelled");save(r);}
    }
    @Override public void close(){
        server.stop(0);workers.shutdownNow();
        try{
            if(!workers.awaitTermination(3,TimeUnit.SECONDS))throw new IllegalStateException("Host transport workers did not stop");
            synchronized(this){
                stopping=true;changed();
                for(JsonObject request:requests.values()){
                    String state=request.get("state").getAsString();
                    if(state.equals("queued")||state.equals("delivered")){
                        request.addProperty("state",state.equals("queued")?"cancelled":"outcome_unknown");
                        try{save(request);}catch(Exception error){throw new IllegalStateException("Cannot persist interrupted host request",error);}
                    }
                }
            }
        }
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted while stopping host transport",e);}
    }
}
