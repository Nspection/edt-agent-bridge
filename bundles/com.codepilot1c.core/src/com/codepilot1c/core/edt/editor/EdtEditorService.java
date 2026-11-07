package com.codepilot1c.core.edt.editor;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import org.eclipse.ui.ide.IDE;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

/** Read-only model access: opens views and selects pages but never saves editors. */
public final class EdtEditorService {
    private static final EdtEditorService INSTANCE = new EdtEditorService();
    private final Map<String,IEditorPart> editors = new ConcurrentHashMap<>();
    private final Map<String,String> requestedFiles = new ConcurrentHashMap<>();
    public static EdtEditorService getInstance() { return INSTANCE; }
    /** Registers an existing editor without reopening it; permits shared tabs/snapshot tools. */
    public String register(IEditorPart editor) {
        if(Display.getCurrent()==null)throw new IllegalStateException("Register editors on the UI thread");
        String id="editor@"+Integer.toHexString(System.identityHashCode(editor));editors.put(id,editor);return id;
    }
    public void forget(IEditorPart editor) {
        String id="editor@"+Integer.toHexString(System.identityHashCode(editor));editors.remove(id,editor);requestedFiles.remove(id);
    }

    public Map<String,Object> execute(String action, Map<String,Object> args) throws Exception {
        if(args.containsKey("capture") && !(args.get("capture") instanceof Boolean)){throw new IllegalArgumentException("capture must be a boolean");}
        if (!PlatformUI.isWorkbenchRunning()) { throw new IllegalStateException("EDT workbench is not running"); }
        Display display = PlatformUI.getWorkbench().getDisplay();
        if (Display.getCurrent()!=null) { throw new IllegalStateException("Call EDT tools from a worker thread"); }
        try (EdtUiRequest<Map<String,Object>> request=EdtUiRequest.acquire(30,TimeUnit.SECONDS)) {
        display.asyncExec(() -> request.dispatch(() -> {
            try {
                if("snapshot".equals(action) || Boolean.TRUE.equals(args.get("capture")) && Set.of("scroll","viewport","form_preview").contains(action)) {
                    boolean restored=restoreWindowForSnapshot(args);
                    String preparation="snapshot".equals(action)
                            ? (args.containsKey("tab_id") || args.containsKey("tab_title") || args.containsKey("tab_index") ? "select_tab" : "tabs") : action;
                    Map<String,Object> operation=executeUi(preparation,args);
                    display.timerExec(600,() -> request.dispatch(() -> {
                        try {
                            Map<String,Object> current=executeUi("tabs",args);
                            if (!Objects.equals(operation.get("active_tab_index"),current.get("active_tab_index"))) {
                                throw new IllegalStateException("Editor tab changed before capture; retry the snapshot");
                            }
                            Map<String,Object> snapshot=executeUi("snapshot",args);
                            snapshot.put("window_restored",restored);
                            if(!"snapshot".equals(action)){snapshot.put("operation_result",operation);}
                            request.complete(snapshot);
                        }
                        catch(Throwable e) { request.fail(e); }
                    }));
                } else { request.complete(executeUi(action,args)); }
            }
            catch (Throwable e) { request.fail(e); }
        }));
        try { return request.await(); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof Exception ex) { throw ex; }
            throw new IllegalStateException("EDT editor request failed",e.getCause());
        }
        }
    }

    static boolean shouldRestoreWindow(boolean minimized,Map<String,Object> args) {
        Object setting=args.get("auto_restore");
        if(args.containsKey("auto_restore") && !(setting instanceof Boolean)) {
            throw new IllegalArgumentException("auto_restore must be a boolean");
        }
        return minimized && !Boolean.FALSE.equals(setting);
    }

    private static boolean restoreWindowForSnapshot(Map<String,Object> args) {
        IWorkbenchWindow window=PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if(window==null) { throw new IllegalStateException("No active EDT window"); }
        Shell shell=window.getShell();
        if(!shouldRestoreWindow(shell.getMinimized(),args)) { return false; }
        EdtWindowService.restore(shell);
        return true;
    }

    private Map<String,Object> executeUi(String action,Map<String,Object> args) throws Exception {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if(window == null || window.getActivePage() == null) { throw new IllegalStateException("No active EDT window"); }
        Shell shell=window.getShell();
        for(Shell other:shell.getDisplay().getShells()) {
            if(other!=shell && other.isVisible() && (other.getStyle() & (SWT.APPLICATION_MODAL|SWT.PRIMARY_MODAL|SWT.SYSTEM_MODAL))!=0) {
                throw new IllegalStateException("EDT has a modal dialog: "+other.getText());
            }
        }
        IWorkbenchPage page=window.getActivePage();
        IEditorPart editor;
        if("open".equals(action)) {
            String projectName=required(args,"project");
            String relative=normalizePath(required(args,"file_path"));
            IProject project=ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if(!project.exists() || !project.isOpen()) { throw new IllegalArgumentException("Open EDT project not found: "+projectName); }
            IFile file=project.getFile("src/"+relative);
            if(!file.exists()) { throw new IllegalArgumentException("Source file not found: "+file.getFullPath()); }
            Class<?> contentUtil=Platform.getBundle("com._1c.g5.v8.dt.ui")
                    .loadClass("com._1c.g5.v8.dt.ui.util.ContentUtil");
            IEditorInput input=(IEditorInput)contentUtil.getMethod("createGranularEditorInput",IFile.class).invoke(null,file);
            if(input==null) { throw new IllegalArgumentException("EDT could not resolve editor model for "+file.getFullPath()); }
            String editorType;
            if(relative.endsWith(".form")) {
                editorType="com._1c.g5.v8.dt.form.ui.formEditor";
            } else {
                EObject model=(EObject)call(input,"getModel",new Class<?>[0]);
                String kind=model.eClass().getName();
                List<String> ids=new ArrayList<>();
                for(var element:Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.editors")) {
                    String registered=element.getAttribute("id");
                    if("editor".equals(element.getName()) && registered!=null
                            && registered.startsWith("com._1c.g5.v8.dt.md.ui.editor.")
                            && registered.substring(registered.lastIndexOf('.')+1).equalsIgnoreCase(kind)) { ids.add(registered); }
                }
                if(ids.size()!=1) { throw new IllegalArgumentException("No unique EDT metadata editor for "+kind+"; found "+ids); }
                editorType=ids.get(0);
            }
            editor=page.openEditor(input,editorType,true);
            requestedFiles.put("editor@"+Integer.toHexString(System.identityHashCode(editor)),file.getFullPath().toString());
        } else {
            editor=editors.get(required(args,"editor_id"));
            final IEditorPart selected=editor;
            if(editor==null || Arrays.stream(page.getEditorReferences()).noneMatch(ref -> ref.getEditor(false)==selected)) {
                editors.remove(required(args,"editor_id"));
                requestedFiles.remove(required(args,"editor_id"));
                throw new IllegalArgumentException("Unknown or closed editor_id; call edt_editor_open again");
            }
            page.activate(editor);
        }
        String id="editor@"+Integer.toHexString(System.identityHashCode(editor));
        editors.put(id,editor);
        List<Map<String,Object>> tabs=tabs(editor);
        if("select_tab".equals(action)) {
            int index=selectTab(tabs,args);
            call(editor,"setActivePage",new Class<?>[]{int.class},index);
            tabs=tabs(editor);
        }
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("editor_id",id);
        out.put("title",editor.getTitle());
        out.put("editor_type",editor.getClass().getName());
        out.put("dirty",editor.isDirty());
        out.put("ready",!tabs.isEmpty() && tabs.stream().noneMatch(tab -> String.valueOf(tab.get("id")).contains("loading")));
        out.put("tabs",tabs);
        out.put("active_tab_index",optionalCall(editor,"getActivePage",new Class<?>[0]));
        out.put("editor_area_maximized",page.getPartState(page.getReference(editor))==IWorkbenchPage.STATE_MAXIMIZED);
        IFile file=editor.getEditorInput().getAdapter(IFile.class);
        out.put("requested_file",requestedFiles.get(id));
        if(file!=null) { out.put("active_resource",file.getFullPath().toString()); }
        if(Set.of("viewport","regions","scroll","form_preview").contains(action)) {
            out.putAll(EdtEditorViewportService.execute(action,page,editor,args));
            out.put("editor_area_maximized",page.getPartState(page.getReference(editor))==IWorkbenchPage.STATE_MAXIMIZED);
        }
        if("snapshot".equals(action)) {
            if(shell.getMinimized()) { throw new IllegalStateException("EDT is still minimized; enable auto_restore or restore the window before capturing"); }
            page.activate(editor);
            shell.layout(true,true);
            shell.update();
            Control control=(Control)call(editor,"getContainer",new Class<?>[0]);
            if(control==null || control.isDisposed() || !control.isVisible()) { throw new IllegalStateException("Editor control is not visible"); }
            Point origin=control.toDisplay(0,0);
            Point size=control.getSize();
            Rectangle area=new Rectangle(0,0,size.x,size.y);
            if(area.width<1 || area.height<1 || area.width>10000 || area.height>10000) { throw new IllegalStateException("Invalid editor dimensions"); }
            Path folder=ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath()
                    .resolve(".metadata/.plugins/com.codepilot1c.core/editor-snapshots");
            Files.createDirectories(folder);
            Path path=folder.resolve(UUID.randomUUID()+".png");
            Image image=new Image(shell.getDisplay(),area.width,area.height);
            GC gc=new GC(image);
            try {
                if("win32".equals(SWT.getPlatform())) {
                    Rectangle windowBounds=shell.getBounds();
                    Image full=new Image(shell.getDisplay(),windowBounds.width,windowBounds.height);
                    GC target=new GC(full);
                    try {
                        int captured=NativeLibrary.getInstance("user32").getFunction("PrintWindow")
                                .invokeInt(new Object[]{new Pointer(shell.handle),new Pointer(target.handle),2});
                        if(captured==0) { throw new IllegalStateException("Windows could not capture the EDT window"); }
                        int x=origin.x-windowBounds.x,y=origin.y-windowBounds.y;
                        gc.drawImage(full,x,y,area.width,area.height,0,0,area.width,area.height);
                    } finally {target.dispose();full.dispose();}
                } else if(!control.print(gc)) {
                    throw new IllegalStateException("EDT control does not support direct capture");
                }
                ImageLoader loader=new ImageLoader();
                loader.data=new ImageData[]{image.getImageData()};
                loader.save(path.toString(),SWT.IMAGE_PNG);
            } finally { gc.dispose(); image.dispose(); }
            out.put("image_path",path.toAbsolutePath().toString());
            out.put("mime_type","image/png");
            out.put("width",area.width);out.put("height",area.height);
            out.put("capture_method","win32".equals(SWT.getPlatform()) ? "edt_window_print" : "edt_control_print");
            out.put("captured_at",java.time.Instant.now().toString());
            out.put("note","Visible EDT editor only. Preview may render asynchronously; inspect image and retry if loading. Runtime form behavior requires a 1C test.");
        }
        return out;
    }

    private static List<Map<String,Object>> tabs(IEditorPart editor) throws Exception {
        Object countValue=optionalCall(editor,"getPageCount",new Class<?>[0]);
        if(!(countValue instanceof Number number)) { throw new IllegalArgumentException("Editor has no supported multipage API: "+editor.getClass().getName()); }
        Object active=optionalCall(editor,"getActivePage",new Class<?>[0]);
        List<Map<String,Object>> result=new ArrayList<>();
        Object formPages=field(editor,"pages");
        for(int index=0;index<number.intValue();index++) {
            String title=String.valueOf(call(editor,"getPageText",new Class<?>[]{int.class},index));
            String pageId="page:"+index;
            if(formPages instanceof Iterable<?> iterable) {
                for(Object formPage:iterable) {
                    Object pageIndex=optionalCall(formPage,"getIndex",new Class<?>[0]);
                    if(pageIndex instanceof Number n && n.intValue()==index) {
                        Object actualId=optionalCall(formPage,"getId",new Class<?>[0]);
                        if(actualId!=null) { pageId=actualId.toString(); }
                    }
                }
            }
            Map<String,Object> tab=new LinkedHashMap<>();
            tab.put("index",index);tab.put("id",pageId);tab.put("title",title);
            tab.put("active",active instanceof Number n && n.intValue()==index);
            result.add(tab);
        }
        return result;
    }

    static String normalizePath(String value) {
        String path=value.replace('\\','/');
        if(path.startsWith("/") || path.matches("^[A-Za-z]:.*")) { throw new IllegalArgumentException("Expected project-relative source path"); }
        for(String segment:path.split("/",-1)) {
            if(segment.isBlank() || segment.equals(".") || segment.equals("..")) { throw new IllegalArgumentException("Invalid source path segment"); }
        }
        if(path.startsWith("src/")) { path=path.substring(4); }
        if(!(path.endsWith(".mdo") || path.endsWith(".form"))) { throw new IllegalArgumentException("Expected .mdo object or .form editor file"); }
        return path;
    }

    static int selectTab(List<Map<String,Object>> tabs,Map<String,Object> args) {
        List<Map<String,Object>> matches=new ArrayList<>();
        int selectors=0;
        for(String key:List.of("tab_id","tab_title","tab_index")) { if(args.containsKey(key)) { selectors++; } }
        if(selectors!=1) { throw new IllegalArgumentException("Specify exactly one of tab_id, tab_title or tab_index"); }
        for(Map<String,Object> tab:tabs) {
            if(args.containsKey("tab_id") && Objects.equals(args.get("tab_id"),tab.get("id"))
                    || args.containsKey("tab_title") && Objects.equals(args.get("tab_title"),tab.get("title"))
                    || args.get("tab_index") instanceof Number n && n.doubleValue()==((Number)tab.get("index")).intValue()) { matches.add(tab); }
        }
        if(matches.size()!=1) { throw new IllegalArgumentException("Tab selector must match exactly one tab; found "+matches.size()); }
        return ((Number)matches.get(0).get("index")).intValue();
    }

    private static String required(Map<String,Object> args,String name) {
        Object value=args.get(name);
        if(!(value instanceof String s) || s.isBlank()) { throw new IllegalArgumentException(name+" is required"); }
        return (String)value;
    }
    static Object field(Object target,String name) {
        for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass()) {
            try { Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(target); }
            catch(ReflectiveOperationException ignored) { }
        }
        return null;
    }
    static Object optionalCall(Object target,String name,Class<?>[] types,Object...args) {
        if(target==null) { return null; }
        try {return call(target,name,types,args);}catch(Exception ignored){return null;}
    }
    static Object call(Object target,String name,Class<?>[] types,Object...args) throws Exception {
        for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass()) {
            try {
                Method method=type.getDeclaredMethod(name,types);method.setAccessible(true);
                try { return method.invoke(target,args); }
                catch(InvocationTargetException e) { if(e.getCause() instanceof Exception ex){throw ex;}throw e; }
            } catch(NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(target.getClass().getName()+"."+name);
    }
}
