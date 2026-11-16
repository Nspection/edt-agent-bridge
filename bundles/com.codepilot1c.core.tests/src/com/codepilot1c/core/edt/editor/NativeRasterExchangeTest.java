// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.editor;
import org.junit.Test;import static org.junit.Assert.*;import java.util.concurrent.atomic.AtomicInteger;
public class NativeRasterExchangeTest {
 @Test public void swallowedNativeOrDecodeFailureStillRejectsCaptureAndRestoresOnce()throws Exception {
  for(boolean paintFails:new boolean[]{true,false}){
   var restored=new AtomicInteger();var exchange=new NativeRasterExchange<String>();
   try{exchange.paint(()->{if(paintFails)throw new IllegalStateException("paint");return "payload";},response->{throw new IllegalStateException("decode");},()->restored.incrementAndGet());}catch(Exception swallowedBySdk){}
   assertEquals(1,restored.get());try{exchange.require();fail("SDK swallowed failure must remain terminal");}catch(IllegalStateException expected){assertEquals(paintFails?"paint":"decode",expected.getMessage());}
  }
 }
 @Test public void restoreFailureDiscardsDecodedImageEvenIfSdkSwallowsIt()throws Exception {
  var exchange=new NativeRasterExchange<String>();
  try{exchange.paint(()->"payload",response->"image",()->{throw new IllegalStateException("restore");});}catch(Exception swallowedBySdk){}
  try{exchange.require();fail("Unrestored HWND accepted");}catch(FormProjectionGeneration.Unverified expected){assertEquals("native_view_restore_unverified",expected.reason);}
 }
 @Test public void sdkCanRepeatSuccessfulReadPassesButCannotReplayAfterUnknown()throws Exception {
  var exchange=new NativeRasterExchange<String>();var restored=new AtomicInteger();
  exchange.paint(()->"first",response->response,()->restored.incrementAndGet());exchange.paint(()->"second",response->response,()->restored.incrementAndGet());
  assertEquals("second",exchange.require());assertEquals(2,restored.get());
  var failed=new NativeRasterExchange<String>();
  try{failed.paint(()->{throw new IllegalStateException("unknown first paint");},response->response,()->null);}catch(Exception swallowedBySdk){}
  try{failed.paint(()->{fail("No second native dispatch after failure");return "second";},response->response,()->null);fail();}catch(IllegalStateException expected){assertEquals("unknown first paint",expected.getMessage());}
 }
 @Test public void nativePaintRetryBudgetIsBoundedEvenWhenSdkSwallowsLimit()throws Exception {
  var exchange=new NativeRasterExchange<String>();var dispatched=new AtomicInteger();for(int i=0;i<4;i++)exchange.paint(()->{dispatched.incrementAndGet();return "image";},response->response,()->null);
  try{exchange.paint(()->{dispatched.incrementAndGet();return "image";},response->response,()->null);fail();}catch(Exception swallowedBySdk){}
  assertEquals(4,dispatched.get());try{exchange.require();fail();}catch(FormProjectionGeneration.Unverified expected){assertEquals("native_capture_render_retry_limit",expected.reason);}
 }
 @Test public void swallowedNonRenderCallbackFailureDiscardsAlreadyDecodedImage()throws Exception {
  var exchange=new NativeRasterExchange<String>();exchange.paint(()->"payload",response->"image",()->null);
  exchange.reject(new IllegalStateException("popup callback rejected"));
  try{exchange.require();fail("SDK callback rejection swallowed after paint");}catch(IllegalStateException expected){assertEquals("popup callback rejected",expected.getMessage());}
 }

}
