// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import org.junit.*;import static org.junit.Assert.*;import java.nio.file.*;import java.net.*;import java.util.*;import java.awt.image.BufferedImage;import java.io.ByteArrayOutputStream;import javax.imageio.ImageIO;import java.nio.charset.StandardCharsets;
public class NativeHostSnapshotTest {
 private NativeHostChannel channel;private Path root;
 @Before public void create()throws Exception{root=Files.createTempDirectory("host-snapshot-").resolve("requests");channel=new NativeHostChannel(root);}
 @After public void cleanup()throws Exception{channel.close();try(var files=Files.walk(root.getParent())){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
 private byte[] png()throws Exception{var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(3,2,BufferedImage.TYPE_INT_ARGB),"png",bytes);return bytes.toByteArray();}
 private String post(String route,byte[] bytes,boolean auth,int expected)throws Exception{var c=(HttpURLConnection)new URL("http://127.0.0.1:"+channel.port()+route).openConnection();c.setRequestMethod("POST");c.setDoOutput(true);c.setReadTimeout(3000);if(auth)c.setRequestProperty("Authorization","Bearer "+channel.token());try(var out=c.getOutputStream()){out.write(bytes);}assertEquals(expected,c.getResponseCode());try(var in=expected==200?c.getInputStream():c.getErrorStream()){return in==null?"":new String(in.readAllBytes(),StandardCharsets.UTF_8);}finally{c.disconnect();}}
 private void delivered()throws Exception{post("/hello","{\"configuration\":\"Fixture\",\"user\":\"\",\"platform\":\"8.5.1\"}".getBytes(StandardCharsets.UTF_8),true,200);channel.submit("batch","batch","{}",Map.of());post("/poll","{}".getBytes(StandardCharsets.UTF_8),true,200);}
 @Test public void captureRequiresAuthenticationAndExecutingRequest()throws Exception{post("/snapshot",png(),false,403);post("/snapshot",png(),true,400);assertTrue(NativeHostChannel.snapshots(root).isEmpty());}
 @Test public void capturedPngHasRequestIdentityAndTamperingIsDetected()throws Exception{delivered();post("/snapshot",png(),true,200);var items=NativeHostChannel.snapshots(root);assertEquals(1,items.size());assertEquals(3,((Number)items.get(0).get("width")).intValue());assertTrue(items.get(0).containsKey("request_id"));Files.writeString(Path.of(items.get(0).get("image_path").toString()),"tampered");try{NativeHostChannel.snapshots(root);fail();}catch(IllegalStateException expected){}}
 @Test public void invalidImageAndPerHostQuotaAreRejected()throws Exception{delivered();post("/snapshot",new byte[]{1,2,3},true,400);for(int i=0;i<64;i++)post("/snapshot",png(),true,200);post("/snapshot",png(),true,400);assertEquals(64,NativeHostChannel.snapshots(root).size());}
 @Test public void gridObservationPreservesNativeManifestAndQuota()throws Exception {
  delivered();post("/snapshot",png(),true,200);var original=NativeHostChannel.snapshots(root).get(0);byte[] bytes=Files.readAllBytes(Path.of(original.get("image_path").toString()));
  var annotated=com.codepilot1c.core.edt.editor.SnapshotAlignment.decorate(original,com.codepilot1c.core.edt.editor.SnapshotAlignment.options(Map.of("grid",true)));
  assertEquals(original.get("request_id"),annotated.get("request_id"));assertEquals(original.get("sha256"),annotated.get("sha256"));assertArrayEquals(bytes,Files.readAllBytes(Path.of(original.get("image_path").toString())));assertNotNull(annotated.get("alignment"));
  for(int i=0;i<63;i++)post("/snapshot",png(),true,200);assertEquals(64,NativeHostChannel.snapshots(root).size());post("/snapshot",png(),true,400);
 }
}
