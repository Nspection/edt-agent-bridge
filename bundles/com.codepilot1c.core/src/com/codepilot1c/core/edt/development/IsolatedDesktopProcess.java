// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;
import com.sun.jna.*;import com.sun.jna.win32.*;import com.sun.jna.platform.win32.*;import com.sun.jna.ptr.IntByReference;
/** Own child on an unselected desktop. Never switches desktops or falls back to visible launch. */
public final class IsolatedDesktopProcess extends Process {
 private interface DesktopApi extends StdCallLibrary{Pointer CreateDesktopW(WString name,Pointer device,Pointer mode,int flags,int access,Pointer security);boolean CloseDesktop(Pointer desktop);Pointer GetThreadDesktop(int thread);boolean SetThreadDesktop(Pointer desktop);}
 private interface ThreadApi extends StdCallLibrary{int ResumeThread(WinNT.HANDLE thread);}
 private static class Api{static final ThreadApi THREAD=Native.load("kernel32",ThreadApi.class);static final DesktopApi DESKTOP=Native.load("user32",DesktopApi.class);}
 private final ProcessHandle child;private final long id;private final CompletableFuture<Process> completion;private volatile Integer exit;
 private IsolatedDesktopProcess(WinBase.PROCESS_INFORMATION info,Pointer desktop)throws Exception{
  id=info.dwProcessId.longValue();child=ProcessHandle.of(id).orElseThrow();var handle=info.hProcess;
  completion=child.onExit().handle((gone,error)->{try{var code=new IntByReference();if(!Kernel32.INSTANCE.GetExitCodeProcess(handle,code))throw new IllegalStateException("Owned child exit status unavailable");exit=code.getValue();return (Process)this;}finally{Kernel32.INSTANCE.CloseHandle(handle);closeDesktop(desktop);}});
 }
 static String quote(String value){if(value.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid process argument");if(!value.isEmpty()&&value.chars().noneMatch(c->Character.isWhitespace(c)||c=='"'))return value;var out=new StringBuilder("\"");int slashes=0;for(char c:value.toCharArray()){if(c=='\\'){slashes++;continue;}if(c=='\"'){out.append("\\".repeat(slashes*2+1));out.append(c);}else{out.append("\\".repeat(slashes));out.append(c);}slashes=0;}return out.append("\\".repeat(slashes*2)).append('\"').toString();}
 private record Desktop(String name,Pointer handle) {}
 private static Desktop createDesktop(){
  String name="EDTBridge-"+UUID.randomUUID().toString().replace("-","");
  var allocated=new java.util.concurrent.atomic.AtomicReference<Pointer>();
  var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
  Thread thread=new Thread(()->{
   try{
    Pointer original=Api.DESKTOP.GetThreadDesktop(Kernel32.INSTANCE.GetCurrentThreadId());
    if(original==null)throw new IllegalStateException("Desktop context unavailable");
    Pointer desktop=Api.DESKTOP.CreateDesktopW(new WString(name),null,null,0,0x00c7,null);
    if(desktop==null)throw new IllegalStateException("Isolated desktop unavailable; no visible fallback");
    allocated.set(desktop);
    if(!Api.DESKTOP.SetThreadDesktop(original))throw new IllegalStateException("Cannot detach owned desktop creator");
   }catch(Throwable problem){failure.set(problem);}
  },"EDTBridge-desktop-create");
  thread.setDaemon(true);thread.start();boolean interrupted=false;
  // A failed detach still leaves the creator assigned to HDESK. Close only after that OS thread exits.
  while(thread.isAlive())try{thread.join();}catch(InterruptedException stop){interrupted=true;}
  if(interrupted){Thread.currentThread().interrupt();failure.compareAndSet(null,new IllegalStateException("Desktop creation interrupted; no client launched"));}
  if(failure.get()!=null){
   if(allocated.get()!=null&&!Api.DESKTOP.CloseDesktop(allocated.get()))failure.get().addSuppressed(new IllegalStateException("Owned desktop cleanup failed"));
   throw new IllegalStateException("Isolated desktop unavailable; no visible fallback",failure.get());
  }
  return new Desktop(name,allocated.get());
 }
 private static void closeDesktop(Pointer desktop){if(!Api.DESKTOP.CloseDesktop(desktop))throw new IllegalStateException("Owned desktop cleanup failed");}
 public static void verifyAvailable(){closeDesktop(createDesktop().handle());}
 public static Process start(ProcessBuilder builder)throws Exception{
  if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows"))throw new IllegalStateException("Isolated desktop requires Windows");
  var command=List.copyOf(builder.command());if(command.isEmpty()||!Path.of(command.get(0)).isAbsolute())throw new IllegalArgumentException("Absolute executable required");
  var env=new TreeMap<String,String>(String.CASE_INSENSITIVE_ORDER);env.putAll(builder.environment());
  var block=new StringBuilder();env.forEach((k,v)->{if(k.indexOf('=')>=0||k.indexOf('\0')>=0||v.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid process environment");block.append(k).append('=').append(v).append('\0');});block.append('\0');
  String line=command.stream().map(IsolatedDesktopProcess::quote).collect(java.util.stream.Collectors.joining(" "));
  byte[] bytes=block.toString().getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
  try(var memory=new Memory(Math.max(4,bytes.length))){
   memory.write(0,bytes,0,bytes.length);Arrays.fill(bytes,(byte)0);
   Desktop allocation=createDesktop();Pointer desktop=allocation.handle();String desktopName=allocation.name();
   if(desktop==null)throw new IllegalStateException("Isolated desktop unavailable ("+Native.getLastError()+"); no visible fallback");
   var startup=new WinBase.STARTUPINFO();startup.cb=new WinDef.DWORD(startup.size());startup.lpDesktop=desktopName;
   var info=new WinBase.PROCESS_INFORMATION();boolean created=false,transferred=false;
   try{
    // Keep the child alive until a stable ProcessHandle and its exit callback own all resources.
    created=Kernel32.INSTANCE.CreateProcess(command.get(0),line,null,null,false,new WinDef.DWORD(0x00000400|0x08000000|0x00000004),memory,builder.directory()==null?null:builder.directory().getAbsolutePath(),startup,info);
    if(!created)throw new IllegalStateException("Isolated child launch failed ("+Native.getLastError()+"); no visible fallback");
    var process=new IsolatedDesktopProcess(info,desktop);transferred=true;
    if(Api.THREAD.ResumeThread(info.hThread)==-1){process.destroyForcibly();throw new IllegalStateException("Isolated child could not resume");}
    return process;
   }finally{
    if(created)Kernel32.INSTANCE.CloseHandle(info.hThread);
    if(!transferred){if(created){Kernel32.INSTANCE.TerminateProcess(info.hProcess,1);Kernel32.INSTANCE.CloseHandle(info.hProcess);}closeDesktop(desktop);}
    memory.clear();
   }
  }finally{Arrays.fill(bytes,(byte)0);}

 }
 @Override public long pid(){return id;}@Override public ProcessHandle toHandle(){return child;}@Override public boolean isAlive(){return child.isAlive();}
 @Override public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}@Override public InputStream getInputStream(){return InputStream.nullInputStream();}@Override public InputStream getErrorStream(){return InputStream.nullInputStream();}
 @Override public int waitFor()throws InterruptedException{try{completion.get();return exitValue();}catch(ExecutionException error){throw new IllegalStateException("Owned child wait failed",error);}}
 @Override public boolean waitFor(long time,TimeUnit unit)throws InterruptedException{try{completion.get(time,unit);return true;}catch(TimeoutException elapsed){return false;}catch(ExecutionException error){throw new IllegalStateException("Owned child wait failed",error);}}
 @Override public int exitValue(){Integer code=exit;if(code==null){if(child.isAlive())throw new IllegalThreadStateException("Owned child is running");completion.join();code=exit;}return code;}
 @Override public void destroy(){child.destroy();}@Override public Process destroyForcibly(){child.destroyForcibly();return this;}@Override public CompletableFuture<Process> onExit(){return completion;}
}
