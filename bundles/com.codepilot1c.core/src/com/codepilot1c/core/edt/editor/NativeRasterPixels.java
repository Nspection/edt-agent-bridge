// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.editor;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.Path;
import org.eclipse.swt.graphics.*;
/** Decode bounded premultiplied BGRA rows; native stride includes padding beyond the visible width. */
final class NativeRasterPixels {
    static ImageData read(Path file,int width,int height,int stride,long capacity)throws Exception {
        if(width<1||height<1||width>10000||height>10000||stride<(long)width*4||stride%4!=0||(long)height*stride>capacity||(long)width*height>8_000_000)throw new IllegalStateException("Invalid native raster dimensions");
        var image=new ImageData(width,height,24,new PaletteData(0xff0000,0xff00,0xff));image.alphaData=new byte[width*height];
        byte[] row=new byte[width*4];
        try(var input=FileChannel.open(file,StandardOpenOption.READ)){
            if(input.size()<(long)(height-1)*stride+row.length)throw new IllegalStateException("Incomplete native raster");
            for(int y=0;y<height;y++){
                input.position((long)y*stride);ByteBuffer bytes=ByteBuffer.wrap(row);
                while(bytes.hasRemaining())if(input.read(bytes)<0)throw new IllegalStateException("Incomplete native raster");
                for(int x=0;x<width;x++){
                    int pos=x*4,a=row[pos+3]&255,b=straight(row[pos]&255,a),g=straight(row[pos+1]&255,a),r=straight(row[pos+2]&255,a);
                    image.setPixel(x,y,(r<<16)|(g<<8)|b);image.alphaData[y*width+x]=(byte)a;
                }
            }
        }
        return image;
    }
    private static int straight(int channel,int alpha){return alpha==0?0:alpha==255?channel:Math.min(255,(channel*255+(alpha>>1))/alpha);}
}
