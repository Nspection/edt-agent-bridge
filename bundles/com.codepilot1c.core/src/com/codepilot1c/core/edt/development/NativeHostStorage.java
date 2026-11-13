// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.nio.file.*;import java.util.*;
/** Internal mutable runtime outputs have fixed roots. Unknown siblings are never adopted by cleanup. */
final class NativeHostStorage {
 private static final Set<String> FILES=Set.of("host.json","native.zip","session.json","session.json.stage","client.log","process.log","requests.claims-v1","startup-question.json","startup-question.json.tmp","startup-visibility.json","startup-visibility.json.tmp");
 private static final Set<String> DIRECTORIES=Set.of("external-project","requests","verification","startup-answers","client-runtime");
 private static boolean known(Path path){String name=path.getFileName().toString();return Files.isDirectory(path)?DIRECTORIES.contains(name):FILES.contains(name)||name.matches("host-state-[0-9]{1,20}[.]tmp");}
 static void verifyDeletion(Path root,Path path){
  if(path.equals(root))return;Path relative=root.relativize(path);String first=relative.getName(0).toString();
  if(first.equals("external-project")||!known(root.resolve(first))||Files.isSymbolicLink(path))throw new IllegalStateException("Unowned resource during cleanup; preserve for review: "+relative);
 }
 static void verifyRoot(Path root,com.google.gson.JsonObject state)throws Exception{
  verifyRoot(root);Path archive=root.resolve("native.zip");
  if(Files.exists(archive)&&(!state.has("native_sha256")||!NativePackageService.sha(archive).equals(state.get("native_sha256").getAsString())))throw new IllegalStateException("Native archive changed; preserve for review");
 }
 static void verifyRoot(Path root)throws Exception{
  if(Files.isSymbolicLink(root))throw new IllegalStateException("Host root changed");
  try(var paths=Files.list(root)){for(Path path:paths.toList()){
   if(Files.isSymbolicLink(path)||!known(path))throw new IllegalStateException("Unknown host resource; preserve for review: "+path.getFileName());
  }}
  try(var paths=Files.walk(root)){if(paths.anyMatch(Files::isSymbolicLink))throw new IllegalStateException("Linked host resource; preserve for review");}
 }
}
