package com.codepilot1c.core.edt.development;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;
public class ProcessCaptureScopeTest {
 private ProcessCaptureScope.Window window(long id,long pid,long thread,long owner,int size,boolean visible,boolean minimized,long style){return new ProcessCaptureScope.Window(id,pid,thread,owner,"Fixture",-30,20,size,size,visible,minimized,style,0);}
 @Test public void includesSmallOwnerlessPopupButNeverAnotherProcessOrThread(){
  var root=window(1,20,30,0,100,true,false,0);var popup=window(2,20,30,0,1,true,false,0x80000000L);var foreign=window(3,21,30,1,20,true,false,0x80000000L);var anotherThread=window(4,20,31,1,20,true,false,0x80000000L);var ordinary=window(5,20,30,0,20,true,false,0);
  assertEquals(List.of(root,popup),ProcessCaptureScope.select(root,List.of(root,popup,foreign,anotherThread,ordinary),true));assertEquals(List.of(root),ProcessCaptureScope.select(root,List.of(popup),false));
 }
 @Test public void hiddenAndMinimizedPopupsAreNotClaimedAsCaptured(){var root=window(1,20,30,0,100,true,false,0);assertEquals(List.of(root),ProcessCaptureScope.select(root,List.of(window(2,20,30,1,20,false,false,0x80000000L),window(3,20,30,1,20,true,true,0x80000000L)),true));}
 @Test public void rejectsTotalAreaAndWindowCountBeforeAnyRenderer(){var root=window(1,20,30,0,3000,true,false,0);assertThrows(IllegalArgumentException.class,()->ProcessCaptureScope.select(root,List.of(window(2,20,30,1,3000,true,false,0x80000000L)),true));var windows=new ArrayList<ProcessCaptureScope.Window>();for(int i=2;i<18;i++)windows.add(window(i,20,30,0,1,true,false,0x80000000L));assertThrows(IllegalArgumentException.class,()->ProcessCaptureScope.select(window(1,20,30,0,100,true,false,0),windows,true));}
 @Test public void identityIncludesOwnerClassGeometryVisibilityAndStyle(){var w=window(1,20,30,0,100,true,false,0);assertNotEquals(w,window(1,20,30,2,100,true,false,0));assertNotEquals(w,window(1,20,30,0,101,true,false,0));assertNotEquals(w,window(1,20,30,0,100,false,false,0));assertNotEquals(w,window(1,20,31,0,100,true,false,0));}
}
