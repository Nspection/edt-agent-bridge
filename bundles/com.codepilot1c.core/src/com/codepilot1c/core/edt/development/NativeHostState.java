// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import com.google.gson.*;
/** Ownership records are replaced atomically; an interrupted write leaves the last valid record. */
final class NativeHostState {
 @FunctionalInterface interface Replace {void move(Path from,Path to)throws Exception;}
 static JsonObject read(Path file)throws Exception{guard(file);if(Files.size(file)>8*1024*1024)throw new IllegalStateException("Host record exceeds limit");return JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject();}
 private static void guard(Path file){if(Files.isSymbolicLink(file)||Files.isSymbolicLink(file.getParent()))throw new IllegalStateException("Host record storage changed");}
 static void write(Path file,JsonObject value)throws Exception{write(file,value,(from,to)->Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING));}
 static void write(Path file,JsonObject value,Replace replace)throws Exception{
  guard(file);String text=value.toString();if(text.getBytes(StandardCharsets.UTF_8).length>8*1024*1024)throw new IllegalStateException("Host record exceeds limit");
  Path temporary=Files.createTempFile(file.getParent(),"host-state-",".tmp");
  try{Files.writeString(temporary,text,StandardCharsets.UTF_8);guard(file);replace.move(temporary,file);}finally{Files.deleteIfExists(temporary);}
 }
}
