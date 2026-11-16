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
 @Test public void ambiguousIdsNeverSelectAnArbitraryItem() {
  var f=FormFactory.eINSTANCE;var form=f.createForm();var a=f.createButton();a.setId(373);a.setName("A");form.getItems().add(a);var b=f.createDecoration();b.setId(373);b.setName("B");form.getItems().add(b);assertThrows(MetadataOperationException.class,()->FormItemTree.find(form,373,null));assertSame(a,FormItemTree.find(form,null,"A"));
 }
 @Test public void newIdsCannotCollideWithDecorationTooltipOrContextMenu() {
  var f=FormFactory.eINSTANCE;var form=f.createForm();var item=f.createFormField();item.setId(10);form.getItems().add(item);var menu=f.createContextMenu();menu.setId(372);item.setContextMenu(menu);var before=FormItemTree.snapshot(form);var added=f.createButton();added.setName("Added");added.setId(372);form.getItems().add(added);assertThrows(MetadataOperationException.class,()->FormItemTree.verifyAllocation(form,before));added.setId(373);FormItemTree.verifyAllocation(form,before);item.setId(11);assertThrows(MetadataOperationException.class,()->FormItemTree.verifyAllocation(form,before));
 }
 @Test public void groupExtensionPropertiesAcceptFlatNestedAndDottedForms()throws Exception {
  var group=FormFactory.eINSTANCE.createFormGroup();var extension=FormFactory.eINSTANCE.createUsualGroupExtInfo();group.setExtInfo(extension);var service=new EdtMetadataService();var method=EdtMetadataService.class.getDeclaredMethod("applyFormPropertySet",org.eclipse.emf.ecore.EObject.class,java.util.Map.class);method.setAccessible(true);
  for(var patch:java.util.List.of(java.util.Map.of("group","VERTICAL"),java.util.Map.of("extInfo.group","VERTICAL"),java.util.Map.of("extInfo",java.util.Map.of("group","VERTICAL","united",true,"showTitle",false)))){method.invoke(service,group,patch);assertEquals("Vertical",extension.getGroup().toString());}
  assertTrue(extension.isUnited());assertFalse(extension.isShowTitle());var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(service,group,java.util.Map.of("extInfo.noSuchProperty",true)));assertTrue(failure.getCause() instanceof MetadataOperationException);
 }
 @Test public void directIdsAndGroupTypeCannotBypassNativeAllocationAndContext()throws Exception {
  var group=FormFactory.eINSTANCE.createFormGroup();group.setId(10);group.setExtInfo(FormFactory.eINSTANCE.createUsualGroupExtInfo());var service=new EdtMetadataService();var method=EdtMetadataService.class.getDeclaredMethod("applyFormPropertySet",org.eclipse.emf.ecore.EObject.class,java.util.Map.class);method.setAccessible(true);
  for(var patch:java.util.List.of(java.util.Map.of("id",900),java.util.Map.of("type","POPUP"))){var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(service,group,patch));assertTrue(failure.getCause() instanceof MetadataOperationException);}assertEquals(10,group.getId());
 }
 @Test public void inspectionReturnsTypedGroupExtensionProperties()throws Exception {
  var group=FormFactory.eINSTANCE.createFormGroup();group.setExtInfo(FormFactory.eINSTANCE.createUsualGroupExtInfo());var service=new EdtMetadataService();var apply=EdtMetadataService.class.getDeclaredMethod("applyFormPropertySet",org.eclipse.emf.ecore.EObject.class,java.util.Map.class);apply.setAccessible(true);apply.invoke(service,group,java.util.Map.of("extInfo",java.util.Map.of("group","Vertical","showAsCard",true,"representation","None","showTitle",false,"united",false)));
  var inspect=EdtMetadataService.class.getDeclaredMethod("collectScalarProperties",org.eclipse.emf.ecore.EObject.class,boolean.class);inspect.setAccessible(true);var result=(java.util.Map<?,?>)inspect.invoke(service,group,false);assertEquals("UsualGroupExtInfo",result.get("extInfoType"));var extension=(java.util.Map<?,?>)result.get("extInfo");assertEquals(true,extension.get("showAsCard"));assertEquals(false,extension.get("showTitle"));assertEquals(false,extension.get("united"));assertEquals("VERTICAL",extension.get("group"));assertEquals("NONE",extension.get("representation"));
 }
 @Test public void newGroupAndChainedPatchPropertiesAreValidatedWithoutWriting(){
  var service=new EdtMetadataService();var good=java.util.Map.<String,Object>of("op","add_group","name","Card","set",java.util.Map.of("extInfo",java.util.Map.of("group","Vertical","showAsCard",true)));service.validateNewGroupProperties(java.util.List.of(good));
  assertThrows(MetadataOperationException.class,()->service.validateNewGroupProperties(java.util.List.of(java.util.Map.of("op","add_group","name","Bad","set",java.util.Map.of("extInfo.noSuchProperty",true)))));
  assertThrows(MetadataOperationException.class,()->service.validateNewGroupProperties(java.util.List.of(good,java.util.Map.of("op","set_item","item_name","Card","set",java.util.Map.of("extInfo.noSuchProperty",true)))));
 }
 @Test public void invalidBooleanGroupPropertiesNeverBecomeFalse()throws Exception {
  var service=new EdtMetadataService();var group=FormFactory.eINSTANCE.createFormGroup();var extension=FormFactory.eINSTANCE.createUsualGroupExtInfo();group.setExtInfo(extension);extension.setShowAsCard(true);
  var apply=EdtMetadataService.class.getDeclaredMethod("applyFormPropertySet",org.eclipse.emf.ecore.EObject.class,java.util.Map.class);apply.setAccessible(true);
  for(Object invalid:java.util.List.of("not-a-boolean",2,-1,0.5,Double.NaN)){var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->apply.invoke(service,group,java.util.Map.of("extInfo.showAsCard",invalid)));assertTrue(failure.getCause() instanceof MetadataOperationException);assertTrue(extension.isShowAsCard());}
  var create=java.util.Map.<String,Object>of("op","add_group","name","Card");var bad=java.util.Map.<String,Object>of("op","set_item","item_name","Card","set",java.util.Map.of("extInfo.showAsCard","not-a-boolean"));assertThrows(MetadataOperationException.class,()->service.validateNewGroupProperties(java.util.List.of(create,bad)));
 }
 @Test public void newItemsCannotDuplicateExistingNamesCaseInsensitively(){
  var f=FormFactory.eINSTANCE;var form=f.createForm();var old=f.createFormGroup();old.setName("Card");old.setId(1);form.getItems().add(old);var before=FormItemTree.snapshot(form);var fresh=f.createFormGroup();fresh.setName("cARD");fresh.setId(2);form.getItems().add(fresh);assertThrows(MetadataOperationException.class,()->FormItemTree.verifyAllocation(form,before));assertEquals(1,old.getId());
 }
 @Test public void renameExistingItemCannotIntroduceDuplicateName(){
  var f=FormFactory.eINSTANCE;var form=f.createForm();var a=f.createFormGroup();a.setName("A");a.setId(1);var b=f.createFormGroup();b.setName("B");b.setId(2);form.getItems().add(a);form.getItems().add(b);var before=FormItemTree.snapshot(form);b.setName("a");assertThrows(MetadataOperationException.class,()->FormItemTree.verifyAllocation(form,before));
  var legacy=FormItemTree.snapshot(form);FormItemTree.verifyAllocation(form,legacy);b.setName("C");FormItemTree.verifyAllocation(form,legacy);
 }
}
