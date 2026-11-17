// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
public class NativeUiCaptureTest {
 private Map<String,Object> target(){return new LinkedHashMap<>(Map.of("process_id",12L,"process_started_at","2026-11-17T00:00:00Z","window_id","0x22","window_class","V8Window20000.0","owner_window_id","0x11","ui_thread_id",33,"left",10,"top",10,"width",200,"height",100));}
 private Map<String,Object> client(){return Map.of("pid",12L,"process_started_at","2026-11-17T00:00:00Z");}
 private Map<String,Object> state(String hash,boolean proven){return new LinkedHashMap<>(Map.of("success",true,"postcondition_verified",true,"window",Map.of("id","source","title","Own","form_name","Catalog.Own.Form.List"),"snapshot_id","own-snapshot","native_ui",new LinkedHashMap<>(Map.of("capture_target",target(),"reference_sha256",hash.repeat(64),"header_menu_proven",proven))));}
 @Test public void capturesExactMenuBetweenMatchingSemanticObservations(){var action=state("a",true);var count=new AtomicInteger();var proof=NativeUiCapture.observe(client(),action,()->state("a",true),(p,s,w)->{count.incrementAndGet();assertEquals(12,p);assertEquals("0x22",w);var image=target();image.put("success",true);return image;});assertEquals(true,proof.get("success"));assertEquals(1,count.get());}
 @Test public void refusesOtherProcessBeforeCapturing(){var wrong=new LinkedHashMap<>(client());wrong.put("pid",99);var count=new AtomicInteger();var proof=NativeUiCapture.observe(wrong,state("a",true),()->state("a",true),(p,s,w)->{count.incrementAndGet();return target();});assertEquals(false,proof.get("success"));assertEquals(0,count.get());}
 @Test public void disappearanceOrReplacementInvalidatesCaptureButNeverReplaysAction(){for(var after:List.of(state("a",false),state("b",true))){var action=state("a",true);var count=new AtomicInteger();var proof=NativeUiCapture.observe(client(),action,()->after,(p,s,w)->{count.incrementAndGet();var image=target();image.put("success",true);return image;});assertEquals(false,proof.get("success"));assertEquals(1,count.get());assertEquals(true,action.get("success"));assertEquals(true,action.get("postcondition_verified"));}}
 @Test public void geometryDriftOrRenderFailureCannotVerifyImage(){for(boolean drift:List.of(true,false)){var action=state("a",true);var proof=NativeUiCapture.observe(client(),action,()->state("a",true),(p,s,w)->{var image=target();image.put("success",false);if(drift)image.put("left",999);return image;});assertEquals(false,proof.get("success"));assertEquals(true,action.get("success"));}}
 @Test public void failedCaptureDoesNotThrowAwayProvenAction(){var action=state("a",true);var proof=NativeUiCapture.observe(client(),action,()->state("a",true),(p,s,w)->{throw new java.io.IOException("Owned renderer failed");});assertEquals(false,proof.get("success"));assertEquals(true,action.get("success"));}
}
