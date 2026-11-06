package com.codepilot1c.core.edt.development;
import org.junit.Test;
import static org.junit.Assert.*;
public class ProcessWindowCaptureTest {
    @Test public void refusesNonOneCProcesses(){assertThrows(IllegalArgumentException.class,()->ProcessWindowCapture.requireProcess(ProcessHandle.current().pid()));}
    @Test public void refusesNonexistentProcesses(){assertThrows(IllegalArgumentException.class,()->ProcessWindowCapture.requireProcess(Long.MAX_VALUE));}
    @Test public void boundsNativeBitmapAllocation(){ProcessWindowCapture.dimensions(1920,1080);assertThrows(IllegalArgumentException.class,()->ProcessWindowCapture.dimensions(0,1080));assertThrows(IllegalArgumentException.class,()->ProcessWindowCapture.dimensions(6000,6000));assertThrows(IllegalArgumentException.class,()->ProcessWindowCapture.dimensions(6001,1));}
}
