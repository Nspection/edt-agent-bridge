package com.codepilot1c.core.edt.metadata;
import static org.junit.Assert.*;
import org.junit.Test;
import com._1c.g5.v8.dt.form.model.*;

public class FormItemTreeTest {
 @Test public void findsAndRemovesAutoCommandBarButtonByNameAndId() {
  var f=FormFactory.eINSTANCE;var form=f.createForm();var bar=f.createAutoCommandBar();form.setAutoCommandBar(bar);var button=f.createButton();button.setName("ФормаЗаписатьИЗакрыть");button.setId(489);bar.getItems().add(button);
  assertTrue(FormItemTree.children(form).contains(bar));assertTrue(FormItemTree.children(bar).contains(button));assertSame(button,FormItemTree.find(form,null,"ФормаЗаписатьИЗакрыть"));assertSame(button,FormItemTree.find(form,489,null));assertSame(bar,FormItemTree.parent(form,button));FormItemTree.parent(form,button).getItems().remove(button);assertNull(FormItemTree.find(form,489,null));
 }
 @Test public void traversesNestedTableBarsAndAllocatesIdsAcrossEveryContainedBranch() {
  var f=FormFactory.eINSTANCE;var form=f.createForm();var group=f.createFormGroup();group.setId(5);form.getItems().add(group);var table=f.createTable();table.setId(8);group.getItems().add(table);
  var bar=f.createAutoCommandBar();table.setAutoCommandBar(bar);var nested=f.createFormGroup();nested.setId(700);bar.getItems().add(nested);var button=f.createButton();button.setId(900);button.setName("ОбеспечениеСвернутьВсеЗадачиОбеспечения");button.setDefaultButton(true);nested.getItems().add(button);
  assertSame(button,FormItemTree.find(form,null,button.getName()));assertSame(nested,FormItemTree.parent(form,button));((Button)FormItemTree.find(form,900,null)).setDefaultButton(false);assertFalse(button.isDefaultButton());assertEquals(901,FormItemTree.nextId(form));assertNull(FormItemTree.parent(f.createForm(),button));
 }
 @Test public void topBottomAndFloatingBarsAreIncludedWithoutFollowingReferences() {
  var f=FormFactory.eINSTANCE;var form=f.createForm();var bars=new AutoCommandBar[]{f.createAutoCommandBar(),f.createAutoCommandBar(),f.createAutoCommandBar()};form.setTopCommandBar(bars[0]);form.setBottomCommandBar(bars[1]);form.setFABCommandBar(bars[2]);
  for(int i=0;i<bars.length;i++){var button=f.createButton();button.setId(40+i);bars[i].getItems().add(button);assertSame(button,FormItemTree.find(form,40+i,null));assertSame(bars[i],FormItemTree.parent(form,button));}assertEquals(43,FormItemTree.nextId(form));
 }
 @Test public void fieldContextMenuIsNotHiddenByNonContainerOwner() {
  var f=FormFactory.eINSTANCE;var form=f.createForm();var field=f.createFormField();field.setId(20);form.getItems().add(field);var menu=f.createContextMenu();menu.setId(21);field.setContextMenu(menu);var button=f.createButton();button.setId(22);menu.getItems().add(button);
  assertTrue(FormItemTree.children(field).contains(menu));assertSame(button,FormItemTree.find(form,22,null));assertSame(menu,FormItemTree.parent(form,button));assertEquals(23,FormItemTree.nextId(form));
 }
}
