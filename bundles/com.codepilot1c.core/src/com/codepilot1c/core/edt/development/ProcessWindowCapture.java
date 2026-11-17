package com.codepilot1c.core.edt.development;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.ImageLoader;
import com.sun.jna.*;
/** Direct process-window observation; no input, foreground activation or desktop pixels. */
public final class ProcessWindowCapture {
    private static final Set<String> NAMES=Set.of("1cv8.exe","1cv8c.exe","1cedt.exe");
    private static final ThreadPoolExecutor WORKERS=new ThreadPoolExecutor(0,2,30,TimeUnit.SECONDS,new SynchronousQueue<>(),task->{Thread t=new Thread(task,"codepilot-window-capture");t.setDaemon(true);return t;});
    private interface Visitor extends Callback,AltCallingConvention {int invoke(Pointer window,Pointer data);}
    record ProcessInfo(long pid,String name,Instant started){}
    static ProcessInfo requireProcess(long pid){
        ProcessHandle process=ProcessHandle.of(pid).filter(ProcessHandle::isAlive).orElseThrow(()->new IllegalArgumentException("Process is not running"));
        ProcessHandle.Info info=process.info();String command=info.command().orElseThrow(()->new IllegalArgumentException("Cannot verify process executable"));String name=Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT);
        if(!NAMES.contains(name))throw new IllegalArgumentException("Only 1C client and EDT windows can be captured");
        return new ProcessInfo(pid,name,info.startInstant().orElseThrow(()->new IllegalArgumentException("Cannot verify process start time")));
    }
    static void dimensions(int width,int height){if(width<1 || height<1 || width>6000 || height>6000 || (long)width*height>16000000)throw new IllegalArgumentException("Unsupported window dimensions");}
    public static Map<String,Object> execute(String action,Long pid,String started,String windowId)throws Exception{return execute(action,pid,started,windowId,true,false);}
    public static Map<String,Object> execute(String action,Long pid,String started,String windowId,boolean includePopups,boolean expectPopup)throws Exception {
        if(!"win32".equals(SWT.getPlatform()))throw new IllegalStateException("Native process-window capture requires Windows");
        if(expectPopup&&!includePopups)throw new IllegalArgumentException("expect_popup requires include_popups=true");
        if("list".equals(action)) {
            List<Map<String,Object>> rows=new ArrayList<>();
            if(pid!=null)rows.add(row(requireProcess(pid)));
            else try(var processes=ProcessHandle.allProcesses()){processes.forEach(process->{try{rows.add(row(requireProcess(process.pid())));}catch(Exception inaccessible){/* Return only verified accessible 1C/EDT processes. */}});}
            return Map.of("processes",rows,"capture_method","win32-print-window","foreground_changed",false);
        }
        if(!"snapshot".equals(action)||pid==null||started==null||windowId==null)throw new IllegalArgumentException("snapshot requires process_id, process_started_at and window_id from list");
        ProcessInfo process=requireProcess(pid);if(!process.started.equals(Instant.parse(started)))throw new IllegalArgumentException("Process identity changed; list windows again");
        long handle=Long.parseUnsignedLong(windowId.startsWith("0x")?windowId.substring(2):windowId,16);
        var lease=ProcessCaptureLease.acquire(process.pid+"/"+process.started,System::nanoTime,TimeUnit.SECONDS.toNanos(5));Future<Map<String,Object>> future;
        try{future=WORKERS.submit(()->{try(lease){var result=capture(process,new Pointer(handle),includePopups,expectPopup,lease);lease.complete();return result;}});}
        catch(RejectedExecutionException busy){lease.close();throw new IllegalStateException("Capture workers are busy; inspect target responsiveness before retrying");}
        try{var result=future.get(5,TimeUnit.SECONDS);lease.accept();return result;}
        catch(TimeoutException timeout){lease.cancel();throw new IllegalStateException("Window capture timed out; native worker retains the process gate until it actually finishes. No UI input was sent.");}
        catch(InterruptedException interrupted){lease.cancel();Thread.currentThread().interrupt();throw interrupted;}
        catch(ExecutionException error){lease.cancel();if(error.getCause() instanceof Exception cause)throw cause;throw error;}
        catch(Exception publicationFailed){lease.cancel();throw publicationFailed;}
    }
    private static Map<String,Object> row(ProcessInfo process){var row=new LinkedHashMap<String,Object>();row.put("process_id",process.pid);row.put("executable",process.name);row.put("process_started_at",process.started.toString());try(var dpi=new DpiContext()){row.put("windows",windows(process).stream().map(ProcessWindowCapture::describeWindow).toList());}return row;}
    private static Map<String,Object> describeWindow(ProcessCaptureScope.Window window){var row=new LinkedHashMap<String,Object>(window.describe());NativeLibrary user=NativeLibrary.getInstance("user32");Memory title=new Memory(4096);title.clear();Pointer hwnd=new Pointer(window.handle());user.getFunction("GetWindowTextW").invokeInt(new Object[]{hwnd,title,2048});row.put("title",title.getWideString(0));row.put("hung_hint",user.getFunction("IsHungAppWindow").invokeInt(new Object[]{hwnd})!=0);return row;}
    private static void ownChildren(ProcessInfo process,Pointer hwnd){NativeLibrary user=NativeLibrary.getInstance("user32");boolean[] owned={true};Visitor visitor=(child,data)->{Memory pid=new Memory(4);pid.clear();user.getFunction("GetWindowThreadProcessId").invokeInt(new Object[]{child,pid});if(user.getFunction("IsWindowVisible").invokeInt(new Object[]{child})!=0 && Integer.toUnsignedLong(pid.getInt(0))!=process.pid){owned[0]=false;return 0;}return 1;};user.getFunction("EnumChildWindows").invokeInt(new Object[]{hwnd,visitor,Pointer.NULL});if(!owned[0])throw new IllegalStateException("A visible embedded window belongs to another process");}
    private static final class DpiContext implements AutoCloseable {
        final NativeLibrary user=NativeLibrary.getInstance("user32");final Pointer previous;
        DpiContext(){previous=user.getFunction("SetThreadDpiAwarenessContext").invokePointer(new Object[]{new Pointer(-4)});if(previous==null)throw new IllegalStateException("Per-monitor window coordinate context unavailable");}
        public void close(){if(user.getFunction("SetThreadDpiAwarenessContext").invokePointer(new Object[]{previous})==null)throw new IllegalStateException("Cannot restore capture coordinate context");}
    }
    private static ProcessCaptureScope.Window window(ProcessInfo process,Pointer window){
        NativeLibrary user=NativeLibrary.getInstance("user32");Memory pid=new Memory(4);pid.clear();long thread=Integer.toUnsignedLong(user.getFunction("GetWindowThreadProcessId").invokeInt(new Object[]{window,pid}));
        if(Integer.toUnsignedLong(pid.getInt(0))!=process.pid||thread==0)throw new IllegalArgumentException("Window no longer belongs to the selected process");
        Memory rect=new Memory(16);if(user.getFunction("GetWindowRect").invokeInt(new Object[]{window,rect})==0)throw new IllegalArgumentException("Window no longer exists");Memory type=new Memory(1024);type.clear();if(user.getFunction("GetClassNameW").invokeInt(new Object[]{window,type,512})==0)throw new IllegalArgumentException("Window class unavailable");
        Pointer owner=user.getFunction("GetWindow").invokePointer(new Object[]{window,4});return new ProcessCaptureScope.Window(Pointer.nativeValue(window),process.pid,thread,Pointer.nativeValue(owner),type.getWideString(0),rect.getInt(0),rect.getInt(4),rect.getInt(8)-rect.getInt(0),rect.getInt(12)-rect.getInt(4),user.getFunction("IsWindowVisible").invokeInt(new Object[]{window})!=0,user.getFunction("IsIconic").invokeInt(new Object[]{window})!=0,Integer.toUnsignedLong(user.getFunction("GetWindowLongW").invokeInt(new Object[]{window,-16})),Integer.toUnsignedLong(user.getFunction("GetWindowLongW").invokeInt(new Object[]{window,-20})));
    }
    private static List<ProcessCaptureScope.Window> windows(ProcessInfo process){
        NativeLibrary user=NativeLibrary.getInstance("user32");var result=new ArrayList<ProcessCaptureScope.Window>();Exception[] failure={null};
        Visitor visitor=(hwnd,data)->{Memory pid=new Memory(4);pid.clear();user.getFunction("GetWindowThreadProcessId").invokeInt(new Object[]{hwnd,pid});if(Integer.toUnsignedLong(pid.getInt(0))==process.pid)try{var item=window(process,hwnd);if(item.width()>0&&item.height()>0 && (item.visible() || item.width()>=100&&item.height()>=80)){result.add(item);if(result.size()>64)throw new IllegalArgumentException("Process window inventory exceeds 64 windows");}}catch(Exception error){failure[0]=error;return 0;}return 1;};
        user.getFunction("EnumWindows").invokeInt(new Object[]{visitor,Pointer.NULL});if(failure[0]!=null)throw new IllegalStateException("Process window inventory changed or exceeded its bound",failure[0]);return result;
    }
    private static void unchanged(ProcessInfo process,ProcessCaptureScope.Window before,ProcessCaptureLease lease){lease.check();if(!requireProcess(process.pid).started.equals(process.started)||!before.equals(window(process,new Pointer(before.handle()))))throw new IllegalStateException("Process/window identity or geometry changed during capture");}
    private static Map<String,Object> capture(ProcessInfo process,Pointer target,boolean includePopups,boolean expectPopup,ProcessCaptureLease lease)throws Exception {
        try(var dpi=new DpiContext()) {
            lease.check();var root=window(process,target);var selected=ProcessCaptureScope.select(root,windows(process),includePopups);var result=print(process,root,lease);var images=new ArrayList<Map<String,Object>>();boolean complete=true;
            for(int i=1;i<selected.size();i++) {var popup=selected.get(i);var row=new LinkedHashMap<String,Object>(describeWindow(popup));try{var image=print(process,popup,lease);row.putAll(image);boolean informative=Boolean.TRUE.equals(image.get("content_informative"));row.put("render_success",true);row.put("content_informative",informative);if(!informative)row.put("reason","uniform_or_transparent_popup_surface");}catch(CancellationException cancelled){throw cancelled;}catch(Exception failed){row.put("render_success",false);row.put("reason","popup_print_failed");row.put("error",String.valueOf(failed.getMessage()));complete=false;}images.add(row);}
            unchanged(process,root,lease);boolean stable;
            try{stable=selected.equals(ProcessCaptureScope.select(window(process,target),windows(process),includePopups));}
            catch(Exception changed){lease.check();stable=false;result.put("inventory_error",String.valueOf(changed.getMessage()));}complete&=stable;
            long rendered=images.stream().filter(row->Boolean.TRUE.equals(row.get("render_success"))).count();long informative=images.stream().filter(row->Boolean.TRUE.equals(row.get("content_informative"))).count();boolean expected=complete&&!Boolean.TRUE.equals(result.get("blank"))&&(!expectPopup||informative>0);
            result.put("success",expected);result.put("state",expected?"captured":expectPopup?"popup_not_observed_or_not_captured":"window_capture_incomplete");result.put("capture_scope",includePopups?"selected_window_and_same_ui_thread_popup_images":"selected_window");result.put("composed",false);result.put("popup_images",images);result.put("popup_window_count",images.size());result.put("captured_popup_count",rendered);result.put("informative_popup_count",informative);result.put("capture_complete",complete);result.put("inventory_stable",stable);result.put("pixel_content_verified",false);result.put("expected_menu_identity_verified",false);result.put("captured_at",Instant.now().toString());result.put("note","image_path is the selected window only. Inspect every popup_images PNG separately. This is one bounded inventory, not an atomic composed desktop frame. An informative WS_POPUP is not proof of the intended menu identity. Only popups present during capture can be observed; no UI input, activation or command replay is performed.");lease.check();return result;
        }
    }
    private static Map<String,Object> print(ProcessInfo process,ProcessCaptureScope.Window window,ProcessCaptureLease lease)throws Exception {
        unchanged(process,window,lease);dimensions(window.width(),window.height());int width=window.width(),height=window.height();var alpha=layeredAttributes(window);int[] black=printPass(process,window,0,lease),white=printPass(process,window,0x00ffffff,lease);if(!alpha.equals(layeredAttributes(window)))throw new IllegalStateException("Layered window attributes changed during capture");boolean reconstructed=WindowPrintPixels.reconstruct(black,white,(window.extendedStyle()&0x80000L)!=0&&!alpha.available());if(alpha.available())WindowPrintPixels.layered(black,alpha.colorKey(),alpha.alpha(),alpha.flags());unchanged(process,window,lease);
        ImageData image=new ImageData(width,height,24,new PaletteData(0xff0000,0xff00,0xff));image.alphaData=new byte[width*height];int[] row=new int[width];boolean allBlack=true,allWhite=true;
        for(int y=0;y<height;y++){for(int x=0;x<width;x++){int at=y*width+x,pixel=WindowPrintPixels.straight(black[at]);row[x]=pixel&0xffffff;image.alphaData[at]=(byte)(pixel>>>24);allBlack&=(pixel&0xffffff)==0;allWhite&=(pixel&0xffffff)==0xffffff;}image.setPixels(0,y,width,row,0);}
        Path folder=ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/runtime-snapshots");Files.createDirectories(folder);Path file=folder.resolve(UUID.randomUUID()+".png"),pending=file.resolveSibling(file.getFileName()+".pending");Files.createFile(pending);lease.owns(pending,file);try{ImageLoader loader=new ImageLoader();loader.data=new ImageData[]{image};loader.save(pending.toString(),SWT.IMAGE_PNG);lease.check();}catch(Exception failed){lease.discard(pending);throw failed;}var result=new LinkedHashMap<String,Object>(describeWindow(window));result.put("image_path",file.toString());result.put("mime_type","image/png");result.put("process_id",process.pid);result.put("process_started_at",process.started.toString());result.put("blank",allBlack||allWhite);result.put("foreground_changed",false);result.put("capture_method","win32-print-window-matte");result.put("transparency_reconstructed",reconstructed);result.put("printed_inconclusive",!reconstructed);result.put("content_informative",reconstructed&&WindowPrintPixels.informative(black));result.put("window_shape_equivalence_verified",false);byte[] pixels=new byte[black.length*4];for(int i=0;i<black.length;i++)for(int shift=0;shift<32;shift+=8)pixels[i*4+shift/8]=(byte)(black[i]>>>shift);var quality=CaptureQuality.assess(pixels,width,height);result.put("quality",quality);result.put("low_information",Boolean.TRUE.equals(quality.get("low_information")));return result;
    }
    private record LayeredAttributes(boolean available,int colorKey,int alpha,int flags){}
    private static LayeredAttributes layeredAttributes(ProcessCaptureScope.Window window){
        if((window.extendedStyle()&0x80000L)==0)return new LayeredAttributes(false,0,255,0);
        Memory key=new Memory(4),alpha=new Memory(1),flags=new Memory(4);key.clear();alpha.clear();flags.clear();boolean available=NativeLibrary.getInstance("user32").getFunction("GetLayeredWindowAttributes").invokeInt(new Object[]{new Pointer(window.handle()),key,alpha,flags})!=0;
        return new LayeredAttributes(available,key.getInt(0),Byte.toUnsignedInt(alpha.getByte(0)),flags.getInt(0));
    }
    private static int[] printPass(ProcessInfo process,ProcessCaptureScope.Window window,int background,ProcessCaptureLease lease){
        unchanged(process,window,lease);ownChildren(process,new Pointer(window.handle()));NativeLibrary user=NativeLibrary.getInstance("user32"),gdi=NativeLibrary.getInstance("gdi32");Pointer hwnd=new Pointer(window.handle()),dc=user.getFunction("GetDC").invokePointer(new Object[]{hwnd});if(dc==null)throw new IllegalStateException("Cannot obtain window graphics context");Pointer memory=null,bitmap=null,previous=null;
        try {memory=gdi.getFunction("CreateCompatibleDC").invokePointer(new Object[]{dc});if(memory==null)throw new IllegalStateException("Cannot allocate capture context");Memory header=new Memory(40);header.clear();header.setInt(0,40);header.setInt(4,window.width());header.setInt(8,-window.height());header.setShort(12,(short)1);header.setShort(14,(short)32);Memory bits=new Memory(Native.POINTER_SIZE);bits.clear();bitmap=gdi.getFunction("CreateDIBSection").invokePointer(new Object[]{dc,header,0,bits,Pointer.NULL,0});if(bitmap==null)throw new IllegalStateException("Cannot allocate capture bitmap");previous=gdi.getFunction("SelectObject").invokePointer(new Object[]{memory,bitmap});if(previous==null||Pointer.nativeValue(previous)==-1)throw new IllegalStateException("Cannot select capture bitmap");int[] pixels=new int[window.width()*window.height()];Arrays.fill(pixels,background);bits.getPointer(0).write(0,pixels,0,pixels.length);
            if(user.getFunction("PrintWindow").invokeInt(new Object[]{hwnd,memory,2})==0)throw new IllegalStateException("Windows could not print this window");gdi.getFunction("GdiFlush").invokeInt(new Object[]{});ownChildren(process,hwnd);unchanged(process,window,lease);bits.getPointer(0).read(0,pixels,0,pixels.length);return pixels;
        }finally {if(previous!=null&&memory!=null&&Pointer.nativeValue(previous)!=-1)gdi.getFunction("SelectObject").invokePointer(new Object[]{memory,previous});if(bitmap!=null)gdi.getFunction("DeleteObject").invokeInt(new Object[]{bitmap});if(memory!=null)gdi.getFunction("DeleteDC").invokeInt(new Object[]{memory});user.getFunction("ReleaseDC").invokeInt(new Object[]{hwnd,dc});}
    }
}
