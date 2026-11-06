package com.codepilot1c.core.mcp.host.transport;
import org.junit.Test;
import static org.junit.Assert.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import com.sun.net.httpserver.Headers;
import com.codepilot1c.core.mcp.host.McpHostConfig;
public class McpBridgeSurfaceTest {
 @Test public void migrationClosesOnlyRetiredViewReferences(){
  java.util.List<String> hidden=new java.util.ArrayList<>();
  String[] ids={"com.codepilot1c.ui.views.ChatView","org.eclipse.ui.views.ProblemView","com.codepilot1c.ui.views.MemoryInspectorView"};
  var refs=new org.eclipse.ui.IViewReference[ids.length];
  for(int n=0;n<ids.length;n++){final String id=ids[n];refs[n]=(org.eclipse.ui.IViewReference)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{org.eclipse.ui.IViewReference.class},(proxy,m,args)->m.getName().equals("getId")?id:null);}
  var page=(org.eclipse.ui.IWorkbenchPage)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{org.eclipse.ui.IWorkbenchPage.class},(proxy,m,args)->{
   if(m.getName().equals("getViewReferences"))return refs;
   if(m.getName().equals("hideView")){hidden.add(((org.eclipse.ui.IViewReference)args[0]).getId());return null;}
   throw new AssertionError("Unexpected editor/workbench mutation: "+m.getName());
  });
  assertEquals(2,com.codepilot1c.ui.bridge.BridgeWorkbenchMigration.closeLegacyViews(page));
  assertEquals(java.util.List.of(ids[0],ids[2]),hidden);
 }
 @Test public void migrationRemovesOwnedPlaceholdersAndSharedPartsOnly(){
  var basic=org.eclipse.e4.ui.model.application.ui.basic.MBasicFactory.INSTANCE;
  var app=org.eclipse.e4.ui.model.application.MApplicationFactory.INSTANCE.createApplication();
  var window=basic.createWindow();app.getChildren().add(window);var stack=basic.createPartStack();window.getChildren().add(stack);
  var chat=basic.createPart();chat.setElementId("com.codepilot1c.ui.views.ChatView");window.getSharedElements().add(chat);
  var keep=basic.createPart();keep.setElementId("org.eclipse.ui.views.ProblemView");stack.getChildren().add(keep);
  var ref=org.eclipse.e4.ui.model.application.ui.advanced.MAdvancedFactory.INSTANCE.createPlaceholder();ref.setRef(chat);stack.getChildren().add(ref);
  var models=(org.eclipse.e4.ui.workbench.modeling.EModelService)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{org.eclipse.e4.ui.workbench.modeling.EModelService.class},(proxy,m,args)->{
   if(!m.getName().equals("findElements"))throw new AssertionError(m.getName());
   if(args[2]==org.eclipse.e4.ui.model.application.ui.basic.MPart.class)return java.util.List.of(chat,keep);
   if(args[2]==org.eclipse.e4.ui.model.application.ui.basic.MWindow.class)return java.util.List.of(window);
   return java.util.List.of(ref);
  });
  assertEquals(2,com.codepilot1c.ui.bridge.BridgeWorkbenchMigration.removeLegacyModelElements(app,models));
  assertFalse(stack.getChildren().contains(ref));assertFalse(window.getSharedElements().contains(chat));assertTrue(stack.getChildren().contains(keep));
 }
 @Test public void defaultPortAndAuthenticationRemainStableEvenWhenDefaultPortIsOccupied()throws Exception{
  java.net.ServerSocket occupied=null;
  try{try{occupied=new java.net.ServerSocket(8765,1,java.net.InetAddress.getLoopbackAddress());}catch(java.io.IOException alreadyOccupied){/* A real listener already exercises the occupied-port case. */}
   var config=McpHostConfig.defaults();assertEquals(8765,config.getPort());assertEquals(McpHostConfig.AuthMode.BEARER_ONLY,config.getAuthMode());assertFalse(config.isLlmEnabled());
  }finally{if(occupied!=null)occupied.close();}
 }
 @Test public void tokenIsMandatoryAndMultipleHeadersAreRejected(){
  var auth=new McpBearerAuthorization("127.0.0.1",0,"bridge-test-token");var h=new Headers();
  assertFalse(auth.isAuthorized(h));h.add("Authorization","Bearer wrong");assertFalse(auth.isAuthorized(h));
  h.set("Authorization","Bearer bridge-test-token");assertTrue(auth.isAuthorized(h));
  h.add("Authorization","Bearer bridge-test-token");assertFalse(auth.isAuthorized(h));
  h.set("Authorization","Bearer ");assertFalse(new McpBearerAuthorization("127.0.0.1",0,"").isAuthorized(h));
 }
 @Test public void accountAndAgentRoutesDoNotExist()throws Exception{
  var server=new McpHostHttpTransport("127.0.0.1",0,new McpBearerAuthorization("127.0.0.1",0,"bridge-test-token"),null,McpHostConfig.AuthMode.BEARER_ONLY);
  server.start();try{
   var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
   for(String path:new String[]{"/oauth/authorize","/oauth/token","/register","/llm/v1/chat","/llm/v1/capabilities","/remote/","/.well-known/openid-configuration"}){
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getBoundPort()+path)).timeout(Duration.ofSeconds(3)).build();
    assertEquals(path,404,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
   }
   var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getBoundPort()+"/mcp")).timeout(Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
   assertEquals(401,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
  }finally{server.stop();}
 }

 @Test public void legacyPanelMigrationPreservesDebugAndGitPerspectivesAndSelection(){
  var basic=org.eclipse.e4.ui.model.application.ui.basic.MBasicFactory.INSTANCE;var advanced=org.eclipse.e4.ui.model.application.ui.advanced.MAdvancedFactory.INSTANCE;
  var app=org.eclipse.e4.ui.model.application.MApplicationFactory.INSTANCE.createApplication();var window=basic.createWindow();app.getChildren().add(window);
  var stack=advanced.createPerspectiveStack();window.getChildren().add(stack);var debug=advanced.createPerspective();debug.setElementId("org.eclipse.debug.ui.DebugPerspective");var git=advanced.createPerspective();git.setElementId("org.eclipse.egit.ui.GitRepositoriesPerspective");stack.getChildren().add(debug);stack.getChildren().add(git);stack.setSelectedElement(debug);
  var parts=basic.createPartStack();debug.getChildren().add(parts);var chat=basic.createPart();chat.setElementId("com.codepilot1c.ui.views.ChatView");window.getSharedElements().add(chat);var ref=advanced.createPlaceholder();ref.setRef(chat);parts.getChildren().add(ref);
  var models=(org.eclipse.e4.ui.workbench.modeling.EModelService)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{org.eclipse.e4.ui.workbench.modeling.EModelService.class},(proxy,m,args)->{
   if(args[2]==org.eclipse.e4.ui.model.application.ui.basic.MPart.class)return java.util.List.of(chat);
   if(args[2]==org.eclipse.e4.ui.model.application.ui.basic.MWindow.class)return java.util.List.of(window);
   if(args[2]==org.eclipse.e4.ui.model.application.ui.advanced.MPlaceholder.class)return java.util.List.of(ref);
   throw new AssertionError("Unexpected perspective mutation/query: "+args[2]);
  });
  com.codepilot1c.ui.bridge.BridgeWorkbenchMigration.removeLegacyModelElements(app,models);
  assertEquals(java.util.List.of(debug,git),stack.getChildren());assertSame(debug,stack.getSelectedElement());assertSame(stack,debug.getParent());assertSame(stack,git.getParent());
 }
}
