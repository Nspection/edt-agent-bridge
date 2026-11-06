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
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.*;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager.ThickClientInfo;

public class InfobaseProvisionServiceTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private Fake gateway;private InfobaseProvisionService service;private Map<String,Object> args;
    @Before public void setup() throws Exception {
        gateway=new Fake(temp.getRoot().toPath().resolve("state"));service=new InfobaseProvisionService(gateway);
        args=new LinkedHashMap<>(Map.of("project","Test","runtime_version","8.5.1.1522","database_path",temp.getRoot().toPath().resolve("new-base").toString()));
    }
    private void approve() throws Exception {args.put("expected_preview_sha256",service.preview(args).get("preview_sha256"));}
    @Test public void previewDoesNotCreateDirectoriesOrEchoPasswords() throws Exception {
        args.put("login","test-user");args.put("password","secret-42");var plan=service.preview(args);
        assertFalse(plan.toString().contains("secret-42"));assertFalse(Files.exists(Path.of((String)args.get("database_path"))));assertTrue(gateway.calls.isEmpty());
    }
    @Test public void firstUpdateUsesFullReloadAfterVerifiedRestoreAndBinding() throws Exception {
        Path dt=temp.newFile("source.dt").toPath();Files.writeString(dt,"test dump");args.put("dt_path",dt.toString());approve();
        var stages=new ArrayList<String>();var result=service.run(args,p->stages.add((String)p.get("stage")));
        assertEquals(List.of("create","require-full","restore","verify","bind","full-update"),gateway.calls);
        assertEquals("full",result.get("update_mode"));assertEquals(true,result.get("configuration_synchronized"));assertTrue(stages.indexOf("restore_dt")<stages.indexOf("full_reload"));
    }
    @Test public void deferredUpdateKeepsFullRequirement() throws Exception {
        args.put("update_from_project",false);approve();var result=service.run(args,p->{});
        assertTrue(gateway.calls.contains("require-full"));assertFalse(gateway.calls.contains("full-update"));assertEquals(true,result.get("full_reload_required"));
    }
    @Test public void changedDtRejectsApplyBeforeAnyMutation() throws Exception {
        Path dt=temp.newFile("source.dt").toPath();Files.writeString(dt,"first");args.put("dt_path",dt.toString());approve();Files.writeString(dt,"different size");
        try {service.run(args,p->{});fail();}catch(IllegalStateException e){assertTrue(e.getMessage().contains("STALE_PREVIEW"));}
        assertTrue(gateway.calls.isEmpty());
    }
    @Test public void existingDatabaseCannotBeReplacedImplicitly() throws Exception {
        Path db=Path.of((String)args.get("database_path"));Files.createDirectory(db);Files.writeString(db.resolve("1Cv8.1CD"),"old");
        try {service.preview(args);fail();}catch(IllegalArgumentException expected){}assertTrue(gateway.calls.isEmpty());
    }
    @Test public void backupFailurePreventsRestore() throws Exception {
        Path db=Path.of((String)args.get("database_path"));Files.createDirectory(db);Files.writeString(db.resolve("1Cv8.1CD"),"old");
        Path dt=temp.newFile("source.dt").toPath();Files.writeString(dt,"new");args.put("dt_path",dt.toString());args.put("restore_existing",true);gateway.failBackup=true;approve();
        try{service.run(args,p->{});fail();}catch(IllegalStateException expected){}
        assertFalse(gateway.calls.contains("restore"));assertEquals("old",Files.readString(db.resolve("1Cv8.1CD")));
    }
    @Test public void failedVerificationNeverBindsOrUpdates() throws Exception {
        gateway.failVerify=true;approve();try{service.run(args,p->{});fail();}catch(IllegalStateException expected){}
        assertFalse(gateway.calls.contains("bind"));assertFalse(gateway.calls.contains("full-update"));
    }
    @Test public void explicitRestoreBacksUpBeforeReplacing() throws Exception {
        Path db=Path.of((String)args.get("database_path"));Files.createDirectory(db);Files.writeString(db.resolve("1Cv8.1CD"),"old");
        Path dt=temp.newFile("source.dt").toPath();Files.writeString(dt,"new");args.put("dt_path",dt.toString());args.put("restore_existing",true);approve();var result=service.run(args,p->{});
        assertEquals(List.of("disconnect","backup","require-full","restore","verify","bind","full-update"),gateway.calls);assertTrue(Files.isRegularFile(Path.of((String)result.get("backup_dt"))));
    }
    @Test public void oldAndRestoredCredentialsGoToTheirOwnStages() throws Exception {
        Path db=Path.of((String)args.get("database_path"));Files.createDirectory(db);Files.writeString(db.resolve("1Cv8.1CD"),"old");
        Path dt=temp.newFile("source.dt").toPath();Files.writeString(dt,"new");args.put("dt_path",dt.toString());args.put("restore_existing",true);
        args.put("existing_login","old-user");args.put("existing_password","old-password");args.put("login","restored-user");args.put("password","restored-password");
        approve();service.run(args,p->{});
        assertEquals("old-user",gateway.backupUser);assertEquals("old-password",gateway.backupPassword);
        assertEquals("old-user",gateway.restoreUser);assertEquals("restored-user",gateway.verifyUser);
        assertEquals("restored-user",gateway.boundUser);assertEquals("restored-password",gateway.boundPassword);
    }
    @Test public void secretsAreRemovedFromNativeErrors() {
        assertFalse(InfobaseProvisionService.safeError(new RuntimeException("native failed secret-42"),Map.of("password","secret-42")).contains("secret-42"));
    }
    @Test public void passwordWithoutLoginFailsBeforeCreatingDatabase() throws Exception {
        for(String prefix:List.of("","existing_")) {
            var invalid=new LinkedHashMap<>(args);invalid.put(prefix+"password","secret");
            try{service.preview(invalid);fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains(prefix+"login"));}
        }
        assertTrue(gateway.calls.isEmpty());
    }
    @Test public void changedDtDuringBackupIsNotRestored() throws Exception {
        Path db=Path.of((String)args.get("database_path"));Files.createDirectory(db);Files.writeString(db.resolve("1Cv8.1CD"),"old");
        Path dt=temp.newFile("source.dt").toPath();Files.writeString(dt,"approved");args.put("dt_path",dt.toString());args.put("restore_existing",true);
        gateway.changeDtDuringBackup=dt;approve();
        try{service.run(args,p->{});fail();}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("STALE_PREVIEW"));}
        assertTrue(gateway.calls.contains("backup"));assertFalse(gateway.calls.contains("restore"));assertEquals("old",Files.readString(db.resolve("1Cv8.1CD")));
    }
    private static class Fake extends NativeInfobaseGateway {
        final Path root;final List<String> calls=new ArrayList<>();boolean failBackup,failVerify,selectedRuntime;
        Path changeDtDuringBackup;
        String backupUser,backupPassword,restoreUser,verifyUser,boundUser,boundPassword;
        Fake(Path root){this.root=root;}
        @Override public IProject project(String name){return null;}
        @Override public void awaitProject(String name){}
        @Override public void selectRuntimeForApplication(String name,InfobaseReference ref,String version){assertEquals("8.5.1.1522",version);selectedRuntime=true;}
        @Override public Path stateRoot(){return root;}
        @Override public InfobaseReference reference(Path path){return InfobaseReferences.newFileInfobaseReference(path.toString());}
        @Override public String runtimeDescription(InfobaseReference ref,String version){return "test-native-runtime";}
        @Override public ThickClientInfo runtime(InfobaseReference ref,String version){return null;}
        @Override public List<String> ownerProjects(InfobaseReference ref){return List.of();}
        @Override public Map<String,Object> primaryIdentity(String n){return Map.of("present",false);}
        @Override public void checkPrimary(String p,Path path,boolean primary,boolean replace){}
        @Override public void requireFull(InfobaseReference ref){calls.add("require-full");}
        @Override public void create(ThickClientInfo info,InfobaseReference ref)throws Exception{calls.add("create");Files.writeString(Path.of(ref.getConnectionString().asConnectionString().split("\"")[1]).resolve("1Cv8.1CD"),"created");}
        @Override public void restore(ThickClientInfo i,InfobaseReference r,Path dt,RuntimeExecutionArguments a){calls.add("restore");restoreUser=a.getUsername();}
        @Override public void disconnectBeforeRestore(String p,InfobaseReference r){calls.add("disconnect");}
        @Override public void dump(ThickClientInfo i,InfobaseReference r,Path dt,RuntimeExecutionArguments a)throws Exception{calls.add("backup");backupUser=a.getUsername();backupPassword=a.getPassword();if(failBackup)throw new IllegalStateException("backup failed");Files.writeString(dt,"backup");if(changeDtDuringBackup!=null)Files.writeString(changeDtDuringBackup,"unapproved replacement");}
        @Override public Path export(ThickClientInfo i,InfobaseReference r,Path p,RuntimeExecutionArguments a)throws Exception{calls.add("verify");verifyUser=a.getUsername();if(failVerify)throw new IllegalStateException("access failed");Files.createDirectories(p);Files.writeString(p.resolve("Configuration.xml"),"configuration");return p;}
        @Override public InfobaseReference bind(String n,InfobaseReference r,String l,String p,boolean primary){assertTrue(selectedRuntime);calls.add("bind");boundUser=l;boundPassword=p;return r;}
        @Override public boolean bound(String n,InfobaseReference r){return true;}
        @Override public Map<String,Object> update(String n,InfobaseReference r,IProgressMonitor m){calls.add("full-update");return Map.of("update_mode","full","configuration_synchronized",true);}
    }
}
