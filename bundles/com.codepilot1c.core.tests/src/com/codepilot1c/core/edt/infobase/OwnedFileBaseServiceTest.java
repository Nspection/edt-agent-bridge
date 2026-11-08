// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
public class OwnedFileBaseServiceTest {
 static class Gateway extends NativeInfobaseGateway {
  boolean foreign;
  public InfobaseReference reference(Path path){var ref=InfobaseReferences.newFileInfobaseReference(path.toString());ref.setUuid(UUID.fromString("12345678-1234-1234-1234-123456789abc"));return ref;}
  public List<String> ownerProjects(InfobaseReference ref){return foreign?List.of("Other"):List.of("Fixture");}
 }
 @Test public void leaseRejectsDifferentBaseAndForeignAssociationsBeforeDeletion()throws Exception {
  Path root=Files.createTempDirectory("owned-file-base-");try {
   Path db=root.resolve("db");Files.createDirectory(db);Files.writeString(db.resolve("1Cv8.1CD"),"fixture");var gateway=new Gateway();var service=new OwnedFileBaseService(gateway,root.resolve("leases"));String uuid=gateway.reference(db).getUuid().toString();String id=service.register("Fixture",db,gateway.reference(db));
   assertThrows(IllegalStateException.class,()->service.preview(id,"Other",uuid));assertThrows(IllegalStateException.class,()->service.preview(id,"Fixture",UUID.randomUUID().toString()));
   var before=service.preview(id,"Fixture",uuid);Files.writeString(db.resolve("extra.txt"),"changed");assertNotEquals(before.get("plan_sha256"),service.preview(id,"Fixture",uuid).get("plan_sha256"));
   gateway.foreign=true;assertThrows(IllegalStateException.class,()->service.preview(id,"Fixture",uuid));assertTrue(Files.exists(db.resolve("1Cv8.1CD")));
  }finally{try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
 }
 @Test public void replacedDatabaseFileInvalidatesItsLease()throws Exception {
  Path root=Files.createTempDirectory("owned-file-key-");try {
   Path db=root.resolve("db");Files.createDirectory(db);Path file=db.resolve("1Cv8.1CD");Files.writeString(file,"old");var gateway=new Gateway();var service=new OwnedFileBaseService(gateway,root.resolve("leases"));String id=service.register("Fixture",db,gateway.reference(db));
   Files.move(file,db.resolve("old.1CD"));Files.writeString(file,"replacement");assertThrows(IllegalStateException.class,()->service.preview(id,"Fixture",gateway.reference(db).getUuid().toString()));
  }finally{try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
 }
 @Test public void interruptedDeletionRejectsNewFilesBeforeNativeTeardown()throws Exception {
  Path root=Files.createTempDirectory("owned-file-resume-");try {
   Path db=root.resolve("db"),leases=root.resolve("leases");Files.createDirectory(db);Files.writeString(db.resolve("1Cv8.1CD"),"old");var gateway=new Gateway();var service=new OwnedFileBaseService(gateway,leases);String id=service.register("Fixture",db,gateway.reference(db));Path state=leases.resolve(id+".json");var doc=com.google.gson.JsonParser.parseString(Files.readString(state)).getAsJsonObject();doc.addProperty("delete_started",true);doc.add("files_before_delete",new com.google.gson.JsonObject());Files.writeString(state,doc.toString());
   Files.writeString(db.resolve("foreign.txt"),"keep");assertThrows(IllegalStateException.class,()->service.preview(id,"Fixture",gateway.reference(db).getUuid().toString()));assertTrue(Files.exists(db.resolve("foreign.txt")));
  }finally{try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
 }

 @Test public void removalQuarantinesBeforeDeletingAndProtectsRecreatedPaths()throws Exception {
  Path root=Files.createTempDirectory("owned-quarantine-");try {
   Path db=root.resolve("db");Files.createDirectory(db);Files.writeString(db.resolve("1Cv8.1CD"),"fixture");var g=new Gateway();int[] detach={0},settings={0};
   var service=new OwnedFileBaseService(g,root.resolve("leases"),new OwnedFileBaseService.CleanupHooks(){
    public void detach(String project,InfobaseReference ref)throws Exception{detach[0]++;assertTrue(Files.exists(db));Files.setLastModifiedTime(db.resolve("1Cv8.1CD"),java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()+2000));try(var channel=java.nio.channels.FileChannel.open(db.resolve("1Cv8.1CD"),StandardOpenOption.READ,StandardOpenOption.WRITE)){assertThrows(java.nio.channels.OverlappingFileLockException.class,()->channel.tryLock());}}
    public void removeSettings(String uuid){settings[0]++;}
   });String uuid=g.reference(db).getUuid().toString(),id=service.register("Fixture",db,g.reference(db));String hash=(String)service.preview(id,"Fixture",uuid).get("plan_sha256");
   assertEquals(true,service.cleanup(id,"Fixture",uuid,hash).get("cleanup_verified"));assertFalse(Files.exists(db));assertFalse(Files.exists(root.resolve(".edt-delete-"+id)));assertEquals(1,detach[0]);assertEquals(1,settings[0]);
   var done=service.preview(id,"Fixture",uuid);service.cleanup(id,"Fixture",uuid,(String)done.get("plan_sha256"));assertEquals(1,detach[0]);assertEquals(2,settings[0]);
   Files.createDirectory(db);Files.writeString(db.resolve("1Cv8.1CD"),"new");assertThrows(IllegalStateException.class,()->service.preview(id,"Fixture",uuid));assertTrue(Files.exists(db));
  }finally{try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
 }

 @Test public void closingEventLogMayFlushButDataFilesAndNewEntriesRemainProtected()throws Exception {
  Path root=Files.createTempDirectory("owned-closing-log-");try {
   Path db=root.resolve("db"),leases=root.resolve("leases");Files.createDirectory(db);Files.writeString(db.resolve("1Cv8.1CD"),"fixture");Files.createDirectory(db.resolve("1Cv8Log"));Path log=db.resolve("1Cv8Log/20261102000000.lgp");Files.writeString(log,"before");var g=new Gateway();var service=new OwnedFileBaseService(g,leases);String uuid=g.reference(db).getUuid().toString(),id=service.register("Fixture",db,g.reference(db));
   Path state=leases.resolve(id+".json");var doc=com.google.gson.JsonParser.parseString(Files.readString(state)).getAsJsonObject();var manifest=new com.google.gson.JsonObject();
   try(var files=Files.walk(db)){for(Path file:files.toList()){var row=new com.google.gson.JsonObject();row.addProperty("directory",Files.isDirectory(file));if(Files.isRegularFile(file)){row.addProperty("size",Files.size(file));row.addProperty("modified",Files.getLastModifiedTime(file).toMillis());}manifest.add(db.relativize(file).toString(),row);}}
   doc.addProperty("delete_started",true);doc.add("files_before_delete",manifest);Files.writeString(state,doc.toString());Files.writeString(log,"closing event flush");assertNotNull(service.preview(id,"Fixture",uuid).get("plan_sha256"));
   Files.writeString(db.resolve("1Cv8Log/20261103000000.lgp"),"foreign");assertThrows(IllegalStateException.class,()->service.preview(id,"Fixture",uuid));Files.delete(db.resolve("1Cv8Log/20261103000000.lgp"));
   Files.writeString(db.resolve("1Cv8.1CD"),"changed data");assertThrows(IllegalStateException.class,()->service.preview(id,"Fixture",uuid));
  }finally{try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
 }

}
