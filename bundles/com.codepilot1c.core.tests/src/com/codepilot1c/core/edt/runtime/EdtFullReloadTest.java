// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.runtime;
import static org.junit.Assert.*;
import org.junit.Test;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.*;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
public class EdtFullReloadTest {
 public static class Manager {
  int full,incremental;
  public IStatus reloadInfobase(IProject p,InfobaseReference r,Object c,boolean keep,IProgressMonitor m){full++;return Status.OK_STATUS;}
  public IStatus updateInfobase(IProject p,InfobaseReference r,Object c,boolean keep,IProgressMonitor m){incremental++;return Status.OK_STATUS;}
 }
 @Test public void explicitFullUsesNativeReloadAndNeverIncremental()throws Exception{check(true,false,1,0);}
 @Test public void pendingRestoreForcesFullEvenWhenCallerRequestsIncremental()throws Exception{check(false,true,1,0);}
 @Test public void ordinarySubsequentUpdateCanRemainIncremental()throws Exception{check(false,false,0,1);}
 @Test public void failedEqualityCheckKeepsFullRequirement()throws Exception {
  var manager=new Manager();var gateway=new EdtRuntimeGateway(){@Override public Object getInfobaseSynchronizationManager(){return manager;}};
  boolean[] marker={false};
  var service=new EdtRuntimeService(gateway){
   @Override protected boolean fullReloadRequired(InfobaseReference ref){return marker[0];}
   @Override protected void fullReloadStarted(InfobaseReference ref){marker[0]=true;}
   @Override protected void verifyFullReload(IProject p,InfobaseReference r,IProgressMonitor m){throw new IllegalStateException("not equal");}
   @Override protected void fullReloadCompleted(InfobaseReference ref){marker[0]=false;}
  };
  try{service.updateInfobase(null,null,true,new NullProgressMonitor(),true);fail();}catch(IllegalStateException expected){}
  assertTrue(marker[0]);assertEquals(1,manager.full);assertEquals(0,manager.incremental);
 }
 @Test public void hiddenVanessaFormUsesThePlatformBooleanLiteral()throws Exception {
  var method=EdtRuntimeService.class.getDeclaredMethod("buildStartupOption",java.io.File.class,java.io.File.class,boolean.class,boolean.class,boolean.class);
  method.setAccessible(true);
  String startup=(String)method.invoke(new EdtRuntimeService(),null,null,false,true,false);
  assertTrue(startup.contains(";ShowMainForm=Ложь"));
 }
 @Test public void asynchronousUpdateFailureCannotBecomeADoneResult()throws Exception {
  var resolver=new EdtProjectResolver(){@Override public InfobaseReference resolveInfobase(String n,java.io.File w){return null;}};
  var runtime=new EdtRuntimeService(){@Override public boolean requiresFullReload(String n){throw new IllegalStateException("test update failure");}};
  var tool=new com.codepilot1c.core.tools.workspace.EdtUpdateInfobaseTool(resolver,runtime);
  var method=tool.getClass().getDeclaredMethod("runUpdateAndRenderResult",String.class,String.class,boolean.class,java.io.File.class,boolean.class);method.setAccessible(true);
  try{method.invoke(tool,"test-op","test-project",true,null,false);fail();}
  catch(java.lang.reflect.InvocationTargetException expected){assertTrue(expected.getCause() instanceof IllegalStateException);assertTrue(expected.getCause().getMessage().contains("test update failure"));}
 }
 private void check(boolean requested,boolean required,int full,int incremental)throws Exception {
  var manager=new Manager();var gateway=new EdtRuntimeGateway(){@Override public Object getInfobaseSynchronizationManager(){return manager;}};
  var service=new EdtRuntimeService(gateway){
   @Override protected boolean fullReloadRequired(InfobaseReference ref){return required;}
   @Override protected void fullReloadStarted(InfobaseReference ref){}
   @Override protected void verifyFullReload(IProject p,InfobaseReference r,IProgressMonitor m){}
   @Override protected void fullReloadCompleted(InfobaseReference ref){}
  };
  assertTrue(service.updateInfobase(null,null,true,new NullProgressMonitor(),requested));assertEquals(full,manager.full);assertEquals(incremental,manager.incremental);
 }
}
