// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.editor;
import org.eclipse.core.resources.IFile;
/** The input adapter can be an owner .mdo, never use it as a Form source revision. */
final class FormSourceBinding {
 static IFile require(IFile actual,IFile input){
  if(actual==null||!actual.exists()||!"form".equals(actual.getFileExtension())||input!=null&&!actual.getProject().equals(input.getProject()))throw new FormProjectionGeneration.Unverified("form_source_binding_unavailable");return actual;
 }
 static boolean isFormEditor(Class<?> type){for(Class<?> current=type;current!=null;current=current.getSuperclass())if(current.getName().equals("com._1c.g5.v8.dt.form.ui.editor.FormEditor"))return true;return false;}
}
