package com.codepilot1c.core.edt.compare;
import static org.junit.Assert.*;import org.junit.Test;import com._1c.g5.v8.dt.moxel.*;import com._1c.g5.v8.dt.moxel.content.ContentFactory;
public class SpreadsheetClipboardSafetyTest {
 @Test public void detectsNeutralContentWithoutChangingIt(){var s=ContentFactory.eINSTANCE.createLocalString();s.getContent().put("#","Visible text");assertTrue(SpreadsheetClipboardSafety.hasNeutralText(s));assertEquals("Visible text",s.getContent().get("#"));}
 @Test public void detectsNestedCellAndAcceptsLocalizedOrEmptyNeutral(){
  var d=MoxelFactory.eINSTANCE.createSpreadsheetDocument();var row=MoxelFactory.eINSTANCE.createRow();var cell=MoxelFactory.eINSTANCE.createCell();var s=ContentFactory.eINSTANCE.createLocalString();cell.setText(s);row.getCells().put(0,cell);d.getRows().put(0,row);
  s.getContent().put("ru","Текст");assertFalse(SpreadsheetClipboardSafety.hasNeutralText(d));s.getContent().put("#","");assertFalse(SpreadsheetClipboardSafety.hasNeutralText(d));s.getContent().put("#","neutral");assertTrue(SpreadsheetClipboardSafety.hasNeutralText(d));
 }
}
