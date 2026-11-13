// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.nio.file.*;
import java.util.*;
/** Active folders and immutable cleaned receipts have separate storage and lookup. */
final class DirectClientStorage {
 private DirectClientStorage(){}
 static Path key(Path root,String id){if(id==null||!id.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid client_id");return root.resolve(id);}
 static Path resolve(Path root,String id)throws Exception{
  key(root,id);
  Path active=root.resolve(id);if(Files.exists(active,LinkOption.NOFOLLOW_LINKS))return active;
  Path history=root.resolve("history");if(Files.isSymbolicLink(history))throw new IllegalStateException("Client history moved");Path archived=history.resolve(id);if(!history.toRealPath().equals(root.toRealPath().resolve("history"))||!cleaned(archived))throw new IllegalStateException("Invalid cleaned client receipt");return archived;
 }
 static boolean cleaned(Path folder)throws Exception{Path receipt=folder.resolve("client.json");if(Files.isSymbolicLink(folder)||Files.isSymbolicLink(receipt)||!Files.isRegularFile(receipt)||Files.size(receipt)>32768)return false;var row=PythonEnvironmentService.JSON.fromJson(Files.readString(receipt),Map.class);return row!=null&&Boolean.TRUE.equals(row.get("cleaned"))&&"cleaned".equals(row.get("state"))&&folder.getFileName().toString().equals(row.get("client_id"));}
 static List<Path> pending(Path root)throws Exception{try(var entries=Files.list(root)){return entries.filter(p->p.getFileName().toString().matches("[a-f0-9]{32}")).toList();}}
 static List<Path> active(Path root)throws Exception{
  var result=new ArrayList<Path>();try(var entries=Files.list(root)){for(Path folder:entries.filter(p->p.getFileName().toString().matches("[a-f0-9]{32}")).toList()){
   if(!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(folder)){result.add(folder);continue;}
   try{synchronized(DirectClientLifecycle.lock(folder)){if(archive(root,folder))continue;}}catch(Exception preserved){/* Unproven entries still count against capacity. */}
   result.add(folder);
  }}return result;
 }
 static boolean archive(Path root,Path folder)throws Exception{
  Path receipt=folder.resolve("client.json");if(Files.isSymbolicLink(folder)||Files.isSymbolicLink(receipt)||!Files.isRegularFile(receipt)||Files.size(receipt)>32768)return false;
  var row=PythonEnvironmentService.JSON.fromJson(Files.readString(receipt),Map.class);
  if(!cleaned(folder))return false;
  try(var children=Files.list(folder)){if(children.anyMatch(p->!p.equals(receipt)))return false;}
  Path history=root.resolve("history");if(Files.isSymbolicLink(history))throw new IllegalStateException("Client history moved");Files.createDirectories(history);
  if(!history.toRealPath().equals(root.toRealPath().resolve("history")))throw new IllegalStateException("Client history changed");
  Path target=history.resolve(folder.getFileName());if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))return false;
  Files.move(folder,target,StandardCopyOption.ATOMIC_MOVE);return true;
 }
}
