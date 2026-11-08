// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.google.gson.*;
import com.codepilot1c.core.edt.runtime.EdtRuntimeGateway;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/** Removal leases exist only for newly created, explicitly disposable FILE bases. */
public final class OwnedFileBaseService {
    public interface CleanupHooks {
        void detach(String project,InfobaseReference reference)throws Exception;
        void removeSettings(String uuid)throws Exception;
    }
    private static final Gson JSON=new Gson();
    private static final Object LOCK=new Object();
    private final NativeInfobaseGateway gateway;
    private final Path root;
    private final CleanupHooks hooks;
    public OwnedFileBaseService(){this(new NativeInfobaseGateway(),null,null);}
    public OwnedFileBaseService(NativeInfobaseGateway gateway,Path root){this(gateway,root,null);}
    public OwnedFileBaseService(NativeInfobaseGateway gateway,Path root,CleanupHooks hooks){
        this.gateway=gateway;this.root=root==null?gateway.stateRoot().resolve("owned-file-bases"):root;
        this.hooks=hooks==null?new CleanupHooks(){
            public void detach(String project,InfobaseReference ref)throws Exception {
                var p=org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getProject(project);
                if(p.exists())gateway.removeOwnAssociation(project,ref);
                var manager=new EdtRuntimeGateway().getInfobaseManager();
                var registered=manager.findInfobaseByUuid(ref.getUuid());
                if(registered.isPresent())manager.delete(registered.get());
                if(manager.findInfobaseByUuid(ref.getUuid()).isPresent())throw new IllegalStateException("EDT launcher registration remains");
            }
            public void removeSettings(String uuid)throws Exception{removeOwnedApplicationSettings(uuid);}
        }:hooks;
    }
    public String register(String project,Path path,InfobaseReference ref)throws Exception {
        Path db=path.toRealPath(),file=db.resolve("1Cv8.1CD");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("Disposable database file missing");
        String id=UUID.randomUUID().toString();Files.createDirectories(root);
        Path witness=db.resolve(witness(id));Files.createLink(witness,file);
        Map<String,Object> doc=new LinkedHashMap<>();doc.put("lease_id",id);doc.put("project",project);
        doc.put("base_uuid",ref.getUuid().toString());doc.put("database_path",db.toString());
        doc.put("witness",witness.getFileName().toString());doc.put("cleaned",false);write(id,doc);return id;
    }
    private static String witness(String id){return ".edt-agent-base-"+id+".witness";}
    private Path file(String id){UUID.fromString(id);return root.resolve(id+".json");}
    @SuppressWarnings("unchecked") private Map<String,Object> read(String id)throws Exception {
        Path p=file(id);if(Files.isSymbolicLink(p)||Files.size(p)>8*1024*1024)throw new IllegalStateException("Invalid database lease");
        Map<String,Object> doc=JSON.fromJson(Files.readString(p,StandardCharsets.UTF_8),LinkedHashMap.class);
        if(!id.equals(doc.get("lease_id")))throw new IllegalStateException("Lease identity differs");return doc;
    }
    private void write(String id,Map<String,Object> doc)throws Exception {
        String content=JSON.toJson(doc);if(content.getBytes(StandardCharsets.UTF_8).length>8*1024*1024)throw new IllegalStateException("Lease exceeds size limit");
        Files.createDirectories(root);if(Files.isSymbolicLink(file(id)))throw new IllegalStateException("Linked lease");
        Path temp=Files.createTempFile(root,"lease-",".tmp");
        try{Files.writeString(temp,content,StandardCharsets.UTF_8);Files.move(temp,file(id),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally{Files.deleteIfExists(temp);}
    }
    private static Path original(Map<String,Object> doc){return Path.of((String)doc.get("database_path"));}
    private static Path quarantine(String id,Map<String,Object> doc){return original(doc).resolveSibling(".edt-delete-"+id);}
    private static Path materialPath(String id,Map<String,Object> doc)throws Exception {
        Path original=original(doc),q=quarantine(id,doc);
        if(doc.containsKey("quarantine_path")&&!q.toString().equals(doc.get("quarantine_path")))throw new IllegalStateException("Invalid quarantine path");
        if(Files.exists(q,LinkOption.NOFOLLOW_LINKS)){
            if(!Boolean.TRUE.equals(doc.get("delete_started"))||!doc.containsKey("quarantine_path"))throw new IllegalStateException("Unowned quarantine path exists");
            if(Files.exists(original,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("Original path reappeared; retain both paths for review");
            return q;
        }
        if(doc.containsKey("quarantine_path")&&Files.exists(original,LinkOption.NOFOLLOW_LINKS)&&Boolean.TRUE.equals(doc.get("moved")))throw new IllegalStateException("Original path was recreated; retained");
        return Boolean.TRUE.equals(doc.get("moved"))?q:original;
    }
    private static Map<String,Object> tree(Path path)throws Exception {
        Map<String,Object> files=new TreeMap<>();
        try(var stream=Files.walk(path)){
            var it=stream.iterator();while(it.hasNext()){
                Path p=it.next();if(files.size()>=20000)throw new IllegalStateException("Database cleanup file limit");
                if(Files.isSymbolicLink(p)||!p.toRealPath().startsWith(path))throw new IllegalStateException("Linked database content");
                BasicFileAttributes a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                Map<String,Object> row=new LinkedHashMap<>();row.put("directory",a.isDirectory());
                if(a.isRegularFile()){row.put("size",a.size());row.put("modified",a.lastModifiedTime().toMillis());}
                else if(!a.isDirectory())throw new IllegalStateException("Unsupported database content");
                files.put(path.relativize(p).toString(),row);
            }
        }return files;
    }
    private static void verifyRemaining(Path path,Map<String,Object> doc)throws Exception {
        Map<?,?> manifest=doc.get("files_before_delete") instanceof Map<?,?> m?m:Map.of();
        for(var entry:tree(path).entrySet()){
            if(!(manifest.get(entry.getKey()) instanceof Map<?,?> old))throw new IllegalStateException("New database content; retained");
            Map<?,?> now=(Map<?,?>)entry.getValue();
            if(!Objects.equals(old.get("directory"),now.get("directory")))throw new IllegalStateException("Database entry type changed");
            boolean closingLog=entry.getKey().replace('\\','/').matches("1Cv8Log/[0-9]{14}\\.lg[pdf]");
            if(Boolean.FALSE.equals(now.get("directory"))&&!closingLog)for(String key:List.of("size","modified")){
                if(!(old.get(key) instanceof Number a)||!(now.get(key) instanceof Number b)||a.longValue()!=b.longValue())throw new IllegalStateException("Remaining database file changed; retained");
            }
        }
    }
    public Map<String,Object> preview(String id,String project,String baseUuid)throws Exception {
        synchronized(LOCK){
            var doc=read(id);if(!project.equals(doc.get("project"))||!baseUuid.equals(doc.get("base_uuid")))throw new IllegalStateException("Database lease target differs");
            Path path=materialPath(id,doc);var ref=gateway.reference(original(doc));ref.setUuid(UUID.fromString(baseUuid));
            if(gateway.ownerProjects(ref).stream().anyMatch(x->!project.equals(x)))throw new IllegalStateException("Another project owns this database");
            Map<String,Object> out=new LinkedHashMap<>(doc);
            if(Boolean.TRUE.equals(doc.get("cleaned"))){
                if(Files.exists(original(doc),LinkOption.NOFOLLOW_LINKS)||Files.exists(quarantine(id,doc),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("A path reappeared after cleanup; retained");
            }else if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)){
                if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||!path.toRealPath().equals(path))throw new IllegalStateException("Database directory moved or linked");
                Path base=path.resolve("1Cv8.1CD");
                if(Files.exists(base,LinkOption.NOFOLLOW_LINKS)){
                    Path witness=path.resolve(witness(id));
                    if(!Files.isRegularFile(witness,LinkOption.NOFOLLOW_LINKS)||!Files.isSameFile(witness,base))throw new IllegalStateException("Database file identity changed");
                }else if(!Boolean.TRUE.equals(doc.get("delete_started")))throw new IllegalStateException("Database file missing before cleanup");
                if(Boolean.TRUE.equals(doc.get("delete_started")))verifyRemaining(path,doc);
                out.put("tree_sha256",InfobasePaths.hash(JSON.toJson(tree(path))));
            }else if(!Boolean.TRUE.equals(doc.get("delete_started")))throw new IllegalStateException("Database directory missing before cleanup");
            else out.put("files_removed",true);
            out.put("plan_sha256",InfobasePaths.hash(JSON.toJson(out)));return out;
        }
    }
    private static final class BaseLock implements AutoCloseable {
        final FileChannel channel;final FileLock lock;
        BaseLock(Path base)throws Exception {
            channel=Files.exists(base)?FileChannel.open(base,StandardOpenOption.READ,StandardOpenOption.WRITE):null;
            FileLock acquired=null;
            try{if(channel!=null){acquired=channel.tryLock();if(acquired==null)throw new IllegalStateException("Database is in use; retained");}}
            catch(Exception e){if(channel!=null)channel.close();throw e;}lock=acquired;
        }
        public void close()throws Exception{try{if(lock!=null)lock.close();}finally{if(channel!=null)channel.close();}}
    }
    public Map<String,Object> cleanup(String id,String project,String baseUuid,String expected)throws Exception {
        synchronized(LOCK){
            var doc=read(id);var ref=gateway.reference(original(doc));ref.setUuid(UUID.fromString(baseUuid));
            try(var workflow=new InfobaseWorkflow(InfobaseWorkflow.resourceKeys(project,ref),progress->{})){
                var current=preview(id,project,baseUuid);
                if(!Objects.equals(expected,current.get("plan_sha256")))throw new IllegalStateException("Database cleanup preview stale");
                if(Boolean.TRUE.equals(doc.get("cleaned"))){hooks.removeSettings(baseUuid);return completed();}
                Path path=materialPath(id,doc),q=quarantine(id,doc);
                // Windows cannot rename a directory with this Java handle open. Hold the
                // lock through detach/manifest, release only for the atomic move, then lock
                // the quarantined file again. If a client entered that gap, no file is deleted.
                try(var busy=new BaseLock(path.resolve("1Cv8.1CD"))){
                    if(Boolean.TRUE.equals(doc.get("delete_started"))&&Files.exists(path))verifyRemaining(path,doc);
                    doc.put("detach_started",true);write(id,doc);hooks.detach(project,ref);
                    // EDT may flush its synchronization metadata when disconnecting. The
                    // deletion manifest is recorded after that native operation, under lock.
                    if(Files.exists(path)&&!Boolean.TRUE.equals(doc.get("delete_started"))){doc.put("files_before_delete",tree(path));doc.put("delete_started",true);write(id,doc);}
                    if(Files.exists(path)&&path.equals(original(doc))){verifyRemaining(path,doc);doc.put("quarantine_path",q.toString());write(id,doc);}
                }
                if(Files.exists(path)&&path.equals(original(doc))){Files.move(path,q,StandardCopyOption.ATOMIC_MOVE);path=q;doc.put("moved",true);write(id,doc);}
                else if(path.equals(q)&&Files.exists(path)){doc.put("moved",true);write(id,doc);}
                try(var quarantined=new BaseLock(path.resolve("1Cv8.1CD"))){if(Files.exists(path))verifyRemaining(path,doc);}
                if(Files.exists(path)){
                    verifyRemaining(path,doc);
                    try(var files=Files.walk(path)){for(Path p:files.sorted(Comparator.reverseOrder()).toList()){
                        if(Files.isSymbolicLink(p)||!p.toRealPath().startsWith(path))throw new IllegalStateException("Database path changed during cleanup");Files.delete(p);
                    }}
                }
                if(Files.exists(path)||Files.exists(original(doc)))throw new IllegalStateException("Database path remains or was recreated; retained for review");
                hooks.removeSettings(baseUuid);doc.put("cleaned",true);write(id,doc);return completed();
            }
        }
    }
    private static Map<String,Object> completed(){return Map.of("cleanup_verified",true,"database_removed",true,"launcher_record_removed",true,"application_settings_removed",true,"disposable_test_base",true);}
    private static void removeOwnedApplicationSettings(String uuid)throws Exception {
        UUID.fromString(uuid);String prefix="applicationTypes/com.e1c.g5.dt.applications.type.infobase/"+uuid+"/";
        var preferences=org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE.getNode("com.e1c.g5.dt.applications");
        for(String key:preferences.keys())if(key.startsWith(prefix))preferences.remove(key);
        String node=prefix.substring(0,prefix.length()-1);if(preferences.nodeExists(node))preferences.node(node).removeNode();preferences.flush();
        for(String key:preferences.keys())if(key.startsWith(prefix))throw new IllegalStateException("Owned application settings remain");
    }
}
