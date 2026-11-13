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
    static final int MAX_RECORD_BYTES=2*MAX_BYTES+128*1024+4096; // request + result + retained progress + envelope
    private static final Gson JSON=new GsonBuilder().serializeNulls().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
    private final HttpServer server;
    private final ExecutorService workers;
    private final String token;
    private final Path root;
    private final NativeRequestStore store;
    private final LinkedHashMap<String,JsonObject> requests=new LinkedHashMap<>();
    private final Map<String,Path> requestFiles=new HashMap<>();
    private final Set<String> claimedKeys=new HashSet<>();
    private final Map<String,String> claimedIds=new HashMap<>();
    private JsonObject identity;
    private volatile long lastSeen;
    private final java.util.function.LongSupplier clock;
    private long lastContact;private boolean exited;private String interruptedReason;
    private final Map<String,Long> queueDeadlines=new HashMap<>();
    static final long QUEUE_TIMEOUT_NANOS=TimeUnit.SECONDS.toNanos(60),CONTACT_TIMEOUT_NANOS=TimeUnit.SECONDS.toNanos(60);
    private boolean stopping;
    private long revision;
    public synchronized long revision(){return revision;}
    private void changed(){revision++;notifyAll();}
    public synchronized void awaitRevision(long before,long timeoutMillis)throws Exception{
        expireQueued();
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while(revision==before){long left=deadline-System.nanoTime();if(left<=0)return;TimeUnit.NANOSECONDS.timedWait(this,Math.min(left,TimeUnit.SECONDS.toNanos(1)));expireQueued();}
    }
    public NativeHostChannel(Path root)throws Exception {this(root,true);}
    NativeHostChannel(Path root,boolean allowCreate)throws Exception {this(root,allowCreate,false);}
    NativeHostChannel(Path root,boolean allowCreate,boolean requireIndexed)throws Exception {this(root,allowCreate,requireIndexed,System::nanoTime);}
    NativeHostChannel(Path root,boolean allowCreate,boolean requireIndexed,java.util.function.LongSupplier clock)throws Exception {
        this.clock=clock;
        this.root=root;store=new NativeRequestStore(root,allowCreate);
        if(requireIndexed)store.requireIndexed();
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("Original Native history directory missing; prepare a new host");
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);token=HexFormat.of().formatHex(bytes);
        server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),8);
        workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"bridge-native-host");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(workers);server.createContext("/",this::handle);server.start();
        // Keep the endpoint private and local to one client instance. Restart never replays requests.
    }
    public int port(){return server.getAddress().getPort();}
    String token(){return token;}
    public synchronized Map<String,Object> status()throws Exception{
        expireQueued();return Map.of("connected",fresh(),"contact_fresh",fresh(),"handshake_completed",identity!=null,"client_exited",exited,"identity",identity==null?Map.of():JSON.fromJson(identity,Map.class),"last_seen",lastSeen,"stopping",stopping,"requests",requests.size());
    }
    private boolean fresh(){return identity!=null&&!stopping&&!exited&&clock.getAsLong()-lastContact<CONTACT_TIMEOUT_NANOS;}
    private void expireQueued()throws Exception {
        if(interruptedReason!=null)finishInterrupted();long now=clock.getAsLong();for(var entry:new ArrayList<>(requests.entrySet()))if("queued".equals(entry.getValue().get("state").getAsString())&&now>=queueDeadlines.getOrDefault(entry.getKey(),Long.MAX_VALUE)){
            JsonObject cancelled=entry.getValue().deepCopy();cancelled.addProperty("state","cancelled");cancelled.addProperty("terminal_reason","queue_deadline_before_dispatch");save(cancelled);requests.put(entry.getKey(),cancelled);queueDeadlines.remove(entry.getKey());changed();
        }
    }
    public synchronized void clientExited()throws Exception{
        exited=true;stopping=true;interruptedReason="owned_client_exited";finishInterrupted();changed();
    }
    public synchronized void transportFailed()throws Exception{stopping=true;if(interruptedReason==null)interruptedReason="client_transport_failed";finishInterrupted();changed();}
    private void finishInterrupted()throws Exception{
        for(var entry:new ArrayList<>(requests.entrySet())){String state=entry.getValue().get("state").getAsString();if(!Set.of("queued","delivered").contains(state))continue;
            JsonObject ended=entry.getValue().deepCopy();ended.addProperty("state",state.equals("queued")?"cancelled":"outcome_unknown");ended.addProperty("terminal_reason",interruptedReason);save(ended);requests.put(entry.getKey(),ended);queueDeadlines.remove(entry.getKey());changed();
        }
    }
    public static String requireKey(Map<String,Object> arguments){
        String key=RuntimeExecutionPlan.required(arguments,"idempotency_key");
        if(!key.matches("[A-Za-z0-9_.:-]{1,128}"))throw new IllegalArgumentException("Invalid idempotency_key");return key;
    }
    public synchronized Map<String,Object> submit(String key,String context,String payload,Map<String,Object> parameters) throws Exception {
        return submit(NativeExecutionPlan.wire(key,context,payload,parameters));
    }
    synchronized Map<String,Object> submit(NativeExecutionPlan plan)throws Exception {
        var previous=previous(plan);if(previous.isPresent())return previous.orElseThrow();
        if(stopping)throw new IllegalStateException("Host is stopping");
        if(!fresh())throw new IllegalStateException("Host contact is absent, stale or client exited");
        String key=plan.key();JsonObject body=plan.body();
        String keyHash=hash(key),digest=hash(body.toString());Path claim=root.resolve(keyHash+".json");
        String id=UUID.randomUUID().toString();body.addProperty("request_id",id);body.addProperty("state","queued");body.addProperty("digest",digest);body.addProperty("claim_identity_sha256",hash(keyHash+"\0"+id+"\0"+digest));
        store.claim(keyHash,body);claimedKeys.add(keyHash);claimedIds.put(keyHash,id);requestFiles.put(id,claim);requests.put(id,body);queueDeadlines.put(id,clock.getAsLong()+QUEUE_TIMEOUT_NANOS);changed();return result(id);
    }
    synchronized Optional<Map<String,Object>> previous(NativeExecutionPlan plan)throws Exception {
        String key=hash(plan.key());var previous=readClaim(root,plan);
        if(previous.isPresent()){
            JsonObject body=previous.orElseThrow();String id=body.get("request_id").getAsString();
            if(claimedIds.containsKey(key)&&!id.equals(claimedIds.get(key)))throw new IllegalStateException("Original Native request identity changed");
            if(requests.containsKey(id)&&!root.resolve(key+".json").equals(requestFiles.get(id)))throw new IllegalStateException("Native request belongs to another key");
            claimedKeys.add(key);claimedIds.put(key,id);return Optional.of(requests.containsKey(id)?result(id):resultView(body,true));
        }
        if(claimedKeys.contains(key))throw new IllegalStateException("Original Native claim disappeared; outcome unknown, no repeat");
        return Optional.empty();
    }
    static void requireIndexedHistory(Path root)throws Exception {new NativeRequestStore(root,false).requireIndexed();}
    static Optional<Map<String,Object>> previous(Path root,NativeExecutionPlan plan)throws Exception{return readClaim(root,plan).map(body->resultView(body,true));}
    static Optional<String> findRequest(Path root,NativeExecutionPlan plan)throws Exception{return readClaim(root,plan).map(body->body.get("request_id").getAsString());}
    private static Optional<JsonObject> readClaim(Path root,NativeExecutionPlan plan)throws Exception {
        return new NativeRequestStore(root,false).previous(plan);
    }
    public synchronized Map<String,Object> result(String id) throws Exception {
        expireQueued();
        JsonObject body=requests.get(id);
        store.result(id);return body==null?persistedResult(root,id):resultView(body,false);
    }
    public synchronized boolean batch(String id)throws Exception {JsonObject body=requests.get(id);return body==null?persistedBatch(root,id):"batch".equals(body.get("context").getAsString());}
    public static boolean persistedBatch(Path root,String id)throws Exception{return "batch".equals(persistedBody(root,id).get("context").getAsString());}
    public static Map<String,Object> persistedResult(Path root,String id)throws Exception{return resultView(persistedBody(root,id),true);}
    private static JsonObject persistedBody(Path root,String id)throws Exception {
        return new NativeRequestStore(root,false).result(id);
    }
    private static Map<String,Object> resultView(JsonObject body,boolean recovered){
        String state=body.get("state").getAsString();
        if(recovered&&!Set.of("completed","failed","cancelled").contains(state))state="outcome_unknown";
        var out=new LinkedHashMap<String,Object>();out.put("request_id",body.get("request_id").getAsString());out.put("state",state);out.put("outcome_unknown",state.equals("outcome_unknown"));out.put("terminal",Set.of("completed","failed","cancelled","outcome_unknown").contains(state));
        if(body.has("terminal_reason"))out.put("terminal_reason",body.get("terminal_reason").getAsString());
        if(state.equals("cancelled"))out.put("execution_state","not_dispatched");
        if(body.has("progress")){
            JsonObject progress=body.getAsJsonObject("progress").deepCopy();
            if(body.has("result")){progress.addProperty("reported_steps",progress.getAsJsonArray("value").size());progress.remove("value");}
            out.put("progress",JSON.fromJson(progress,Map.class));
        }
        if(body.has("result"))out.put("execution",JSON.fromJson(body.get("result"),Map.class));return out;
    }
    private synchronized String dispatch(String path,JsonObject data) throws Exception {
        String response=dispatchAccepted(path,data);lastSeen=System.currentTimeMillis();lastContact=clock.getAsLong();return response;
    }
    private String dispatchAccepted(String path,JsonObject data)throws Exception {
        if(exited)throw new IllegalStateException("Owned client exited");expireQueued();
        switch(path){
        case "/hello":
            if(identity!=null&&!identity.equals(data))throw new IllegalStateException("Another client attempted to reuse the host session");
            for(String key:List.of("configuration","user","platform"))if(!data.has(key)||!data.get(key).isJsonPrimitive())throw new IllegalArgumentException("Missing identity "+key);
            identity=data;return "{}";
        case "/poll":
            if(identity==null)throw new IllegalStateException("hello required");
            if(stopping)return "{\"stop\":true}";
            if(requests.values().stream().anyMatch(r->!r.get("context").getAsString().equals("batch")&&r.get("state").getAsString().equals("delivered"))){
                stopping=true;interruptedReason="unresolved_single_delivery";finishInterrupted();changed();return "{\"stop\":true}";
            }
            if(requests.values().stream().anyMatch(r->r.get("state").getAsString().equals("delivered")))return "{}";
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
    static JsonObject captureMetadata(String header){
        if(header==null)return JsonParser.parseString("{\"scope\":\"main_only_legacy\",\"pixel_content_verified\":false}").getAsJsonObject();
        if(header.length()>512)throw new IllegalArgumentException("Capture metadata exceeds limit");
        var data=JsonParser.parseString(header).getAsJsonObject();
        if(!data.keySet().equals(Set.of("scope","window_count","popup_count","armed","pixel_content_verified","captured_at_ms")))throw new IllegalArgumentException("Invalid capture fields");
        if(!data.get("scope").isJsonPrimitive()||!data.get("scope").getAsJsonPrimitive().isString()||!"own_window_composite".equals(data.get("scope").getAsString()))throw new IllegalArgumentException("Invalid capture scope");
        for(String key:List.of("armed","pixel_content_verified"))if(!data.get(key).isJsonPrimitive()||!data.get(key).getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("Invalid capture boolean");
        if(data.get("pixel_content_verified").getAsBoolean())throw new IllegalArgumentException("Pixel verification is external");
        for(String key:List.of("window_count","popup_count","captured_at_ms"))if(!data.get(key).isJsonPrimitive()||!data.get(key).getAsJsonPrimitive().isNumber()||!data.get(key).getAsString().matches("[0-9]{1,16}"))throw new IllegalArgumentException("Invalid capture integer");
        long windows=data.get("window_count").getAsLong(),popups=data.get("popup_count").getAsLong(),at=data.get("captured_at_ms").getAsLong();
        if(windows<1||windows>16||popups!=windows-1||at<1||at>System.currentTimeMillis()+5000||data.get("armed").getAsBoolean()&&popups==0)throw new IllegalArgumentException("Invalid own-window inventory");return data;
    }
    private synchronized String snapshot(byte[] png,String captureHeader)throws Exception {
        if(identity==null||stopping)throw new IllegalStateException("Connected client required");
        var delivered=requests.values().stream().filter(r->r.get("state").getAsString().equals("delivered")).toList();
        if(delivered.size()!=1)throw new IllegalStateException("Screenshot requires exactly one executing request");
        if(png.length<24||!Arrays.equals(Arrays.copyOf(png,8),new byte[]{(byte)137,80,78,71,13,10,26,10})||!Arrays.equals(Arrays.copyOfRange(png,12,16),new byte[]{73,72,68,82}))throw new IllegalArgumentException("PNG required");
        var header=java.nio.ByteBuffer.wrap(png);int width=header.getInt(16),height=header.getInt(20);
        if(width<1||height<1||width>8192||height>8192||(long)width*height>24*1024*1024)throw new IllegalArgumentException("Image dimensions exceed limit");
        var capture=captureMetadata(captureHeader);
        Path folder=root.resolve("snapshots");if(Files.isSymbolicLink(root)||Files.isSymbolicLink(folder))throw new IllegalStateException("Snapshot directory changed");Files.createDirectories(folder);long total=0;int count=0;
        try(var files=Files.list(folder)){for(Path file:files.toList()){if(Files.isSymbolicLink(file))throw new IllegalStateException("Snapshot directory changed");if(file.toString().endsWith(".png")){count++;total+=Files.size(file);}}}
        if(count>=64||total+png.length>128*1024*1024)throw new IllegalStateException("Snapshot quota reached");
        String id=UUID.randomUUID().toString();Path image=folder.resolve(id+".png");Files.write(image,png,StandardOpenOption.CREATE_NEW);
        JsonObject value=new JsonObject();value.addProperty("snapshot_id",id);value.addProperty("image_path",image.toString());value.addProperty("mime_type","image/png");value.addProperty("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)));value.addProperty("width",width);value.addProperty("height",height);value.addProperty("request_id",delivered.get(0).get("request_id").getAsString());value.addProperty("captured_at",capture.has("captured_at_ms")?Instant.ofEpochMilli(capture.get("captured_at_ms").getAsLong()).toString():Instant.now().toString());value.addProperty("upload_received_at",Instant.now().toString());
        value.add("capture",capture);try{persist(folder.resolve(id+".json"),value);}catch(Exception failed){Files.deleteIfExists(image);throw failed;}changed();lastSeen=System.currentTimeMillis();return value.toString();
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
                if(value.has("capture"))row.put("capture",JSON.fromJson(value.get("capture"),Map.class));row.put("verification","capture_receipt_only");out.add(row);
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
            response=capture?snapshot(body,exchange.getRequestHeaders().getFirst("X-Bridge-Capture")):dispatch(exchange.getRequestURI().getPath(),JsonParser.parseString(new String(body,StandardCharsets.UTF_8)).getAsJsonObject());
        }catch(Exception e){status=400;response="{\"error\":\"Request rejected\"}";}
        finally{
            byte[] body=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.sendResponseHeaders(status,body.length);try(var stream=exchange.getResponseBody()){stream.write(body);}exchange.close();
        }
    }
    private void save(JsonObject request) throws Exception {
        String id=request.get("request_id").getAsString();Path path=requestFiles.get(id);
        if(path==null)throw new IllegalStateException("Original Native claim unavailable; preserve unknown outcome");
        store.save(path,request);
    }
    static void persist(Path path,JsonObject body) throws IOException {
        Path temp=path.resolveSibling(path.getFileName()+".tmp");Files.writeString(temp,body.toString(),StandardCharsets.UTF_8);Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    static String hash(String value) throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    public synchronized void freezeForRefresh()throws Exception {
        requireRefreshSafe(root);stopping=true;changed();
    }
    public static void requireRefreshSafe(Path root)throws Exception {
        new NativeRequestStore(root,false).requireRefreshSafe();
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
