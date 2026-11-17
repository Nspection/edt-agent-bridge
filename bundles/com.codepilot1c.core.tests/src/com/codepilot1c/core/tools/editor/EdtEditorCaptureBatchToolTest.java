package com.codepilot1c.core.tools.editor;
import static org.junit.Assert.*;import org.junit.Test;import java.util.*;
public class EdtEditorCaptureBatchToolTest {
 @Test public void inventoryFailurePreservesCapturesAndCleanupEvidence(){
  var rows=List.<Map<String,Object>>of(Map.of("captured",true,"snapshot",Map.of("image_path","own.png"),"cleanup",Map.of("status","cleanup_failed","error","modal")));
  var out=EdtEditorCaptureBatchTool.finishResults(rows,()->{throw new IllegalStateException("modal");});
  assertSame(rows,out.get("results"));assertEquals(false,out.get("all_succeeded"));assertEquals("modal",out.get("remaining_editors_error"));assertEquals(1,out.get("total"));
 }
 @Test public void annotationFailureDoesNotClaimCompleteBatch(){
  var rows=List.<Map<String,Object>>of(Map.of("captured",true,"snapshot",Map.of("image_path","retained.png","alignment_error","quota reached"),"cleanup",Map.of("status","closed","closed",true)));
  var result=EdtEditorCaptureBatchTool.finishResults(rows,()->Map.of("total",0));assertEquals(false,result.get("all_succeeded"));assertSame(rows,result.get("results"));assertEquals(true,rows.get(0).get("captured"));
 }
 @Test public void batchGridValidatedBeforeOpeningAnyEditors(){
  assertThrows(IllegalArgumentException.class,()->EdtEditorCaptureBatchTool.validateItems(List.of(Map.of("project","P","file_path","A.mdo"),Map.of("project","P","file_path","B.mdo","grid",true,"grid_step",0))));
  assertEquals(true,EdtEditorCaptureBatchTool.validateItems(List.of(Map.of("project","P","file_path","A.mdo","grid",true))).get(0).get("grid"));
 }
 @Test public void allItemsValidatedBeforeOpeningAnyEditors(){
  assertThrows(IllegalArgumentException.class,()->EdtEditorCaptureBatchTool.validateItems(Collections.nCopies(9,Map.of("project","P","file_path","A.mdo"))));
  assertThrows(IllegalArgumentException.class,()->EdtEditorCaptureBatchTool.validateItems(List.of(Map.of("project","P","file_path","A.mdo"),Map.of("project","P","file_path","B.mdo","tab_id","a","tab_index",0))));
 }

 @Test public void actualFormPageUsesOneFreshNativeCaptureWithoutSelectors()throws Exception {
  var actions=new ArrayList<String>();var result=Map.<String,Object>of("state","projection_unverified","reason","native_rebuild_busy","active_tab_index",0);
  var args=new LinkedHashMap<String,Object>(Map.of("editor_id","e","tab_title","Form","grid",true,"grid_step",24));
  var captured=EdtEditorCaptureBatchTool.capture((action,passed)->{
   actions.add(action);
   if(action.equals("tabs"))return Map.of("active_tab_index",0,"tabs",List.of(Map.of("id","editors.form.pages.main","active",true)));
   assertEquals("form_preview",action);assertEquals(Map.of("editor_id","e","grid",true,"grid_step",24,"mode","refresh","capture",true),passed);return result;
  },args,0);
  assertSame(result,captured);assertEquals(List.of("tabs","form_preview"),actions);assertTrue(args.containsKey("tab_title"));
 }
 @Test public void modulePageKeepsGenericSnapshotEvenForFormEditor()throws Exception {
  var actions=new ArrayList<String>();
  EdtEditorCaptureBatchTool.capture((action,args)->{
   actions.add(action);
   if(action.equals("tabs"))return Map.of("active_tab_index",1,"tabs",List.of(Map.of("id","editors.form.pages.main","active",false),Map.of("id","module","active",true)));
   assertEquals("snapshot",action);assertEquals(Map.of("editor_id","e"),args);return Map.of();
  },Map.of("editor_id","e","tab_index",1),1);
  assertEquals(List.of("tabs","snapshot"),actions);
 }
 @Test public void changedTabDoesNotCaptureAnotherPage() {
  assertThrows(IllegalStateException.class,()->EdtEditorCaptureBatchTool.capture((action,args)->{
   assertEquals("tabs",action);return Map.of("active_tab_index",1,"tabs",List.of());
  },Map.of("editor_id","e"),0));
 }
 @Test public void refusalRetainsReasonAndNextCallWithoutClaimingCapture() {
  var row=new LinkedHashMap<String,Object>();var freshness=Map.of("verified",false,"state","source_changed");var next=Map.of("tool","unexpected");
  EdtEditorCaptureBatchTool.recordSnapshot(row,Map.of("ready",false,"success",false,"state","projection_unverified","reason","source_changed","projection_freshness",freshness,"next_call",next));
  assertEquals(false,row.get("captured"));var snapshot=(Map<?,?>)row.get("snapshot");assertEquals(freshness,snapshot.get("projection_freshness"));assertEquals(next,snapshot.get("next_call"));assertEquals("source_changed",snapshot.get("reason"));
  row.put("cleanup",Map.of("status","closed"));assertEquals(false,EdtEditorCaptureBatchTool.finishResults(List.of(row),()->Map.of()).get("all_succeeded"));
 }
 @Test public void batchCannotTrustFabricatedCapturedFlagWithoutImageMetadata() {
  var row=Map.<String,Object>of("captured",true,"snapshot",Map.of("ready",false),"cleanup",Map.of("status","closed"));
  assertEquals(false,EdtEditorCaptureBatchTool.finishResults(List.of(row),()->Map.of()).get("all_succeeded"));
 }
 @Test public void pngMustExistDecodeAndMatchAcceptedMetadata()throws Exception {
  var file=java.nio.file.Files.createTempFile("batch-capture-",".png");
  try {
   javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(3,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"PNG",file.toFile());
   var snapshot=new LinkedHashMap<String,Object>(Map.of("image_path",file.toString(),"mime_type","image/png","width",3,"height",2));
   var row=new LinkedHashMap<String,Object>();EdtEditorCaptureBatchTool.recordSnapshot(row,snapshot);assertEquals(true,row.get("captured"));row.put("cleanup",Map.of("status","closed"));assertEquals(true,EdtEditorCaptureBatchTool.finishResults(List.of(row),()->Map.of()).get("all_succeeded"));
   snapshot.put("success",false);assertFalse(EdtEditorCaptureBatchTool.hasCapture(snapshot));snapshot.remove("success");
   snapshot.put("capture_scope","native_form_body_from_same_renderer_cache");assertFalse(EdtEditorCaptureBatchTool.hasCapture(snapshot));snapshot.put("ready",true);snapshot.put("projection_freshness",Map.of("verified",true));assertTrue(EdtEditorCaptureBatchTool.hasCapture(snapshot));
   snapshot.put("projection_freshness",Map.of("verified",false));assertFalse(EdtEditorCaptureBatchTool.hasCapture(snapshot));snapshot.put("projection_freshness",Map.of("verified",true));
   snapshot.put("alignment_error","annotation failed");EdtEditorCaptureBatchTool.recordSnapshot(row,snapshot);assertEquals(true,row.get("captured"));assertEquals(false,EdtEditorCaptureBatchTool.finishResults(List.of(row),()->Map.of()).get("all_succeeded"));snapshot.remove("alignment_error");
   snapshot.put("width",4);assertFalse(EdtEditorCaptureBatchTool.hasCapture(snapshot));snapshot.put("width",3);
   java.nio.file.Files.writeString(file,"not a PNG");assertFalse(EdtEditorCaptureBatchTool.hasCapture(snapshot));java.nio.file.Files.delete(file);assertFalse(EdtEditorCaptureBatchTool.hasCapture(snapshot));
  }finally {java.nio.file.Files.deleteIfExists(file);}
 }

 @Test public void changedTabDuringCaptureRejectsResultWithoutReplaying()throws Exception {
  var actions=new ArrayList<String>();var snapshot=EdtEditorCaptureBatchTool.capture((action,args)->{
   actions.add(action);if(action.equals("tabs"))return Map.of("active_tab_index",0,"tabs",List.of());
   return Map.of("image_path","other.png","active_tab_index",1);
  },Map.of("editor_id","e"),0);
  assertEquals(false,snapshot.get("success"));assertEquals("editor_tab_changed_during_batch_capture",((Map<?,?>)snapshot.get("capture_tab_verification")).get("reason"));assertEquals(List.of("tabs","snapshot"),actions);
  var row=new LinkedHashMap<String,Object>();EdtEditorCaptureBatchTool.recordSnapshot(row,snapshot);assertEquals(false,row.get("captured"));assertTrue(((Map<?,?>)row.get("snapshot")).containsKey("capture_tab_verification"));
 }
}
