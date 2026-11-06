package com.codepilot1c.core.edt.editor;

import java.util.*;
import java.util.concurrent.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

/** Workbench-window controls independent of any editor or project model. */
public final class EdtWindowService {
    private static final EdtWindowService INSTANCE=new EdtWindowService();
    public static EdtWindowService getInstance(){return INSTANCE;}

    static String action(Map<String,Object> args) {
        Object value=args.getOrDefault("action","state");
        if(!(value instanceof String text) || !Set.of("state","minimize","restore").contains(text)) {
            throw new IllegalArgumentException("action must be state, minimize or restore");
        }
        return (String)value;
    }

    public Map<String,Object> execute(Map<String,Object> args) throws Exception {
        String action=action(args);
        if(!PlatformUI.isWorkbenchRunning()){throw new IllegalStateException("EDT workbench is not running");}
        Display display=PlatformUI.getWorkbench().getDisplay();
        if(Display.getCurrent()!=null){throw new IllegalStateException("Call EDT tools from a worker thread");}
        try(EdtUiRequest<Map<String,Object>> request=EdtUiRequest.acquire(10,TimeUnit.SECONDS)){
        display.asyncExec(() -> request.dispatch(() -> {
            try {
                IWorkbenchWindow window=resolve(args);
                Shell shell=window.getShell();
                Map<String,Object> before=state(window);
                if(!action.equals("state")) {
                    for(Shell other:display.getShells()) {
                        if(other!=shell && other.isVisible() && (other.getStyle()&(SWT.APPLICATION_MODAL|SWT.PRIMARY_MODAL|SWT.SYSTEM_MODAL))!=0) {
                            throw new IllegalStateException("EDT has a modal dialog: "+other.getText());
                        }
                    }
                    if(action.equals("restore")){restore(shell);}
                    else if(!shell.getMinimized()) {
                        if("win32".equals(SWT.getPlatform())){showWithoutActivation(shell,7);} // SW_SHOWMINNOACTIVE
                        else{shell.setMinimized(true);}
                    }
                }
                display.timerExec(action.equals("state")?0:200,() -> request.dispatch(() -> {
                    try {
                        Map<String,Object> after=state(window);
                        if(action.equals("restore") && Boolean.TRUE.equals(after.get("minimized"))
                                || action.equals("minimize") && !Boolean.TRUE.equals(after.get("minimized"))) {
                            throw new IllegalStateException("EDT window did not reach requested state");
                        }
                        Map<String,Object> response=new LinkedHashMap<>();
                        response.put("action",action);response.put("before",before);response.put("window",after);
                        response.put("changed",!Objects.equals(before.get("minimized"),after.get("minimized")));
                        request.complete(response);
                    } catch(Throwable e){request.fail(e);}
                }));
            } catch(Throwable e){request.fail(e);}
        }));
        try{return request.await();}
        catch(ExecutionException e){if(e.getCause() instanceof Exception ex){throw ex;}throw new IllegalStateException(e.getCause());}
        }
    }

    static void restore(Shell shell) {
        if(!shell.getMinimized()){return;}
        if("win32".equals(SWT.getPlatform())){
            NativeLibrary user32=NativeLibrary.getInstance("user32");
            Pointer previous=user32.getFunction("GetForegroundWindow").invokePointer(new Object[0]);
            showWithoutActivation(shell,4); // SW_SHOWNOACTIVATE
            // EDT can activate itself from a restore listener. Return focus only if
            // it became foreground; do not interfere if the user selected another app.
            Runnable preserveFocus=() -> {
                if(shell.isDisposed()){return;}
                Pointer current=user32.getFunction("GetForegroundWindow").invokePointer(new Object[0]);
                if(shouldReturnFocus(Pointer.nativeValue(previous),Pointer.nativeValue(current),shell.handle)
                        && user32.getFunction("IsWindow").invokeInt(new Object[]{previous})!=0) {
                    user32.getFunction("SetForegroundWindow").invokeInt(new Object[]{previous});
                }
            };
            preserveFocus.run();
            shell.getDisplay().timerExec(100,preserveFocus);
        }
        else{shell.setMinimized(false);}
    }
    static boolean shouldReturnFocus(long previous,long current,long edt) {
        return previous!=0 && previous!=edt && current==edt;
    }
    private static void showWithoutActivation(Shell shell,int command) {
        NativeLibrary.getInstance("user32").getFunction("ShowWindow")
                .invokeInt(new Object[]{new Pointer(shell.handle),command});
    }
    private static String id(IWorkbenchWindow window){return "window@"+Integer.toHexString(System.identityHashCode(window));}
    private static IWorkbenchWindow resolve(Map<String,Object> args) {
        Object requested=args.get("window_id");
        if(requested!=null) {
            if(!(requested instanceof String text) || text.isBlank()){throw new IllegalArgumentException("window_id must be a nonempty string");}
            for(IWorkbenchWindow window:PlatformUI.getWorkbench().getWorkbenchWindows()) {
                if(requested.equals(id(window))){return window;}
            }
            throw new IllegalArgumentException("Unknown window_id");
        }
        IWorkbenchWindow active=PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if(active!=null){return active;}
        IWorkbenchWindow[] windows=PlatformUI.getWorkbench().getWorkbenchWindows();
        if(windows.length!=1){throw new IllegalStateException("No unique EDT window; specify window_id");}
        return windows[0];
    }
    private static Map<String,Object> state(IWorkbenchWindow window) {
        Shell shell=window.getShell();
        if(shell.isDisposed()){throw new IllegalStateException("EDT window is closed");}
        Map<String,Object> state=new LinkedHashMap<>();
        state.put("window_id",id(window));state.put("title",shell.getText());
        state.put("minimized",shell.getMinimized());state.put("maximized",shell.getMaximized());
        state.put("visible",shell.isVisible());
        if("win32".equals(SWT.getPlatform())) {
            Pointer foreground=NativeLibrary.getInstance("user32").getFunction("GetForegroundWindow").invokePointer(new Object[0]);
            state.put("foreground",Pointer.nativeValue(foreground)==shell.handle);
        }
        return state;
    }
}
