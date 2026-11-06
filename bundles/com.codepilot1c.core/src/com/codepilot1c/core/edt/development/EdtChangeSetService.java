package com.codepilot1c.core.edt.development;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.Path;
import java.io.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import com.google.gson.Gson;
/** Explicit text ownership: snapshots alone never authorize rollback of later edits. */
public final class EdtChangeSetService {
    private static final Gson JSON=new Gson();
    private static final int MAX_BYTES=1024*1024;
    private static final Object LOCK=new Object();
    public Map<String,Object> execute(Map<String,Object> args) throws Exception {synchronized(LOCK){
        String projectName=required(args,"project"),action=(String)args.getOrDefault("action","status");
        IProject project=ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if(!project.isOpen()){throw new IllegalArgumentException("Project must be open");}
        Path storage=ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/change-sets");Files.createDirectories(storage);
        if(action.equals("begin")){
            if(!(args.get("files") instanceof List<?> paths) || paths.isEmpty() || paths.size()>50){throw new IllegalArgumentException("files requires 1..50 project-relative existing files");}
            var files=new LinkedHashMap<String,Object>();long total=0;
            for(Object path:paths){String relative=String.valueOf(path);IFile file=file(project,relative);byte[] content=read(file);total+=content.length;if(total>8*MAX_BYTES){throw new IllegalArgumentException("Baseline exceeds 8 MB");}files.put(relative,new LinkedHashMap<>(Map.of("before",Base64.getEncoder().encodeToString(content),"before_sha256",hash(content),"owned",false)));}
            String id="changes-"+UUID.randomUUID();var state=new LinkedHashMap<String,Object>();state.put("id",id);state.put("project",projectName);state.put("project_location",project.getLocation().toFile().toPath().toRealPath().toString());state.put("files",files);state.put("diagnostics",diagnostics(project));save(storage,id,state);return summary(project,state);
        }
        String id=required(args,"change_set_id");if(!id.matches("changes-[0-9a-f-]{36}")){throw new IllegalArgumentException("Invalid change_set_id");}
        @SuppressWarnings("unchecked") var state=JSON.fromJson(Files.readString(storage.resolve(id+".json"),StandardCharsets.UTF_8),LinkedHashMap.class);
        if(!projectName.equals(state.get("project")) || !project.getLocation().toFile().toPath().toRealPath().toString().equals(state.get("project_location"))){throw new IllegalArgumentException("Change set belongs to another project");}
        @SuppressWarnings("unchecked") Map<String,Map<String,Object>> files=(Map<String,Map<String,Object>>)state.get("files");
        if(action.equals("status")){return summary(project,state);}
        if(action.equals("write_text")){
            String relative=required(args,"file_path");if(!writable(relative)){throw new IllegalArgumentException("Owned text edits support .bsl/.feature/.json/.md/.txt only; metadata uses native model tools");}
            var entry=files.get(relative);if(entry==null){throw new IllegalArgumentException("File was not in the baseline");}
            IFile file=file(project,relative);byte[] before=read(file);String current=hash(before);
            if(!current.equals(required(args,"expected_sha256")) || !current.equals(entry.getOrDefault("after_sha256",entry.get("before_sha256")))){throw new IllegalStateException("File changed outside this change set; no write performed");}
            byte[] after=required(args,"content").getBytes(StandardCharsets.UTF_8);if(after.length>MAX_BYTES){throw new IllegalArgumentException("Content exceeds 1 MB");}
            write(project,file,before,after);entry.put("after_sha256",hash(after));entry.put("owned",true);save(storage,id,state);return summary(project,state);
        }
        if(action.equals("rollback")){
            List<String> restored=new ArrayList<>();
            // Preflight all owned files before the first restore; an unowned delta is never erased.
            for(var entry:files.entrySet()){if(Boolean.TRUE.equals(entry.getValue().get("owned"))){IFile file=file(project,entry.getKey());if(!hash(read(file)).equals(entry.getValue().get("after_sha256"))){throw new IllegalStateException("Owned file changed later; rollback refused: "+entry.getKey());}ensureNotDirty(file);}}
            for(var entry:files.entrySet()){if(!Boolean.TRUE.equals(entry.getValue().get("owned"))){continue;}IFile file=file(project,entry.getKey());byte[] current=read(file);if(!hash(current).equals(entry.getValue().get("after_sha256"))){throw new IllegalStateException("File changed during rollback; remaining files were not restored: "+entry.getKey());}byte[] original=Base64.getDecoder().decode((String)entry.getValue().get("before"));write(project,file,current,original);entry.getValue().remove("after_sha256");entry.getValue().put("owned",false);restored.add(entry.getKey());save(storage,id,state);}
            var result=summary(project,state);result.put("restored_files",restored);return result;
        }
        throw new IllegalArgumentException("Unknown change-set action");
    }}
    static boolean writable(String path){return path.matches("(?i).+\\.(bsl|feature|json|md|txt)");}
    private static IFile file(IProject project,String relative) throws Exception {
        if(relative.isBlank() || relative.contains("\\") || relative.startsWith("/") || Arrays.asList(relative.split("/")).contains("..") || Arrays.stream(relative.split("/")).anyMatch(p->p.startsWith("."))){throw new IllegalArgumentException("Use a project-relative source path without hidden segments");}
        IFile file=project.getFile(relative);if(!file.exists() || file.getLocation()==null || project.getLocation()==null){throw new IllegalArgumentException("Existing local file required: "+relative);}
        if(!file.getLocation().toFile().toPath().toRealPath().startsWith(project.getLocation().toFile().toPath().toRealPath())){throw new IllegalArgumentException("Linked file escapes project root");}return file;
    }
    private static byte[] read(IFile file) throws Exception {file.refreshLocal(IResource.DEPTH_ZERO,null);try(var stream=file.getContents()){byte[] value=stream.readNBytes(MAX_BYTES+1);if(value.length>MAX_BYTES){throw new IllegalArgumentException("File exceeds 1 MB");}return value;}}
    private static void ensureNotDirty(IFile file){
        if(!org.eclipse.ui.PlatformUI.isWorkbenchRunning()){return;}
        org.eclipse.swt.widgets.Display.getDefault().syncExec(()->{for(var window:org.eclipse.ui.PlatformUI.getWorkbench().getWorkbenchWindows()){for(var page:window.getPages()){for(var ref:page.getEditorReferences()){var editor=ref.getEditor(false);if(editor!=null && editor.isDirty() && file.equals(editor.getEditorInput().getAdapter(IFile.class))){throw new IllegalStateException("File has unsaved editor changes: "+file.getFullPath());}}}}});
    }
    private static void write(IProject project,IFile file,byte[] expected,byte[] content) throws Exception {
        ensureNotDirty(file);
        ResourcesPlugin.getWorkspace().run(monitor->{try{if(!Arrays.equals(read(file),expected)){throw new IllegalStateException("File changed before write");}file.setContents(new ByteArrayInputStream(content),IResource.KEEP_HISTORY,monitor);}catch(Exception e){throw new CoreException(new Status(IStatus.ERROR,"com.codepilot1c.core",e.getMessage(),e));}},project,IWorkspace.AVOID_UPDATE,null);
    }
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private static void save(Path storage,String id,Object state) throws Exception {Path temp=Files.createTempFile(storage,"state-",".json");try{Files.writeString(temp,JSON.toJson(state),StandardCharsets.UTF_8);Files.move(temp,storage.resolve(id+".json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}}
    private static List<String> diagnostics(IProject project) throws Exception {var rows=new TreeSet<String>();for(var marker:project.findMarkers(IMarker.PROBLEM,true,IResource.DEPTH_INFINITE)){rows.add(marker.getResource().getProjectRelativePath()+"|"+marker.getAttribute(IMarker.LINE_NUMBER,0)+"|"+marker.getAttribute(IMarker.SEVERITY,0)+"|"+marker.getAttribute(IMarker.MESSAGE,""));if(rows.size()>10000){throw new IllegalStateException("Too many diagnostics; scope project first");}}return new ArrayList<>(rows);}
    @SuppressWarnings("unchecked") private static Map<String,Object> summary(IProject project,Map<String,Object> state) throws Exception {
        var output=new LinkedHashMap<String,Object>();output.put("change_set_id",state.get("id"));output.put("project",state.get("project"));var rows=new ArrayList<Map<String,Object>>();
        for(var item:((Map<String,Map<String,Object>>)state.get("files")).entrySet()){var value=item.getValue();String now=hash(read(file(project,item.getKey())));var row=new LinkedHashMap<String,Object>();row.put("file_path",item.getKey());row.put("before_sha256",value.get("before_sha256"));row.put("current_sha256",now);row.put("changed",!now.equals(value.get("before_sha256")));row.put("owned",value.get("owned"));row.put("rollback_available",Boolean.TRUE.equals(value.get("owned")) && now.equals(value.get("after_sha256")));rows.add(row);}
        var before=new TreeSet<>((List<String>)state.get("diagnostics"));var after=new TreeSet<>(diagnostics(project));var added=new TreeSet<>(after);added.removeAll(before);var removed=new TreeSet<>(before);removed.removeAll(after);output.put("files",rows);output.put("diagnostics_added",added);output.put("diagnostics_removed",removed);output.put("diagnostics_note","Current workspace markers; builders may still be running. Metadata model changes are observed but cannot be rolled back through text ownership.");return output;
    }
    private static String required(Map<String,Object> args,String name){if(!(args.get(name) instanceof String value) || value.isBlank() && !name.equals("content")){throw new IllegalArgumentException("Missing "+name);}return (String)args.get(name);}
}
