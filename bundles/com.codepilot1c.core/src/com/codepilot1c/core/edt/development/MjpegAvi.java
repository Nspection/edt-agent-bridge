// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** One silent MJPEG stream in a RIFF AVI container. All sizes are little-endian. */
final class MjpegAvi implements AutoCloseable {
    private record Index(int offset, int bytes) {}
    private final RandomAccessFile file;
    private final List<Index> frames = new ArrayList<>();
    private final long mainCount, streamCount, moviSize, moviStart;
    private boolean closed;

    MjpegAvi(Path path, int width, int height, int fps) throws IOException {
        file = new RandomAccessFile(path.toFile(), "rw");
        try {
            four("RIFF"); n(0); four("AVI ");
            four("LIST"); long header = file.getFilePointer(); n(0); four("hdrl");
            four("avih"); n(56);
            n(1_000_000 / fps); n(0); n(0); n(16);
            mainCount=file.getFilePointer(); n(0); n(0); n(1); n(0); n(width); n(height);
            for(int i=0;i<4;i++)n(0);
            four("LIST"); long stream=file.getFilePointer(); n(0); four("strl");
            four("strh"); n(56); four("vids"); four("MJPG");
            n(0); n(0); n(0); n(1); n(fps); n(0);
            streamCount=file.getFilePointer(); n(0); n(0); n(-1); n(0);
            n(0); file.writeShort(Short.reverseBytes((short)width)); file.writeShort(Short.reverseBytes((short)height));
            four("strf"); n(40); n(40); n(width); n(height);
            file.writeShort(Short.reverseBytes((short)1)); file.writeShort(Short.reverseBytes((short)24));
            four("MJPG"); n(width*height*3); n(0); n(0); n(0); n(0);
            long end=file.getFilePointer();
            patch(stream,end-stream-4); patch(header,end-header-4);
            four("LIST"); moviSize=file.getFilePointer(); n(0);
            moviStart=file.getFilePointer(); four("movi");
        } catch (IOException | RuntimeException failure) {
            file.close();
            throw failure;
        }
    }

    void frame(byte[] jpeg) throws IOException {
        frames.add(new Index(Math.toIntExact(file.getFilePointer()-moviStart),jpeg.length));
        four("00dc"); n(jpeg.length); file.write(jpeg);
        if((jpeg.length&1)!=0)file.writeByte(0);
    }

    long bytes() throws IOException { return file.getFilePointer(); }
    int frames() { return frames.size(); }

    @Override public void close() throws IOException {
        if(closed)return;
        closed=true;
        try {
            patch(moviSize,file.getFilePointer()-moviStart);
            four("idx1"); n(frames.size()*16);
            for(var frame:frames){four("00dc");n(16);n(frame.offset());n(frame.bytes());}
            patch(4,file.getFilePointer()-8);
            patch(mainCount,frames.size());patch(streamCount,frames.size());
        } finally {
            file.close();
        }
    }

    private void four(String value) throws IOException { file.writeBytes(value); }
    private void n(int value) throws IOException { file.writeInt(Integer.reverseBytes(value)); }
    private void patch(long at,long value) throws IOException {
        long position=file.getFilePointer();
        file.seek(at);n(Math.toIntExact(value));file.seek(position);
    }
}
