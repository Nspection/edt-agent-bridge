// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import org.eclipse.core.resources.ResourcesPlugin;

/** Content receipt of the EDT source, independent of Git dirtiness. Never reads databases or credentials. */
public final class SourceReceipt {
    private SourceReceipt() {}
    public static Map<String,Object> workspace(String project) throws Exception {
        var p=ResourcesPlugin.getWorkspace().getRoot().getProject(project);
        if(!p.isOpen() || p.getLocation()==null)throw new IllegalArgumentException("Open local project required");
        boolean[] dirty={false};
        org.eclipse.swt.widgets.Display display=org.eclipse.ui.PlatformUI.getWorkbench().getDisplay();
        display.syncExec(()->{
            for(var window:org.eclipse.ui.PlatformUI.getWorkbench().getWorkbenchWindows())
                for(var page:window.getPages())for(var ref:page.getEditorReferences()) {
                    var part=ref.getEditor(false);
                    if(part!=null && part.isDirty() && part.getEditorInput() instanceof org.eclipse.ui.IFileEditorInput input
                        && input.getFile().getProject().equals(p))dirty[0]=true;
                }
        });
        if(dirty[0])return Map.of("complete",false,"reason","unsaved_editor");
        return capture(p.getLocation().toFile().toPath());
    }
    public static Map<String,Object> capture(Path location) throws Exception {
        Path root=location.toRealPath();List<Path> files=new ArrayList<>();boolean[] linked={false};
        for(String name:List.of("src","DT-INF",".settings",".project")) {
            Path item=root.resolve(name);if(!Files.exists(item,LinkOption.NOFOLLOW_LINKS))continue;
            Files.walkFileTree(item,new SimpleFileVisitor<Path>() {
                public FileVisitResult preVisitDirectory(Path p,BasicFileAttributes a) throws java.io.IOException {
                    if(Files.isSymbolicLink(p)){linked[0]=true;return FileVisitResult.SKIP_SUBTREE;}return FileVisitResult.CONTINUE;
                }
                public FileVisitResult visitFile(Path p,BasicFileAttributes a) throws java.io.IOException {
                    if(a.isSymbolicLink() || !a.isRegularFile())linked[0]=true;
                    else files.add(p);
                    if(files.size()>20000)throw new java.io.IOException("Source receipt file limit exceeded");
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        if(files.isEmpty() || linked[0])return Map.of("complete",false,"reason",linked[0]?"linked_source":"no_sources");
        files.sort(Comparator.comparing(p->root.relativize(p).toString()));
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long budget=256L*1024*1024,total=0;
        for(Path p:files) {
            if(!p.toRealPath().startsWith(root))return Map.of("complete",false,"reason","linked_source");
            BasicFileAttributes before=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!before.isRegularFile() || before.size()>16L*1024*1024 || before.size()>budget)
                return Map.of("complete",false,"reason","source_size_or_type_limit");
            digest.update(root.relativize(p).toString().replace('\\','/').getBytes(StandardCharsets.UTF_8));digest.update((byte)0);
            long read=0;try(var in=Files.newInputStream(p,LinkOption.NOFOLLOW_LINKS)) {
                byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1){read+=n;if(read>before.size())return Map.of("complete",false,"reason","source_changed_during_capture");digest.update(buf,0,n);}
            }
            BasicFileAttributes after=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(read!=before.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()) || !Objects.equals(before.fileKey(),after.fileKey()))
                return Map.of("complete",false,"reason","source_changed_during_capture");
            budget-=read;total+=read;digest.update((byte)0);
        }
        return Map.of("complete",true,"sha256",HexFormat.of().formatHex(digest.digest()),"files",files.size(),"bytes",total,
            "scope","src,DT-INF,.settings,.project","project_location",root.toString(),"atomic",false);
    }
    public static String compare(Map<?,?> before,Map<?,?> launched,Map<?,?> finished,Map<?,?> current) {
        for(Map<?,?> receipt:List.of(before,launched,finished,current))if(!Boolean.TRUE.equals(receipt.get("complete")))return "unknown_incomplete_receipt";
        for(Map<?,?> receipt:List.of(launched,finished,current))
            if(!Objects.equals(before.get("sha256"),receipt.get("sha256")) || !Objects.equals(before.get("project_location"),receipt.get("project_location")))return "changed";
        return "matched_at_checkpoints";
    }
}
