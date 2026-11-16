// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import com.google.gson.*;
import org.eclipse.core.resources.ResourcesPlugin;
import org.osgi.framework.FrameworkUtil;

/** Durable receipts, bounded workers and callback-based human input. No implicit replay. */
public final class BugReportRequests {
    @FunctionalInterface interface Dispatch {void run(Runnable work);}
    private static final String PROCESS=UUID.randomUUID().toString();
    private static final Object[] LOCKS=new Object[64];
    private static final java.util.concurrent.locks.ReentrantLock[] RESERVATIONS=new java.util.concurrent.locks.ReentrantLock[16];
    static {Arrays.setAll(LOCKS,i->new Object());Arrays.setAll(RESERVATIONS,i->new java.util.concurrent.locks.ReentrantLock());}
    private static final Object POOL_GATE=new Object();private static Pool pool;
    private static final class Pool implements AutoCloseable {
        final long epoch;final ThreadPoolExecutor workers;final ExecutorService completions;
        Pool(long epoch){this.epoch=epoch;workers=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),work->{Thread t=new Thread(work,"bridge-bug-report");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());workers.allowCoreThreadTimeOut(true);/* At most 64 active receipts per workspace, each future completes once. This mailbox is independent of the 16-slot work queue. */completions=Executors.newSingleThreadExecutor(work->{Thread t=new Thread(work,"bridge-report-receipt");t.setDaemon(true);return t;});}
        public void close(){workers.shutdownNow();completions.shutdownNow();}
    }
    private static void dispatch(long epoch,Runnable work){
        var lifecycle=RuntimeProcessLifecycle.global();lifecycle.require(epoch);
        synchronized(POOL_GATE){if(pool==null||pool.epoch!=epoch||pool.workers.isShutdown())try{pool=lifecycle.create(epoch,"bug-report-requests/executor",()->new Pool(epoch));}catch(Exception failure){throw new RejectedExecutionException("Report executor unavailable");}pool.workers.execute(work);}
    }
    private static void complete(long epoch,Runnable work){
        RuntimeProcessLifecycle.global().require(epoch);
        synchronized(POOL_GATE){if(pool==null||pool.epoch!=epoch)throw new RejectedExecutionException("Report lifecycle ended");pool.completions.execute(work);}
    }
    private final Path root,history;private final BugReportRecordStore records;private final Dispatch dispatch,completion;private final String owner;private final long epoch;
    public static BugReportRequests workspace()throws Exception{
        var path=ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/bug-reports/requests");long epoch=RuntimeProcessLifecycle.global().epoch();
        return new BugReportRequests(path,work->dispatch(epoch,work),work->complete(epoch,work),epoch);
    }
    BugReportRequests(Path root,Dispatch dispatch)throws Exception{this(root,dispatch,dispatch);}
    BugReportRequests(Path root,Dispatch dispatch,Dispatch completion)throws Exception{this(root,dispatch,completion,RuntimeProcessLifecycle.global().epoch());}
    BugReportRequests(Path root,Dispatch dispatch,Dispatch completion,long epoch)throws Exception{this.epoch=epoch;this.completion=completion;this.root=root.toAbsolutePath().normalize();records=new BugReportRecordStore(this.root);history=this.root.resolve("history");this.dispatch=dispatch;owner=PROCESS+":"+epoch;Files.createDirectories(this.root);if(Files.isSymbolicLink(this.root)||Files.isSymbolicLink(history))throw new IllegalStateException("Report request storage changed");Files.createDirectories(history);recoverStale();}
    private void recoverStale()throws Exception{
        java.util.List<Path> files;try(var entries=Files.list(root)){files=entries.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).limit(256).toList();}
        for(Path file:files){String id=file.getFileName().toString().substring(0,64);synchronized(lock(id)){try{var row=read(id);if(terminal(row))archiveTerminal(id,row);else recover(id,row);}catch(BugReportRecordStore.Unavailable unavailable){/* Permanent ID barrier, no automatic replay. */}catch(RuntimeException|java.io.IOException corrupt){records.preserve(file,id,corrupt);}}}
    }
    private Path file(String id){if(id==null||!id.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid request_id");return root.resolve(id+".json");}
    private Object lock(String id){return LOCKS[Math.floorMod(file(id).hashCode(),LOCKS.length)];}
    private static boolean terminal(JsonObject row){return !Set.of("queued","running").contains(row.get("state").getAsString());}
    private JsonObject read(String id)throws Exception{
        if(records.blocked(id))return AgentResponses.JSON.toJsonTree(BugReportRecordStore.unavailable("request_id",id)).getAsJsonObject();
        Path file=file(id);boolean archived=Files.exists(history.resolve(id+".json"),LinkOption.NOFOLLOW_LINKS);if(archived)file=history.resolve(id+".json");
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))throw new java.nio.file.NoSuchFileException("Request ID not found");
        try{
            if(Files.isSymbolicLink(file)||Files.size(file)>262144)throw new IllegalStateException("Report request size limit");var row=NativeHostState.read(file);
            if(!id.equals(row.get("request_id").getAsString())||!Set.of("queued","running","completed","cancelled","failed","interrupted").contains(row.get("state").getAsString()))throw new IllegalStateException("Report request identity/state changed");
            if(!row.get("payload_sha256").getAsString().matches("[a-f0-9]{64}")||!row.get("kind").getAsString().matches("[a-z_]{1,40}"))throw new IllegalStateException("Invalid report request payload identity");java.time.Instant.parse(row.get("created_at").getAsString());
            for(String slot:List.of("request_id","report_id","kind","state","phase","reason","error_type","created_at","owner","payload_sha256"))if(row.has(slot)&&(!row.get(slot).isJsonPrimitive()||!row.get(slot).getAsJsonPrimitive().isString()))throw new IllegalStateException("Invalid request field type");
            if(row.has("result")&&!row.get("result").isJsonObject())throw new IllegalStateException("Invalid request result type");
            if(archived&&(!terminal(row)||Files.exists(file(id),LinkOption.NOFOLLOW_LINKS)))throw new IllegalStateException("Conflicting or nonterminal request history");
            if(!terminal(row)){String owner=row.get("owner").getAsString();if(!owner.matches("[a-f0-9-]{36}(?::[0-9]{1,18})?"))throw new IllegalStateException("Invalid report request owner");}
            return row;
        }catch(RuntimeException|java.io.IOException corrupt){records.preserve(file,id,corrupt);return AgentResponses.JSON.toJsonTree(BugReportRecordStore.unavailable("request_id",id)).getAsJsonObject();}
    }

    private void write(String id,JsonObject row)throws Exception{
        if(row.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144)throw new IllegalStateException("Report request size limit");
        Path file=file(id);NativeHostState.write(file,row);
        if(terminal(row))archiveTerminal(id,row);
    }
    private void archiveTerminal(String id,JsonObject row)throws Exception{
        Path source=file(id);if(!Files.exists(source,LinkOption.NOFOLLOW_LINKS))return;
        if(Files.isSymbolicLink(history))throw new IllegalStateException("Report history changed");Path target=history.resolve(id+".json");
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){if(!NativeHostState.read(target).equals(row))throw new IllegalStateException("Conflicting report receipt; preserve for review");Files.delete(source);}
        else Files.move(source,target,StandardCopyOption.ATOMIC_MOVE);
    }
    private Map<String,Object> start(String kind,String key,Map<String,Object> payload,RunnableFactory factory)throws Exception{
        if(key==null||!key.matches("[A-Za-z0-9_.:-]{1,128}"))throw new IllegalArgumentException("A stable idempotency_key is required");
        String id=DirectClientJournal.hash(key),digest=TaskSessionService.fingerprint(Map.of("kind",kind,"payload",payload));
        var reservation=RESERVATIONS[Math.floorMod(root.hashCode(),RESERVATIONS.length)];if(!reservation.tryLock(1,TimeUnit.SECONDS))throw new TimeoutException("Report request reservation busy");
        try{synchronized(lock(id)){
            if(records.blocked(id))return view(read(id));
            if(Files.exists(file(id))||Files.exists(history.resolve(id+".json"))){var prior=read(id);if(records.blocked(id))return view(prior);if(!digest.equals(prior.get("payload_sha256").getAsString()))throw new IllegalArgumentException("Request key has another payload");return view(recover(id,prior));}
            try(var files=Files.list(root)){if(files.filter(p->p.getFileName().toString().endsWith(".json")).limit(65).count()>=64)throw new IllegalStateException("Too many active report requests");}
            var row=new JsonObject();row.addProperty("request_id",id);row.addProperty("payload_sha256",digest);row.addProperty("kind",kind);row.addProperty("owner",owner);row.addProperty("state","queued");row.addProperty("phase",kind.equals("auth")||kind.equals("review")?"awaiting_user":"local_or_network_work");row.addProperty("created_at",java.time.Instant.now().toString());
            if(payload.containsKey("report_id"))row.addProperty("report_id",Objects.toString(payload.get("report_id")));write(id,row);
            try{dispatch.run(factory.create(id));}catch(RuntimeException busy){row.addProperty("state","failed");row.addProperty("reason","request_queue_busy_not_started");write(id,row);}
            return view(read(id));
        }}finally{reservation.unlock();}
    }
    @FunctionalInterface private interface RunnableFactory {Runnable create(String id);}
    public Map<String,Object> start(String kind,String key,Map<String,Object> payload,Callable<Map<String,Object>> work)throws Exception{return start(kind,key,payload,id->()->{
        try{if(!running(id))return;finish(id,work.call(),null);}catch(Exception|LinkageError failure){finish(id,null,failure);}
    });}
    public Map<String,Object> startUi(String kind,String key,Map<String,Object> payload,Supplier<CompletableFuture<Map<String,Object>>> work)throws Exception{return start(kind,key,payload,id->()->{
        try{if(!running(id))return;work.get().whenComplete((result,failure)->{try{completion.run(()->finish(id,result,failure));}catch(RuntimeException closed){/* Only lifecycle shutdown may reject receipt completion; epoch recovery never replays. */}});}catch(Exception|LinkageError failure){finish(id,null,failure);}
    });}
    private boolean running(String id)throws Exception{synchronized(lock(id)){var row=read(id);if(!"queued".equals(row.get("state").getAsString())||!owner.equals(row.get("owner").getAsString()))return false;row.addProperty("state","running");write(id,row);return true;}}
    private void finish(String id,Map<String,Object> result,Throwable failure){
        if(result==null&&failure==null)failure=new IllegalStateException("Empty report response");
        try{synchronized(lock(id)){var row=read(id);if(!"running".equals(row.get("state").getAsString())||!owner.equals(row.get("owner").getAsString()))return;
            if(failure==null){row.addProperty("state",Set.of("cancelled","declined").contains(Objects.toString(result.get("state")))?"cancelled":"completed");row.add("result",AgentResponses.JSON.toJsonTree(result));}
            else{row.addProperty("state",failure instanceof CancellationException||failure instanceof InterruptedException?"cancelled":"failed");row.addProperty("reason","report_action_failed");row.addProperty("error_type",failure.getClass().getSimpleName());}
            write(id,row);
        }}catch(Exception persistence){try{synchronized(lock(id)){var row=read(id);if(!terminal(row)){row.remove("result");row.addProperty("state","failed");row.addProperty("reason","result_persistence_failed");write(id,row);}}}catch(Exception unavailable){/* Preserve the last durable phase; no implicit replay. */}}
        if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
    }
    private boolean olderOwner(String other){if(owner.equals(other))return false;if(!other.startsWith(PROCESS+":"))return true;try{return Long.parseLong(other.substring(PROCESS.length()+1))<epoch;}catch(NumberFormatException invalid){return true;}}
    private JsonObject recover(String id,JsonObject row)throws Exception{if(!terminal(row)&&olderOwner(row.get("owner").getAsString())){row.addProperty("state","interrupted");row.addProperty("reason","edt_restarted_no_automatic_replay");write(id,row);}return row;}
    public Map<String,Object> status(String id)throws Exception{synchronized(lock(id)){if(!records.blocked(id)&&!Files.exists(file(id),LinkOption.NOFOLLOW_LINKS)&&!Files.exists(history.resolve(id+".json"),LinkOption.NOFOLLOW_LINKS))return Map.of("request_id",id,"state","not_found","terminal",true,"reason","request_not_found","automatic_replay",false);return view(recover(id,read(id)));}}
    private static Map<String,Object> view(JsonObject row){
        var out=new LinkedHashMap<String,Object>();for(String key:List.of("request_id","report_id","kind","state","phase","reason","error_type","created_at"))if(row.has(key))out.put(key,row.get(key).getAsString());out.put("terminal",terminal(row));out.put("automatic_replay",false);if(row.has("requires_manual_recovery"))out.put("requires_manual_recovery",row.get("requires_manual_recovery").getAsBoolean());if(row.has("result"))out.put("result",AgentResponses.JSON.fromJson(row.get("result"),Map.class));
        if(!terminal(row))out.put("next_call",call(Map.of("action","request_status","request_id",row.get("request_id").getAsString())));else if(row.has("report_id")&&!row.has("result"))out.put("next_call",call(Map.of("action","status","report_id",row.get("report_id").getAsString())));return out;
    }
    public static Map<String,Object> call(Map<String,Object> args){return Map.of("tool","edt.edit","arguments",Map.of("op","edt_bug_report","args",args));}
    public static CompletableFuture<Map<String,Object>> interact(String kind,String method,String id,String hash){
        try{var bundle=FrameworkUtil.getBundle(BugReportRequests.class);if(bundle==null)throw new IllegalStateException("EDT UI unavailable");var context=bundle.getBundleContext();var reference=context.getServiceReference(BugReportInteraction.class);if(reference==null)throw new IllegalStateException("EDT UI unavailable");var ui=context.getService(reference);if(ui==null){context.ungetService(reference);throw new IllegalStateException("EDT UI unavailable");}
            boolean handed=false;try{var pending=kind.equals("auth")?ui.login(method):ui.review(id,hash);pending.whenComplete((result,failure)->{try{context.ungetService(reference);}catch(IllegalStateException stopped){/* Workbench ended. */}});handed=true;return pending;}finally{if(!handed)try{context.ungetService(reference);}catch(IllegalStateException stopped){}}
        }catch(Exception|LinkageError unavailable){return CompletableFuture.completedFuture(Map.of("state","ui_unavailable","reason","open_edt_workbench"));}
    }
}
