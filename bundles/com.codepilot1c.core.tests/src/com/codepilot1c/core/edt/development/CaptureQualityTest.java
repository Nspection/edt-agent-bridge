// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;
public class CaptureQualityTest {
 @Test public void oneIconDoesNotMakeAnOtherwiseEmptyFrameInformative(){byte[] pixels=new byte[400*300*4];Arrays.fill(pixels,(byte)255);for(int i=0;i<80;i++){pixels[i*4]=0;pixels[i*4+1]=0;}var result=CaptureQuality.assess(pixels,400,300);assertEquals(true,result.get("low_information"));assertEquals(true,result.get("heuristic"));}
 @Test public void uniformGrayAlsoNeedsVisualReview(){byte[] pixels=new byte[64*64*4];Arrays.fill(pixels,(byte)127);assertEquals(true,CaptureQuality.assess(pixels,64,64).get("low_information"));}
 @Test public void NonuniformFrameIsNotClassifiedAsEmpty(){byte[] pixels=new byte[64*64*4];for(int i=0;i<pixels.length/2;i++)pixels[i]=(byte)255;assertEquals(false,CaptureQuality.assess(pixels,64,64).get("low_information"));}
 @Test public void invalidBuffersNeverReachSampling(){assertThrows(IllegalArgumentException.class,()->CaptureQuality.assess(new byte[4],100,100));}
}
