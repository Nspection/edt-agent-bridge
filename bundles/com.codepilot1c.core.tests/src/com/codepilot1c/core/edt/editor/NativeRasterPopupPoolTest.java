// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.editor;
import java.util.*;import java.util.concurrent.atomic.AtomicInteger;import org.junit.Test;import static org.junit.Assert.*;
public class NativeRasterPopupPoolTest {
 @Test public void coldAndLastAllowedSdkChildRemainOwnedBySdk()throws Exception {
  for(int initial:new int[]{0,3}){var children=new ArrayList<Object>();for(int i=0;i<initial;i++)children.add(new Object());var child=new Object();NativeRasterPopupPool.grow(()->children,()->{children.add(child);return null;},candidate->candidate==child);assertEquals(initial+1,children.size());assertSame(child,children.get(initial));}
 }
 @Test public void fifthChildIsRejectedBeforeSdkCall()throws Exception {
  var called=new AtomicInteger();var children=new ArrayList<>(List.of(new Object(),new Object(),new Object(),new Object()));
  try{NativeRasterPopupPool.grow(()->children,()->{called.incrementAndGet();return null;},candidate->true);fail();}catch(FormProjectionGeneration.Unverified expected){assertEquals("native_capture_popup_limit",expected.reason);}assertEquals(0,called.get());assertEquals(4,children.size());
 }
 @Test public void unexpectedOrForeignSdkChildRejectsWithoutDeletingIt()throws Exception {
  for(boolean foreign:new boolean[]{true,false}){var children=new ArrayList<Object>();var child=new Object();try{NativeRasterPopupPool.grow(()->children,()->{children.add(child);if(!foreign)children.add(new Object());return null;},candidate->!foreign);fail();}catch(FormProjectionGeneration.Unverified expected){assertEquals("native_capture_popup_ownership_changed",expected.reason);}assertTrue(children.contains(child));}
 }
}
