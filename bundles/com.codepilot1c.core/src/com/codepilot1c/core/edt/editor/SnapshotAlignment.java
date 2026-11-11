// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.editor;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;

/** Post-capture annotation only. Coordinates always refer to original image pixels. */
public final class SnapshotAlignment {
    private SnapshotAlignment() {}
    public static final int GUTTER=32;
    private static final java.util.concurrent.locks.ReentrantLock RENDER=new java.util.concurrent.locks.ReentrantLock(true);
    public record Options(boolean grid,int step) {
        public Options {if(step<8||step>500)throw new IllegalArgumentException("grid_step must be an integer from 8 to 500");}
    }
    public static Options options(Map<String,Object> args) {
        Object grid=args.getOrDefault("grid",false),step=args.getOrDefault("grid_step",20);
        if(!(grid instanceof Boolean))throw new IllegalArgumentException("grid must be boolean");
        if(!(step instanceof Number))throw new IllegalArgumentException("grid_step must be an integer from 8 to 500");
        int pixels;
        try{pixels=new java.math.BigDecimal(step.toString()).intValueExact();}catch(ArithmeticException|NumberFormatException e){throw new IllegalArgumentException("grid_step must be an integer from 8 to 500");}
        if(pixels<8||pixels>500)throw new IllegalArgumentException("grid_step must be an integer from 8 to 500");
        return new Options((Boolean)grid,pixels);
    }
    public static Map<String,Object> decorate(Map<String,Object> snapshot,Options options)throws Exception {
        if(!options.grid())return snapshot;
        // Bound aggregate raster memory before reading/decoding; never acquire the SWT gate here.
        try {
            if(!RENDER.tryLock(30,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("Snapshot alignment queue is busy; original retained");
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Snapshot alignment interrupted; original retained",e);}
        try{return decorateLocked(snapshot,options);}finally{RENDER.unlock();}
    }
    /** Annotation failure is a partial capture, never a reason to repeat the UI operation. */
    public static Map<String,Object> decorateOrReport(Map<String,Object> snapshot,Options options) {
        try{return decorate(snapshot,options);}
        catch(Exception error){
            var result=new LinkedHashMap<String,Object>(snapshot);
            result.put("alignment_error",Objects.toString(error.getMessage(),error.getClass().getSimpleName()));
            result.put("alignment_applied",false);return result;
        }
    }
    private static Map<String,Object> decorateLocked(Map<String,Object> snapshot,Options options)throws Exception {
        Path original=Path.of(Objects.toString(snapshot.get("image_path"),"")).toAbsolutePath().normalize();
        if(!Files.isRegularFile(original,LinkOption.NOFOLLOW_LINKS)||Files.size(original)>32L*1024*1024)throw new IOException("Invalid original snapshot");
        for(Path ancestor=original;ancestor!=null;ancestor=ancestor.getParent())if(Files.isSymbolicLink(ancestor))throw new IOException("Snapshot path changed");
        byte[] bytes=Files.readAllBytes(original);
        if(snapshot.containsKey("sha256")&&!hash(bytes).equals(snapshot.get("sha256")))throw new IOException("Original snapshot hash changed");
        BufferedImage source;
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("PNG snapshot required");
            var reader=readers.next();
            try{reader.setInput(input);int w=reader.getWidth(0),h=reader.getHeight(0);
                if(!reader.getFormatName().equalsIgnoreCase("png")||w<1||h<1||w>10000||h>10000||(long)(w+GUTTER)*(h+GUTTER)>26L*1024*1024)throw new IOException("Snapshot dimensions exceed alignment limit");
                source=reader.read(0);
            }finally{reader.dispose();}
        }
        BufferedImage rendered=render(source,options.step());ByteArrayOutputStream output=new ByteArrayOutputStream();
        if(!ImageIO.write(rendered,"png",output))throw new IOException("PNG encoder unavailable");
        byte[] annotated=output.toByteArray();
        if(!Arrays.equals(bytes,Files.readAllBytes(original)))throw new IOException("Original snapshot changed while annotating");
        Path path=save(original,options.step(),annotated);
        Map<String,Object> result=new LinkedHashMap<>(snapshot);
        result.put("original_image_path",original.toString());result.put("original_sha256",hash(bytes));
        result.put("alignment",Map.of("image_path",path.toString(),"sha256",hash(annotated),"mime_type","image/png","width",rendered.getWidth(),"height",rendered.getHeight(),"grid_step",options.step(),"origin_x",GUTTER,"origin_y",GUTTER,"coordinate_space","original_image_pixels","rulers",true));
        return result;
    }
    static BufferedImage render(BufferedImage source,int step) {
        int w=source.getWidth(),h=source.getHeight();var image=new BufferedImage(w+GUTTER,h+GUTTER,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();
        try {
            g.setColor(new Color(242,245,248));g.fillRect(0,0,image.getWidth(),image.getHeight());
            g.setComposite(AlphaComposite.Src);g.drawImage(source,GUTTER,GUTTER,null);g.setComposite(AlphaComposite.SrcOver);
            g.setFont(new Font(Font.MONOSPACED,Font.PLAIN,10));
            int labelEvery=Math.max(1,(80+step-1)/step);
            for(int x=0,index=0;x<w;x+=step,index++) {
                int px=GUTTER+x;g.setColor(new Color(0,100,210,index%labelEvery==0?125:65));g.drawLine(px,GUTTER,px,GUTTER+h-1);
                g.setColor(new Color(35,45,55));g.drawLine(px,GUTTER-1,px,GUTTER-(index%labelEvery==0?9:4));
                if(index%labelEvery==0&&px+g.getFontMetrics().stringWidth(Integer.toString(x))<image.getWidth())g.drawString(Integer.toString(x),px+2,14);
            }
            for(int y=0,index=0;y<h;y+=step,index++) {
                int py=GUTTER+y;g.setColor(new Color(0,100,210,index%labelEvery==0?125:65));g.drawLine(GUTTER,py,GUTTER+w-1,py);
                g.setColor(new Color(35,45,55));g.drawLine(GUTTER-1,py,GUTTER-(index%labelEvery==0?9:4),py);
                if(index%labelEvery==0&&py+10<image.getHeight()){Graphics2D label=(Graphics2D)g.create();try{label.rotate(-Math.PI/2,13,py+2);label.drawString(Integer.toString(y),13,py+2);}finally{label.dispose();}}
            }
            g.setColor(new Color(35,45,55));g.drawString("px",6,14);g.drawLine(GUTTER-1,GUTTER-1,image.getWidth()-1,GUTTER-1);g.drawLine(GUTTER-1,GUTTER-1,GUTTER-1,image.getHeight()-1);
        }finally{g.dispose();}
        return image;
    }
    private static synchronized Path save(Path original,int step,byte[] png)throws IOException {
        Path folder=original.getParent().resolve("alignment");
        if(Files.exists(folder,LinkOption.NOFOLLOW_LINKS)&&(!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(folder)))throw new IOException("Alignment directory changed");
        Files.createDirectories(folder);String stem=original.getFileName().toString().replaceFirst("(?i)\\.png$","");Path target=folder.resolve(stem+"-grid-"+step+".png");
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
            if(Files.isSymbolicLink(target)||!Files.isRegularFile(target)||Files.size(target)!=png.length||!Arrays.equals(Files.readAllBytes(target),png))throw new IOException("Existing alignment artifact changed");
            return target;
        }
        long total=0;int count=0;
        try(var files=Files.list(folder)){for(Path file:files.toList()){if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Alignment directory changed");total+=Files.size(file);count++;}}
        if(count>=128||total+png.length>256L*1024*1024)throw new IOException("Alignment artifact quota reached");
        Path temp=Files.createTempFile(folder,"alignment-",".tmp");
        try {
            Files.write(temp,png,StandardOpenOption.TRUNCATE_EXISTING);
            // Same-directory rename, no REPLACE_EXISTING: edited targets are preserved.
            Files.move(temp,target);return target;
        }finally{Files.deleteIfExists(temp);}
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
