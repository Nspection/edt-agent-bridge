// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import com.google.gson.*;

/** Durable association-only claims. An interrupted SDK change is never automatically repeated. */
public final class InfobaseBindingService {
    public interface Gateway {
        Map<String,Object> describe(String project,String uuid)throws Exception;
        List<Map<String,Object>> list(String filter,int offset,int limit)throws Exception;
        Map<String,Object> bind(String project,String uuid,Map<String,Object> expected,boolean primary)throws Exception;
    }
    private static final Gson JSON=new GsonBuilder().serializeNulls().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
    private static final Object CLAIM_LOCK=new Object();
    private final Gateway gateway;private final Path root;
    public InfobaseBindingService() {this(new RegisteredInfobaseGateway());}
    private InfobaseBindingService(RegisteredInfobaseGateway g){this(g,g.bindingRoot());}
    public InfobaseBindingService(Gateway gateway,Path root){this.gateway=gateway;this.root=root;}
    private static String required(Map<String,Object>a,String k){Object v=a.get(k);if(!(v instanceof String s)||s.isBlank()||s.length()>256)throw new IllegalArgumentException(k+" required");return s;}
    private static boolean flag(Map<String,Object>a,String k,boolean fallback){if(!a.containsKey(k))return fallback;if(!(a.get(k) instanceof Boolean b))throw new IllegalArgumentException(k+" must be boolean");return b;}
    private static Map<String,Object> request(Map<String,Object>a){
        String project=required(a,"project"),uuid=required(a,"base_uuid");try{if(!UUID.fromString(uuid).toString().equals(uuid))throw new IllegalArgumentException();}catch(Exception e){throw new IllegalArgumentException("Canonical base_uuid required");}
        return Map.of("project",project,"base_uuid",uuid,"set_primary",flag(a,"set_primary",true),"replace_primary",flag(a,"replace_primary",false));
    }
    static void validateTarget(Map<String,Object> state,Map<String,Object> a){
        String project=(String)a.get("project"),uuid=(String)a.get("base_uuid");
        if(!(state.get("owners") instanceof List<?> owners)||owners.stream().anyMatch(x->!project.equals(x)))throw new IllegalStateException("BASE_OWNED_BY_ANOTHER_PROJECT: explicit reassignment is a separate operation");
        if(Boolean.TRUE.equals(a.get("set_primary"))&&!Boolean.TRUE.equals(a.get("replace_primary"))&&state.get("primary_uuid") instanceof String current&&!current.isBlank()&&!uuid.equals(current))throw new IllegalStateException("PRIMARY_EXISTS: replace_primary=true required");
    }
    private static Object canonical(Object v){if(v instanceof Map<?,?> m){var sorted=new TreeMap<String,Object>();m.forEach((k,x)->sorted.put(String.valueOf(k),canonical(x)));return sorted;}if(v instanceof List<?> l)return l.stream().map(InfobaseBindingService::canonical).toList();return v;}
    public Map<String,Object> preview(Map<String,Object>a)throws Exception{
        var args=request(a);var state=gateway.describe((String)args.get("project"),(String)args.get("base_uuid"));validateTarget(state,args);
        var plan=new LinkedHashMap<String,Object>();plan.put("request",args);plan.put("before",state);plan.put("configuration_updated",false);plan.put("credentials_preserved",true);plan.put("database_started",false);plan.put("registered_base_only",true);plan.put("preview_sha256",InfobasePaths.hash(JSON.toJson(canonical(plan))));plan.put("dry_run",true);return plan;
    }
    public Map<String,Object> list(String filter,int offset,int limit)throws Exception{
        if(filter==null||filter.length()>128||offset<0||limit<1||limit>50)throw new IllegalArgumentException("Invalid inventory pagination");var all=gateway.list(filter,offset,limit);var page=all.stream().skip(offset).limit(limit).toList();return Map.of("bases",page,"total",all.size(),"has_more",(long)offset+page.size()<all.size(),"configuration_updated",false,"database_started",false);
    }
    private Path receipt(String hash)throws Exception{if(!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid preview hash");Files.createDirectories(root);if(Files.isSymbolicLink(root)||!root.toRealPath().equals(root.toAbsolutePath().normalize()))throw new IllegalStateException("Binding storage changed");return root.resolve(hash+".json");}
    private static void write(Path file,Map<String,Object> record)throws Exception{Path tmp=file.resolveSibling(file.getFileName()+".tmp");if(Files.isSymbolicLink(file)||Files.isSymbolicLink(tmp))throw new IllegalStateException("Binding storage changed");Files.writeString(tmp,JSON.toJson(record),StandardCharsets.UTF_8);Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    @SuppressWarnings("unchecked") public Map<String,Object> apply(Map<String,Object>a)throws Exception{
        var args=request(a);String hash=required(a,"expected_preview_sha256");Path file=receipt(hash);
        synchronized(CLAIM_LOCK){
            if(Files.exists(file)){
                if(Files.isSymbolicLink(file)||Files.size(file)>65536)throw new IllegalStateException("Binding receipt changed");var old=JSON.fromJson(Files.readString(file),LinkedHashMap.class);
                if(!Objects.equals(old.get("request"),args))throw new IllegalStateException("Preview hash belongs to another request");
                if(old.get("result") instanceof Map<?,?> r)return new LinkedHashMap<>((Map<String,Object>)r);
                return Map.of("state","outcome_unknown","outcome_unknown",true,"terminal",true,"configuration_updated",false,"reason","inspect_original_binding_operation");
            }
            var initial=gateway.describe((String)args.get("project"),(String)args.get("base_uuid"));
            var keys=List.of("project:"+args.get("project"),"infobase:"+initial.get("target_identity_sha256"));
            try(var lock=new InfobaseWorkflow(keys,p->{})){
                var plan=preview(args);if(!hash.equals(plan.get("preview_sha256")))throw new IllegalStateException("STALE_PREVIEW: binding context, primary or target changed");
                var record=new LinkedHashMap<String,Object>();record.put("request",args);record.put("before",plan.get("before"));record.put("state","claimed");write(file,record);
                Map<String,Object> result;
                try{
                    var actual=gateway.bind((String)args.get("project"),(String)args.get("base_uuid"),(Map<String,Object>)plan.get("before"),Boolean.TRUE.equals(args.get("set_primary")));
                    validateTarget(actual,args);
                    var before=(Map<String,Object>)plan.get("before");
                    if(!Objects.equals(actual.get("project"),args.get("project"))||!Objects.equals(actual.get("base_uuid"),args.get("base_uuid"))||!Boolean.TRUE.equals(actual.get("associated"))||!Objects.equals(actual.get("context"),before.get("context"))||!Objects.equals(actual.get("target_identity_sha256"),before.get("target_identity_sha256"))||(Boolean.TRUE.equals(args.get("set_primary"))&&!args.get("base_uuid").equals(actual.get("primary_uuid"))))throw new IllegalStateException("Binding result not verified");
                    result=new LinkedHashMap<>(Map.of("state","completed","terminal",true,"outcome_unknown",false,"binding_verified",true,"configuration_updated",false,"database_started",false,"credentials_preserved",true,"live_state",false));result.put("binding",actual);
                }catch(Exception failure){result=Map.of("state","outcome_unknown","terminal",true,"outcome_unknown",true,"binding_verified",false,"configuration_updated",false,"reason","binding_may_be_partial_inspect_original_operation");}
                result=new LinkedHashMap<>(result);result.put("operation_id",hash);result.put("project",args.get("project"));result.put("base_uuid",args.get("base_uuid"));
                record.put("result",result);record.put("state",result.get("state"));write(file,record);return result;
            }
        }
    }
}
