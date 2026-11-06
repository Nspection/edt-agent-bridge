package com.codepilot1c.core.edt.development;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
public class RunnerProfileStoreTest {
    private static Map<String,Object> profile(Path root){Map<String,Object> p=new HashMap<>();p.put("platform_bin",root.resolve("platform").toString());p.put("infobase_path",root.resolve("target").toString());p.put("manager_infobase_path",root.resolve("manager").toString());p.put("vanessa_epf",root.resolve("va.epf").toString());p.put("params_template",root.resolve("va.json").toString());p.put("expected_vanessa_sha256","a".repeat(64));p.put("user_name","");return p;}
    @Test public void missingProfileReturnsSetupInsteadOfStartingAnything() throws Exception {var store=new RunnerProfileStore(Files.createTempDirectory("runner-profile-"));assertEquals("needs_setup",store.get("Fixture",null).get("state"));assertThrows(IllegalStateException.class,()->store.resolve(Map.of("project","Fixture","profile_id","default")));}
    @Test public void pathsSurviveRestartAndExplicitRunOptionsOverrideProfile() throws Exception {Path root=Files.createTempDirectory("runner-profile-");var store=new RunnerProfileStore(root);var profile=profile(root);store.put("Fixture",null,profile);var restored=new RunnerProfileStore(root);var args=restored.resolve(Map.of("project","Fixture","profile_id","default","update_db",false,"timeout_s",120));assertEquals(profile.get("vanessa_epf"),args.get("vanessa_epf"));assertEquals(120,args.get("timeout_s"));assertFalse((Boolean)args.get("update_db"));}
    @Test public void projectNamesDoNotCollideOrEscapeTheStorageDirectory() throws Exception {Path root=Files.createTempDirectory("runner-profile-");var store=new RunnerProfileStore(root);store.put("../Fixture",null,profile(root));assertFalse((Boolean)store.get("Fixture",null).get("configured"));try(var files=Files.list(root)){assertEquals(1,files.count());}assertThrows(IllegalArgumentException.class,()->store.get("Fixture","../other"));}
    @Test public void refusesCredentialsAndArbitraryCommandLines() throws Exception {Path root=Files.createTempDirectory("runner-profile-");var store=new RunnerProfileStore(root);for(String field:List.of("password","command_line","additional_parameters")){var profile=profile(root);profile.put(field,"must-not-persist");assertThrows(IllegalArgumentException.class,()->store.put("Fixture",null,profile));}try(var files=Files.list(root)){assertEquals(0,files.count());}}
    @Test public void requiresSeparateManagerAndPinnedHash() throws Exception {Path root=Files.createTempDirectory("runner-profile-");var store=new RunnerProfileStore(root);var sameBase=profile(root);sameBase.put("manager_infobase_path",sameBase.get("infobase_path"));assertThrows(IllegalArgumentException.class,()->store.put("Fixture",null,sameBase));var unpinned=profile(root);unpinned.put("expected_vanessa_sha256","unknown");assertThrows(IllegalArgumentException.class,()->store.put("Fixture",null,unpinned));}
    @org.junit.Test public void childEnvironmentExcludesArbitrarySecretsAndAgentTransportVariables() {
        var source=java.util.Map.of("Path","C:/Windows/System32","TEMP","C:/Temp","ANTHROPIC_API_KEY","fixture-secret","UNKNOWN_CREDENTIAL","fixture-secret","CODEX_APP_TOOLS_PIPE_PATH","fixture-pipe");
        var result=RunnerEnvironment.isolated(source);org.junit.Assert.assertEquals(java.util.Map.of("Path","C:/Windows/System32","TEMP","C:/Temp"),result);
    }
    @org.junit.Test public void controllerPathIsStableShortAndSeparatedByWorkspace() throws Exception {
        var a=DevelopmentRunService.runnerController(java.nio.file.Path.of("C:/very/long/workspace/.metadata/.plugins/com.codepilot1c.core/development"),"Project");
        org.junit.Assert.assertEquals(a,DevelopmentRunService.runnerController(java.nio.file.Path.of("C:/very/long/workspace/.metadata/.plugins/com.codepilot1c.core/development"),"Project"));
        org.junit.Assert.assertTrue(a.toString().length()<140);
        org.junit.Assert.assertNotEquals(a,DevelopmentRunService.runnerController(java.nio.file.Path.of("C:/other/workspace"),"Project"));
    }
    @org.junit.Test public void controllerReplyDoesNotWaitForDescendantToCloseInheritedOutput() throws Exception {
        java.nio.file.Path folder=java.nio.file.Files.createTempDirectory("codepilot-pipe-contract");
        String javaExecutable=java.nio.file.Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String cp=System.getProperty("java.class.path").replace('\\','/');
        var child=folder.resolve("child.args");java.nio.file.Files.writeString(child,"-classpath\n\""+cp+"\"\n"+Descendant.class.getName());
        var parent=folder.resolve("parent.args");java.nio.file.Files.writeString(parent,"-classpath\n\""+cp+"\"\n"+Relay.class.getName()+"\n\""+child.toString().replace('\\','/')+"\"");
        long before=System.nanoTime();var result=ManagedVanessaProcess.execute(java.util.List.of(javaExecutable,"@"+parent),folder.resolve("output.json"));
        org.junit.Assert.assertEquals(Boolean.TRUE,result.get("success"));org.junit.Assert.assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)<4000);
    }
    public static class Descendant {public static void main(String[] args) throws Exception {Thread.sleep(5000);}}
    public static class Relay {public static void main(String[] args) throws Exception {String javaExecutable=java.nio.file.Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();new ProcessBuilder(javaExecutable,"@"+args[0]).inheritIO().start();System.out.print("{\"success\":true}");}}
    @Test public void supportsServerTargetWithoutTreatingItAsAPath() throws Exception {Path root=Files.createTempDirectory("runner-server-profile-");var store=new RunnerProfileStore(root);var input=profile(root);input.remove("infobase_path");input.put("server_address","localhost:1541");input.put("server_infobase","Audit");assertTrue(RunnerProfileStore.serverTarget(input));assertEquals(true,store.put("Fixture",null,input).get("configured"));input.put("infobase_path",root.toString());assertThrows(IllegalArgumentException.class,()->store.put("Fixture",null,input));}
}
