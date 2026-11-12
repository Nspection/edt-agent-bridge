// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.workspace;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.Test;
public class ProjectLifecycleServiceTest {
 static class Fake implements ProjectLifecycleService.Backend {
  boolean open=true,partial=false,rebuild;int calls;String operation;List<String> blockers=new ArrayList<>();
  public Map<String,Object> snapshot(String project){return Map.of("project",project,"project_path","C:/owned/fixture","open",open,"blockers",new ArrayList<>(blockers));}
  public Map<String,Object> perform(String project,String operation,boolean rebuild,String expectedPath,Consumer<String> phase){calls++;this.operation=operation;this.rebuild=rebuild;if(partial){open=false;phase.accept("closed");throw new IllegalStateException("open failed");}open=!operation.equals("close");return Map.of("project_open",open,"phase","completed");}
 }
 static Map<String,Object> preview(ProjectLifecycleService service,String operation)throws Exception{return service.execute(Map.of("project","Fixture","action","preview","operation",operation));}
 static Map<String,Object> applyArgs(String op,Map<String,Object> preview,String key){return Map.of("project","Fixture","action","apply","operation",op,"expected_plan_sha256",preview.get("plan_sha256"),"idempotency_key",key);}
 @Test public void closeAndOpenAreExactScopedAndJournalledAcrossServiceRestart()throws Exception {
  var f=new Fake();var root=Files.createTempDirectory("project-lifecycle-");var service=new ProjectLifecycleService(root,f);var a=applyArgs("close",preview(service,"close"),"close1");
  var result=service.execute(a);assertEquals("completed",result.get("state"));assertEquals(false,result.get("project_open"));assertEquals(1,f.calls);
  assertEquals("completed",new ProjectLifecycleService(root,f).execute(a).get("state"));assertEquals(1,f.calls);
  assertEquals(true,preview(service,"open").get("allowed"));service.execute(applyArgs("open",preview(service,"open"),"open1"));assertTrue(f.open);assertEquals(2,f.calls);
 }
 @Test public void dirtyCompareRuntimeBuildBlockersNeverDispatch()throws Exception {
  for(String blocker:List.of("dirty_editors","active_native_comparison","active_or_unresolved_runtime","workspace_build_in_progress")){
   var f=new Fake();f.blockers.add(blocker);var service=new ProjectLifecycleService(Files.createTempDirectory("lifecycle-block-"),f);var p=preview(service,"reopen");assertEquals(false,p.get("allowed"));assertThrows(IllegalStateException.class,()->service.execute(applyArgs("reopen",p,"k")));assertEquals(0,f.calls);
  }
 }
 @Test public void freshStateChangeInvalidatesQueuedPreview()throws Exception {
  var f=new Fake();var service=new ProjectLifecycleService(Files.createTempDirectory("lifecycle-stale-"),f);var a=applyArgs("close",preview(service,"close"),"k");f.blockers.add("dirty_editors");assertThrows(IllegalStateException.class,()->service.execute(a));assertEquals(0,f.calls);
 }
 @Test public void partialReopenKeepsClosedPhaseAndCannotReplayWithSameKey()throws Exception {
  var f=new Fake();f.partial=true;var root=Files.createTempDirectory("lifecycle-partial-");var service=new ProjectLifecycleService(root,f);var a=applyArgs("reopen",preview(service,"reopen"),"k");var result=service.execute(a);assertEquals("failed",result.get("state"));assertEquals("closed",result.get("phase"));assertEquals(true,result.get("outcome_unknown"));assertFalse(f.open);new ProjectLifecycleService(root,f).execute(a);assertEquals(1,f.calls);
 }
 @Test public void sameKeyWithDifferentRequestCannotCauseAnotherBuild()throws Exception {
  var f=new Fake();var service=new ProjectLifecycleService(Files.createTempDirectory("lifecycle-key-"),f);service.execute(applyArgs("clean",preview(service,"clean"),"k"));assertThrows(IllegalStateException.class,()->service.execute(applyArgs("reopen",preview(service,"reopen"),"k")));assertEquals(1,f.calls);
 }
 @Test public void cleanRebuildIsExplicitAndClosedProjectCannotSilentlySucceed()throws Exception {
  var f=new Fake();var service=new ProjectLifecycleService(Files.createTempDirectory("lifecycle-clean-"),f);service.execute(applyArgs("clean",preview(service,"clean"),"k"));assertTrue(f.rebuild);assertEquals("clean",f.operation);
  var p=service.execute(Map.of("project","Fixture","action","preview","operation","clean","rebuild",false));var a=new HashMap<>(applyArgs("clean",p,"k2"));a.put("rebuild",false);service.execute(a);assertFalse(f.rebuild);f.open=false;assertEquals(false,preview(service,"clean").get("allowed"));
 }
 @Test public void unknownActionAndAllProjectsAreNotSupported()throws Exception {
  var service=new ProjectLifecycleService(Files.createTempDirectory("lifecycle-args-"),new Fake());assertThrows(IllegalArgumentException.class,()->service.execute(Map.of("project","Fixture","action","delete")));assertThrows(IllegalArgumentException.class,()->service.execute(Map.of("project","Fixture","action","preview","operation","clean","all",true)));
 }
}
