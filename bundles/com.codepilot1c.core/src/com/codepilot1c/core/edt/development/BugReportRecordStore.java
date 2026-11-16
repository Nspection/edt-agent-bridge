// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.nio.file.*;
import java.util.*;
/** Preserve damaged evidence and a permanent ID barrier; corruption never authorizes replay. */
final class BugReportRecordStore {
 static final class Unavailable extends IllegalStateException {private static final long serialVersionUID=1L;Unavailable(){super("report_record_unavailable_no_automatic_replay");}}
 private final Path root,blocked,quarantine;
 BugReportRecordStore(Path root)throws Exception{this.root=root.toAbsolutePath().normalize();blocked=this.root.resolve("blocked");quarantine=this.root.resolve("quarantine");directory(this.root);directory(blocked);directory(quarantine);}
 static void directory(Path path)throws Exception{if(Files.isSymbolicLink(path)||Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("Report storage directory unavailable");Files.createDirectories(path);}
 private Path barrier(String id){if(id==null||!id.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid report record ID");return blocked.resolve(id+".json");}
 boolean blocked(String id){return Files.exists(barrier(id),LinkOption.NOFOLLOW_LINKS);}
 void requireAvailable(String id){if(blocked(id))throw new Unavailable();}
 void preserve(Path source,String id,Throwable failure)throws Exception{
  if(!source.toAbsolutePath().normalize().startsWith(root))throw new IllegalStateException("Invalid report evidence path");directory(blocked);directory(quarantine);
  if(!blocked(id)){var row=new com.google.gson.JsonObject();row.addProperty("id",id);row.addProperty("reason","record_corrupt_no_automatic_replay");row.addProperty("error_type",failure.getClass().getSimpleName());row.addProperty("automatic_replay",false);NativeHostState.write(barrier(id),row);}
  if(Files.exists(source,LinkOption.NOFOLLOW_LINKS))Files.move(source,quarantine.resolve(id+"-"+UUID.randomUUID()+".corrupt"),StandardCopyOption.ATOMIC_MOVE);
 }
 static Map<String,Object> unavailable(String field,String id){return Map.of(field,id,"state","unavailable","terminal",true,"reason","record_corrupt_no_automatic_replay","automatic_replay",false,"retry_safe",false,"requires_manual_recovery",true);}
}
