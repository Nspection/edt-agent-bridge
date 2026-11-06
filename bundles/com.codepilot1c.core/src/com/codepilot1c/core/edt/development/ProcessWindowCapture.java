package com.codepilot1c.core.edt.development;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.ImageLoader;
import com.sun.jna.*;
/** Native bootstrap observation for 1C/EDT processes; no input or foreground activation. */
public final class ProcessWindowCapture {
    private static final Set<String> NAMES=Set.of("1cv8.exe","1cv8c.exe","1cedt.exe");
    private static final ThreadPoolExecutor WORKERS=new ThreadPoolExecutor(0,2,30,TimeUnit.SECONDS,new SynchronousQueue<>(),task->{Thread t=new Thread(task,"codepilot-window-capture");t.setDaemon(true);return t;});
    private interface Visitor extends Callback,AltCallingConvention {int invoke(Pointer window,Pointer data);}
    record ProcessInfo(long pid,String name,Instant started){}
    static ProcessInfo requireProcess(long pid){
        ProcessHandle process=ProcessHandle.of(pid).filter(ProcessHandle::isAlive).orElseThrow(()->new IllegalArgumentException("Process is not running"));
        ProcessHandle.Info info=process.info();String command=info.command().orElseThrow(()->new IllegalArgumentException("Cannot verify process executable"));String name=Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT);
        if(!NAMES.contains(name)){throw new IllegalArgumentException("Only 1C client and EDT windows can be captured");}
        return new ProcessInfo(pid,name,info.startInstant().orElseThrow(()->new IllegalArgumentException("Cannot verify process start time")));
    }
    static void dimensions(int width,int height){if(width<1 || height<1 || width>6000 || height>6000 || (long)width*height>16000000){throw new IllegalArgumentException("Unsupported window dimensions");}}
    public static Map<String,Object> execute(String action,Long pid,String started,String windowId) throws Exception {
        if(!"win32".equals(SWT.getPlatform())){throw new IllegalStateException("Native process-window capture requires Windows");}
        if("list".equals(action)) {
            List<Map<String,Object>> rows=new ArrayList<>();
            if(pid!=null){ProcessInfo process=requireProcess(pid);rows.add(row(process));}
            else{try(var processes=ProcessHandle.allProcesses()){processes.forEach(process->{try{rows.add(row(requireProcess(process.pid())));}catch(Exception inaccessible){/* Only verified 1C processes are returned. */}});}}
            return Map.of("processes",rows,"capture_method","win32-print-window","foreground_changed",false);
        }
        if(!"snapshot".equals(action) || pid==null || started==null || windowId==null){throw new IllegalArgumentException("snapshot requires process_id, process_started_at and window_id from list");}
        ProcessInfo process=requireProcess(pid);if(!process.started.equals(Instant.parse(started))){throw new IllegalArgumentException("Process identity changed; list windows again");}
        long handle=Long.parseUnsignedLong(windowId.startsWith("0x")?windowId.substring(2):windowId,16);Pointer window=new Pointer(handle);AtomicBoolean cancelled=new AtomicBoolean();
        Future<Map<String,Object>> future;
        try{future=WORKERS.submit(()->capture(process,window,cancelled));}catch(RejectedExecutionException busy){throw new IllegalStateException("Capture workers are busy; inspect target responsiveness before retrying");}
        try{return future.get(5,TimeUnit.SECONDS);}catch(TimeoutException timeout){cancelled.set(true);future.cancel(false);throw new IllegalStateException("Window capture timed out; target may be paused or unresponsive. No UI input was sent.");}catch(ExecutionException error){if(error.getCause() instanceof Exception e){throw e;}throw error;}
    }
    private static Map<String,Object> row(ProcessInfo process) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("process_id",process.pid);row.put("executable",process.name);row.put("process_started_at",process.started.toString());row.put("windows",windows(process));return row;
    }
    private static List<Map<String,Object>> windows(ProcessInfo process) {
        NativeLibrary user=NativeLibrary.getInstance("user32");List<Map<String,Object>> result=new ArrayList<>();
        Visitor visitor=(window,data)->{if(owner(user,window)==process.pid && result.size()<64){Memory rect=new Memory(16);if(user.getFunction("GetWindowRect").invokeInt(new Object[]{window,rect})!=0){int width=rect.getInt(8)-rect.getInt(0),height=rect.getInt(12)-rect.getInt(4);if(width>=100 && height>=80){Memory title=new Memory(4096);title.clear();user.getFunction("GetWindowTextW").invokeInt(new Object[]{window,title,2048});Pointer parent=user.getFunction("GetWindow").invokePointer(new Object[]{window,4});Map<String,Object> row=new LinkedHashMap<>();row.put("window_id",hex(window));row.put("title",title.getWideString(0));row.put("width",width);row.put("height",height);row.put("visible",user.getFunction("IsWindowVisible").invokeInt(new Object[]{window})!=0);row.put("minimized",user.getFunction("IsIconic").invokeInt(new Object[]{window})!=0);row.put("owner_window_id",hex(parent));result.add(row);}}}return 1;};
        user.getFunction("EnumWindows").invokeInt(new Object[]{visitor,Pointer.NULL});return result;
    }
    private static long owner(NativeLibrary user,Pointer window){Memory pid=new Memory(4);pid.clear();user.getFunction("GetWindowThreadProcessId").invokeInt(new Object[]{window,pid});return Integer.toUnsignedLong(pid.getInt(0));}
    private static String hex(Pointer pointer){return "0x"+Long.toUnsignedString(Pointer.nativeValue(pointer),16);}
    private static Map<String,Object> capture(ProcessInfo process,Pointer window,AtomicBoolean cancelled) throws Exception {
        NativeLibrary user=NativeLibrary.getInstance("user32"),gdi=NativeLibrary.getInstance("gdi32");
        if(owner(user,window)!=process.pid){throw new IllegalArgumentException("Window no longer belongs to the selected process");}
        Memory rect=new Memory(16);if(user.getFunction("GetWindowRect").invokeInt(new Object[]{window,rect})==0){throw new IllegalArgumentException("Window no longer exists");}
        int width=rect.getInt(8)-rect.getInt(0),height=rect.getInt(12)-rect.getInt(4);dimensions(width,height);
        Pointer dc=user.getFunction("GetDC").invokePointer(new Object[]{window});if(dc==null){throw new IllegalStateException("Cannot obtain window graphics context");}
        Pointer memory=null,bitmap=null,previous=null;
        try {
            memory=gdi.getFunction("CreateCompatibleDC").invokePointer(new Object[]{dc});if(memory==null){throw new IllegalStateException("Cannot allocate capture context");}
            Memory header=new Memory(40);header.clear();header.setInt(0,40);header.setInt(4,width);header.setInt(8,-height);header.setShort(12,(short)1);header.setShort(14,(short)32);
            Memory bits=new Memory(Native.POINTER_SIZE);bits.clear();bitmap=gdi.getFunction("CreateDIBSection").invokePointer(new Object[]{dc,header,0,bits,Pointer.NULL,0});if(bitmap==null){throw new IllegalStateException("Cannot allocate capture bitmap");}
            previous=gdi.getFunction("SelectObject").invokePointer(new Object[]{memory,bitmap});
            if(user.getFunction("PrintWindow").invokeInt(new Object[]{window,memory,2})==0){throw new IllegalStateException("Windows could not capture this window");}
            byte[] pixels=bits.getPointer(0).getByteArray(0,width*height*4);
            if(cancelled.get() || owner(user,window)!=process.pid || !requireProcess(process.pid).started.equals(process.started)){throw new IllegalStateException("Capture cancelled or process/window identity changed");}
            ImageData image=new ImageData(width,height,24,new PaletteData(0xff0000,0xff00,0xff));int[] row=new int[width];boolean black=true,white=true;
            for(int y=0;y<height;y++){for(int x=0;x<width;x++){int at=(y*width+x)*4;int color=(pixels[at+2]&255)<<16|(pixels[at+1]&255)<<8|(pixels[at]&255);row[x]=color;black&=color==0;white&=color==0xffffff;}image.setPixels(0,y,width,row,0);}
            if(cancelled.get()){throw new CancellationException("Capture expired");}
            Path folder=ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/runtime-snapshots");Files.createDirectories(folder);Path file=folder.resolve(UUID.randomUUID()+".png");ImageLoader loader=new ImageLoader();loader.data=new ImageData[]{image};loader.save(file.toString(),SWT.IMAGE_PNG);
            return Map.of("image_path",file.toString(),"mime_type","image/png","process_id",process.pid,"process_started_at",process.started.toString(),"window_id",hex(window),"width",width,"height",height,"blank",black||white,"foreground_changed",false,"note","Native PrintWindow; hidden/minimized/loading windows can return blank images. This is observation, not an application readiness assertion.");
        } finally {if(previous!=null && memory!=null){gdi.getFunction("SelectObject").invokePointer(new Object[]{memory,previous});}if(bitmap!=null){gdi.getFunction("DeleteObject").invokeInt(new Object[]{bitmap});}if(memory!=null){gdi.getFunction("DeleteDC").invokeInt(new Object[]{memory});}user.getFunction("ReleaseDC").invokeInt(new Object[]{window,dc});}
    }
}
