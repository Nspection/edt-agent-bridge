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
}
