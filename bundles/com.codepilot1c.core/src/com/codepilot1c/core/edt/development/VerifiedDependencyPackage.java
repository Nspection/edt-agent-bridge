// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;
import java.util.zip.*;
import com.google.gson.*;

/** Immutable dependency generations. Existing runtimes are never replaced in place. */
public final class VerifiedDependencyPackage {
    public record Spec(String id,String version,URI uri,String sha256,String prefix,String entry,String entrySha256) {
        public Spec {
            if(!Set.of("python","vanessa").contains(id)||!version.matches("[0-9.]{3,30}")||!sha256.matches("[a-f0-9]{64}")||!entrySha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid dependency descriptor");
            if(!allowed(uri)||!entry.startsWith(prefix)||!safeName(entry))throw new IllegalArgumentException("Invalid dependency descriptor path");
        }
    }
    @FunctionalInterface public interface Downloader {void download(URI uri,Path target,BooleanSupplier stop)throws Exception;}
    private static final Map<Path,Object> LOCKS=new ConcurrentHashMap<>();
    private static final Gson JSON=new Gson();
    private static final long MAX_ZIP=64L*1024*1024,MAX_EXPANDED=256L*1024*1024;
    private final Path root;private final Spec spec;private final Downloader downloader;
    public VerifiedDependencyPackage(Path root,Spec spec,Downloader downloader){this.root=root.toAbsolutePath().normalize();this.spec=spec;this.downloader=downloader;}
    public static Spec bundled(String resource)throws Exception {
        try(var in=VerifiedDependencyPackage.class.getResourceAsStream(resource)){
            if(in==null)throw new IOException("Dependency descriptor missing");byte[] bytes=in.readNBytes(8193);if(bytes.length>8192)throw new IOException("Descriptor too large");var j=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();if(j.get("schema").getAsInt()!=1)throw new IOException("Unsupported dependency descriptor");return new Spec(j.get("id").getAsString(),j.get("version").getAsString(),URI.create(j.get("url").getAsString()),j.get("sha256").getAsString(),j.get("prefix").getAsString(),j.get("entry").getAsString(),j.get("entry_sha256").getAsString());
        }
    }
    public static VerifiedDependencyPackage python()throws Exception {return workspace(bundled("/resources/test-client/python-runtime.json"));}
    public static VerifiedDependencyPackage workspace(Spec spec)throws Exception {
        if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows"))throw new IOException("Dependency packages require Windows x64");
        Path workspace=org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().toRealPath();String identity=digest(workspace.toString().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)).substring(0,16);String local=System.getenv("LOCALAPPDATA");if(local==null||!Path.of(local).isAbsolute())throw new IOException("LOCALAPPDATA unavailable");
        return new VerifiedDependencyPackage(Path.of(local).resolve("EDT-Agent-Bridge/runtime").resolve(identity).resolve(spec.id()),spec,VerifiedDependencyPackage::download);
    }
    public Path root(){return root;}
    private Path archive(){return root.resolve(spec.sha256()+".zip");}
    private void guard()throws Exception {if(Files.exists(root,LinkOption.NOFOLLOW_LINKS)&&(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||!root.toRealPath().equals(root)))throw new IOException("Dependency storage changed; preserve it");}
    static boolean safeName(String name){if(name.isEmpty()||name.startsWith("/")||name.contains("\\")||name.contains(":")||name.contains("\u0000"))return false;for(String part:name.split("/"))if(part.equals(".")||part.equals("..")||part.endsWith(".")||part.endsWith(" "))return false;return true;}
    private Path active()throws Exception {
        Path file=root.resolve("active.json");if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return null;if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>4096)throw new IOException("Dependency pointer changed; preserve it");var j=JsonParser.parseString(Files.readString(file)).getAsJsonObject();if(!spec.id().equals(j.get("owner").getAsString()))throw new IOException("Dependency ownership changed");String generation=j.get("generation").getAsString();if(!generation.matches("runtime-[a-f0-9]{32}"))throw new IOException("Dependency generation invalid");Path folder=root.resolve(generation);if(!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS)||!folder.toRealPath().equals(folder))throw new IOException("Dependency generation moved");return folder;
    }
    private Map<String,Object> result(Path directory,boolean hit){var out=new LinkedHashMap<String,Object>();out.put("ready",true);out.put("state","ready");out.put("package",spec.id());out.put("version",spec.version());out.put("path",directory.resolve(spec.entry()).toString());out.put("sha256",spec.entrySha256());out.put("archive_sha256",spec.sha256());out.put("cache_hit",hit);out.put("system_installation_changed",false);return out;}
    public Map<String,Object> status(){
        try{guard();Path directory=active();if(directory==null)return Map.of("state","missing","ready",false,"package",spec.id());verify(directory);return result(directory,true);}
        catch(Exception invalid){return Map.of("state","needs_attention","ready",false,"package",spec.id(),"reason","dependency_cache_unavailable_or_changed");}
    }
    private static void cancelled(BooleanSupplier stop)throws IOException {if(stop.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("Dependency preparation cancelled");}
    public Map<String,Object> ensure(BooleanSupplier stop,Consumer<String> progress)throws Exception {
        synchronized(LOCKS.computeIfAbsent(root,k->new Object())){
            guard();cancelled(stop);progress.accept("checking_cache");var current=status();if(Boolean.TRUE.equals(current.get("ready")))return current;
            Files.createDirectories(root);guard();active();capacity();Path partial=Files.createTempFile(root,"download-",".part");Path directory=null;
            try{
                if(!verifiedArchive()){
                    progress.accept("downloading");downloader.download(spec.uri(),partial,stop);cancelled(stop);checkArchive(partial);
                    if(Files.exists(archive(),LinkOption.NOFOLLOW_LINKS)){if(!Files.isRegularFile(archive(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Archive path changed");Files.move(archive(),root.resolve("preserved-"+UUID.randomUUID()+".zip"));}
                    Files.move(partial,archive());
                }
                progress.accept("extracting");directory=root.resolve("runtime-"+UUID.randomUUID().toString().replace("-",""));Files.createDirectory(directory);extract(directory,stop);cancelled(stop);progress.accept("verifying");verify(directory);cancelled(stop);
                Path pointer=root.resolve("active.json"),tmp=Files.createTempFile(root,"pointer-",".part");
                try{if(Files.exists(pointer,LinkOption.NOFOLLOW_LINKS))active();Files.writeString(tmp,JSON.toJson(Map.of("owner",spec.id(),"generation",directory.getFileName().toString())),StandardCharsets.UTF_8);Files.move(tmp,pointer,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}
                return result(directory,false);
            }finally{Files.deleteIfExists(partial);}
        }
    }
    private void capacity()throws Exception {long bytes=0;int files=0;try(var walk=Files.walk(root)){for(Path p:walk.limit(20001).toList()){if(Files.isSymbolicLink(p)||!p.toRealPath().startsWith(root))throw new IOException("Dependency path moved");if(Files.isRegularFile(p)){bytes+=Files.size(p);files++;}}}if(files>=19000||bytes>512L*1024*1024)throw new IOException("Dependency cache capacity reached; preserve/review older generations");try(var dirs=Files.list(root)){if(dirs.filter(p->p.getFileName().toString().startsWith("runtime-")).count()>=4)throw new IOException("Dependency generation capacity reached; preserve/review older generations");}}
    private boolean verifiedArchive(){try{checkArchive(archive());return true;}catch(Exception invalid){return false;}}
    private void checkArchive(Path path)throws Exception {if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)<1||Files.size(path)>MAX_ZIP||!hash(path).equals(spec.sha256()))throw new IOException("Dependency archive SHA256 mismatch");}
    private Map<String,String> entries(Path extract,BooleanSupplier stop)throws Exception {
        checkArchive(archive());var hashes=new TreeMap<String,String>();var names=new HashSet<String>();long total=0;int count=0;
        try(var zip=new ZipInputStream(Files.newInputStream(archive()))){ZipEntry e;while((e=zip.getNextEntry())!=null){cancelled(stop);String name=e.getName();if(++count>10000||!safeName(name)||!names.add(name.toLowerCase(Locale.ROOT)))throw new IOException("Unsafe dependency ZIP entry");if(e.isDirectory())continue;boolean selected=name.startsWith(spec.prefix());var md=MessageDigest.getInstance("SHA-256");OutputStream output=OutputStream.nullOutputStream();
            if(selected&&extract!=null){Path p=extract.resolve(name).normalize();if(!p.startsWith(extract)||p.equals(extract))throw new IOException("Dependency path escaped");Files.createDirectories(p.getParent());output=Files.newOutputStream(p,StandardOpenOption.CREATE_NEW);}
            try(var out=output){byte[] block=new byte[32768];int n;while((n=zip.read(block))!=-1){cancelled(stop);total+=n;if(total>MAX_EXPANDED)throw new IOException("Dependency expansion limit");md.update(block,0,n);out.write(block,0,n);}}
            if(selected)hashes.put(name,HexFormat.of().formatHex(md.digest()));
        }}
        if(!spec.entrySha256().equals(hashes.get(spec.entry())))throw new IOException("Dependency entrypoint SHA256 mismatch");return hashes;
    }
    private void extract(Path directory,BooleanSupplier stop)throws Exception {entries(directory,stop);}
    private void verify(Path directory)throws Exception {
        var expected=entries(null,()->false);var actual=new TreeMap<String,String>();try(var walk=Files.walk(directory)){for(Path p:walk.limit(10001).toList()){if(Files.isSymbolicLink(p)||!p.toRealPath().startsWith(directory))throw new IOException("Dependency file moved");if(Files.isRegularFile(p))actual.put(directory.relativize(p).toString().replace('\\','/'),hash(p));}}if(!expected.equals(actual))throw new IOException("Dependency runtime changed; preserved");
    }
    static String digest(byte[] bytes)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static String hash(Path path)throws Exception {var md=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(path)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)md.update(b,0,n);}return HexFormat.of().formatHex(md.digest());}
    static boolean allowed(URI uri){return "https".equals(uri.getScheme())&&uri.getUserInfo()==null&&(uri.getPort()==-1||uri.getPort()==443)&&uri.getFragment()==null&&Set.of("api.nuget.org","github.com","release-assets.githubusercontent.com","objects.githubusercontent.com").contains(uri.getHost());}
    private static void download(URI uri,Path target,BooleanSupplier stop)throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(180);URI current=uri;
        for(int redirect=0;redirect<5;redirect++){
            cancelled(stop);if(System.nanoTime()>deadline)throw new IOException("Dependency download deadline");if(!allowed(current))throw new IOException("Dependency download origin rejected");var c=(HttpURLConnection)current.toURL().openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(5000);c.setReadTimeout(10000);c.setRequestProperty("User-Agent","EDT-Agent-Bridge-dependency");
            try{int status=c.getResponseCode();if(Set.of(301,302,303,307,308).contains(status)){String location=c.getHeaderField("Location");if(location==null)throw new IOException("Dependency redirect missing");current=current.resolve(location);continue;}if(status!=200)throw new IOException("Dependency download HTTP "+status);if(c.getContentLengthLong()>MAX_ZIP)throw new IOException("Dependency archive too large");
                try(var in=c.getInputStream();var out=Files.newOutputStream(target,StandardOpenOption.TRUNCATE_EXISTING)){byte[] b=new byte[32768];int n;long total=0;while((n=in.read(b))!=-1){cancelled(stop);if(System.nanoTime()>deadline)throw new IOException("Dependency download deadline");total+=n;if(total>MAX_ZIP)throw new IOException("Dependency archive too large");out.write(b,0,n);}}return;
            }finally{c.disconnect();}
        }throw new IOException("Dependency redirect limit");
    }
}
