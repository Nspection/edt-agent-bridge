// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
public class InfobasePathsTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 @Test public void relativePathsAndRootsRejected()throws Exception {
  for(String s:List.of("relative/db",temp.getRoot().toPath().getRoot().toString(),temp.getRoot()+"/../db"))try{InfobasePaths.absolute(s);fail(s);}catch(IllegalArgumentException expected){}
 }
 @Test public void competingWorkflowCannotAcquireSameDatabase() throws Exception {
  try(var flow=new InfobaseWorkflow(List.of("db:test"),p->{})) {
   var task=new java.util.concurrent.FutureTask<Boolean>(()->{
    try{new InfobaseWorkflow(List.of("project:new","db:test"),p->{});return false;}
    catch(IllegalStateException expected){try(var free=new InfobaseWorkflow(List.of("project:new"),p->{})){}return true;}
   });
   new Thread(task).start();assertTrue(task.get(3,java.util.concurrent.TimeUnit.SECONDS));
  }
  try(var free=new InfobaseWorkflow(List.of("db:test"),p->{})){}
 }
 @Test public void nestedNativeUpdateCanReenterItsProvisioningLock() {
  try(var outer=new InfobaseWorkflow(List.of("db:test"),p->{})) {
   try(var nested=new InfobaseWorkflow(List.of("db:test"),p->{})){}
  }
  try(var released=new InfobaseWorkflow(List.of("db:test"),p->{})){}
 }
 @Test public void cleanupHashIncludesFilesOutsideSources() throws Exception {
  Path root=temp.newFolder("snapshot").toPath();Files.createDirectory(root.resolve("src"));Files.writeString(root.resolve("src/a.bsl"),"code");
  String before=ProjectFromApplicationService.treeHash(root);
  Files.writeString(root.resolve("user-note.txt"),"keep me");assertNotEquals(before,ProjectFromApplicationService.treeHash(root));
 }
 @Test public void nestedPasswordsWithSpacesQuotesAndNewlinesAreNeverLogged() {
  String secret="private \"quoted\"\npassword";
  var input=Map.<String,Object>of("password",secret,"nested",List.of(Map.of("existing_password",secret)),"database_path","test-db");
  String safe=new com.google.gson.Gson().toJson(com.codepilot1c.core.logging.LogSanitizer.safeArguments(input));
  assertFalse(safe.contains("private"));assertFalse(safe.contains("quoted"));assertTrue(safe.contains("test-db"));assertEquals(secret,input.get("password"));
 }
 @Test public void linkageFailureDoesNotLeaveAnOperationRunningForever()throws Exception {
  var registry=com.codepilot1c.core.tools.workspace.BackgroundJobRegistry.createForTest();
  try {
   String id=registry.startJob("missing-sdk",()->{throw new NoClassDefFoundError("MissingSdkForTest");});
   long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
   while(registry.getStatus(id).orElseThrow().getFinishedAt()==null && System.nanoTime()<deadline)Thread.sleep(10);
   assertEquals(com.codepilot1c.core.tools.workspace.BackgroundJobRegistry.JobState.FAILED,registry.getStatus(id).orElseThrow().getState());
  } finally {registry.shutdown();}
 }
 @Test public void fingerprintIgnoresMapOrder() {
  assertEquals(InfobasePaths.fingerprintHash(Map.of("a",1,"b",2)),InfobasePaths.fingerprintHash(new LinkedHashMap<>(Map.of("b",2,"a",1))));
 }
}
