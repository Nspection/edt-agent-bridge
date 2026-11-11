package com.codepilot1c.core.edt.editor;
import org.junit.*;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import com.codepilot1c.core.tools.editor.*;
import com.codepilot1c.core.tools.development.EdtRuntimeHostTool;
import com.google.gson.*;
public class SnapshotAlignmentTest {
 private Path root,image;
 @Before public void setup()throws Exception{root=Files.createTempDirectory("snapshot-alignment-");image=root.resolve("fixture.png");var src=new BufferedImage(240,160,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<160;y++)for(int x=0;x<240;x++)src.setRGB(x,y,0xff000000|(x<<16)|(y<<8)|100);ImageIO.write(src,"png",image.toFile());}
 @After public void cleanup()throws Exception{try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList()){for(int attempt=0;;attempt++)try{Files.deleteIfExists(p);break;}catch(java.nio.file.FileSystemException transientWindowsHandle){if(attempt>=20)throw transientWindowsHandle;Thread.sleep(50);}}}}
 private Map<String,Object> snapshot()throws Exception{return Map.of("image_path",image.toString(),"width",240,"height",160,"request_id","owned-request","sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image))));}
 private Map<String,Object> decorate(int step)throws Exception{return SnapshotAlignment.decorate(snapshot(),SnapshotAlignment.options(Map.of("grid",true,"grid_step",step)));}
 @Test public void disabledPreservesLegacyResponseAndCreatesNothing()throws Exception{var original=snapshot();assertSame(original,SnapshotAlignment.decorate(original,SnapshotAlignment.options(Map.of())));assertFalse(Files.exists(root.resolve("alignment")));}
 @Test public void annotatedCopyKeepsOriginalBytesIdentityAndPixelScale()throws Exception{
  byte[] before=Files.readAllBytes(image);var result=decorate(20);assertArrayEquals(before,Files.readAllBytes(image));assertEquals(image.toString(),result.get("image_path"));assertEquals(snapshot().get("sha256"),result.get("sha256"));assertEquals("owned-request",result.get("request_id"));
  var a=(Map<?,?>)result.get("alignment");assertEquals(32,a.get("origin_x"));assertEquals(32,a.get("origin_y"));assertEquals("original_image_pixels",a.get("coordinate_space"));var output=ImageIO.read(Path.of(a.get("image_path").toString()).toFile());assertEquals(272,output.getWidth());assertEquals(192,output.getHeight());var src=ImageIO.read(image.toFile());assertEquals(src.getRGB(11,13),output.getRGB(43,45));assertNotEquals(src.getRGB(20,13),output.getRGB(52,45));
 }
 @Test public void sameOptionsReuseCopyAndDifferentStepCreatesSeparateCopy()throws Exception{assertEquals(decorate(20),decorate(20));assertNotEquals(decorate(20).get("alignment"),decorate(40).get("alignment"));try(var files=Files.list(root.resolve("alignment"))){assertEquals(2,files.count());}}
 @Test public void changedDerivativeIsPreservedAndReported()throws Exception{var a=(Map<?,?>)decorate(20).get("alignment");Path copy=Path.of(a.get("image_path").toString());Files.writeString(copy,"edited");assertThrows(java.io.IOException.class,()->decorate(20));assertEquals("edited",Files.readString(copy));assertTrue(ImageIO.read(image.toFile())!=null);}
 @Test public void rejectsInvalidOptionsBeforeCapture(){for(Object v:List.of("true",1))assertThrows(IllegalArgumentException.class,()->SnapshotAlignment.options(Map.of("grid",v)));for(Object v:List.of(0,7,501,20.5,"20",Double.NaN))assertThrows(IllegalArgumentException.class,()->SnapshotAlignment.options(Map.of("grid_step",v)));assertEquals(20,SnapshotAlignment.options(Map.of("grid_step",new java.math.BigDecimal("20.0"))).step());}
 @Test public void badPngRejectedWithoutOutput()throws Exception{Files.writeString(image,"not-png");assertThrows(Exception.class,()->decorate(20));assertFalse(Files.exists(root.resolve("alignment")));}
 @Test public void changedOriginalHashCannotAcquireAnOverlay()throws Exception{var stale=snapshot();Files.writeString(image,"changed");assertThrows(java.io.IOException.class,()->SnapshotAlignment.decorate(stale,new SnapshotAlignment.Options(true,20)));assertFalse(Files.exists(root.resolve("alignment")));}
 @Test public void directOptionsCannotBypassBounds(){assertThrows(IllegalArgumentException.class,()->new SnapshotAlignment.Options(true,0));}
 @Test public void partialAnnotationRetainsCaptureEvidence()throws Exception{
  var original=snapshot();Files.writeString(root.resolve("alignment"),"occupied");var result=SnapshotAlignment.decorateOrReport(original,new SnapshotAlignment.Options(true,20));
  for(var entry:original.entrySet())assertEquals(entry.getValue(),result.get(entry.getKey()));assertEquals(false,result.get("alignment_applied"));assertTrue(result.containsKey("alignment_error"));assertFalse(result.containsKey("alignment"));assertTrue(Files.isRegularFile(image));
 }
 @Test public void parallelRequestsYieldCompleteIndependentImages()throws Exception{
  var pool=java.util.concurrent.Executors.newFixedThreadPool(4);try{var tasks=new ArrayList<java.util.concurrent.Future<Map<String,Object>>>();for(int i=0;i<4;i++){final int step=20+i;tasks.add(pool.submit(()->decorate(step)));}for(var task:tasks)assertNotNull(task.get(10,java.util.concurrent.TimeUnit.SECONDS).get("alignment"));}finally{pool.shutdownNow();}
  try(var files=Files.list(root.resolve("alignment"))){assertEquals(4,files.count());}
 }
 @Test public void refusesOccupiedAlignmentDirectory()throws Exception{Files.writeString(root.resolve("alignment"),"owned-by-other");assertThrows(java.io.IOException.class,()->decorate(20));assertEquals("owned-by-other",Files.readString(root.resolve("alignment")));}
 @Test public void alignmentQuotaIsBounded()throws Exception{Files.createDirectory(root.resolve("alignment"));for(int i=0;i<128;i++)Files.writeString(root.resolve("alignment/"+i+".png"),"x");assertThrows(java.io.IOException.class,()->decorate(20));}
 @Test public void screenshotContractsExposeIdenticalGridBounds(){
  for(var tool:List.of(new EdtEditorSnapshotTool(),new EdtEditorFormPreviewTool(),new EdtEditorScrollTool(),new EdtEditorViewTool(),new EdtRuntimeHostTool())){
   var props=JsonParser.parseString(tool.getParameterSchema()).getAsJsonObject().getAsJsonObject("properties");assertEquals(false,props.getAsJsonObject("grid").get("default").getAsBoolean());assertEquals(20,props.getAsJsonObject("grid_step").get("default").getAsInt());assertEquals(8,props.getAsJsonObject("grid_step").get("minimum").getAsInt());assertEquals(500,props.getAsJsonObject("grid_step").get("maximum").getAsInt());
  }
 }
}
