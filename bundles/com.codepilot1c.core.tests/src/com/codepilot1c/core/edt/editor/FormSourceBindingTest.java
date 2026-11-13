// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.editor;
import static org.junit.Assert.*;
import org.eclipse.core.resources.*;
import org.junit.Test;
public class FormSourceBindingTest {
 private IFile file(String extension,IProject project){return (IFile)java.lang.reflect.Proxy.newProxyInstance(IFile.class.getClassLoader(),new Class<?>[]{IFile.class},(proxy,method,args)->switch(method.getName()){case "exists"->true;case "getFileExtension"->extension;case "getProject"->project;case "equals"->proxy==args[0];case "hashCode"->System.identityHashCode(proxy);default->null;});}
 private IProject project(){return (IProject)java.lang.reflect.Proxy.newProxyInstance(IProject.class.getClassLoader(),new Class<?>[]{IProject.class},(proxy,method,args)->switch(method.getName()){case "equals"->proxy==args[0];case "hashCode"->System.identityHashCode(proxy);default->null;});}
 @Test public void restoredOwnerInputCannotSubstituteForFormSource(){var project=project();var owner=file("mdo",project);var form=file("form",project);assertSame(form,FormSourceBinding.require(form,owner));assertThrows(FormProjectionGeneration.Unverified.class,()->FormSourceBinding.require(owner,owner));}
 @Test public void nativeBindingMustExistAndRemainInSameProject(){var owner=file("mdo",project());assertThrows(FormProjectionGeneration.Unverified.class,()->FormSourceBinding.require(file("form",project()),owner));assertThrows(FormProjectionGeneration.Unverified.class,()->FormSourceBinding.require(null,owner));}
 @Test public void actualFormEditorTypeClassifiesWithoutRequestedFileCache()throws Exception{Class<?> type=Class.forName("com._1c.g5.v8.dt.form.ui.editor.FormEditor",false,getClass().getClassLoader());assertTrue(FormSourceBinding.isFormEditor(type));assertFalse(FormSourceBinding.isFormEditor(Object.class));}
}
