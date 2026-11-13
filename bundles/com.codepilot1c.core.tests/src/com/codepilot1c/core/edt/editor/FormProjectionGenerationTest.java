// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.editor;
import static org.junit.Assert.*;
import org.junit.Test;

public class FormProjectionGenerationTest {
 static final class Backend implements FormProjectionGeneration.Backend {
  boolean available=true,dirty,busy,replace=true,installed=true,changeDuring;int renders;
  Object editor=new Object(),input=new Object(),session=new Object(),layout=new Object();String hash="a".repeat(64);
  public boolean available(){return available;}public boolean dirty(){return dirty;}public boolean busy(){return busy;}
  public Object editor(){return editor;}public Object input(){return input;}public Object session(){return session;}public Object layout(){return layout;}
  public String fingerprint(){return hash;}public boolean installed(Object s,Object l){return installed&&s==session&&l==layout;}
  public void rebuild(Object mapping){renders++;if(replace){session=new Object();layout=new Object();}if(changeDuring)hash="b".repeat(64);}
 }
 @Test public void unchangedPixelsStillPermitNewCausalNativeGeneration()throws Exception{
  var backend=new Backend();var request=new FormProjectionGeneration(backend,backend.hash);var result=request.complete(backend,new Object());
  assertTrue(result.current(backend));assertEquals(1,backend.renders);assertEquals(backend.hash,result.modelSha256());
  assertThrows(FormProjectionGeneration.Unverified.class,()->request.complete(backend,new Object()));assertEquals(1,backend.renders);
 }
 @Test public void staleGenerationOrWrongInstalledControlCannotVerify()throws Exception{
  for(boolean replacement:new boolean[]{false,true}){var backend=new Backend();backend.replace=replacement;backend.installed=false;var request=new FormProjectionGeneration(backend,null);assertThrows(FormProjectionGeneration.Unverified.class,()->request.complete(backend,new Object()));}
 }
 @Test public void modelChangesBeforeOrDuringRenderCannotVerify()throws Exception{
  var before=new Backend();var request=new FormProjectionGeneration(before,null);before.hash="b".repeat(64);assertThrows(FormProjectionGeneration.Unverified.class,()->request.complete(before,new Object()));assertEquals(0,before.renders);
  var during=new Backend();during.changeDuring=true;var started=new FormProjectionGeneration(during,null);assertThrows(FormProjectionGeneration.Unverified.class,()->started.complete(during,new Object()));assertEquals(1,during.renders);
 }
 @Test public void closedEditorReplacedInputAndDirtyChangesArePreserved()throws Exception{
  for(int mode=0;mode<3;mode++){var backend=new Backend();var request=new FormProjectionGeneration(backend,null);if(mode==0)backend.available=false;if(mode==1)backend.input=new Object();if(mode==2)backend.dirty=true;assertThrows(FormProjectionGeneration.Unverified.class,()->request.complete(backend,new Object()));assertEquals(0,backend.renders);}
 }
 @Test public void oldReceiptExpiresWhenNativeSelectionReplacesGeneration()throws Exception{
  var backend=new Backend();var receipt=new FormProjectionGeneration(backend,null).complete(backend,new Object());backend.session=new Object();assertFalse(receipt.current(backend));
 }
 @Test public void expectedModelAndBusyGateRejectBeforeNativeCall()throws Exception{
  var backend=new Backend();assertThrows(FormProjectionGeneration.Unverified.class,()->new FormProjectionGeneration(backend,"b".repeat(64)));var request=new FormProjectionGeneration(backend,null);backend.busy=true;assertTrue(request.busy(backend));assertThrows(FormProjectionGeneration.Unverified.class,()->request.complete(backend,new Object()));assertEquals(0,backend.renders);
 }
 @Test public void fingerprintTracksLiteralLineBreaksAndExplicitDefaults()throws Exception{
  var factory=org.eclipse.emf.ecore.EcoreFactory.eINSTANCE;var pkg=factory.createEPackage();pkg.setName("fixture");pkg.setNsPrefix("fixture");pkg.setNsURI("urn:fixture");var type=factory.createEClass();type.setName("Form");pkg.getEClassifiers().add(type);var title=factory.createEAttribute();title.setName("title");title.setEType(org.eclipse.emf.ecore.EcorePackage.Literals.ESTRING);type.getEStructuralFeatures().add(title);var object=pkg.getEFactoryInstance().create(type);
  object.eSet(title,"Line one Line two");String old=FormProjectionFingerprint.of(object);object.eSet(title,"Line one\nLine two");String current=FormProjectionFingerprint.of(object);assertNotEquals(old,current);assertEquals(current,FormProjectionFingerprint.of(object));
 }
}
