// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.editor;

import java.lang.reflect.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.nio.file.Path;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.widgets.*;
import com._1c.g5.v8.bm.integration.*;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.NativeRenderEvent;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import org.eclipse.core.runtime.IProgressMonitor;

/** Pinned SDK adapter: paint the existing renderer cache into owned buffers, then restore HWND output.
 * Does not replace editor fields or the global layout service/buffered-render setting.
 */
final class NativeFormRaster {
    record Raster(ImageData image,String id){}
    private static final long MAX_BUFFER=64L*1024*1024;
    private static Object field(Object receiver,String name)throws Exception{return EdtEditorService.field(receiver,name);}
    private static Object call(Object receiver,String name)throws Exception{return EdtEditorService.call(receiver,name,new Class<?>[0]);}
    static Raster capture(NativeFormProjection.Context context,Object mapping)throws Exception {
        NativeFormProjection.supported();
        if(mapping==null||!context.available()||!context.nativeLock.tryLock())throw new FormProjectionGeneration.Unverified("native_capture_not_ready");
        Path folder=null;
        try {
            Object representation=context.projection.representation();
            Object installed=context.session(),layout=context.layout();
            Object installedImage=field(representation,"formImageData");
            if(installedImage instanceof ImageData image){
                if(image.width<1||image.height<1||(long)image.width*image.height>8_000_000)throw new FormProjectionGeneration.Unverified("native_capture_size_limit");
                return new Raster((ImageData)image.clone(),UUID.randomUUID().toString());
            }
            ClassLoader loader=representation.getClass().getClassLoader();
            if(!"win32".equals(org.eclipse.swt.SWT.getPlatform())||Boolean.TRUE.equals(loader.loadClass("com._1c.g5.v8.dt.form.layout.service.NativeRenderService").getMethod("isBufferedRender").invoke(null)))throw new FormProjectionGeneration.Unverified("native_capture_buffered_image_unavailable");
            Control control=(Control)field(representation,"formNativeComposite");
            if(control.isDisposed()||!control.isVisible()||control.getSize().x<1||control.getSize().y<1)throw new FormProjectionGeneration.Unverified("native_capture_control_unavailable");
            Object delegate=field(representation,"renderService");
            if(delegate==null)throw new FormProjectionGeneration.Unverified("native_capture_renderer_unavailable");
            Object rendererIdentity=field(delegate,"visualizerLoader");
            if(rendererIdentity==null)throw new FormProjectionGeneration.Unverified("native_capture_cache_unavailable");
            Class<?> serviceClass=loader.loadClass("com._1c.g5.v8.dt.form.layout.service.HippoLayoutService");
            Class<?> renderer=loader.loadClass("com._1c.g5.v8.dt.form.layout.service.ILayoutRenderService");
            Point size=((Control)field(representation,"scrolledComposite")).getSize();
            int width=((Number)call(layout,"getWidth")).intValue(),height=((Number)call(layout,"getHeight")).intValue();
            long capacity=Math.multiplyExact(Math.multiplyExact((long)Math.max(width,size.x)+1000,(long)Math.max(height,size.y)+1000),4);
            if(capacity<1||capacity>MAX_BUFFER)throw new FormProjectionGeneration.Unverified("native_capture_size_limit");
            var popups=(List<?>)call(delegate,"getPopupWindows");
            if(popups.size()>NativeRasterPopupPool.LIMIT)throw new FormProjectionGeneration.Unverified("native_capture_popup_limit");
            Path parent=context.file.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/editor-capture-buffers");
            Files.createDirectories(parent);folder=Files.createTempDirectory(parent,"capture-");
            Path pixels=folder.resolve("form.bin"),popup=folder.resolve("popup.bin");
            allocate(pixels,capacity);allocate(popup,NativeRasterPopupPool.LIMIT*16L*1000*1000);
            var exchange=new NativeRasterExchange<ImageData>();
            Object proxy=Proxy.newProxyInstance(renderer.getClassLoader(),new Class<?>[]{renderer},(ignored,method,args)->{
                if(method.getDeclaringClass()==Object.class)return switch(method.getName()){case "hashCode"->System.identityHashCode(ignored);case "equals"->ignored==args[0];default->"EDT Bridge scoped native raster";};
                try{
                if(method.getName().equals("setSelectedElements"))return null; // observation must not consume/replace pending selection
                if(method.getName().equals("addPopupWindow")){
                    if(call(delegate,"getFormWindow")!=control)throw new FormProjectionGeneration.Unverified("native_capture_popup_owner_changed");
                    NativeRasterPopupPool.grow(()->(List<?>)call(delegate,"getPopupWindows"),()->invoke(method,delegate,args),child->child instanceof Control widget&&!widget.isDisposed()&&widget.getParent()==control&&!widget.getVisible());
                    return null;
                }
                if(Set.of("setWindows","dispose","drainSelectedElements").contains(method.getName()))throw new FormProjectionGeneration.Unverified("native_capture_renderer_contract_changed");
                if(!method.getName().equals("render"))return invoke(method,delegate,args);
                if(args.length!=30||!Boolean.TRUE.equals(args[29]))throw new FormProjectionGeneration.Unverified("native_capture_cache_contract_changed");
                Object[] buffered=args.clone();buffered[1]=pixels.toString();buffered[2]=popup.toString();
                return exchange.paint(()->(String)invoke(method,delegate,buffered),result->{
                    Object parsed=loader.loadClass("com._1c.g5.v8.dt.form.layout.service.NativeFormData").getMethod("deserialize",String.class).invoke(null,result);
                    Object data=call(parsed,"getFormData");
                    int w=((Number)call(data,"getOffscreenFormWidth")).intValue(),h=((Number)call(data,"getOffscreenFormHeight")).intValue(),stride=((Number)call(data,"getOffscreenFormStride")).intValue();
                    if(w!=width||h!=height)throw new FormProjectionGeneration.Unverified("native_capture_layout_dimensions_changed");
                    return NativeRasterPixels.read(pixels,w,h,stride,capacity);
                },()->{
                    try{
                        Object restored=invoke(method,delegate,args);
                        if(!(restored instanceof String response)||response.isBlank())throw new IllegalStateException("Native renderer did not acknowledge HWND restore");
                        Object parsed=loader.loadClass("com._1c.g5.v8.dt.form.layout.service.NativeFormData").getMethod("deserialize",String.class).invoke(null,response);
                        if(call(parsed,"getFormData")==null)throw new IllegalStateException("Invalid native restore receipt");
                        return null;
                    }catch(Exception error){NativeFormProjection.invalidate(context);throw error;}
                });
                }catch(Exception error){exchange.reject(error);NativeFormProjection.invalidate(context);throw error;}
            });
            IBmModel model=(IBmModel)field(representation,"bmModel");Form form=(Form)context.input();
            Object version=((IRuntimeVersionSupport)field(representation,"versionSupport")).getRuntimeVersion(form);
            Object theme=field(representation,"themeProjection"),visual=call(theme,"getPlatformVisualComputer");
            boolean large=(Boolean)call(call(theme,"getHippoTheme"),"isLargeFont");
            Method create=Arrays.stream(serviceClass.getMethods()).filter(m->m.getName().equals("createHippoSession")&&m.getParameterCount()==21).findFirst().orElseThrow();
            model.executeAndRollback(new AbstractBmTask<Void>("EDT Bridge native capture") {
                public Void execute(IBmTransaction transaction,IProgressMonitor monitor){
                    Object session=null;
                    try {
                        Object service=serviceClass.getConstructor().newInstance();
                        session=invoke(create,service,new Object[]{transaction,form,field(representation,"transformatorService"),proxy,visual,mapping,field(representation,"interfaceVariant"),field(representation,"interfaceTheme"),field(representation,"targetPlatform"),field(representation,"interfaceScale"),size.x,size.y,field(representation,"isRotated"),field(representation,"mobileDeviceIndex"),field(representation,"mobileScaleIndex"),field(representation,"resolutionIndex"),field(representation,"scaleIndex"),large,version,NativeRenderEvent.buildUpdateEvent(),true});
                        return null;
                    }catch(Exception error){throw new IllegalStateException("Native raster capture failed",error);}
                    finally{if(session!=null)try{call(session,"dispose");}catch(Exception error){throw new IllegalStateException("Native capture session disposal failed",error);}}
                }
            });
            ImageData captured=exchange.require();
            if(field(delegate,"visualizerLoader")!=rendererIdentity||context.session()!=installed||context.layout()!=layout||!context.installed(installed,layout))throw new FormProjectionGeneration.Unverified("native_capture_generation_changed");
            return new Raster(captured,UUID.randomUUID().toString());
        }finally {
            context.nativeLock.unlock();
            if(folder!=null){Files.deleteIfExists(folder.resolve("form.bin"));Files.deleteIfExists(folder.resolve("popup.bin"));Files.delete(folder);}
        }
    }
    private static void allocate(Path path,long bytes)throws Exception {
        try(var channel=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){channel.position(bytes-1);channel.write(ByteBuffer.wrap(new byte[]{0}));}
    }
    private static Object invoke(Method method,Object receiver,Object[] args)throws Exception {
        try{return method.invoke(receiver,args);}catch(InvocationTargetException error){if(error.getCause() instanceof Exception cause)throw cause;if(error.getCause() instanceof Error cause)throw cause;throw error;}
    }
}
