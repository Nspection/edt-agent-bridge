// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
/** Per-file ownership survives a partially successful native refactoring. */
public final class OwnedHostSources {
    private OwnedHostSources(){}
    public static Map<String,String> snapshot(Path root)throws Exception{
        if(!Files.exists(root))return Map.of();
        if(Files.isSymbolicLink(root)||!Files.isDirectory(root))throw new IllegalStateException("Owned source root changed");
        var out=new TreeMap<String,String>();
        try(var paths=Files.walk(root)){
            for(Path file:paths.toList()){
                if(Files.isSymbolicLink(file))throw new IllegalStateException("Symbolic link in owned source tree");
                if(Files.isRegularFile(file)){
                    if(Files.size(file)>2*1024*1024)throw new IllegalStateException("Unexpected large file in owned sources");
                    out.put(root.relativize(file).toString().replace('\\','/'),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
                }
            }
        }
        return out;
    }
    public static void deleteRemaining(Path root,Map<String,String> expected)throws Exception{
        verifyRemaining(root,expected);if(!Files.exists(root))return;
        try(var paths=Files.walk(root)){
            for(Path path:paths.sorted(Comparator.reverseOrder()).toList()){
                if(Files.isSymbolicLink(path))throw new IllegalStateException("Symbolic link during owned cleanup");
                if(Files.isRegularFile(path)){
                    String key=root.relativize(path).toString().replace('\\','/');
                    String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
                    if(!Objects.equals(expected.get(key),actual))throw new IllegalStateException("Owned source changed during cleanup: "+key);
                }
                Files.deleteIfExists(path);
            }
        }
    }
    public static void verifyRemaining(Path root,Map<String,String> expected)throws Exception{
        for(var item:snapshot(root).entrySet())if(!Objects.equals(expected.get(item.getKey()),item.getValue()))throw new IllegalStateException("Owned source changed or was added; preserve for review: "+item.getKey());
    }
}
