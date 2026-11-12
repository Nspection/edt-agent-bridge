// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.workspace;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.ui.*;
import com.google.gson.*;
import com.codepilot1c.core.edt.compare.EdtComparisonService;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
/** Uses Eclipse project/build APIs only. No source, cache-directory or infobase deletion. */
final class NativeProjectLifecycle implements ProjectLifecycleService.Backend {
 private static IProject project(String name){var ws=ResourcesPlugin.getWorkspace();if(!ws.validateName(name,IResource.PROJECT).isOK())throw new IllegalArgumentException("Invalid project name");var p=ws.getRoot().getProject(name);if(!p.exists()||p.getLocation()==null)throw new IllegalArgumentException("Project does not exist");return p;}
 @Override public Map<String,Object> snapshot(String name)throws Exception {
  var p=project(name);var blockers=new ArrayList<String>();var dirty=dirtyEditors(p);
  if(!dirty.isEmpty())blockers.add("dirty_editors");
  if(p.isOpen())try{EdtComparisonService.getInstance().assertProjectNotCompared(name);}catch(IllegalStateException active){blockers.add("active_native_comparison");}
  var jobs=Job.getJobManager();if(activeBuild(jobs.find(ResourcesPlugin.FAMILY_AUTO_BUILD))||activeBuild(jobs.find(ResourcesPlugin.FAMILY_MANUAL_BUILD)))blockers.add("workspace_build_in_progress");
  var registry=BackgroundJobRegistry.getInstance();
  for(String id:registry.listJobs(null)){var j=registry.getStatus(id).orElse(null);if(j==null)continue;String state=j.getState().name(),kind=j.getKind();if((state.equals("RUNNING")||state.equals("PENDING")) && !Set.of("project-lifecycle","project_bm25_search","bsl_method_batch").contains(kind)){blockers.add("workspace_runtime_job_in_progress");break;}}
  if(activeRecords(name))blockers.add("active_or_unresolved_runtime");
  var debug=org.eclipse.debug.core.DebugPlugin.getDefault();if(debug!=null)for(var launch:debug.getLaunchManager().getLaunches())if(!launch.isTerminated()){blockers.add("active_debug_or_launch");break;}
  return Map.of("project",name,"project_path",p.getLocation().toFile().toPath().toRealPath().toString(),"open",p.isOpen(),"dirty_editors",dirty,"blockers",blockers);
 }
 static boolean activeBuild(Job[] jobs){return Arrays.stream(jobs).anyMatch(j->j.getState()==Job.RUNNING||j.getState()==Job.WAITING);}
 private static List<String> dirtyEditors(IProject project){
  if(!PlatformUI.isWorkbenchRunning())throw new IllegalStateException("Workbench unavailable");var result=new ArrayList<String>();
  // Never enter the UI thread while holding an Eclipse resource scheduling rule.
  PlatformUI.getWorkbench().getDisplay().syncExec(()->{for(var window:PlatformUI.getWorkbench().getWorkbenchWindows())for(var page:window.getPages())for(var ref:page.getEditorReferences()){
   var editor=ref.getEditor(false);if(editor==null||!editor.isDirty())continue;IResource resource=editor.getEditorInput().getAdapter(IResource.class);if(resource==null)resource=editor.getEditorInput().getAdapter(IFile.class);
   if(resource==null||project.equals(resource.getProject()))result.add(ref.getId());
  }});Collections.sort(result);return result;
 }
 private static void closeCleanEditors(IProject project)throws Exception {
  Exception[] error={null};
  PlatformUI.getWorkbench().getDisplay().syncExec(()->{try{
   var pages=new ArrayList<IWorkbenchPage>();for(var window:PlatformUI.getWorkbench().getWorkbenchWindows())pages.addAll(Arrays.asList(window.getPages()));
   // The repeated guard and each clean close run in one UI dispatch, without resource rules.
   if(!dirtyEditors(project).isEmpty())throw new IllegalStateException("Dirty editor appeared");
   for(var page:pages)for(var ref:page.getEditorReferences()){
    var editor=ref.getEditor(false);if(editor==null)continue;var input=editor.getEditorInput();IResource resource=input.getAdapter(IResource.class);if(resource==null)resource=input.getAdapter(IFile.class);
    if(resource!=null&&project.equals(resource.getProject())){if(editor.isDirty()||!page.closeEditor(editor,false))throw new IllegalStateException("Target editor could not be closed cleanly");}
   }
   if(!dirtyEditors(project).isEmpty())throw new IllegalStateException("Dirty editor appeared");
  }catch(Exception e){error[0]=e;}});
  if(error[0]!=null)throw error[0];
 }
 private static boolean activeRecords(String project)throws Exception {
  Path plugin=ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core");
  return activeRecords(plugin,project);
 }
 static boolean activeRecords(Path plugin,String project)throws Exception {
  for(String type:List.of("native-host","direct-test-client")){
   Path root=plugin.resolve(type);if(!Files.isDirectory(root))continue;
   try(var dirs=Files.list(root)){var folders=dirs.filter(Files::isDirectory).iterator();while(folders.hasNext()){Path folder=folders.next();Path file=folder.resolve(type.equals("native-host")?"host.json":"client.json");if(!Files.exists(file))continue;if(Files.isSymbolicLink(folder)||Files.isSymbolicLink(file)||Files.size(file)>131072)throw new IllegalStateException("Runtime inventory changed");var r=JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject();if(!matches(r,"project",project)&&!matches(r,"external_project",project))continue;if(r.has("cleaned")&&r.get("cleaned").getAsBoolean())continue;String state=r.has("state")?r.get("state").getAsString():"unknown";if(!Set.of("stopped","cleaned").contains(state))return true;}}
  }return false;
 }
 private static boolean matches(JsonObject r,String key,String value){return r.has(key)&&!r.get(key).isJsonNull()&&value.equals(r.get(key).getAsString());}
 private void guard(String name,String expectedPath)throws Exception {var s=snapshot(name);if(!expectedPath.equals(s.get("project_path")))throw new IllegalStateException("Project location changed");com.codepilot1c.core.edt.development.RuntimeConsentPolicy.assertToolAllowed("edt_project_lifecycle");if(!((List<?>)s.get("blockers")).isEmpty())throw new IllegalStateException("Project became busy");}
 @Override public Map<String,Object> perform(String name,String operation,boolean rebuild,String expectedPath,Consumer<String> phase)throws Exception {
  var p=project(name);var monitor=new NullProgressMonitor();boolean clean=false,full=false;
  guard(name,expectedPath);
  if(operation.equals("close")||operation.equals("reopen")){phase.accept("closing_clean_editors");closeCleanEditors(p);phase.accept("closing");p.close(monitor);phase.accept("closed");}
  if(operation.equals("open")||operation.equals("reopen")){if(!p.exists()||!expectedPath.equals(p.getLocation().toFile().toPath().toRealPath().toString()))throw new IllegalStateException("Project location changed");phase.accept("opening");p.open(monitor);phase.accept("opened");phase.accept("refreshing");p.refreshLocal(IResource.DEPTH_INFINITE,monitor);phase.accept("refreshed");}
  if(operation.equals("clean")){if(!p.isOpen())throw new IllegalStateException("Project closed");phase.accept("cleaning");p.build(IncrementalProjectBuilder.CLEAN_BUILD,monitor);clean=true;phase.accept("cleaned");if(rebuild){phase.accept("rebuilding");p.build(IncrementalProjectBuilder.FULL_BUILD,monitor);full=true;phase.accept("rebuilt");}}
  return Map.of("phase","completed","project_open",p.isOpen(),"clean_build_performed",clean,"full_build_performed",full);
 }
}
