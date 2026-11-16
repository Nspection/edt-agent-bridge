// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.editor;
import java.nio.file.*;import org.junit.Test;import static org.junit.Assert.*;
public class NativeRasterPixelsTest {
 @Test public void paddedPremultipliedBgraUsesCorrectRowsChannelsAndAlpha()throws Exception {
  Path file=Files.createTempFile("native-raster-",".bin");
  try{Files.write(file,new byte[]{0,0,(byte)255,(byte)255,99,99,99,99,0,64,0,(byte)128,99,99,99,99});
   var image=NativeRasterPixels.read(file,1,2,8,16);assertEquals(0xff0000,image.getPixel(0,0));assertEquals(0x008000,image.getPixel(0,1));assertEquals(255,image.getAlpha(0,0));assertEquals(128,image.getAlpha(0,1));
  }finally{Files.delete(file);}
 }
 @Test public void refusesOversizeStrideOverflowAndTruncatedPixelFile()throws Exception {
  Path file=Files.createTempFile("native-raster-",".bin");
  try{Files.write(file,new byte[3]);for(int[] size:new int[][]{{1,1,4},{1,2,8},{0,2,8},{1,2,3},{10000,10000,Integer.MAX_VALUE}}){try{NativeRasterPixels.read(file,size[0],size[1],size[2],64L*1024*1024);fail("Unsafe raster accepted");}catch(IllegalStateException expected){}}}finally{Files.delete(file);}
 }
}
