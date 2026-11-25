// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import com.codepilot1c.core.edt.development.AgentBrief;
import com.google.gson.JsonParser;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import com._1c.g5.v8.dt.platform.services.model.*;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.*;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager.ThickClientInfo;
public class ServerInfobaseProvisionTest {
 @org.junit.Test public void taskAccessRequiresUnambiguousCredentials(){
  var empty=ServerInfobaseProvisionService.readTaskAccess(
      "<div>Пользователь - &quot;Test_User&quot; (без пароля)</div>");
  assertEquals("Test_User",empty.user());
  assertEquals("",empty.password());
  var withPassword=ServerInfobaseProvisionService.readTaskAccess(
      "<div>Пользователь - &quot;Test_User&quot;</div><div>Пароль: &quot;fixture-secret&quot;</div>");
  assertEquals("fixture-secret",withPassword.password());
  assertFalse(withPassword.toString().contains("fixture-secret"));
  assertThrows(IllegalArgumentException.class,()->ServerInfobaseProvisionService.readTaskAccess(
      "<div>Пользователь - &quot;Test_User&quot; (пароль указан отдельно)</div>"));
  assertThrows(IllegalArgumentException.class,()->ServerInfobaseProvisionService.readTaskAccess(
      "<div>Пользователь - &quot;A&quot; (без пароля)</div><div>Пользователь - &quot;B&quot; (без пароля)</div>"));
 }

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
 @Test public void savedOldAccessAndChangedRestoredAccessStaySeparate() throws Exception {
  configureRestore();
  gateway.savedLogin="OldUser";
  gateway.savedPassword="stored-old-fixture";
  gateway.updatedLogin="NewUser";
  gateway.updatedPassword="confirmed-new-fixture";
  var progress=new ArrayList<Map<String,Object>>();
  var result=service.run(args,progress::add);
  assertEquals("OldUser",gateway.backupLogin);
  assertEquals("OldUser",gateway.importLogin);
  assertEquals("stored-old-fixture",gateway.backupPassword);
  assertEquals(gateway.backupPassword,gateway.importPassword);
  assertNull(gateway.initialVerifyLogin);
  assertEquals("NewUser",gateway.boundLogin);
  assertEquals("confirmed-new-fixture",gateway.boundPassword);
  assertEquals(List.of("backup","full-required","restore","full-required","verify","bind","full-update"),gateway.calls);
  assertTrue(progress.stream().anyMatch(p->"verify_restored_access".equals(p.get("stage"))));
  assertFalse((result.toString()+progress).contains("fixture-secret"));
  assertFalse((result.toString()+progress).contains("stored-old-fixture"));
  assertFalse((result.toString()+progress).contains("confirmed-new-fixture"));
  var lines = Files.readAllLines(Path.of(result.get("restore_journal").toString()));
  var starts = new ArrayList<String>();
  for (String line : lines) {
   var event = JsonParser.parseString(line).getAsJsonObject();
   if (event.get("event").getAsString().equals("stage_started")) starts.add(event.get("stage").getAsString());
   else assertTrue(event.get("duration_ms").getAsLong() >= 0);
   assertTrue(Set.of("event","stage","timestamp","outcome","duration_ms").containsAll(event.keySet()));
  }
  assertEquals(List.of("preflight","disconnect_existing","backup_existing","restore_dt",
      "verify_restored_access","bind_application","full_reload"),starts);
  assertEquals("completed",JsonParser.parseString(lines.get(lines.size()-1)).getAsJsonObject().get("outcome").getAsString());
  assertFalse(lines.toString().contains("stored-old-fixture"));
  assertFalse(lines.toString().contains("confirmed-new-fixture"));
  assertFalse(lines.toString().contains("OldUser"));
  assertFalse(lines.toString().contains("NewUser"));
 }
 @Test public void correctedBackupArgumentsAreReusedForImport() throws Exception {
  configureRestore();
  gateway.savedLogin="ExpiredUser";
  gateway.correctBackupLogin=true;
  gateway.updatedLogin="NewUser";
  service.run(args,p->{});
  assertEquals("ExpiredUser",gateway.backupLogin);
  assertEquals("CorrectedOldUser",gateway.importLogin);
  assertEquals("NewUser",gateway.boundLogin);
 }
 @Test public void aFailedBackupNeverDispatchesImport() throws Exception {
  configureRestore();
  gateway.failBackup=true;
  assertThrows(IllegalStateException.class,()->service.run(args,p->{}));
  assertEquals(List.of("backup"),gateway.calls);
 }
 @Test public void unknownImportBlocksASecondApplyForTheSameBase() throws Exception {
  configureRestore();
  gateway.failImport=true;
  var progress=new ArrayList<Map<String,Object>>();
  var error=assertThrows(IllegalStateException.class,()->service.run(args,progress::add));
  assertFalse(error.getMessage().contains("fixture-secret"));
  var unknown = progress.stream().filter(p->Boolean.TRUE.equals(p.get("outcome_unknown"))).findFirst().orElseThrow();
  assertFalse(unknown.containsKey("import_completed"));
  gateway.failImport=false;
  assertThrows(IllegalStateException.class,()->service.run(args,p->{}));
  assertEquals(1,Collections.frequency(gateway.calls,"backup"));
  assertEquals(1,Collections.frequency(gateway.calls,"restore"));
 }
 @Test public void activeOrUnverifiedTraceReportsSafeBlockBeforeNativeMutation() throws Exception {
  configureRestore();
  for (var trace : List.of(NativeInfobaseGateway.PlatformTrace.ACTIVE,
      NativeInfobaseGateway.PlatformTrace.UNVERIFIED)) {
   gateway.trace = trace;
   var progress = new ArrayList<Map<String,Object>>();
   var error = assertThrows(IllegalStateException.class, () -> service.run(args, progress::add));
   var status = progress.get(progress.size()-1);
   assertTrue(error.getMessage().contains("Leave EDT settings unchanged"));
   assertEquals("restore_blocked_by_trace", status.get("stage"));
   assertEquals("preflight", status.get("blocked_before_stage"));
   assertEquals(false, status.get("import_completed"));
   assertEquals(false, status.get("retry_safe"));
   assertEquals("keep_edt_tracing_unchanged", status.get("operator_action"));
   var compact = (Map<?,?>) AgentBrief.operation(Map.of("progress", status)).get("progress");
   assertEquals(status.get("error_code"), compact.get("error_code"));
   assertEquals(status.get("restore_journal"), compact.get("restore_journal"));
   assertTrue(Files.readString(Path.of(status.get("restore_journal").toString())).contains(status.get("error_code").toString()));
   assertEquals(0, gateway.disconnects);
   assertTrue(gateway.calls.isEmpty());
  }
 }

 @Test public void traceDetectedAfterImportReportsConfirmedImportWithoutReplaying() throws Exception {
  configureRestore();
  gateway.traceAfterImport = true;
  var progress = new ArrayList<Map<String,Object>>();
  var error = assertThrows(IllegalStateException.class, () -> service.run(args, progress::add));
  var status = progress.get(progress.size()-1);
  assertEquals("verify_restored_access", status.get("blocked_before_stage"));
  assertEquals(true, status.get("import_completed"));
  assertEquals(false, status.get("retry_safe"));
  assertTrue(error.getMessage().contains("Do not repeat it"));
  assertEquals(1, Collections.frequency(gateway.calls,"restore"));
  assertFalse(gateway.calls.contains("verify"));
 }

 @Test public void confirmationWriteFailureKeepsTheImportClaimAndReportsKnownCompletion() throws Exception {
  configureRestore();
  gateway.blockImportConfirmation = true;
  var progress = new ArrayList<Map<String,Object>>();
  var error = assertThrows(IllegalStateException.class, () -> service.run(args, progress::add));
  var status = progress.get(progress.size()-1);
  assertEquals("restore_import_confirmation_write_failed", status.get("error_code"));
  assertEquals(true, status.get("import_completed"));
  assertEquals(false, status.get("retry_safe"));
  assertTrue(error.getMessage().contains("Do not repeat the import"));
  assertTrue(Files.readString(Path.of(status.get("restore_journal").toString()))
      .contains("restore_import_confirmation_write_failed"));
  gateway.blockImportConfirmation = false;
  assertThrows(IllegalStateException.class, () -> service.run(args, p->{}));
  assertEquals(1, Collections.frequency(gateway.calls,"restore"));
 }

 void configureRestore()throws Exception {
  Path dt=temp.newFile("restored.dt").toPath();
  Files.writeString(dt,"fixture");
  args.put("server_operation","restore");
  args.put("server_infobase","Existing");
  args.put("dt_path",dt.toString());
  args.put("restore_existing",true);
  approve();
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
        String savedLogin,savedPassword,backupLogin,backupPassword,importLogin,importPassword;
        String initialVerifyLogin,updatedLogin,updatedPassword,boundLogin,boundPassword;
        boolean correctBackupLogin,failBackup,failImport,traceAfterImport,blockImportConfirmation;
        PlatformTrace trace = PlatformTrace.INACTIVE;
        @Override PlatformTrace platformCommandTrace(){return trace;}
        @Override public RuntimeExecutionArguments existingServerArguments(InfobaseReference r,String login,String password,IProgressMonitor monitor,Path log) {
            return arguments(login==null?savedLogin:login,login==null?savedPassword:password,monitor,log);
        }
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
  @Override public void restore(ThickClientInfo i,InfobaseReference r,Path dt,RuntimeExecutionArguments a)throws Exception {
   importLogin=a.getUsername();
   importPassword=a.getPassword();
   calls.add("restore");
   if (failImport) throw new IllegalStateException("fixture-secret");
   if (traceAfterImport) trace=PlatformTrace.ACTIVE;
   if (blockImportConfirmation) {
    try (var candidates=Files.list(root)) {
     var operation=candidates.filter(path->Files.isRegularFile(path.resolve("before-restore.dt"))).findFirst().orElseThrow();
     Files.createDirectory(operation.resolve("import-confirmed"));
    }
   }
  }
  @Override public Path export(ThickClientInfo i,InfobaseReference r,Path dir,RuntimeExecutionArguments a)throws Exception{initialVerifyLogin=a.getUsername();if(updatedLogin!=null){a.setAccess(InfobaseAccess.INFOBASE);a.setUsername(updatedLogin);a.setPassword(updatedPassword);}calls.add("verify");Files.createDirectories(dir);Files.writeString(dir.resolve("Configuration.xml"),"fixture");return dir;}
  @Override public void selectRuntimeForApplication(String p,InfobaseReference r,String v){}
  @Override public InfobaseReference bind(String p,InfobaseReference r,String login,String password,boolean primary){boundLogin=login;boundPassword=password;calls.add("bind");return r;}
  @Override public boolean bound(String p,InfobaseReference r){return true;}
  @Override public Map<String,Object> update(String p,InfobaseReference r,IProgressMonitor m){calls.add("full-update");return Map.of("update_mode","full");}
  @Override public void disconnectBeforeRestore(String p,InfobaseReference r){disconnects++;}
  @Override public void dump(ThickClientInfo i,InfobaseReference r,Path dt,RuntimeExecutionArguments a)throws Exception{backupLogin=a.getUsername();backupPassword=a.getPassword();calls.add("backup");if(failBackup)throw new IllegalStateException("Backup failed");if(correctBackupLogin)a.setUsername("CorrectedOldUser");Files.writeString(dt,"backup");}
 }
    @org.junit.Test public void plainTaskAccessPreservesLiteralPasswordCharacters() {
        var access = ServerInfobaseProvisionService.readPlainTaskAccess(
                "\uFEFFЛогин: \"DemoUser\"\nПароль: \"p<abc>&amp;\"");
        org.junit.Assert.assertEquals("DemoUser",access.user());
        org.junit.Assert.assertEquals("p<abc>&amp;",access.password());
    }
}
