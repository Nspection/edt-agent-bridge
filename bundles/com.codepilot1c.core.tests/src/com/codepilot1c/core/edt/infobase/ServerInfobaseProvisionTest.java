// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import com._1c.g5.v8.dt.platform.services.model.*;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.*;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager.ThickClientInfo;
public class ServerInfobaseProvisionTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 Fake gateway;ServerInfobaseProvisionService service;Map<String,Object> args;
 @Before public void init(){gateway=new Fake(temp.getRoot().toPath().resolve("state"));service=new ServerInfobaseProvisionService(gateway);args=new LinkedHashMap<>(Map.of("project","Test","kind","server","server_address","localhost:1541","runtime_version","8.3.27.2325","db_server","localhost","db_user","postgres","db_password","unit-password"));}
 void approve()throws Exception{var preview=service.preview(args);if(preview.containsKey("creation_id"))args.put("creation_id",preview.get("creation_id"));args.put("expected_preview_sha256",preview.get("preview_sha256"));}
 @Test public void previewIsSecretFreeAndApplyUsesTheSameUniqueNames()throws Exception {
  var first=service.preview(args);assertFalse(first.toString().contains("unit-password"));assertFalse(Files.exists(gateway.root));
  args.put("creation_id",first.get("creation_id"));assertEquals(first.get("preview_sha256"),service.preview(args).get("preview_sha256"));
  assertTrue(first.get("database_name").toString().startsWith("eb_"));
 }
 @Test public void createRestoreVerifyBindFullIsNativeAndOrdered()throws Exception {
  Path dt=temp.newFile("fixture.dt").toPath();Files.writeString(dt,"fixture");args.put("dt_path",dt.toString());approve();var result=service.run(args,p->{});
  assertEquals(List.of("create-server","full-required","restore","full-required","restored-access","verify","bind","full-update"),gateway.calls);assertEquals("full",result.get("update_mode"));assertEquals(true,result.get("application_bound"));
 }
 @Test public void restoredCredentialsAreInstalledBeforeVerification()throws Exception {Path dt=temp.newFile("credentials.dt").toPath();Files.writeString(dt,"fixture");args.put("dt_path",dt.toString());args.put("login","RestoredUser");args.put("password","restored-fixture-secret");approve();var result=service.run(args,p->{});assertEquals("RestoredUser",gateway.restoredLogin);assertEquals("restored-fixture-secret",gateway.restoredPassword);assertTrue(gateway.calls.indexOf("restored-access")<gateway.calls.indexOf("verify"));assertFalse(result.toString().contains("restored-fixture-secret"));}
 @Test public void usedCreationIdIsNeverSilentlyReused()throws Exception {
  approve();service.run(args,p->{});assertThrows(IllegalStateException.class,()->service.run(args,p->{}));assertEquals(1,Collections.frequency(gateway.calls,"create-server"));
 }
 @Test public void serverRestoreRequiresExplicitPermissionAndBackup()throws Exception {
  args.put("server_operation","restore");args.put("server_infobase","Existing");assertThrows(IllegalArgumentException.class,()->service.preview(args));
  Path dt=temp.newFile("fixture.dt").toPath();Files.writeString(dt,"fixture");args.put("dt_path",dt.toString());args.put("restore_existing",true);approve();service.run(args,p->{});
  assertTrue(gateway.calls.indexOf("backup")<gateway.calls.indexOf("restore"));assertFalse(gateway.calls.contains("create-server"));
 }
 @Test public void bindDoesNotCreateRestoreOrUpdateByDefault()throws Exception {
  args.put("server_operation","bind");args.put("server_infobase","Existing");approve();service.run(args,p->{});assertEquals(List.of("verify","bind"),gateway.calls);
 }
 @Test public void bindRetainsPendingFullReload()throws Exception {args.put("server_operation","bind");args.put("server_infobase","Existing");approve();assertEquals(true,service.run(args,p->{}).get("full_reload_required"));}
 @Test public void rebindingDetachesOldSynchronizationBeforeRuntimeAccessUpdates()throws Exception {args.put("server_operation","bind");args.put("server_infobase","Existing");args.put("update_from_project",true);approve();service.run(args,p->{});assertEquals(1,gateway.disconnects);assertTrue(gateway.calls.contains("full-update"));}
 @Test public void endpointAndNativeArgumentDelimiterInjectionRejected()throws Exception {
  assertThrows(IllegalArgumentException.class,()->new ServerInfobaseTarget("localhost;Ref=bad","Test"));assertThrows(IllegalArgumentException.class,()->new ServerInfobaseTarget("localhost","bad\";Ref=Other"));
  args.put("db_password","test\";DB=other");assertThrows(IllegalArgumentException.class,()->service.preview(args));
 }
 @Test public void sqlPasswordIsRedactedFromNativeErrors(){assertFalse(InfobaseProvisionService.safeError(new RuntimeException("native unit-password"),args).contains("unit-password"));}
 @Test public void rawFilePathCannotBeAccidentallyUsedForServer()throws Exception{args.put("database_path",temp.getRoot().toString());assertThrows(IllegalArgumentException.class,()->service.preview(args));}
 static class Fake extends NativeInfobaseGateway {
        String restoredLogin,restoredPassword;
        @Override public void restoredAccess(com._1c.g5.v8.dt.platform.services.model.InfobaseReference ref,String login,String password) {restoredLogin=login;restoredPassword=password;calls.add("restored-access");}
  int disconnects;final Path root;final List<String> calls=new ArrayList<>();Fake(Path root){this.root=root;}
  @Override public Path stateRoot(){return root;}
  @Override public IProject project(String n){return null;}
  @Override public void awaitProject(String n){}
  @Override public InfobaseReference serverReference(ServerInfobaseTarget t){var ref=t.reference();ref.setUuid(UUID.randomUUID());return ref;}
  @Override public boolean isRegistered(InfobaseReference r){return false;}
  @Override public void checkPrimaryServer(String p,InfobaseReference r,boolean primary,boolean replace){}
  @Override public List<String> ownerProjects(InfobaseReference r){return List.of();}
  @Override public Map<String,Object> primaryIdentity(String p){return Map.of("present",false);}
  @Override public String runtimeDescription(InfobaseReference r,String v){return "native";}
  @Override public ThickClientInfo runtime(InfobaseReference r,String v){return null;}
  @Override public void createServer(ThickClientInfo i,InfobaseReference r,Map<String,Object> a,String db){assertTrue(db.matches("eb_[a-f0-9]{32}"));calls.add("create-server");}
  @Override public boolean requiresFull(InfobaseReference r){return true;}
  @Override public void requireFull(InfobaseReference r){calls.add("full-required");}
  @Override public void restore(ThickClientInfo i,InfobaseReference r,Path dt,RuntimeExecutionArguments a){calls.add("restore");}
  @Override public Path export(ThickClientInfo i,InfobaseReference r,Path dir,RuntimeExecutionArguments a)throws Exception{calls.add("verify");Files.createDirectories(dir);Files.writeString(dir.resolve("Configuration.xml"),"fixture");return dir;}
  @Override public void selectRuntimeForApplication(String p,InfobaseReference r,String v){}
  @Override public InfobaseReference bind(String p,InfobaseReference r,String login,String password,boolean primary){calls.add("bind");return r;}
  @Override public boolean bound(String p,InfobaseReference r){return true;}
  @Override public Map<String,Object> update(String p,InfobaseReference r,IProgressMonitor m){calls.add("full-update");return Map.of("update_mode","full");}
  @Override public void disconnectBeforeRestore(String p,InfobaseReference r){disconnects++;}
  @Override public void dump(ThickClientInfo i,InfobaseReference r,Path dt,RuntimeExecutionArguments a)throws Exception{calls.add("backup");Files.writeString(dt,"backup");}
 }
}
