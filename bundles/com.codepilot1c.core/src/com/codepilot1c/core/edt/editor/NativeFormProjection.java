// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.editor;

import java.lang.reflect.*;
import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;

/** Versioned SDK completion adapter. Calls the same locked native rebuild as EDT's callback;
 * never replaces renderer fields, switches tabs, saves editors or changes model data.
 */
final class NativeFormProjection {
    private static final ProjectionReadExecutor READS=new ProjectionReadExecutor(com.codepilot1c.core.edt.development.RuntimeProcessLifecycle.global());
    record Source(String modelHash,String fileRevision){}
    private record Saved(String id,String hash,String revision,WeakReference<Object> input,WeakReference<Object> session,WeakReference<Object> layout){}
    private static final Map<Object,Saved> RECEIPTS=new WeakHashMap<>(); // UI thread only; values never retain the key
    private static final String PRESENTATION="16.0.2.v202607090722",LAYOUT="15.0.1.v202607090722";
    static final class Context implements FormProjectionGeneration.Backend {
        final IEditorPart editor;final IEditorInput editorInput;final IFile file;
        final long epoch=com.codepilot1c.core.edt.development.RuntimeProcessLifecycle.global().epoch();
        final EdtEditorViewportService.Projection projection;final org.eclipse.emf.common.util.URI uri;
        final Map<String,Object> args;final ReentrantLock nativeLock;
        Context(IEditorPart editor,IFile file,Map<String,Object> args)throws Exception {
            this.editor=editor;editorInput=editor.getEditorInput();this.args=args;
            projection=EdtEditorViewportService.projection(editor);uri=EcoreUtil.getURI(projection.form());
            this.file=FormSourceBinding.require(sourceLookup().getPlatformResource(projection.form()),file);
            nativeLock=(ReentrantLock)EdtEditorService.field(projection.representation(),"lock");
        }
        CompletableFuture<Source> sourceAsync()throws Exception {
            return READS.submit(epoch,this::source);
        }
        Source source()throws Exception {
            if(file==null||!file.exists()||!file.isSynchronized(IResource.DEPTH_ZERO))throw new FormProjectionGeneration.Unverified("source_not_synchronized");
            String before=revision(file);var gateway=new EdtMetadataGateway();IProject project=file.getProject();
            String hash=gateway.getBmModelManager().executeReadOnlyTask(project,tx->{
                EObject current=tx.getObjectByUri(uri);
                if(current==null||!"Form".equals(current.eClass().getName())||!uri.equals(EcoreUtil.getURI(current)))throw new FormProjectionGeneration.Unverified("authoritative_form_unavailable");
                var expected=gateway.getV8ProjectManager().getProject(project);var actual=gateway.getV8ProjectManager().getProject(current);
                if(expected==null||actual==null||!expected.getProject().equals(actual.getProject()))throw new FormProjectionGeneration.Unverified("authoritative_form_project_changed");
                if(!file.equals(sourceLookup().getPlatformResource(current)))throw new FormProjectionGeneration.Unverified("authoritative_form_source_changed");
                try{return FormProjectionFingerprint.of(current);}catch(Exception error){throw new IllegalStateException("Form fingerprint unavailable",error);}
            });
            if(!before.equals(revision(file)))throw new FormProjectionGeneration.Unverified("source_changed_during_read");
            return new Source(hash,before);
        }
        public boolean available(){try{
            com.codepilot1c.core.edt.development.RuntimeProcessLifecycle.global().require(epoch);
            var current=EdtEditorViewportService.projection(editor);Object control=EdtEditorService.call(projection.viewer(),"getControl",new Class<?>[0]);
            return editor.getEditorInput()==editorInput&&current.viewer()==projection.viewer()&&current.representation()==projection.representation()
                &&control instanceof Control widget&&!widget.isDisposed()&&widget.isVisible()
                &&editor.getSite().getPage().getActiveEditor()==editor;
        }catch(Exception error){return false;}}
        public boolean dirty(){return editor.isDirty();}
        public boolean busy(){return nativeLock.isLocked();}
        public Object editor(){return editor;}
        public Object input(){try{return EdtEditorService.field(projection.representation(),"form");}catch(Exception error){return null;}}
        public String fingerprint()throws Exception{return FormProjectionFingerprint.of((EObject)input());}
        public Object session(){try{return EdtEditorService.field(projection.representation(),"hippoSession");}catch(Exception error){return null;}}
        public Object layout(){try{return EdtEditorService.field(projection.representation(),"hippoLayForm");}catch(Exception error){return null;}}
        public boolean installed(Object session,Object layout)throws Exception {
            if(session==null||layout==null)return false;
            Object control=EdtEditorService.field(projection.representation(),"formNativeComposite");
            return control instanceof Control widget&&!widget.isDisposed()&&widget.isVisible()
                &&EdtEditorService.call(session,"getHippoForm",new Class<?>[0])==layout
                &&EdtEditorService.field(control,"form")==layout
                &&EdtEditorService.call(session,"getFormImageData",new Class<?>[0])==EdtEditorService.field(projection.representation(),"formImageData");
        }
        public void rebuild(Object mapping)throws Exception {
            supported();Object representation=projection.representation();ClassLoader loader=representation.getClass().getClassLoader();
            if(!Boolean.TRUE.equals(EdtEditorService.call(representation,"isRebuildAllowed",new Class<?>[0])))throw new FormProjectionGeneration.Unverified("native_rebuild_not_ready");
            Class<?> eventClass=loader.loadClass("com._1c.g5.v8.dt.form.model.NativeRenderEvent");
            Method rebuild=Arrays.stream(representation.getClass().getDeclaredMethods()).filter(m->m.getName().equals("rebuildInternal")&&m.getParameterCount()==4).findFirst().orElseThrow(()->new FormProjectionGeneration.Unverified("unsupported_native_rebuild_contract"));
            rebuild.setAccessible(true);
            if(!nativeLock.tryLock())throw new FormProjectionGeneration.Unverified("native_rebuild_busy");
            try{
                Object update=eventClass.getMethod("buildUpdateEvent").invoke(null);
                invoke(rebuild,representation,input(),mapping,update,false);
                if("select".equals(args.getOrDefault("mode","list"))){
                    EObject target=EdtEditorViewportService.selection((EObject)input(),args);
                    // Preserve native domain selection/property integration. Its asynchronous event
                    // is followed here by our own full (non-cache) render in this causal generation.
                    EdtEditorService.call(projection.viewer(),"setSelection",new Class<?>[]{ISelection.class,boolean.class},new StructuredSelection(target),true);
                    var feature=target.eClass().getEStructuralFeature("id");int id=feature==null?0:((Number)target.eGet(feature)).intValue();
                    Object select=eventClass.getMethod("buildSelectByIdEvent",long.class,int[].class).invoke(null,0L,new int[]{id});
                    invoke(rebuild,representation,input(),mapping,select,false);
                }
            }finally{nativeLock.unlock();}
        }
    }
    private static com._1c.g5.v8.dt.core.platform.IResourceLookup sourceLookup(){return com._1c.g5.wiring.ServiceAccess.get(com._1c.g5.v8.dt.core.platform.IResourceLookup.class);}
    private static void supported(){
        var presentation=Platform.getBundle("com._1c.g5.v8.dt.form.presentation");var layout=Platform.getBundle("com._1c.g5.v8.dt.form.layout");
        if(presentation==null||layout==null||!PRESENTATION.equals(presentation.getVersion().toString())||!LAYOUT.equals(layout.getVersion().toString()))throw new FormProjectionGeneration.Unverified("unsupported_sdk_completion_contract");
    }
    private static void invoke(Method method,Object receiver,Object...args)throws Exception {
        try{method.invoke(receiver,args);}catch(InvocationTargetException error){if(error.getCause() instanceof Exception cause)throw cause;throw error;}
    }
    static void refresh(Context context,Source source,EdtUiRequest<?> request,Consumer<FormProjectionGeneration.Receipt> complete,Consumer<Throwable> failure)throws Exception {
        supported();if(!source.modelHash().equals(context.fingerprint()))throw new FormProjectionGeneration.Unverified("editor_model_differs_from_authoritative_bm");
        if("select".equals(context.args.getOrDefault("mode","list")))EdtEditorViewportService.selection(context.projection.form(),context.args);
        var generation=new FormProjectionGeneration(context,(String)context.args.get("expected_model_sha256"));
        Object controller=EdtEditorService.field(context.projection.representation(),"controller");
        Method mapping=Arrays.stream(controller.getClass().getMethods()).filter(m->m.getName().equals("getMappingRootAsync")&&m.getParameterCount()==2&&m.getParameterTypes()[0]==Class.class).findFirst().orElseThrow(()->new FormProjectionGeneration.Unverified("unsupported_mapping_completion_contract"));
        Class<?> handler=mapping.getParameterTypes()[1],mappingClass=controller.getClass().getClassLoader().loadClass("com._1c.g5.v8.dt.form.mapping.model.CommandInterfaceMapping");
        var finished=new java.util.concurrent.atomic.AtomicBoolean();Display display=Display.getCurrent();
        Object callback=Proxy.newProxyInstance(handler.getClassLoader(),new Class<?>[]{handler},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class)return switch(method.getName()){case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];default->"EDT Bridge owned projection completion";};
            if(!method.getName().equals("handle")||args==null||args.length!=1||!mappingClass.isInstance(args[0])){failure.accept(new FormProjectionGeneration.Unverified("invalid_mapping_completion"));return null;}
            Object root=args[0];
            Runnable advance=new Runnable(){public void run(){request.dispatch(()->{
                if(finished.get())return;
                try{
                    if(generation.busy(context)){display.timerExec(50,this);return;}
                    var receipt=generation.complete(context,root);finished.set(true);
                    RECEIPTS.put(context.projection.representation(),new Saved(receipt.requestId(),receipt.modelSha256(),source.fileRevision(),new WeakReference<>(receipt.input()),new WeakReference<>(receipt.session()),new WeakReference<>(receipt.layout())));
                    complete.accept(receipt);
                }catch(Throwable error){finished.set(true);failure.accept(error);}
            });}};
            display.asyncExec(advance);return null;
        });
        invoke(mapping,controller,mappingClass,callback);
    }
    static Map<String,Object> status(Context context,Source source)throws Exception {
        var out=new LinkedHashMap<String,Object>();out.put("scope","authoritative_bm_form_model_and_native_generation");out.put("model_sha256",source.modelHash());out.put("editor_model_sha256",context.fingerprint());out.put("visual_correctness_verified",false);out.put("fingerprint_scope","form_containment_and_reference_uris");out.put("external_dependency_contents_verified",false);
        Saved saved=RECEIPTS.get(context.projection.representation());boolean verified=saved!=null&&saved.hash().equals(source.modelHash())&&saved.revision().equals(source.fileRevision())&&saved.input().get()==context.input()&&saved.session().get()==context.session()&&saved.layout().get()==context.layout()&&!context.dirty()&&context.available()&&saved.hash().equals(context.fingerprint())&&context.installed(context.session(),context.layout());
        out.put("verified",verified);out.put("state",verified?"refreshed":"unverified");if(saved!=null)out.put("projection_id",saved.id());return out;
    }
    static String revision(IFile file)throws Exception {
        Path path=file.getLocation().toFile().toPath();if(Files.size(path)>16*1024*1024)throw new IllegalStateException("Form file exceeds revision limit");
        var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(path)){byte[] bytes=new byte[65536];int n;while((n=input.read(bytes))!=-1)digest.update(bytes,0,n);}
        return file.getModificationStamp()+":"+Files.getLastModifiedTime(path)+":"+HexFormat.of().formatHex(digest.digest());
    }
}
