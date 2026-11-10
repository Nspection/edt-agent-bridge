// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.io.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.*;
import org.eclipse.core.resources.ResourcesPlugin;
import com.codepilot1c.core.edt.infobase.InfobasePaths;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder.ThickClientMode;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;

/** Stage1 owns its local client and private pipe. Never attaches to an arbitrary listening port. */
public final class DirectTestClientService {
    private static final Gson JSON=new GsonBuilder().serializeNulls().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
    private static final Map<String,Pipe> PIPES=new ConcurrentHashMap<>();
    private static final Map<String,DirectClientJournal> JOURNALS=new ConcurrentHashMap<>();
    private static final Map<String,Object> LOCKS=new ConcurrentHashMap<>();
    private static final List<String> RESOURCES=List.of("worker.py","tables.py","vendor/tc1c.py","vendor/_collection.py","vendor/guids.py","vendor/LICENSE","vendor/SOURCE.json");
    private static final Object START_LOCK=new Object();
    private final Path root;
    private final EdtRuntimeService runtime=new EdtRuntimeService();
    public DirectTestClientService()throws Exception{
        root=ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/direct-test-client");Files.createDirectories(root);
    }
    private Path dir(String id){if(id==null||!id.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid client_id");return root.resolve(id);}
    private Object lock(String id){return LOCKS.computeIfAbsent(id,k->new Object());}
    private Map<String,Object> load(String id)throws Exception {return loadState(dir(id));}
    @SuppressWarnings("unchecked") private static Map<String,Object> loadState(Path folder)throws Exception {
        Path file=folder.resolve("client.json");if(Files.isSymbolicLink(file)||Files.isSymbolicLink(folder)||Files.size(file)>32768)throw new IllegalStateException("Client storage changed");return JSON.fromJson(Files.readString(file,StandardCharsets.UTF_8),LinkedHashMap.class);
    }
    static String reserveDirectory(Path root)throws Exception {
        // Tools instantiate services per request; the reservation lock must span instances.
        synchronized(START_LOCK){
            try(var files=Files.list(root)){long count=0;for(Path path:files.filter(Files::isDirectory).toList()){try{if(!Boolean.TRUE.equals(loadState(path).get("cleaned")))count++;}catch(Exception unavailable){count++;}}if(count>=4)throw new IllegalStateException("Clean up existing direct clients before starting another");}
            String id=UUID.randomUUID().toString().replace("-","");Files.createDirectory(root.resolve(id));return id;
        }
    }
    private void save(String id,Map<String,Object> state)throws Exception{
        Path file=dir(id).resolve("client.json"),tmp=dir(id).resolve("client.tmp");if(Files.isSymbolicLink(file)||Files.isSymbolicLink(tmp))throw new IllegalStateException("Client storage changed");Files.writeString(tmp,JSON.toJson(state),StandardCharsets.UTF_8);Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    private static String required(Map<String,Object> a,String key){return RuntimeExecutionPlan.required(a,key);}
    private static String baseHash(String connection){return ClientBindingVerifier.targetHash(connection);}
    private DirectClientJournal journal(String id)throws Exception{
        DirectClientJournal existing=JOURNALS.get(id);if(existing!=null)return existing;
        synchronized(lock(id)){existing=JOURNALS.get(id);if(existing==null){existing=new DirectClientJournal(dir(id).resolve("operations"));JOURNALS.put(id,existing);}return existing;}
    }
    private void verifyTarget(Map<String,Object> state)throws Exception{
        String project=String.valueOf(state.get("project"));var base=runtime.resolveDefaultInfobase(project);
        if(base==null||!base.getUuid().toString().equals(state.get("base_uuid"))||!baseHash(base.getConnectionString().asConnectionString()).equals(state.get("base_identity_sha256")))throw new IllegalStateException("Project binding changed; no TestClient action dispatched");
        verifyProcess(state);
    }
    private static ProcessHandle verifyProcess(Map<String,Object> state)throws Exception{
        long pid=((Number)state.get("pid")).longValue();var process=ProcessHandle.of(pid).orElseThrow(()->new IllegalStateException("Owned TestClient exited"));
        if(!process.isAlive()||!process.info().startInstant().map(Instant::toString).orElse("").equals(state.get("process_started_at")))throw new IllegalStateException("Owned process identity changed");
        var info=process.info();String executable=info.command().orElseThrow();
        if(!Set.of("1cv8.exe","1cv8c.exe").contains(Path.of(executable).getFileName().toString().toLowerCase(Locale.ROOT)))throw new IllegalStateException("Unsupported owned client executable");
        // One correlated process snapshot supplies both target and port. On Windows the
        // existing WMI helper also checks executable/start time before returning arguments.
        var arguments=info.arguments().map(List::of).orElseGet(()->{try{return ClientBindingVerifier.windowsArguments(pid,executable,info.startInstant().orElseThrow());}catch(Exception e){throw new IllegalStateException("Process arguments unavailable");}});
        if(!ClientBindingVerifier.processTarget(arguments).equals(state.get("base_identity_sha256")))throw new IllegalStateException("Live TestClient targets another base");
        if(!process.isAlive()||!process.info().startInstant().map(Instant::toString).orElse("").equals(state.get("process_started_at")))throw new IllegalStateException("Owned process changed during verification");
        String port=state.get("port").toString();boolean matched=false;
        for(int i=0;i+1<arguments.size();i++)if(arguments.get(i).equalsIgnoreCase("-TPort")&&arguments.get(i+1).equals(port))matched=true;
        if(!matched)throw new IllegalStateException("Owned TestClient port changed");return process;
    }
    public Map<String,Object> start(Map<String,Object> args)throws Exception {
        if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows"))throw new IllegalStateException("Direct TestClient stage1 supports Windows only");
        String project=required(args,"project");var base=runtime.resolveDefaultInfobase(project);if(base==null)throw new IllegalStateException("Bind a permitted test base first");
        if(!Boolean.TRUE.equals(runtime.synchronizationState(project).get("configuration_synchronized")))throw new IllegalStateException("Project/base must be native EQUAL; this command never updates the base");
        try(var selection=PythonEnvironmentService.workspace().select(args)){
        Path python=selection.python();
        String id=reserveDirectory(root);
        var state=new LinkedHashMap<String,Object>();state.put("client_id",id);state.put("project",project);state.put("base_uuid",base.getUuid().toString());state.put("base_identity_sha256",baseHash(base.getConnectionString().asConnectionString()));state.put("state","starting");if(selection.environmentId()!=null)state.put("environment_id",selection.environmentId());save(id,state);
        try{
            Path worker=dir(id).resolve("worker");Files.createDirectory(worker);
            var ownedFiles=new LinkedHashMap<String,String>();state.put("worker_files_sha256",ownedFiles);save(id,state);
            for(String name:RESOURCES){Path file=worker.resolve(name);Files.createDirectories(file.getParent());try(var input=DirectTestClientService.class.getResourceAsStream("/resources/test-client/"+name)){if(input==null)throw new IllegalStateException("Packaged TestClient worker missing");Files.copy(input,file);}ownedFiles.put(name,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));save(id,state);}
            Pipe pipe=new Pipe(python,worker.resolve("worker.py"),dir(id),selection.environmentId()==null?null:python.getParent().getParent().getParent().resolve("temp"));PIPES.put(id,pipe);state.put("worker_pid",pipe.process.pid());state.put("worker_started_at",pipe.process.info().startInstant().orElseThrow().toString());save(id,state);
            var probe=pipe.call(Map.of("action","probe"));if(!Boolean.TRUE.equals(probe.get("success")))throw new IllegalStateException("Python runtime requires pywin32 and cryptography; prepare the explicit isolated runtime first");
            int port;try(var reservation=new ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))){port=reservation.getLocalPort();}
            var info=runtime.resolveThickClientInfo(base);var access=runtime.resolveAccessSettings(base);
            Path executable=info.component().getFile().toPath().resolveSibling("1cv8c.exe");if(!Files.isRegularFile(executable))throw new IllegalStateException("Selected platform has no thin client");
            var version=com.sun.jna.platform.win32.VersionUtil.getFileVersionInfo(executable.toString());int ms=version.dwFileVersionMS.intValue(),ls=version.dwFileVersionLS.intValue();String platform=(ms>>>16)+"."+(ms&65535)+"."+(ls>>>16)+"."+(ls&65535);
            var builder=new RuntimeExecutionCommandBuilder(executable.toFile(),ThickClientMode.ENTERPRISE);builder.forInfobase(base.getConnectionString(),false);
            if(access.isInfobaseAuthentication()){if(access.getUserName()!=null)builder.userName(access.getUserName());if(access.getPassword()!=null)builder.userPassword(access.getPassword());}else builder.osAuthentication(true);
            builder.disableStartupDialogs().disableStartupMessages().logTo(dir(id).resolve("client.log").toFile(),true);
            var command=builder.toProcessBuilder();command.command().addAll(List.of("/TESTCLIENT","-TPort",Integer.toString(port)));if((ms>>>16)==8&&(ms&65535)>=5)command.command().add("/i85");command.directory(dir(id).toFile());command.environment().clear();command.environment().putAll(RunnerEnvironment.isolated(System.getenv()));command.redirectErrorStream(true);command.redirectOutput(dir(id).resolve("process.log").toFile());Process child=command.start();
            state.put("pid",child.pid());state.put("process_started_at",child.info().startInstant().orElseThrow().toString());state.put("port",Integer.toString(port));save(id,state);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);Map<String,Object> connected=null;
            while(System.nanoTime()<deadline){verifyProcess(state);connected=pipe.call(Map.of("action","connect","port",port,"pid",child.pid(),"platform_version",platform));if(Boolean.TRUE.equals(connected.get("success")))break;if(!"rejected".equals(connected.get("state")))throw new IllegalStateException("TestClient handshake failed; inspect original startup");Thread.sleep(250);}
            if(connected==null||!Boolean.TRUE.equals(connected.get("success")))throw new IllegalStateException("Owned TestClient did not become ready");var ready=pipe.call(Map.of("operation","ready"));if(!Boolean.TRUE.equals(ready.get("success")))throw new IllegalStateException("Owned TestClient window readiness timed out; no UI mutation was dispatched");state.put("ui_ready",true);verifyTarget(state);state.put("state","running");state.put("platform_version",platform);save(id,state);return status(id);
        }catch(Exception error){state.put("state","start_failed");save(id,state);try{stop(id);}catch(Exception preserved){error.addSuppressed(preserved);}throw new IllegalStateException("client_id="+id+" start failed: "+error.getMessage(),error);}
        }
    }
    public Map<String,Object> status(String id)throws Exception {
        var state=load(id);var out=new LinkedHashMap<String,Object>();for(String key:List.of("client_id","project","base_uuid","base_identity_sha256","state","platform_version","pid","process_started_at","cleaned","environment_id","ui_ready"))if(state.containsKey(key))out.put(key,state.get(key));out.put("transport","testclient");
        boolean verified=false;if(PIPES.containsKey(id))try{verifyTarget(state);verified=true;}catch(Exception unavailable){}out.put("live_client_binding_verified",verified);Pipe current=PIPES.get(id);out.put("connected",verified&&current!=null&&current.available()&&"running".equals(state.get("state")));return out;
    }
    public Map<String,Object> observe(Map<String,Object> args)throws Exception {
        String id=required(args,"client_id");var state=load(id);if(!"running".equals(state.get("state")))throw new IllegalStateException("Direct client is not running");verifyTarget(state);
        var payload=new LinkedHashMap<>(args);payload.remove("transport");payload.remove("client_id");if(!Set.of("view","fields","snapshot_id","table","columns","wait_timeout_ms").containsAll(payload.keySet()))throw new IllegalArgumentException("Unsupported direct observation field");if("table".equals(payload.get("view"))){required(payload,"table");validateColumns(payload.get("columns"));}else if(payload.containsKey("table")||payload.containsKey("columns"))throw new IllegalArgumentException("table/columns require view=table");if(payload.containsKey("wait_timeout_ms")){boundedInt(payload.get("wait_timeout_ms"),"wait_timeout_ms",0,10000);if(payload.containsKey("snapshot_id"))throw new IllegalArgumentException("Saved details do not wait");}payload.put("operation",payload.containsKey("snapshot_id")?"details":"observe");var result=pipe(id).call(payload);result.put("client_id",id);result.put("live_client_binding_verified",true);return result;
    }
    private Pipe pipe(String id){var pipe=PIPES.get(id);if(pipe==null||!pipe.available())throw new IllegalStateException("Direct pipe unavailable after restart/disconnect; do not replay unknown actions");return pipe;}
    public Map<String,Object> act(Map<String,Object> args)throws Exception {
        String id=required(args,"client_id"),key=required(args,"idempotency_key");var state=load(id);if(!"running".equals(state.get("state")))throw new IllegalStateException("Direct client is not running");
        var payload=new LinkedHashMap<>(args);payload.keySet().removeAll(Set.of("transport","client_id","idempotency_key"));validateAction(payload);verifyTarget(state);pipe(id);payload.put("operation","batch".equals(payload.get("action"))?"batch":"action");
        var journal=journal(id);var claim=journal.claim(key,payload);var record=claim.record();String operation=String.valueOf(record.get("operation_id"));
        if(!claim.created()){if(record.get("result") instanceof Map<?,?>)return present(journal.result(operation),payload,operation);if(record.get("job_id") instanceof String job)return Map.of("job_id",job,"client_id",id,"operation_id",operation,"next_tool","edt_operation_status","reused",true);return journal.result(operation);}
        CountDownLatch submitted=new CountDownLatch(1);var armed=new java.util.concurrent.atomic.AtomicBoolean();String job;
        try{job=BackgroundJobRegistry.getInstance().startJob("test-client-action",()->{
            submitted.await();if(!armed.get())return JSON.toJson(Map.of("success",false,"action_dispatched",false,"outcome_unknown",false,"state","rejected","message","Operation reservation was not persisted"));Map<String,Object> result;
            try{verifyTarget(load(id));}catch(Exception unavailable){var rejected=new LinkedHashMap<String,Object>(Map.of("success",false,"outcome_unknown",false,"action_dispatched",false,"state","rejected","message","Live client binding changed before dispatch"));var completed=journal.read(operation);completed.put("result",rejected);completed.put("state","failed");journal.write(operation,completed);return JSON.toJson(rejected);}
            try{result=pipe(id).call(payload);}catch(Exception unavailable){result=new LinkedHashMap<>(Map.of("success",false,"outcome_unknown",true,"state","outcome_unknown","message","TestClient action outcome is unknown; inspect this operation, never replay it"));}
            var completed=journal.read(operation);completed.put("result",result);completed.put("state",Boolean.TRUE.equals(result.get("outcome_unknown"))?"outcome_unknown":Boolean.TRUE.equals(result.get("success"))?"completed":"failed");journal.write(operation,completed);return JSON.toJson(present(result,payload,operation));
        });record.put("job_id",job);journal.write(operation,record);armed.set(true);}finally{submitted.countDown();}
        return Map.of("job_id",job,"client_id",id,"operation_id",operation,"next_tool","edt_operation_status");
    }
    private static final Set<String> TABLE_ACTIONS=Set.of("table_read","table_select","table_add","table_set");
    private static final Set<String> TABLE_FIELDS=Set.of("table","columns","row_match","cells","max_rows","offset","expect_count","expect_rows");
    private static void textMap(Object value,String name){
        if(!(value instanceof Map<?,?> map)||map.isEmpty()||map.size()>8||map.entrySet().stream().anyMatch(e->!(e.getKey() instanceof String key)||key.isBlank()||key.length()>128||!(e.getValue() instanceof String text)||text.length()>4096))throw new IllegalArgumentException(name+" requires 1..8 named text values");
    }
    private static int boundedInt(Object value,String name,int min,int max){
        if(!(value instanceof Number n))throw new IllegalArgumentException(name+" must be an integer");
        try{int result=new java.math.BigDecimal(n.toString()).intValueExact();if(result<min||result>max)throw new ArithmeticException();return result;}catch(ArithmeticException e){throw new IllegalArgumentException(name+" is outside bounds");}
    }
    private static void validateColumns(Object value){
        if(!(value instanceof List<?> cols)||cols.isEmpty()||cols.size()>8||new HashSet<>(cols).size()!=cols.size()||cols.stream().anyMatch(v->!(v instanceof String name)||name.isBlank()||name.length()>128))throw new IllegalArgumentException("columns requires 1..8 unique element names");
    }
    private static void validateTable(Map<String,Object> args,String action){
        String table=required(args,"table");if(table.length()>128)throw new IllegalArgumentException("Table name exceeds limit");
        if(action.equals("table_read")){
            validateColumns(args.get("columns"));
            if(args.containsKey("cells"))throw new IllegalArgumentException("table_read does not write cells");
            if(args.containsKey("row_match"))textMap(args.get("row_match"),"row_match");
            if(args.containsKey("max_rows"))boundedInt(args.get("max_rows"),"max_rows",1,50);
            if(args.containsKey("offset"))boundedInt(args.get("offset"),"offset",0,1000);
            if(args.containsKey("expect_count"))boundedInt(args.get("expect_count"),"expect_count",0,1000);
            if(args.containsKey("expect_rows")){
                if(!(args.get("expect_rows") instanceof List<?> rows)||rows.size()>50)throw new IllegalArgumentException("expect_rows requires up to50 rows");
                for(Object row:rows){textMap(row,"expect_rows");if(!((Map<?,?>)row).keySet().equals(new HashSet<>((List<?>)args.get("columns"))))throw new IllegalArgumentException("Expected row keys must match columns exactly");}
            }
        }else{
            if(args.keySet().stream().anyMatch(k->Set.of("columns","max_rows","offset","expect_count","expect_rows").contains(k)))throw new IllegalArgumentException("Read options require table_read");
            if(action.equals("table_add")){if(args.containsKey("row_match"))throw new IllegalArgumentException("table_add does not select an existing row");}
            else textMap(args.get("row_match"),"row_match");
            if(action.equals("table_select")){if(args.containsKey("cells"))throw new IllegalArgumentException("table_select does not write cells");}
            else {textMap(args.get("cells"),"cells");if(((Map<?,?>)args.get("cells")).values().stream().anyMatch(v->"".equals(v)))throw new IllegalArgumentException("Empty table-cell writes are not supported; native null cannot prove cleared text");}
        }
        if(args.keySet().stream().anyMatch(k->Set.of("element","value","navigation_link","steps").contains(k)))throw new IllegalArgumentException("Field does not apply to table action");
    }
    public static void validateAction(Map<String,Object> args){
        if(JSON.toJson(args).getBytes(StandardCharsets.UTF_8).length>128*1024)throw new IllegalArgumentException("Action exceeds 128 KiB");
        var allowed=new HashSet<>(Set.of("action","expected_window","expected_form","snapshot_id","element","value","navigation_link","expect","steps","wait_timeout_ms","response_mode"));allowed.addAll(TABLE_FIELDS);
        if(!allowed.containsAll(args.keySet()))throw new IllegalArgumentException("Unsupported direct action field");
        if(!(args.get("expected_window") instanceof String window)||window.length()>1024)throw new IllegalArgumentException("Exact expected_window is required");required(args,"snapshot_id");String action=required(args,"action");
        if(args.containsKey("wait_timeout_ms"))boundedInt(args.get("wait_timeout_ms"),"wait_timeout_ms",0,10000);
        if(args.containsKey("response_mode")&&!Set.of("full","summary").contains(args.get("response_mode")))throw new IllegalArgumentException("response_mode must be full or summary");
        if(args.containsKey("expected_form")&&!(args.get("expected_form") instanceof String))throw new IllegalArgumentException("expected_form must be text");
        if(action.equals("batch")){
            if(!Set.of("action","expected_window","expected_form","snapshot_id","steps","response_mode").containsAll(args.keySet()))throw new IllegalArgumentException("Batch-level fields do not execute actions; use explicit steps");
            if(!(args.get("steps") instanceof List<?> steps)||steps.isEmpty()||steps.size()>16)throw new IllegalArgumentException("Batch requires 1..16 steps");
            for(Object item:steps){if(!(item instanceof Map<?,?> map))throw new IllegalArgumentException("Invalid batch step");var step=new LinkedHashMap<String,Object>();map.forEach((k,v)->step.put(String.valueOf(k),v));if(step.containsKey("snapshot_id")||step.containsKey("response_mode")||step.containsKey("steps")||"batch".equals(step.get("action")))throw new IllegalArgumentException("Unsupported direct batch step");step.put("snapshot_id",args.get("snapshot_id"));validateAction(step);}return;
        }
        if(TABLE_ACTIONS.contains(action))validateTable(args,action);
        else{
            if(!Set.of("open","close","input","command","wait").contains(action))throw new IllegalArgumentException("Unsupported direct action");
            if(args.keySet().stream().anyMatch(TABLE_FIELDS::contains)||args.containsKey("steps")||args.containsKey("navigation_link")&&!action.equals("open")||args.containsKey("value")&&!action.equals("input")||args.containsKey("element")&&!Set.of("input","command").contains(action))throw new IllegalArgumentException("Field does not apply to this direct action");
            if(action.equals("open")){String link=required(args,"navigation_link");if(!link.matches("e1cib/(list|app|data)/[^\\s\\x00-\\x1f]+")||link.length()>2048)throw new IllegalArgumentException("Use an in-base navigation link");}
            if(Set.of("input","command").contains(action))required(args,"element");
            if(action.equals("input")&&(!(args.get("value") instanceof String text)||text.length()>4096))throw new IllegalArgumentException("Input requires text up to 4096 characters");
        }
        if("wait".equals(action)&&args.get("expect") instanceof Map<?,?> e&&e.containsKey("window")&&!window.equals(e.get("window")))throw new IllegalArgumentException("Wait window conditions conflict");
        if(args.get("expect") instanceof Map<?,?> e&&e.containsKey("elements")){
            if(!(e.get("elements") instanceof Map<?,?> elements)||elements.isEmpty()||elements.size()>16)throw new IllegalArgumentException("expect.elements requires 1..16 exact names");
            for(var entry:elements.entrySet())if(!(entry.getKey() instanceof String name)||name.isBlank()||name.length()>128||!(entry.getValue() instanceof Map<?,?> states)||states.isEmpty()||!Set.of("visible","enabled","readonly").containsAll(states.keySet())||states.values().stream().anyMatch(v->!(v instanceof Boolean)))throw new IllegalArgumentException("Element conditions require exact boolean visible/enabled/readonly states");
        }
        if(args.containsKey("expect")){if(!(args.get("expect") instanceof Map<?,?> expected)||!Set.of("window","fields","elements").containsAll(expected.keySet())||expected.isEmpty())throw new IllegalArgumentException("Invalid expect");if(expected.containsKey("window")&&!(expected.get("window") instanceof String))throw new IllegalArgumentException("Expected window must be text");if(expected.containsKey("fields")){if(!(expected.get("fields") instanceof Map<?,?> fields)||fields.isEmpty()||fields.size()>16||fields.entrySet().stream().anyMatch(e->!(e.getKey() instanceof String)||!(e.getValue() instanceof String)))throw new IllegalArgumentException("Expected fields must be 1..16 exact text values");}}
    }
    static Map<String,Object> present(Map<String,Object> full,Map<String,Object> request,String operation){
        if(!"summary".equals(request.get("response_mode")))return full;
        var out=new LinkedHashMap<String,Object>();
        for(String key:List.of("success","state","outcome_unknown","action_dispatched","postcondition_verified","message","reason","stage","failed_step_index","wait"))if(full.containsKey(key))out.put(key,full.get(key));
        out.put("operation_id",operation);out.put("details_tool","edt_test_client");out.put("details_action","result");
        Object results=full.get("results");var steps=new ArrayList<Map<String,Object>>();
        List<?> entries="batch".equals(request.get("action"))?(results instanceof List<?> list?list:List.of()):List.of(full);
        for(Object entry:entries)if(entry instanceof Map<?,?> step){var summary=new LinkedHashMap<String,Object>();for(String key:List.of("step_index","action","success","state","action_dispatched","postcondition_verified","snapshot_id","wait"))if(step.containsKey(key))summary.put(key,step.get(key));if(!"batch".equals(request.get("action"))){summary.putIfAbsent("step_index",0);summary.putIfAbsent("action",request.get("action"));}steps.add(summary);}
        out.put("steps",steps);out.put("finished_steps",steps.size());out.put("passed_steps",steps.stream().filter(s->Boolean.TRUE.equals(s.get("success"))).count());out.put("verified_steps",steps.stream().filter(s->Boolean.TRUE.equals(s.get("postcondition_verified"))).count());if(request.get("steps") instanceof List<?> requested)out.put("total_steps",requested.size());else out.put("total_steps",1);
        if(out.get("failed_step_index")==null)for(var step:steps)if(!Boolean.TRUE.equals(step.get("success"))){out.put("failed_step_index",step.get("step_index"));break;}
        if(full.containsKey("window"))out.put("window",full.get("window"));
        if(full.containsKey("snapshot_id"))out.put("snapshot_id",full.get("snapshot_id"));return out;
    }
    public Map<String,Object> result(String id,String operation)throws Exception {if(Boolean.TRUE.equals(load(id).get("cleaned")))throw new IllegalStateException("Cleanup removed operation details");return journal(id).result(operation);}
    public Map<String,Object> stop(String id)throws Exception {
        var state=load(id);if(Boolean.TRUE.equals(state.get("cleaned")))return status(id);var pipe=PIPES.remove(id);if(pipe!=null)pipe.close();
        if(state.containsKey("pid")){var handle=ProcessHandle.of(((Number)state.get("pid")).longValue());if(handle.isPresent()&&handle.get().isAlive()){var owned=verifyProcess(state);owned.destroy();try{owned.onExit().get(4,TimeUnit.SECONDS);}catch(TimeoutException timeout){owned.destroyForcibly();owned.onExit().get(4,TimeUnit.SECONDS);}}}
        state.put("state","stopped");save(id,state);return status(id);
    }
    public Map<String,Object> cleanup(String id)throws Exception {
        stop(id);var state=load(id);Path folder=dir(id);List<Path> paths;try(var files=Files.walk(folder)){paths=files.limit(300).toList();}if(paths.size()>=300)throw new IllegalStateException("Unexpected client storage; preserve for review");
        for(Path file:paths){
            if(Files.isSymbolicLink(file)||!file.toRealPath().startsWith(folder.toRealPath()))throw new IllegalStateException("Client storage changed; preserve for review");
            if(!Files.isRegularFile(file))continue;String relative=folder.relativize(file).toString().replace('\\','/');
            if(relative.startsWith("worker/")){
                String name=relative.substring(7);if(Files.size(file)>1024*1024)throw new IllegalStateException("Owned worker size changed");if(!(state.get("worker_files_sha256") instanceof Map<?,?> hashes)||!Objects.equals(hashes.get(name),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))))throw new IllegalStateException("Owned worker changed; preserve for review: "+name);
            }else if(!Set.of("client.json","client.tmp","worker.log","client.log","process.log").contains(relative)&&!relative.matches("operations/[a-f0-9]{64}\\.(json|tmp)"))throw new IllegalStateException("Unknown owned-client file; preserve for review");
        }
        for(Path file:paths.stream().sorted(Comparator.reverseOrder()).toList())if(!file.equals(folder)&&!file.equals(folder.resolve("client.json")))deleteOwned(file);
        var tombstone=new LinkedHashMap<String,Object>();for(String key:List.of("client_id","project","base_uuid"))tombstone.put(key,state.get(key));tombstone.put("state","cleaned");tombstone.put("cleaned",true);save(id,tombstone);JOURNALS.remove(id);return status(id);
    }
    private static void deleteOwned(Path file)throws Exception{
        for(int attempt=0;;attempt++)try{Files.deleteIfExists(file);return;}catch(java.nio.file.FileSystemException locked){if(attempt>=20)throw locked;Thread.sleep(100);}
    }
    static boolean pythonEnvironmentInUse(String environmentId){
        try{var service=new DirectTestClientService();try(var paths=Files.list(service.root)){
            for(Path path:paths.filter(Files::isDirectory).toList()){
                var state=loadState(path);if(!environmentId.equals(state.get("environment_id")))continue;
                if(!Set.of("stopped","cleaned").contains(state.get("state")))return true;
                if(state.get("worker_pid") instanceof Number pid){var process=ProcessHandle.of(pid.longValue());if(process.isPresent()&&process.get().isAlive()&&process.get().info().startInstant().map(Instant::toString).orElse("").equals(state.get("worker_started_at")))return true;}
            }
        }return false;}catch(Exception unavailable){return true;}
    }
    public static void shutdown(){
        for(var pipe:PIPES.values())pipe.close();PIPES.clear();
        try{var service=new DirectTestClientService();try(var dirs=Files.list(service.root)){for(Path path:dirs.filter(Files::isDirectory).toList())try{service.stop(path.getFileName().toString());}catch(Exception preserved){/* Keep ownership evidence; never terminate a different process. */}}}catch(Exception preserved){}
    }
    private static final class Pipe implements AutoCloseable {
        final Process process;final ExecutorService reader;final InputStream input;volatile boolean closed;
        Pipe(Path python,Path script,Path directory,Path runtimeTemp)throws Exception {
            var builder=new ProcessBuilder(python.toString(),"-I","-B","-u",script.toString());builder.directory(directory.toFile());builder.environment().clear();builder.environment().putAll(RunnerEnvironment.isolated(System.getenv()));if(runtimeTemp!=null){builder.environment().put("TEMP",runtimeTemp.toString());builder.environment().put("TMP",runtimeTemp.toString());}builder.redirectError(directory.resolve("worker.log").toFile());process=builder.start();input=process.getInputStream();reader=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"bridge-test-client-pipe");t.setDaemon(true);return t;});
        }
        synchronized Map<String,Object> call(Map<String,Object> request)throws Exception {
            if(closed||!process.isAlive())throw new IllegalStateException("Direct worker disconnected");byte[] bytes=JSON.toJson(request).getBytes(StandardCharsets.UTF_8);if(bytes.length>128*1024)throw new IllegalArgumentException("Direct request exceeds limit");
            try{
            process.getOutputStream().write(bytes);process.getOutputStream().write('\n');process.getOutputStream().flush();
            Future<String> reply=reader.submit(()->{var out=new ByteArrayOutputStream();for(int n;(n=input.read())!=-1;){if(n=='\n')return out.toString(StandardCharsets.UTF_8);if(out.size()>=256*1024)throw new IOException("Direct reply exceeds limit");out.write(n);}throw new EOFException("Direct worker closed");});
            @SuppressWarnings("unchecked") Map<String,Object> value=JSON.fromJson(reply.get(35,TimeUnit.SECONDS),LinkedHashMap.class);if(value==null)throw new IOException("Invalid worker result");if("outcome_unknown".equals(value.get("state"))||"transport_failed".equals(value.get("state")))close();return value;}catch(Exception unavailable){close();throw unavailable;}
        }
        boolean available(){return !closed&&process.isAlive();}
        public void close(){closed=true;process.destroy();try{input.close();process.getOutputStream().close();if(!process.waitFor(3,TimeUnit.SECONDS))process.destroyForcibly();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(IOException ignored){}reader.shutdownNow();}
    }
}
