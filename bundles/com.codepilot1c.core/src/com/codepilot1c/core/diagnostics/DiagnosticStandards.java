// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.diagnostics;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import com.google.gson.*;
import org.eclipse.core.resources.ResourcesPlugin;

/** Optional advisory enrichment. Neither missing rules nor service failure affect analyzer results. */
public final class DiagnosticStandards {
    public record Entry(String state,String title,String url,List<Map<String,String>> standards) {}
    private record Cached(long at,Entry entry) {}
    @FunctionalInterface public interface Resolver {Map<String,Entry> resolve(List<String> codes)throws Exception;}
    private static final Gson JSON=new Gson();
    private static final ExecutorService NETWORK=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"edt-v8std");t.setDaemon(true);return t;});
    private static final Map<Path,DiagnosticStandards> INSTANCES=new HashMap<>();
    private final Path file;private final Resolver resolver;private final Executor executor;private final LongSupplier clock;
    private final LinkedHashMap<String,Cached> cache=new LinkedHashMap<>();private CompletableFuture<Void> pending;private long retryAfter;private String failure="";
    public DiagnosticStandards(Path directory,Resolver resolver,Executor executor,LongSupplier clock) {
        this.file=directory.resolve("cache-v1.json");this.resolver=resolver;this.executor=executor;this.clock=clock;load();
    }
    public static synchronized DiagnosticStandards workspace() {
        Path directory=ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/v8std");
        return INSTANCES.computeIfAbsent(directory,p->new DiagnosticStandards(p,new V8StdClient(),NETWORK,System::currentTimeMillis));
    }
    /** Strictly namespaced identifiers only; never extract IDs from free-form messages/snippets. */
    public static String canonical(String raw) {
        if(raw==null||raw.length()>140)return null;String s=raw.trim();int colon=s.indexOf(':');if(colon<1)return null;
        String ns=s.substring(0,colon).toLowerCase(Locale.ROOT),id=s.substring(colon+1);
        if(ns.equals("апк"))ns="acc";
        if(ns.equals("acc"))return id.matches("[0-9]{1,8}")?ns+":"+id.toLowerCase(Locale.ROOT):null;
        return Set.of("bslls","v8cs").contains(ns)&&id.matches("[A-Za-z][A-Za-z0-9_.-]{0,119}")?ns+":"+id.toLowerCase(Locale.ROOT):null;
    }
    public static String code(Map<String,?> diagnostic) {
        Object raw=diagnostic.get("diagnostic_code");if(raw==null)raw=diagnostic.get("check_id");if(raw==null)raw=diagnostic.get("code");
        if(!(raw instanceof String text))return null;String result=canonical(text);if(result!=null)return result;
        // Only explicit analyzer provenance permits an unqualified ID. EDT core IDs are NOT BSLLS aliases.
        String source=String.valueOf(diagnostic.get("diagnostic_source"));String ns=switch(source){case "bslls","bsl-language-server"->"bslls";case "acc","апк"->"acc";case "v8cs","v8-code-style"->"v8cs";default->null;};
        return ns==null?null:canonical(ns+":"+text);
    }
    /** Exact native check ID + verified contributor, never a message/title similarity crosswalk. */
    public static Map<String,String> edtCodeMap() {
        try {
            var plugin=com.codepilot1c.core.internal.VibeCorePlugin.getDefault();
            if(plugin==null||plugin.getCheckRepository()==null)return Map.of();
            Map<String,Set<String>> contributors=new LinkedHashMap<>();
            for(var uid:plugin.getCheckRepository().getChecksWithDescriptions().keySet())
                if(uid!=null)contributors.computeIfAbsent(uid.getCheckId(),k->new HashSet<>()).add(uid.getContributorId());
            return contributorCodes(contributors);
        } catch(Exception|LinkageError e){return Map.of();}
    }
    public static Map<String,String> contributorCodes(Map<String,Set<String>> contributors) {
        Map<String,String> codes=new HashMap<>();contributors.forEach((id,sources)->{
            if(id!=null&&sources.size()==1){String source=sources.iterator().next();
                if(source!=null&&(source.equals("com.e1c.v8codestyle")||source.startsWith("com.e1c.v8codestyle."))){String code=canonical("v8cs:"+id);if(code!=null)codes.put(id,code);}}
        });return codes;
    }
    public static Map<String,Object> markerIdentity(org.eclipse.core.resources.IMarker marker)throws org.eclipse.core.runtime.CoreException {
        Map<String,Object> row=new LinkedHashMap<>();row.put("marker_type",marker.getType());row.put("diagnostic_source","edt");
        String id=marker.getAttribute("checkId","");if(id.isEmpty())id=marker.getAttribute("check_id","");if(id.isEmpty())id=marker.getAttribute("diagnostic_code","");
        if(!id.isEmpty())row.put("check_id",id);return row;
    }
    public static Map<String,Object> advisory(List<? extends Map<String,?>> rows) {
        try{return workspace().annotate(rows,false);}catch(Exception|LinkageError e){return Map.of("provider","v8std","state","unavailable","advisory_only",true,"items",List.of(),"standards",List.of());}
    }
    public Map<String,Object> lookup(List<String> codes,boolean wait)throws Exception {
        if(codes==null||codes.isEmpty()||codes.size()>100)throw new IllegalArgumentException("codes must contain 1..100 namespaced diagnostic IDs");
        List<Map<String,?>> rows=new ArrayList<>();for(String raw:codes){String code=canonical(raw);if(code==null)throw new IllegalArgumentException("Use exact bslls:, acc: or v8cs: diagnostic identifiers");rows.add(Map.of("diagnostic_code",code));}return annotate(rows,wait);
    }
    public Map<String,Object> annotate(List<? extends Map<String,?>> rows,boolean wait) {
        LinkedHashSet<String> codes=new LinkedHashSet<>();for(var row:rows.subList(0,Math.min(100,rows.size()))){String c=code(row);if(c!=null&&codes.size()<100)codes.add(c);}
        CompletableFuture<Void> job=schedule(codes);
        if(wait&&job!=null)try{job.get(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}catch(Exception ignored){}
        if(wait)schedule(codes);
        synchronized(this) {
            List<Map<String,Object>> items=new ArrayList<>();Map<String,Map<String,String>> standards=new LinkedHashMap<>();int index=0;
            for(var row:rows.subList(0,Math.min(100,rows.size()))){String c=code(row);Map<String,Object> item=new LinkedHashMap<>();item.put("index",index++);
                if(c==null){item.put("state",row.containsKey("check_id")||row.containsKey("diagnostic_code")||row.containsKey("code")?"unsupported_source":"missing_id");}
                else if(!codes.contains(c)){item.put("code",c);item.put("state","limit_exceeded");}
                else {item.put("code",c);Cached cached=cache.get(c);boolean active=pending!=null&&!pending.isDone();
                    if(cached==null)item.put("state",active?"lookup_pending":Boolean.getBoolean("codepilot.v8std.offline")?"offline":failure.isEmpty()?"unresolved":"unavailable");
                    else {boolean fresh=fresh(cached);item.put("state",fresh?cached.entry.state:"stale_cache");item.put("cached_state",cached.entry.state);item.put("fetched_at_ms",cached.at);item.put("diagnostic_title",cached.entry.title);item.put("diagnostic_url",cached.entry.url);
                        List<String> refs=new ArrayList<>();for(var std:cached.entry.standards){String key=std.get("url");standards.putIfAbsent(key,std);refs.add(key);}item.put("standard_refs",refs);}
                }items.add(item);
            }
            Map<String,Object> out=new LinkedHashMap<>();out.put("provider","v8std");out.put("advisory_only",true);out.put("summary_source","catalog_description");out.put("input_count",rows.size());out.put("items_truncated",rows.size()>100);out.put("automatic_lookup",!Boolean.getBoolean("codepilot.v8std.offline"));out.put("items",items);out.put("standards",standards.values());out.put("lookup_pending",pending!=null&&!pending.isDone());out.put("cache_state",failure.isEmpty()?"available":"unavailable");if(!failure.isEmpty())out.put("reason",failure);
            if((Boolean.TRUE.equals(out.get("lookup_pending"))||items.stream().anyMatch(i->"unresolved".equals(i.get("state"))))&&!codes.isEmpty())out.put("next_call",Map.of("tool","edt.check","arguments",Map.of("op","edt_check","args",Map.of("action","standards","codes",new ArrayList<>(codes)))));
            return out;
        }
    }
    private boolean fresh(Cached c){long age=clock.getAsLong()-c.at;return age>=0&&age<(c.entry.state.equals("unknown_code")?86400000L:604800000L);}
    private synchronized CompletableFuture<Void> schedule(Set<String> codes) {
        if(Boolean.getBoolean("codepilot.v8std.offline"))return null;
        if(pending!=null&&!pending.isDone())return pending;
        if(clock.getAsLong()<retryAfter)return null;
        List<String> need=codes.stream().filter(c->!cache.containsKey(c)||!fresh(cache.get(c))).limit(100).toList();if(need.isEmpty())return null;
        CompletableFuture<Void> done=new CompletableFuture<>();pending=done;
        try{executor.execute(()->{try{Map<String,Entry> values=resolver.resolve(need);if(!values.keySet().equals(new HashSet<>(need)))throw new IllegalArgumentException("Incomplete resolver result");for(var v:values.values())validate(v);
                    synchronized(this){for(String c:need){cache.remove(c);cache.put(c,new Cached(clock.getAsLong(),values.get(c)));}while(cache.size()>512)cache.remove(cache.keySet().iterator().next());failure="";retryAfter=0;save();}
                }catch(Exception e){synchronized(this){failure="v8std_unavailable";retryAfter=clock.getAsLong()+60000;}}finally{done.complete(null);}});}catch(RuntimeException e){failure="v8std_unavailable";retryAfter=clock.getAsLong()+60000;done.complete(null);}return done;
    }
    private static void validate(Entry e){if(e==null||!Set.of("resolved","unknown_code","no_standard").contains(e.state)||e.title==null||e.title.length()>300||e.url==null||e.standards==null||e.standards.size()>32)throw new IllegalArgumentException("Invalid cached entry");if(!e.url.isEmpty())safeUrl(e.url);for(var s:e.standards){if(!s.keySet().equals(Set.of("id","title","description","description_scope","url"))||!s.get("id").matches("std[0-9]{1,8}")||s.get("title").length()>300||s.get("description").length()>400||!"article_intro".equals(s.get("description_scope")))throw new IllegalArgumentException("Invalid cached standard");safeUrl(s.get("url"));}}
    private static void safeUrl(String s){var u=java.net.URI.create(s);if(s.length()>600||!"https".equals(u.getScheme())||!"v8std.ru".equals(u.getHost())||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getPort()!=-1)throw new IllegalArgumentException("Untrusted cached URL");}
    private synchronized void load(){try{if(!Files.exists(file))return;if(Files.isSymbolicLink(file)||Files.isSymbolicLink(file.getParent())||Files.size(file)>4*1024*1024)throw new IllegalArgumentException();JsonObject root=JsonParser.parseString(Files.readString(file)).getAsJsonObject();if(root.get("schema").getAsInt()!=1||root.getAsJsonObject("entries").size()>512)throw new IllegalArgumentException();Map<String,Cached> loaded=new LinkedHashMap<>();for(var row:root.getAsJsonObject("entries").entrySet()){if(!row.getKey().equals(canonical(row.getKey())))throw new IllegalArgumentException();Cached c=JSON.fromJson(row.getValue(),Cached.class);validate(c.entry);loaded.put(row.getKey(),c);}cache.putAll(loaded);}catch(Exception e){failure="cache_invalid";}}
    private void save(){try{Files.createDirectories(file.getParent());if(Files.isSymbolicLink(file)||Files.isSymbolicLink(file.getParent()))return;String text=JSON.toJson(Map.of("schema",1,"entries",cache));if(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4*1024*1024)return;Path tmp=Files.createTempFile(file.getParent(),"v8std-",".tmp");try{Files.writeString(tmp,text);Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}}catch(Exception ignored){/* Cached advisory failures must not replace original diagnostics. */}}
}
