// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import com.google.gson.*;

/** Pinned download/cache only: never installs a component, opens 1C or changes trust. */
public final class NativePackageService {
 public static final int MAX_ZIP=16*1024*1024;
 private static final Map<Path,Object> LOCKS=new ConcurrentHashMap<>();
 public record Descriptor(String releaseTag,String zipSha256,String dllSha256,List<String> features){
  public Descriptor {
   if(releaseTag==null||!releaseTag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+\\.[A-Za-z0-9_-]{1,100}")||zipSha256==null||!zipSha256.matches("[a-f0-9]{64}")||dllSha256==null||!dllSha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid pinned Native descriptor");
   features=List.copyOf(features);if(!features.equals(List.of("screenshot_png")))throw new IllegalArgumentException("Unsupported Native features");
  }
  public URI uri(){return URI.create("https://github.com/Nspection/edt-agent-bridge/releases/download/"+releaseTag+"/agent-host-native-windows-x64.zip");}
 }
 @FunctionalInterface public interface Downloader {void download(URI uri,Path target,BooleanSupplier cancelled)throws Exception;}
 private final Path root;private final Descriptor descriptor;private final Downloader downloader;
 public NativePackageService(Path root,Descriptor descriptor,Downloader downloader)throws Exception {
  this.root=root.toAbsolutePath().normalize();this.descriptor=descriptor;this.downloader=downloader;Files.createDirectories(this.root);guard();
 }
 public static Descriptor bundled()throws Exception {
  try(var in=NativePackageService.class.getResourceAsStream("/resources/agent-host/native-package.json")){
   if(in==null)throw new IOException("Native descriptor unavailable");byte[] bytes=in.readNBytes(8193);if(bytes.length>8192)throw new IOException("Native descriptor exceeds limit");var j=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
   if(j.get("schema").getAsInt()!=1||j.get("protocol").getAsInt()!=2||!"windows-x64".equals(j.get("platform").getAsString()))throw new IOException("Unsupported Native descriptor");
   var features=new ArrayList<String>();j.getAsJsonArray("features").forEach(x->features.add(x.getAsString()));return new Descriptor(j.get("release_tag").getAsString(),j.get("zip_sha256").getAsString(),j.get("dll_sha256").getAsString(),features);
  }
 }
 public static NativePackageService workspace()throws Exception {
  Path workspace=org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().toRealPath();
  return new NativePackageService(workspace.resolve(".metadata/.plugins/com.codepilot1c.core/native-package"),bundled(),NativePackageService::downloadHttps);
 }
 public Descriptor descriptor(){return descriptor;}
 private void guard()throws Exception {if(Files.isSymbolicLink(root)||!root.toRealPath().equals(root))throw new IOException("Native cache path changed");}
 public Path archive(){return root.resolve(descriptor.zipSha256()+".zip");}
 private static void cancelled(BooleanSupplier test)throws IOException {if(test.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("Native package operation cancelled");}
 public Map<String,Object> status() {
  var out=new LinkedHashMap<String,Object>();out.put("ready",false);out.put("state","missing");out.put("package_release",descriptor.releaseTag());out.put("expected_native_sha256",descriptor.zipSha256());out.put("protocol",2);out.put("features",descriptor.features());
  try {guard();if(Files.exists(archive(),LinkOption.NOFOLLOW_LINKS)){validate(archive());out.put("ready",true);out.put("state","ready");out.put("native_path",archive().toString());}}
  catch(Exception invalid){out.put("state","invalid_cache");out.put("reason","cache_integrity_or_path_check_failed");}
  return out;
 }
 public Map<String,Object> ensure(BooleanSupplier stop,Consumer<String> progress)throws Exception {
  synchronized(LOCKS.computeIfAbsent(root,k->new Object())){
   guard();cancelled(stop);progress.accept("checking_cache");var existing=status();if(Boolean.TRUE.equals(existing.get("ready"))){existing.put("cache_hit",true);return existing;}
   capacity();Path temp=Files.createTempFile(root,"native-package-",".part");
   try {
    progress.accept("downloading");downloader.download(descriptor.uri(),temp,stop);cancelled(stop);progress.accept("verifying");validate(temp);cancelled(stop);
    if(Files.exists(archive(),LinkOption.NOFOLLOW_LINKS)){
     if(Boolean.TRUE.equals(status().get("ready"))){var result=status();result.put("cache_hit",true);return result;}
     if(!Files.isRegularFile(archive(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Native cache entry is not a regular file");
     Path preserved=root.resolve("preserved");if(Files.exists(preserved,LinkOption.NOFOLLOW_LINKS)&&!Files.isDirectory(preserved,LinkOption.NOFOLLOW_LINKS))throw new IOException("Native preserved-cache directory changed");Files.createDirectories(preserved);
     try(var files=Files.list(preserved)){if(files.count()>=4)throw new IOException("Native preserved-cache capacity reached");}
     Files.move(archive(),preserved.resolve(UUID.randomUUID()+".zip"));
    }
    Files.move(temp,archive());var result=status();if(!Boolean.TRUE.equals(result.get("ready")))throw new IOException("Native cache verification failed");result.put("cache_hit",false);return result;
   }finally{Files.deleteIfExists(temp);}
  }
 }
 private void capacity()throws Exception {
  long bytes=0;int count=0;
  try(var files=Files.walk(root)){for(Path file:files.toList()){if(Files.isSymbolicLink(file))throw new IOException("Native cache path changed");if(Files.isRegularFile(file)){bytes+=Files.size(file);count++;}}}
  if(count>=32||bytes>128L*1024*1024)throw new IOException("Native cache capacity reached");
 }
 private void validate(Path file)throws Exception {
  if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)<1||Files.size(file)>MAX_ZIP||!sha(file).equals(descriptor.zipSha256()))throw new IOException("Native ZIP SHA256 mismatch");
  var seen=new HashSet<String>();long total=0;byte[] manifest=null;
  try(var zip=new ZipInputStream(Files.newInputStream(file))){ZipEntry entry;
   while((entry=zip.getNextEntry())!=null){String name=entry.getName();if(entry.isDirectory()||!Set.of("manifest.xml","AgentHostNative.dll","SDK-LICENSE","LICENSE").contains(name)||!seen.add(name))throw new IOException("Unexpected Native ZIP entry");
    var hash=MessageDigest.getInstance("SHA-256");ByteArrayOutputStream xml=name.equals("manifest.xml")?new ByteArrayOutputStream():null;byte[] block=new byte[8192];int n;
    while((n=zip.read(block))!=-1){total+=n;if(total>32L*1024*1024||xml!=null&&xml.size()+n>8192)throw new IOException("Native ZIP expansion exceeds limit");hash.update(block,0,n);if(xml!=null)xml.write(block,0,n);}
    if(name.equals("AgentHostNative.dll")&&!HexFormat.of().formatHex(hash.digest()).equals(descriptor.dllSha256()))throw new IOException("Native DLL SHA256 mismatch");if(xml!=null)manifest=xml.toByteArray();
   }
  }
  if(seen.size()!=4||manifest==null)throw new IOException("Incomplete Native ZIP");
  var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);factory.setNamespaceAware(true);
  var doc=factory.newDocumentBuilder().parse(new ByteArrayInputStream(manifest));if(!"bundle".equals(doc.getDocumentElement().getLocalName())||!"http://v8.1c.ru/8.2/addin/bundle".equals(doc.getDocumentElement().getNamespaceURI()))throw new IOException("Native manifest root mismatch");var parts=doc.getElementsByTagNameNS("http://v8.1c.ru/8.2/addin/bundle","component");
  if(parts.getLength()!=1)throw new IOException("Native component manifest mismatch");var component=(org.w3c.dom.Element)parts.item(0);
  if(!"Windows".equals(component.getAttribute("os"))||!"native".equals(component.getAttribute("type"))||!"x86_64".equals(component.getAttribute("arch"))||!"AgentHostNative.dll".equals(component.getAttribute("path")))throw new IOException("Native component architecture mismatch");
 }
 static String sha(Path file)throws Exception {var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(file)){byte[] block=new byte[8192];int n;while((n=in.read(block))!=-1)digest.update(block,0,n);}return HexFormat.of().formatHex(digest.digest());}
 static boolean allowedDownload(URI uri){return "https".equals(uri.getScheme())&&uri.getUserInfo()==null&&(uri.getPort()==-1||uri.getPort()==443)&&uri.getFragment()==null&&Set.of("github.com","release-assets.githubusercontent.com","objects.githubusercontent.com").contains(uri.getHost());}
 private static void downloadHttps(URI uri,Path target,BooleanSupplier stop)throws Exception {
  long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);URI current=uri;
  for(int redirects=0;redirects<5;redirects++){
   cancelled(stop);if(!allowedDownload(current))throw new IOException("Native download origin rejected");var c=(HttpURLConnection)current.toURL().openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(5000);c.setReadTimeout(10000);c.setRequestProperty("User-Agent","EDT-Agent-Bridge-native-package");
   try {
    int status=c.getResponseCode();if(Set.of(301,302,303,307,308).contains(status)){String location=c.getHeaderField("Location");if(location==null)throw new IOException("Native redirect missing");current=current.resolve(location);continue;}
    if(status!=200)throw new IOException("Native download HTTP "+status);if(c.getContentLengthLong()>MAX_ZIP)throw new IOException("Native download exceeds limit");
    try(var in=c.getInputStream();var out=Files.newOutputStream(target,StandardOpenOption.TRUNCATE_EXISTING)){byte[] block=new byte[8192];int n,total=0;while((n=in.read(block))!=-1){cancelled(stop);if(System.nanoTime()>deadline)throw new IOException("Native download deadline exceeded");total+=n;if(total>MAX_ZIP)throw new IOException("Native download exceeds limit");out.write(block,0,n);}}return;
   }finally{c.disconnect();}
  }
  throw new IOException("Native redirect limit reached");
 }
}
