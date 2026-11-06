// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import com.google.gson.Gson;

/** Explicit local paths only; no shell parsing, root targets or filesystem aliases. */
public final class InfobasePaths {
    private InfobasePaths() {}
    public static Path absolute(String value) throws Exception {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("An absolute path is required");
        Path raw=Path.of(value);
        if (!raw.isAbsolute()) throw new IllegalArgumentException("Relative paths are not allowed");
        for (Path part:raw) if (part.toString().equals("..")) throw new IllegalArgumentException("Parent traversal is not allowed");
        Path path=raw.normalize();
        if (path.getParent()==null || path.getFileName()==null) throw new IllegalArgumentException("A filesystem root is not a target");
        Path existing=path;
        while (existing!=null && !Files.exists(existing,LinkOption.NOFOLLOW_LINKS)) existing=existing.getParent();
        if (existing==null || !existing.toRealPath().equals(existing.toAbsolutePath().normalize()))
            throw new IllegalArgumentException("Symbolic links, junctions and aliased paths are not allowed");
        return path;
    }
    public static Map<String,Object> fingerprint(Path path) throws Exception {
        if (!Files.exists(path)) return Map.of("path",path.toString(),"exists",false);
        BasicFileAttributes a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if (!a.isRegularFile()) throw new IllegalArgumentException("Expected a regular file: "+path);
        return Map.of("path",path.toString(),"exists",true,"size",a.size(),"modified",a.lastModifiedTime().toString(),"file_key",String.valueOf(a.fileKey()));
    }
    /** File identity is independent of Windows path spelling and connection formatting. */
    public static String connectionIdentity(String connection) {
        if(connection==null || connection.isBlank()) throw new IllegalArgumentException("Infobase connection is required");
        var file=java.util.regex.Pattern.compile("(?i)(?:^|;)\\s*File\\s*=\\s*\"([^\"]+)\"").matcher(connection);
        if(file.find()) {
            String path=Path.of(file.group(1)).toAbsolutePath().normalize().toString();
            if(java.io.File.separatorChar=='\\') path=path.toLowerCase(Locale.ROOT);
            return "file:"+path;
        }
        return connection.trim();
    }
    public static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch (Exception e) {throw new IllegalStateException(e);}
    }
    public static String fingerprintHash(Map<String,Object> value) { return hash(new Gson().toJson(new TreeMap<>(value))); }
}
