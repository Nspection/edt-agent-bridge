package com.codepilot1c.core.edt.development;
import java.net.URI;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.eclipse.core.resources.ResourcesPlugin;
import com.google.gson.*;
import com.codepilot1c.core.mcp.client.McpClient;
import com.codepilot1c.core.mcp.model.*;
import com.codepilot1c.core.mcp.transport.McpStreamableHttpTransport;
import com.codepilot1c.core.mcp.transport.McpHttpException;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;

/** Reuses Vanessa's semantic TestClient API; OS-input/clipboard/UIAutomation tools are not exposed. */
public final class VanessaMcpBridge {
    public static final Set<String> READ=Set.of("get_va_settings","search_for_steps_by_keywords","frequently_used_steps","check_syntax","get_active_window_data","get_window_list_testclient","get_form_analysis","get_form_element_data","get_table_data","find_form_elements","get_active_form_element","get_test_results","get_vanessa_automation_state","get_window_list_os","get_window_screenshot_os","get_form_element_steps");
    public static final Set<String> WRITE=Set.of("manage_va_settings","manage_form_elements","execute_form_actions","window_management","manage_command_interface","manage_test_client","manage_test_client_profiles","load_features","open_feature_file","run_scenario","stop_scenario","close_test_client");
    private static final VanessaMcpBridge INSTANCE=new VanessaMcpBridge();
    public static VanessaMcpBridge getInstance(){return INSTANCE;}
    private final Map<String,McpClient> connections=new LinkedHashMap<>();private final Map<String,Reservation> actions=new LinkedHashMap<>();
    private record Reservation(String fingerprint,String jobId){}
    private static final Gson GSON=new Gson();
    static String localEndpoint(String text) {
        URI uri=URI.create(text);String host=uri.getHost();
        if(!"http".equals(uri.getScheme()) || host==null || !Set.of("localhost","127.0.0.1","[::1]","::1").contains(host.toLowerCase(Locale.ROOT)) || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !Set.of("/mcp","/mcp/").contains(uri.getPath()) || uri.getPort()<1 || uri.getPort()>65535){throw new IllegalArgumentException("Use an explicit loopback http://127.0.0.1:port/mcp endpoint, or a configured server_id for authenticated connections");}
        return uri.toString();
    }
    private synchronized McpClient client(String endpoint,String serverId) throws Exception {
        if(serverId!=null && !serverId.isBlank()){throw new IllegalArgumentException("Generic server_id connections are not available in EDT Agent Bridge; use the dedicated local Vanessa endpoint");}
        String url=localEndpoint(endpoint==null?"http://127.0.0.1:9874/mcp":endpoint);McpClient existing=connections.get(url);
        if(existing!=null && existing.isConnected()){return existing;}
        if(existing!=null){connections.remove(url);try{existing.close();}catch(Exception ignored){/* Discard a stale transport. */}}
        if(connections.size()>=4){throw new IllegalStateException("Runtime endpoint cache is full; reuse a configured connection");}
        var transport=new McpStreamableHttpTransport(url,Map.of(),null,120000,false,true);transport.connect();McpClient created=new McpClient("CodePilot-Vanessa",transport);
        try{created.initialize().get(20,TimeUnit.SECONDS);}catch(Exception error){created.close();throw error;}
        connections.put(url,created);return created;
    }
    @FunctionalInterface interface ReadCall<T>{T call(McpClient client) throws Exception;}
    static boolean isExpiredSession(Throwable error) {
        for(Throwable current=error;current!=null;current=current.getCause()){
            if(current instanceof McpHttpException http && Set.of(404,410,422).contains(http.getStatusCode())){return true;}
        }
        return false;
    }
    private <T>T read(String endpoint,String serverId,ReadCall<T> work) throws Exception {
        try{return work.call(client(endpoint,serverId));}
        catch(Exception error){
            if(serverId!=null && !serverId.isBlank() || !isExpiredSession(error)){throw error;}
            String key=localEndpoint(endpoint==null?"http://127.0.0.1:9874/mcp":endpoint);
            synchronized(this){McpClient stale=connections.remove(key);if(stale!=null){try{stale.close();}catch(Exception ignored){/* Recreate an expired read session. */}}}
            try{return work.call(client(endpoint,serverId));}catch(Exception second){second.addSuppressed(error);throw second;}
        }
    }
    public Map<String,Object> verifyClient(String endpoint,String expectedHash)throws Exception {
        return read(endpoint,null,client->{synchronized(client){return ClientBindingVerifier.verify(expectedHash,new ClientBindingVerifier.Backend(){
            public Map<String,Object> state()throws Exception {
                var result=invoke(client,"get_vanessa_automation_state",Map.of("format","json"));
                if(!Boolean.TRUE.equals(result.get("success")))throw new IllegalStateException("Client state unavailable");
                if(result.get("content") instanceof List<?> items && items.size()==1 && items.get(0) instanceof Map<?,?> item && item.get("data") instanceof JsonObject data)
                    return Map.of("testClientConnected",data.has("testClientConnected")&&data.get("testClientConnected").getAsBoolean(),"testClientProfile",data.has("testClientProfile")?data.get("testClientProfile").getAsString():"");
                throw new IllegalStateException("Unsupported client state response");
            }
            public String profiles()throws Exception {
                var result=invoke(client,"manage_test_client_profiles",Map.of("action","get_list"));
                if(!Boolean.TRUE.equals(result.get("success")))throw new IllegalStateException("Client profiles unavailable");
                if(result.get("content") instanceof List<?> items && items.size()==1 && items.get(0) instanceof Map<?,?> item && item.get("text") instanceof String text)return text;
                throw new IllegalStateException("Unsupported profiles response");
            }
            public Map<String,Object> process(long pid)throws Exception{return ClientBindingVerifier.localProcess(pid); }
        });}});
    }
    public Map<String,Object> catalog(String endpoint,String serverId) throws Exception {
        List<Map<String,Object>> result=new ArrayList<>();
        for(McpTool tool:read(endpoint,serverId,c->c.listTools().get(15,TimeUnit.SECONDS))) {
            if(!READ.contains(tool.getName()) && !WRITE.contains(tool.getName())){continue;}
            Map<String,Object> row=new LinkedHashMap<>();row.put("name",tool.getName());row.put("mutating",WRITE.contains(tool.getName()));row.put("description",tool.getDescription());row.put("schema",tool.getInputSchema());result.add(row);
        }
        return Map.of("success",!result.isEmpty(),"ready",!result.isEmpty(),"tools",result,"strategy","vanessa-native-mcp","prerequisites",List.of("client_mcp extension in the TestManager base","Vanessa opened as TestManager with VanessaExt","connected TestClient; VAExtension for extension-dependent actions"),"note",result.isEmpty()?"MCP transport is reachable but Vanessa tools are not registered. Check startup dialogs, library loading and client_mcp compatibility.":"Only semantic 1C actions and screenshot observation are exposed. See each returned schema before calling it.");
    }
    public Map<String,Object> observe(String endpoint,String serverId,String tool,Map<String,Object> arguments) throws Exception {
        if(!READ.contains(tool)){throw new IllegalArgumentException("Tool is not a read-only runtime observation");}
        return read(endpoint,serverId,c->invoke(c,tool,arguments));
    }
    public synchronized Map<String,Object> action(String endpoint,String serverId,String tool,Map<String,Object> arguments,String idempotencyKey) throws Exception {
        return actionInternal(endpoint,serverId,tool,arguments,idempotencyKey,null,null);
    }
    public synchronized Map<String,Object> actionGuarded(String endpoint,String tool,Map<String,Object> arguments,String key,String expectedWindow)throws Exception {
        if(expectedWindow==null || expectedWindow.isBlank())throw new IllegalArgumentException("expected_window is required");
        return actionInternal(endpoint,null,tool,arguments,key,expectedWindow,null);
    }
    public synchronized Map<String,Object> actionGuarded(String endpoint,String tool,Map<String,Object> arguments,String key,String expectedWindow,Map<String,Object> proof)throws Exception {
        if(expectedWindow==null||expectedWindow.isBlank())throw new IllegalArgumentException("expected_window is required");
        if(!proof.keySet().equals(Set.of("process_id","process_started_at","profile","base_identity_sha256","proof_kind")))throw new IllegalArgumentException("Invalid client binding");
        return actionInternal(endpoint,null,tool,arguments,key,expectedWindow,proof);
    }
    private boolean clientMatches(String endpoint,Map<String,Object> proof){try{
        var current=verifyClient(endpoint,(String)proof.get("base_identity_sha256"));
        return Boolean.TRUE.equals(current.get("live_client_binding_verified"))&&TaskSessionService.fingerprint(proof).equals(TaskSessionService.fingerprint(AgentBrief.select(current,"process_id","process_started_at","profile","base_identity_sha256","proof_kind")));
    }catch(Exception unavailable){return false;}}
    private Map<String,Object> actionInternal(String endpoint,String serverId,String tool,Map<String,Object> arguments,String idempotencyKey,String expectedWindow,Map<String,Object> proof)throws Exception {
        if(!WRITE.contains(tool)){throw new IllegalArgumentException("Tool is not a supported semantic runtime action");}
        if(idempotencyKey==null || idempotencyKey.isBlank() || idempotencyKey.length()>200){throw new IllegalArgumentException("idempotency_key is required (max 200 characters)");}
        String endpointKey=serverId==null?localEndpoint(endpoint==null?"http://127.0.0.1:9874/mcp":endpoint):"configured:"+serverId;
        String key=hash(endpointKey+"|"+idempotencyKey),fingerprint=hash(tool+"|"+GSON.toJson(new TreeMap<>(arguments))+(expectedWindow==null?"":"|window:"+expectedWindow)+(proof==null?"":"|client:"+TaskSessionService.fingerprint(proof)));
        Reservation old=actions.get(key);
        if(old!=null){if(!old.fingerprint.equals(fingerprint)){throw new IllegalArgumentException("Idempotency key was used for a different action");}return Map.of("job_id",old.jobId,"replayed",true);}
        Path marker=storage().resolve(key+".json");
        if(Files.isRegularFile(marker)) {
            JsonObject saved=JsonParser.parseString(Files.readString(marker,StandardCharsets.UTF_8)).getAsJsonObject();
            if(!fingerprint.equals(saved.get("fingerprint").getAsString())){throw new IllegalArgumentException("Persisted action key belongs to different arguments");}
            if(saved.has("result")){JsonObject previous=saved.getAsJsonObject("result");return Map.of("replayed",true,"state",previous.has("success") && !previous.get("success").getAsBoolean()?"FAILED":"DONE","result",previous);}
            throw new IllegalStateException("Previous action outcome is unknown after restart. Observe the application before issuing a replacement with a new key.");
        }
        if(actions.size()>=256){var first=actions.keySet().iterator().next();actions.remove(first);}
        McpClient connection=client(endpoint,serverId);CountDownLatch submitted=new CountDownLatch(1);
        String job=BackgroundJobRegistry.getInstance().startJob("vanessa-runtime-action",()->{
            submitted.await();
            Map<String,Object> result;
            try {
                synchronized(connection){
                    if(proof!=null&&!clientMatches(endpoint,proof))result=Map.of("success",false,"reason","live_client_binding_changed","action_dispatched",false);
                    else result=guardedDispatch(expectedWindow,()->invoke(connection,"get_active_window_data",Map.of("type","window_caption")),()->{if(expectedWindow!=null)validateSemanticSchema(connection,tool,arguments);return invoke(connection,tool,arguments);});
                }
            }
            catch(Exception error){save(marker,Map.of("fingerprint",fingerprint,"outcome","unknown_or_failed","error",String.valueOf(error.getMessage())));throw error;}
            save(marker,Map.of("fingerprint",fingerprint,"result",result));
            if(Boolean.FALSE.equals(result.get("success"))){throw new IllegalStateException(GSON.toJson(result));}
            return GSON.toJson(result);
        });
        actions.put(key,new Reservation(fingerprint,job));
        try{save(marker,Map.of("fingerprint",fingerprint,"job_id",job,"outcome","submitted"));}finally{submitted.countDown();}
        return Map.of("job_id",job,"state","queued","next_tool","edt_operation_status");
    }
    private static void validateSemanticSchema(McpClient client,String name,Map<String,Object> arguments) {
        McpTool tool=client.getTools().stream().filter(t->name.equals(t.getName())).findFirst().orElseThrow(()->new IllegalArgumentException("Vanessa tool is unavailable; inspect edt_runtime_catalog: "+name));
        JsonObject schema=tool.getInputSchema();if(schema==null || !schema.has("properties"))throw new IllegalStateException("Missing Vanessa schema; inspect edt_runtime_catalog");
        for(String key:arguments.keySet())if(!schema.getAsJsonObject("properties").has(key))throw new IllegalArgumentException("Installed Vanessa lacks parameter: "+key);
    }
    private Map<String,Object> invoke(McpClient client,String name,Map<String,Object> arguments) throws Exception {
        McpTool tool=client.getTools().stream().filter(t->name.equals(t.getName())).findFirst().orElseThrow(()->new IllegalArgumentException("Vanessa tool is unavailable; inspect edt_runtime_catalog: "+name));
        if(tool.getInputSchema()!=null && tool.getInputSchema().has("required")) {
            for(JsonElement key:tool.getInputSchema().getAsJsonArray("required")){if(!arguments.containsKey(key.getAsString()) || arguments.get(key.getAsString())==null){throw new IllegalArgumentException("Missing Vanessa parameter: "+key.getAsString());}}
        }
        McpToolResult response=client.callTool(name,arguments).get(125,TimeUnit.SECONDS);
        boolean success=isSuccessful(response);
        List<Map<String,Object>> content=new ArrayList<>();
        for(McpContent item:response.getContent()) {
            if(item.getType()==McpContent.Type.IMAGE) {
                String mime=item.getMimeType();if(!Set.of("image/png","image/jpeg").contains(mime)){throw new IllegalStateException("Unsupported runtime image type");}
                if(item.getData()==null || item.getData().length()>16*1024*1024){throw new IllegalStateException("Runtime image exceeds capture limit");}
                byte[] bytes=Base64.getDecoder().decode(item.getData());Path path=storage().resolve(UUID.randomUUID()+(mime.equals("image/png")?".png":".jpg"));Files.write(path,bytes);
                content.add(Map.of("type","image","image_path",path.toString(),"mime_type",mime));
            } else {Map<String,Object> text=new LinkedHashMap<>();JsonElement parsed=parseJson(item.getEffectiveText());if(parsed!=null){text.put("type","json");text.put("data",parsed);}else{text.put("type","text");text.put("text",item.getEffectiveText());}content.add(text);}
        }
        Map<String,Object> result=new LinkedHashMap<>();
        if(name.equals("manage_test_client") && Set.of("connect","disconnect").contains(String.valueOf(arguments.get("action")))){
            McpToolResult observed=client.callTool("get_vanessa_automation_state",Map.of("format","json")).get(30,TimeUnit.SECONDS);
            Boolean actual=connected(observed);boolean expected="connect".equals(arguments.get("action"));
            boolean verified=actual!=null && actual==expected;success=success && verified;
            result.put("postcondition",verified?"verified":"failed_or_unverified");result.put("test_client_connected",actual==null?"unknown":actual);
        }
        result.put("success",success);result.put("tool",name);result.put("content",content);result.put("strategy","vanessa-native-mcp");return result;
    }
    static Map<String,Object> guardedDispatch(String expectedWindow,java.util.concurrent.Callable<Map<String,Object>> observe,java.util.concurrent.Callable<Map<String,Object>> write)throws Exception {
        if(expectedWindow!=null && !expectedWindow.equals(RuntimeFormService.windowText(observe.call())))return Map.of("success",false,"reason","active_window_changed","action_dispatched",false);
        return write.call();
    }
    static JsonElement parseJson(String text){if(text==null || !text.stripLeading().startsWith("{")){return null;}try{return JsonParser.parseString(text);}catch(JsonParseException malformed){return null;}}
    static boolean isSuccessful(McpToolResult result){
        if(result.isError()){return false;}
        for(McpContent content:result.getContent()){JsonElement parsed=parseJson(content.getEffectiveText());if(parsed!=null && parsed.isJsonObject()){for(String key:List.of("ok","success")){JsonElement value=parsed.getAsJsonObject().get(key);if(value!=null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && !value.getAsBoolean()){return false;}}}}
        return true;
    }
    static Boolean connected(McpToolResult result){if(!isSuccessful(result)){return null;}for(McpContent content:result.getContent()){JsonElement parsed=parseJson(content.getEffectiveText());if(parsed!=null && parsed.isJsonObject()){JsonElement value=parsed.getAsJsonObject().get("testClientConnected");if(value!=null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()){return value.getAsBoolean();}}}return null;}
    private static Path storage() throws Exception {Path path=ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/runtime-ui");Files.createDirectories(path);return path;}
    private static String hash(String value) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static void save(Path file,Map<String,Object> value) throws Exception {Path temp=Files.createTempFile(file.getParent(),"runtime-",".json");Files.writeString(temp,GSON.toJson(value),StandardCharsets.UTF_8);try{Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}}
}
