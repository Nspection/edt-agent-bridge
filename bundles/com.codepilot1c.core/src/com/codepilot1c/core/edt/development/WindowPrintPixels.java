// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
/** Reconstruct a separate PrintWindow surface from black/white mattes, without reading screen pixels. */
final class WindowPrintPixels {
    static boolean reconstruct(int[] black,int[] white) {return reconstruct(black,white,false);}
    static boolean reconstruct(int[] black,int[] white,boolean perPixelAlpha) {
        if(black.length!=white.length || black.length==0)throw new IllegalArgumentException("Invalid print buffers");
        boolean suppliedAlpha=false,allZero=true;for(int i=0;i<black.length;i++){suppliedAlpha|=(black[i]>>>24)!=0 || (white[i]>>>24)!=0;allZero&=black[i]==0&&white[i]==0;}
        if(allZero)return false;
        boolean informative=false;
        for(int i=0;i<black.length;i++) {
            int b=black[i],w=white[i],alpha=b>>>24;
            if(perPixelAlpha && b==w && alpha>0){for(int shift=0;shift<24;shift+=8)if(((b>>>shift)&255)>alpha)throw new IllegalStateException("Unsupported non-premultiplied print alpha");informative=true;continue;}
            if(perPixelAlpha && b==0&&w==0&&suppliedAlpha){black[i]=0;continue;}
            int low=256,high=-1,sum=0;
            for(int shift=0;shift<24;shift+=8){int delta=((w>>>shift)&255)-((b>>>shift)&255);low=Math.min(low,delta);high=Math.max(high,delta);sum+=delta;}
            if(low<0 || high>255 || high-low>1)throw new IllegalStateException("Window print changed between matte passes");
            alpha=255-(sum+1)/3;int pixel=alpha<<24;
            for(int shift=0;shift<24;shift+=8){int color=(b>>>shift)&255;if(color>alpha+1)throw new IllegalStateException("Unsupported print blending");pixel|=Math.min(color,alpha)<<shift;}
            informative|=alpha!=0;black[i]=pixel;
        }
        return informative;
    }
    static boolean informative(int[] pixels){
        if(pixels.length==0)return false;
        int first=straight(pixels[0]);boolean visible=(first>>>24)!=0,different=false;
        for(int pixel:pixels){int normalized=straight(pixel);visible|=(normalized>>>24)!=0;different|=normalized!=first;}
        return visible&&different;
    }
    static int straight(int pixel){int alpha=pixel>>>24;if(alpha==0)return 0;int out=alpha<<24;for(int shift=0;shift<24;shift+=8)out|=Math.min(255,((pixel>>>shift)&255)*255/alpha)<<shift;return out;}
    static void layered(int[] pixels,int colorKey,int constantAlpha,int flags){
        int key=((colorKey&255)<<16)|(colorKey&0xff00)|((colorKey>>>16)&255);
        for(int i=0;i<pixels.length;i++){int pixel=pixels[i];if((flags&1)!=0 && (straight(pixel)&0xffffff)==key){pixels[i]=0;continue;}if((flags&2)!=0){int adjusted=0;for(int shift=0;shift<32;shift+=8)adjusted|=((pixel>>>shift)&255)*constantAlpha/255<<shift;pixels[i]=adjusted;}}
    }
}
