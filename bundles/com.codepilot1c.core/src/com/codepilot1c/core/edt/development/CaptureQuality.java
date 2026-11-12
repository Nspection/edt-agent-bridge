// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.Map;
/** Image statistics are hints, never proof of application readiness or a meaningful screenshot. */
public final class CaptureQuality {
    private CaptureQuality() {}
    public static Map<String,Object> assess(byte[] bgra,int width,int height){
        long total=(long)width*height;if(width<=0||height<=0||total>24L*1024*1024||bgra.length!=total*4)throw new IllegalArgumentException("Invalid capture dimensions");
        int stride=(int)Math.max(1,(total+65535)/65536),samples=0,dominant=0;int[] colors=new int[4096];
        for(int pixel=0;pixel<total;pixel+=stride){int offset=pixel*4;int color=((bgra[offset+2]&255)>>4)<<8|((bgra[offset+1]&255)>>4)<<4|((bgra[offset]&255)>>4);dominant=Math.max(dominant,++colors[color]);samples++;}
        double fraction=(double)dominant/samples;boolean low=fraction>=0.98;
        return Map.of("low_information",low,"dominant_color_fraction",Math.round(fraction*1000000)/1000000.0,"assessment",low?"low_information":"nonuniform","heuristic",true);
    }
}
