// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import org.junit.*;import org.junit.rules.TemporaryFolder;import static org.junit.Assert.*;
import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
public class DirectClientStartReservationTest {
 @Rule public TemporaryFolder tmp=new TemporaryFolder();
 private static final Map<String,Object> SCOPE=Map.of("project","own","base_uuid","11111111-1111-4111-8111-111111111111","artifact",Map.of("sha256","a".repeat(64)));
 private RuntimeConsentPolicy grant(Path root)throws Exception {var p=new RuntimeConsentPolicy(root);var plan=p.preview(SCOPE,"once");p.apply(SCOPE,"once",(String)plan.get("plan_sha256"));return p;}
 private Path stopped(Path root,int n)throws Exception {Path p=Files.createDirectory(root.resolve(String.format("%032x",n)));Files.writeString(p.resolve("client.json"),PythonEnvironmentService.JSON.toJson(Map.of("client_id",p.getFileName().toString(),"state","stopped","cleaned",false)));return p;}
 @Test public void fourStoppedRecordsBlockBeforeConsentAndReturnExactRecoveryIds()throws Exception {
  Path root=tmp.newFolder().toPath();var p=grant(tmp.newFolder().toPath());for(int i=0;i<4;i++)stopped(root,i);var calls=new AtomicInteger();
  var error=assertThrows(DirectClientStartReservation.CapacityBlocked.class,()->{try(var slot=DirectClientStartReservation.reserve(root)){calls.incrementAndGet();p.claim(SCOPE);fail("Cannot enter prelaunch");}});
  var r=error.result();assertEquals(0,calls.get());assertEquals(false,p.status(SCOPE).get("used"));assertEquals(true,r.get("retry_safe"));assertEquals(false,r.get("consent_consumed"));assertEquals(false,r.get("process_started"));assertEquals(false,r.get("outcome_unknown"));
  var rows=(List<?>)r.get("blocking_clients");assertEquals(4,rows.size());for(int i=0;i<4;i++){var row=(Map<?,?>)rows.get(i);assertEquals(String.format("%032x",i),row.get("client_id"));assertEquals("stopped",row.get("state"));assertEquals(false,row.get("process_liveness_checked"));assertEquals(2,((List<?>)row.get("next_calls")).size());}
 }
 @Test public void parallelLastSlotConsumesExactlyOneIndependentOnceConsent()throws Exception {
  Path root=tmp.newFolder().toPath();for(int i=0;i<3;i++)stopped(root,i);var one=grant(tmp.newFolder().toPath());var two=grant(tmp.newFolder().toPath());var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try{var tasks=new ArrayList<Future<Boolean>>();for(var p:List.of(one,two))tasks.add(pool.submit(()->{ready.countDown();go.await();try(var slot=DirectClientStartReservation.reserve(root)){assertFalse(p.claim(SCOPE).isEmpty());slot.commit();return true;}catch(DirectClientStartReservation.CapacityBlocked blocked){return false;}}));assertTrue(ready.await(2,TimeUnit.SECONDS));go.countDown();assertNotEquals(tasks.get(0).get(2,TimeUnit.SECONDS),tasks.get(1).get(2,TimeUnit.SECONDS));assertNotEquals(one.status(SCOPE).get("used"),two.status(SCOPE).get("used"));assertEquals(4,DirectClientStorage.pending(root).size());}finally{go.countDown();pool.shutdownNow();}
 }
 @Test public void readOnlyConsentCheckPreservesOnceAndMissingConsentDecision()throws Exception {
  Path root=tmp.newFolder().toPath();var p=grant(tmp.newFolder().toPath());try(var slot=DirectClientStartReservation.reserve(root)){RuntimeConsentPolicy.requireAvailable(p,"edt_test_client",Map.of("project","own"),SCOPE);assertEquals(false,p.status(SCOPE).get("used"));}assertTrue(DirectClientStorage.pending(root).isEmpty());
  var missing=new RuntimeConsentPolicy(tmp.newFolder().toPath());var e=assertThrows(RuntimeConsentPolicy.Required.class,()->{try(var slot=DirectClientStartReservation.reserve(root)){RuntimeConsentPolicy.requireAvailable(missing,"edt_test_client",Map.of("project","own"),SCOPE);}});assertEquals("awaiting_consent",e.result().get("state"));assertFalse(((Map<?,?>)e.result().get("decision")).isEmpty());assertTrue(DirectClientStorage.pending(root).isEmpty());
 }
 @Test public void failureBeforeClaimReleasesOnlyEmptyReservation()throws Exception {
  Path root=tmp.newFolder().toPath();var p=grant(tmp.newFolder().toPath());assertThrows(java.io.IOException.class,()->{try(var slot=DirectClientStartReservation.reserve(root)){throw new java.io.IOException("Python preflight failed");}});assertTrue(DirectClientStorage.pending(root).isEmpty());assertEquals(false,p.status(SCOPE).get("used"));
 }
 @Test public void foreignFilesInUncommittedReservationArePreserved()throws Exception {
  Path root=tmp.newFolder().toPath();var slot=DirectClientStartReservation.reserve(root);Path file=root.resolve(slot.id()).resolve("user.txt");Files.writeString(file,"keep");assertThrows(DirectoryNotEmptyException.class,slot::close);assertEquals("keep",Files.readString(file));
 }
 @Test public void replacedOrModifiedOwnershipMarkerIsPreserved()throws Exception {
  Path root=tmp.newFolder().toPath();var slot=DirectClientStartReservation.reserve(root);Path marker=root.resolve(slot.id()).resolve("start-reservation");Files.writeString(marker,"foreign");assertThrows(IllegalStateException.class,slot::close);assertEquals("foreign",Files.readString(marker));assertTrue(Files.isDirectory(marker.getParent()));
 }
 @Test public void corruptAndMissingRecordsCountButNeverSuggestAutomaticCleanup()throws Exception {
  Path root=tmp.newFolder().toPath();for(int i=0;i<4;i++){Path p=Files.createDirectory(root.resolve(String.format("%032x",i)));if(i%2==0)Files.writeString(p.resolve("client.json"),"{}");}var e=assertThrows(DirectClientStartReservation.CapacityBlocked.class,()->DirectClientStartReservation.reserve(root));for(Object o:(List<?>)e.result().get("blocking_clients")){var row=(Map<?,?>)o;assertEquals("unproven",row.get("state"));assertFalse(row.containsKey("next_calls"));}assertEquals(4,DirectClientStorage.pending(root).size());
 }
 @Test public void cleanedHistoryDoesNotBlockAndCommittedReceiptIsKept()throws Exception {
  Path root=tmp.newFolder().toPath();Files.createDirectory(root.resolve("history"));for(int i=0;i<4;i++){var p=stopped(root,i);Files.writeString(p.resolve("client.json"),PythonEnvironmentService.JSON.toJson(Map.of("client_id",p.getFileName().toString(),"state","cleaned","cleaned",true)));}String id;try(var slot=DirectClientStartReservation.reserve(root)){id=slot.id();Files.writeString(root.resolve(id).resolve("client.json"),"durable start receipt");slot.commit();}assertTrue(Files.exists(root.resolve(id).resolve("client.json")));
 }
 @Test public void storedFieldsCannotLeakArbitraryErrorsOrCredentials()throws Exception {
  Path root=tmp.newFolder().toPath();for(int i=0;i<4;i++){var p=stopped(root,i);Files.writeString(p.resolve("client.json"),"{\"client_id\":\""+p.getFileName()+"\",\"state\":\"SECRET_PASSWORD\",\"password\":\"SECRET_PASSWORD\"}");}var e=assertThrows(DirectClientStartReservation.CapacityBlocked.class,()->DirectClientStartReservation.reserve(root));assertFalse(PythonEnvironmentService.JSON.toJson(e.result()).contains("SECRET_PASSWORD"));
 }
}
