// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import org.junit.*;import org.junit.rules.TemporaryFolder;import static org.junit.Assert.*;
import java.nio.file.*;import java.time.*;import java.util.*;import com.google.gson.*;
public class NativeRecoveryAuditTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 @Test public void recycledPidMeansOriginalGoneButUnknownIdentityDoesNotAuthorizeStop(){
  var now=Instant.now();assertEquals(OwnedProcessIdentity.State.GONE,OwnedProcessIdentity.classify(false,Optional.empty(),now));
  assertEquals(OwnedProcessIdentity.State.GONE,OwnedProcessIdentity.classify(true,Optional.of(now.plusMillis(1)),now));
  assertEquals(OwnedProcessIdentity.State.UNKNOWN,OwnedProcessIdentity.classify(true,Optional.empty(),now));
  assertEquals(OwnedProcessIdentity.State.ORIGINAL,OwnedProcessIdentity.classify(true,Optional.of(now),now));
 }
 @Test public void nativeMarkerIsExactStartupArgumentNotSubstring(){
  Path file=Path.of("owned test/session.json").toAbsolutePath();String marker="EDTAgentHost="+file;
  assertTrue(OwnedProcessIdentity.nativeSession(List.of("1cv8.exe","ENTERPRISE","/C",marker),file));
  assertTrue(OwnedProcessIdentity.nativeSession(List.of("/C"+marker),file));
  assertTrue(OwnedProcessIdentity.nativeSession(List.of("/N","/Cuser","/P","/Cpassword","/C",marker),file));
  for(var args:List.of(List.of(marker),List.of("/C",marker+".other"),List.of("/C","prefix"+marker),List.of("/C",marker,"/C","other"),List.of("/Execute",marker)))assertFalse(args.toString(),OwnedProcessIdentity.nativeSession(args,file));
 }
 @Test public void failedAtomicMoveLeavesLastOwnershipRecordAndNoTemporaryFile()throws Exception{
  var root=temp.newFolder().toPath();var file=root.resolve("host.json");var old=JsonParser.parseString("{\"pid\":42,\"state\":\"running\"}").getAsJsonObject();NativeHostState.write(file,old);
  var next=old.deepCopy();next.addProperty("state","stopped");
  assertThrows(java.io.IOException.class,()->NativeHostState.write(file,next,(a,b)->{throw new java.io.IOException("fault");}));assertEquals(old,NativeHostState.read(file));try(var files=Files.list(root)){assertEquals(1,files.count());}
  NativeHostState.write(file,next);assertEquals(next,NativeHostState.read(file));
 }
 @Test public void rootAndExternalUnknownFilesAreNotAdoptedOrDeleted()throws Exception{
  var root=temp.newFolder().toPath();Files.writeString(root.resolve("host.json"),"{}");Files.writeString(root.resolve("my-report.txt"),"user");assertThrows(IllegalStateException.class,()->NativeHostStorage.verifyRoot(root));assertEquals("user",Files.readString(root.resolve("my-report.txt")));
  var external=temp.newFolder().toPath();Files.writeString(external.resolve(".project"),"owned");var expected=OwnedHostSources.snapshot(external);Files.writeString(external.resolve("my-report.txt"),"user");assertThrows(IllegalStateException.class,()->OwnedHostSources.deleteRemaining(external,expected));assertTrue(Files.exists(external.resolve(".project")));
  assertThrows(IllegalStateException.class,()->NativeHostStorage.verifyDeletion(root,root.resolve("external-project/user.txt")));
 }
 @Test public void legacyLateInventoryIsNeverAcceptedAsCreationProof(){
  var legacy=new JsonObject();legacy.add("external_project_files",new JsonObject());legacy.addProperty("external_delete_started",true);assertThrows(IllegalStateException.class,()->OwnedHostSources.requireExternalInventory(legacy));
  legacy.addProperty("external_inventory_version",1);assertTrue(OwnedHostSources.requireExternalInventory(legacy).isEmpty());
 }
 @Test public void currentDoesNotMeanReadyWhenConsentOrBindingBlocksExecution(){
  for(String blocker:List.of("consent_or_startup_question","base_binding_changed")){
   var current=NativeExecutionGate.currentReadiness("host",Map.of("state","running","execution_allowed",false,"execution_blockers",List.of(blocker)),Map.of("compatible",true));assertEquals(true,current.get("current"));assertEquals(false,current.get("ready"));assertEquals(false,current.get("runtime_ready"));assertEquals(List.of(blocker),current.get("execution_blockers"));
  }
 }
 @Test public void oldTransportStageCannotOverrideConfirmedStoppedOrCleanedState(){
  for(String terminal:List.of("stopped","cleaned","prepared","removing_from_base"))assertFalse(NativeStartupInspection.activePhase(terminal));assertTrue(NativeStartupInspection.activePhase("starting"));assertTrue(NativeStartupInspection.activePhase("running"));
 }

}
