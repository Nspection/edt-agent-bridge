// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class PythonEnvironmentServiceTest {
 @Rule public TemporaryFolder temporary=new TemporaryFolder();
 Path root,base;
 Fake runner;
 AtomicBoolean used;
 PythonEnvironmentService service;
 @Before public void setup()throws Exception{
  Path home=temporary.newFolder().toPath();root=home.resolve("managed");base=home.resolve("Python/python.exe");Files.createDirectories(base.getParent());Files.writeString(base,"base-python");runner=new Fake();used=new AtomicBoolean();
  service=new PythonEnvironmentService(root,Map.of("PATH",base.getParent().toString(),"PYTHONPATH","poison","PIP_INDEX_URL","http://untrusted.invalid"),runner,id->used.get(),()->List.of(),"locked-wheels".getBytes(java.nio.charset.StandardCharsets.UTF_8),"test-workspace");
 }
 class Fake implements PythonEnvironmentService.Runner {
  int installs,probes;boolean fail,unproven;int bits=64;String architecture="win-amd64";
  List<String> install;Map<String,String> installEnvironment;
  public PythonEnvironmentService.Output run(List<String> c,Path cwd,Map<String,String> env,int timeout)throws Exception{
   if(c.contains("-c")){
    probes++;boolean dependencies=c.contains("dependencies");var data=new LinkedHashMap<String,Object>();data.put("implementation","CPython");data.put("version",List.of(3,13,9));data.put("bits",bits);data.put("platform","win32");data.put("architecture",architecture);data.put("free_threaded",false);data.put("executable",c.get(0));data.put("base_executable",base.toString());data.put("prefix",dependencies?cwd.resolve("venv").toString():base.getParent().toString());data.put("base_prefix",base.getParent().toString());if(dependencies)data.put("packages",PythonEnvironmentService.PACKAGES);
    return new PythonEnvironmentService.Output(0,PythonEnvironmentService.JSON.toJson(data));
   }
   if(c.contains("venv")){Path target=Path.of(c.get(c.size()-1));Files.createDirectories(target.resolve("Scripts"));Files.writeString(target.resolve("Scripts/python.exe"),"owned-python");Files.writeString(target.resolve("pyvenv.cfg"),"include-system-site-packages = false\n");return new PythonEnvironmentService.Output(0,"created");}
   if(c.contains("install")){installs++;if(unproven)throw new PythonEnvironmentService.PreparationUnproven("simulated lost process proof",new TimeoutException());install=List.copyOf(c);installEnvironment=Map.copyOf(env);return new PythonEnvironmentService.Output(fail?1:0,"installation result");}
   if(c.contains("check"))return new PythonEnvironmentService.Output(0,"consistent");
   throw new AssertionError(c);
  }
 }
 Map<String,Object> prepare()throws Exception{return service.prepare(Map.of(),p->{});}
 String id(Map<String,Object> result){return (String)result.get("environment_id");}
 @Test public void prepareAndReuseWithoutAnotherInstall()throws Exception{
  assertEquals(false,service.inspect(null).get("ready"));assertFalse(Files.exists(root));
  var first=prepare();assertEquals(true,first.get("created"));assertEquals(true,service.inspect(null).get("ready"));var second=prepare();assertEquals(id(first),id(second));assertEquals(true,second.get("reused"));assertEquals(1,runner.installs);
 }
 @Test public void pipIsLockedIsolatedAndOnlyTargetsOwnedVenv()throws Exception{
  var result=prepare();assertTrue(runner.install.containsAll(List.of("--isolated","--no-deps","--require-hashes","--only-binary=:all:","--no-compile","--no-cache-dir","https://pypi.org/simple")));
  assertTrue(Path.of(runner.install.get(0)).startsWith(root.resolve(id(result))));assertFalse(runner.installEnvironment.containsKey("PYTHONPATH"));assertFalse(runner.installEnvironment.containsKey("PIP_INDEX_URL"));assertTrue(Path.of(runner.installEnvironment.get("TEMP")).startsWith(root));assertEquals("base-python",Files.readString(base));
 }
 @Test public void changedFilesArePreservedAndNeverReinstalled()throws Exception{
  var result=prepare();Path python=root.resolve(id(result)).resolve("venv/Scripts/python.exe");Files.writeString(python,"user edit");assertEquals(false,service.inspect(id(result)).get("ready"));
  try{prepare();fail();}catch(java.io.IOException expected){}
  try{service.cleanup(id(result));fail();}catch(java.io.IOException expected){}
  assertEquals("user edit",Files.readString(python));assertEquals(1,runner.installs);
 }
 @Test public void unknownFileOrDirectoryBlocksCleanup()throws Exception{
  var result=prepare();Path extra=root.resolve(id(result)).resolve("user-note.txt");Files.writeString(extra,"keep");
  try{service.cleanup(id(result));fail();}catch(java.io.IOException expected){}assertEquals("keep",Files.readString(extra));Files.delete(extra);
  Path dir=root.resolve(id(result)).resolve("user-directory");Files.createDirectory(dir);try{service.cleanup(id(result));fail();}catch(java.io.IOException expected){}assertTrue(Files.isDirectory(dir));
 }
 @Test public void cleanupRefusesLiveClientThenRemovesOnlyOwnedEnvironment()throws Exception{
  var result=prepare();used.set(true);try{service.cleanup(id(result));fail();}catch(IllegalStateException expected){}assertTrue(Files.exists(root.resolve(id(result))));used.set(false);assertEquals(true,service.cleanup(id(result)).get("cleaned"));assertFalse(Files.exists(root.resolve(id(result))));assertEquals("base-python",Files.readString(base));assertEquals(false,service.inspect(null).get("ready"));
 }
 @Test public void startSelectionLeaseBlocksConcurrentCleanup()throws Exception{
  var result=prepare();var pool=Executors.newSingleThreadExecutor();
  try(var selected=service.select(Map.of())){
   assertEquals(id(result),selected.environmentId());var inspected=pool.submit(()->service.inspect(null)).get(3,TimeUnit.SECONDS);assertEquals("busy",inspected.get("state"));assertEquals(false,inspected.get("ready"));var future=pool.submit(()->service.cleanup(id(result)));
   try{future.get(3,TimeUnit.SECONDS);fail();}catch(ExecutionException expected){assertTrue(expected.getCause() instanceof IllegalStateException);}
  }finally{pool.shutdownNow();}
  assertEquals(true,service.cleanup(id(result)).get("cleaned"));
 }
 @Test public void failedInstallLeavesOwnedInspectableFilesForCleanup()throws Exception{
  runner.fail=true;try{prepare();fail();}catch(IllegalStateException expected){}
  Path folder;try(var dirs=Files.list(root)){folder=dirs.filter(Files::isDirectory).findFirst().orElseThrow();}
  var m=PythonEnvironmentService.JSON.fromJson(Files.readString(folder.resolve("manifest.json")),Map.class);assertEquals("failed",m.get("state"));assertEquals(true,service.cleanup(folder.getFileName().toString()).get("cleaned"));assertEquals("base-python",Files.readString(base));
 }
 @Test public void partialCleanupCanResumeButNeverAcceptsExtraFiles()throws Exception{
  var result=prepare();Path dir=root.resolve(id(result)),record=dir.resolve("manifest.json");var m=PythonEnvironmentService.JSON.fromJson(Files.readString(record),Map.class);m.put("state","cleaning");Files.writeString(record,PythonEnvironmentService.JSON.toJson(m));Files.delete(dir.resolve("install.log"));Path extra=dir.resolve("foreign.txt");Files.writeString(extra,"preserve");
  try{service.cleanup(id(result));fail();}catch(java.io.IOException expected){}assertTrue(Files.exists(extra));Files.delete(extra);assertEquals(true,service.cleanup(id(result)).get("cleaned"));
 }
 @Test public void explicitLegacyPathDoesNotPrepareOrInstall()throws Exception{
  try(var selected=service.select(Map.of("python_path",base.toString()))){assertEquals(base.toRealPath(),selected.python());assertNull(selected.environmentId());}
  assertFalse(Files.exists(root));assertEquals(0,runner.probes);try{service.select(Map.of("python_path",base.toString(),"environment_id","a".repeat(24)));fail();}catch(IllegalArgumentException expected){}
 }
 @Test public void unsupportedInterpreterDoesNotCreateEnvironment()throws Exception{
  runner.bits=32;try{prepare();fail();}catch(IllegalStateException expected){}assertFalse(Files.exists(root));assertEquals(0,runner.installs);
 }
 @Test public void baseInterpreterChangeInvalidatesReadyEnvironment()throws Exception{
  var result=prepare();Files.writeString(base,"upgraded source");assertEquals(false,service.inspect(id(result)).get("ready"));try{service.select(Map.of("environment_id",id(result)));fail();}catch(java.io.IOException expected){}assertTrue(Files.exists(root.resolve(id(result))));
 }
 @Test public void invalidEnvironmentIdsCannotAddressOutsideRoot()throws Exception{
  for(String id:List.of("..","../other","A".repeat(24),"a".repeat(25))){try{service.cleanup(id);fail();}catch(Exception expected){assertFalse(Files.exists(root));}}
 }
 @Test public void anotherWorkspaceCannotReuseOrDeleteEnvironment()throws Exception{
  var result=prepare();var other=new PythonEnvironmentService(root,Map.of(),runner,id->false,()->List.of(),"locked-wheels".getBytes(java.nio.charset.StandardCharsets.UTF_8),"other-workspace");
  assertEquals(false,other.inspect(id(result)).get("ready"));try{other.cleanup(id(result));fail();}catch(java.io.IOException expected){}assertTrue(Files.exists(root.resolve(id(result))));
 }

 @Test public void failedSetupStatusKeepsItsIdForExplicitCleanup()throws Exception{
  runner.fail=true;try{prepare();fail();}catch(IllegalStateException expected){}
  var status=service.inspect(null);assertEquals("failed",status.get("state"));assertEquals(false,status.get("ready"));assertNotNull(status.get("environment_id"));assertEquals("cleanup_environment",status.get("next_action"));
 }
 @Test public void retainedEnvironmentLimitPreventsUnboundedInstallations()throws Exception{
  prepare();for(int i=0;i<3;i++)Files.createDirectory(root.resolve(Integer.toHexString(i).repeat(24)));Files.writeString(base,"another-source-version");
  try{prepare();fail();}catch(IllegalStateException expected){}assertEquals(1,runner.installs);try(var dirs=Files.list(root)){assertEquals(4,dirs.filter(Files::isDirectory).count());}
 }

 @Test public void explicitManagedPathStillGetsAnOwnedLease()throws Exception{
  var result=prepare();try(var selected=service.select(Map.of("python_path",result.get("python_path")))){assertEquals(id(result),selected.environmentId());}
 }
 @Test public void tamperedPartialCleanupCannotAddressOutsideEnvironment()throws Exception{
  var result=prepare();Path record=root.resolve(id(result)).resolve("manifest.json");var m=PythonEnvironmentService.JSON.fromJson(Files.readString(record),Map.class);m.put("state","cleaning");((Map)m.get("files")).put("../../Python/python.exe",PythonEnvironmentService.hash(base));Files.writeString(record,PythonEnvironmentService.JSON.toJson(m));
  try{service.cleanup(id(result));fail();}catch(java.io.IOException expected){}assertEquals("base-python",Files.readString(base));assertTrue(Files.exists(record));
 }

 @Test public void unprovenInstallerIsNeverMarkedSafeForCleanup()throws Exception{
  runner.unproven=true;try{prepare();fail();}catch(IllegalStateException expected){}
  var status=service.inspect(null);assertEquals("interrupted",status.get("state"));assertEquals(false,status.get("ready"));String id=(String)status.get("environment_id");
  try{service.cleanup(id);fail();}catch(IllegalStateException expected){}assertTrue(Files.exists(root.resolve(id)));
  var record=PythonEnvironmentService.JSON.fromJson(Files.readString(root.resolve(id).resolve("manifest.json")),Map.class);assertFalse(record.containsKey("files"));
 }
 @Test public void windowsArm64CannotMasqueradeAsSupportedX64()throws Exception{
  runner.architecture="win-arm64";try{prepare();fail();}catch(IllegalStateException expected){}assertFalse(Files.exists(root));assertEquals(0,runner.installs);
 }


 @Test public void missingEnvironmentGivesExactManagedNextCallWithoutInstalling(){
  var status=service.inspect(null);assertEquals("needs_setup",status.get("state"));assertEquals(Map.of("tool","edt_test_client","arguments",Map.of("action","prepare_environment")),status.get("next_call"));assertEquals("plugin",status.get("setup_owner"));assertEquals(false,status.get("manual_pip_required"));assertEquals(0,runner.installs);
 }
}
