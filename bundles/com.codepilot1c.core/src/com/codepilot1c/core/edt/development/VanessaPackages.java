// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
import java.util.function.*;
/** Artifact preparation only. Manager, target, extensions and trust are separate operations. */
public final class VanessaPackages {
 private VanessaPackages(){}
 static VerifiedDependencyPackage service(String kind)throws Exception {if(!Set.of("vanessa","vanessa-extension","client-mcp").contains(kind))throw new IllegalArgumentException("Unknown Vanessa artifact");return VerifiedDependencyPackage.workspace(VerifiedDependencyPackage.bundled("/resources/vanessa/"+kind+".json"));}
 public static Map<String,Object> status()throws Exception {
  var epf=service("vanessa").status();var extension=service("vanessa-extension").status();var mcp=service("client-mcp").status();return result(epf,extension,mcp);
 }
 public static Map<String,Object> ensure(BooleanSupplier stop,Consumer<String> progress)throws Exception {
  var epf=service("vanessa").ensure(stop,x->progress.accept("vanessa_"+x));var extension=service("vanessa-extension").ensure(stop,x->progress.accept("extension_"+x));var mcp=service("client-mcp").ensure(stop,x->progress.accept("client_mcp_"+x));return result(epf,extension,mcp);
 }
 private static Map<String,Object> result(Map<String,Object> epf,Map<String,Object> extension,Map<String,Object> mcp){
  boolean ready=List.of(epf,extension,mcp).stream().allMatch(x->Boolean.TRUE.equals(x.get("ready")));var out=new LinkedHashMap<String,Object>();out.put("ready",ready);out.put("state",ready?"package_ready":"needs_package");out.put("scope","package_only");out.put("runtime_ready",false);out.put("configuration_updated",false);out.put("trust_changed",false);out.put("client_started",false);out.put("vanessa",epf);out.put("va_extension",extension);out.put("client_mcp",mcp);
  if(Boolean.TRUE.equals(epf.get("ready"))){out.put("vanessa_epf",epf.get("path"));out.put("expected_vanessa_sha256",epf.get("sha256"));}
  if(!ready)out.put("next_call",Map.of("tool","edt.run","arguments",Map.of("op","edt_runner_profile","args",Map.of("action","prepare_package"))));else{out.put("next_tool","edt_runner_profile");out.put("note","Save a dedicated manager/target profile; client_mcp belongs to manager, VAExtension to target. Download does not install extensions or establish live readiness.");}return out;
 }
 /** Explicit artifact pairs are preserved; a partial override is never silently replaced. */
 static Map<String,Object> profileDefaults(Map<String,Object> profile,Supplier<Map<String,Object>> packages){
  try{RunnerProfileStore.validateProfile(profile,true);}catch(Exception invalid){throw new IllegalArgumentException("Invalid runner profile: "+invalid.getMessage(),invalid);}
  boolean path=profile.containsKey("vanessa_epf"),hash=profile.containsKey("expected_vanessa_sha256");if(path!=hash)throw new IllegalArgumentException("Provide both Vanessa EPF/hash or omit both");if(path)return new LinkedHashMap<>(profile);var prepared=packages.get();if(!Boolean.TRUE.equals(prepared.get("ready")))throw new IllegalStateException("Vanessa package is not verified");var out=new LinkedHashMap<>(profile);out.put("vanessa_epf",prepared.get("vanessa_epf"));out.put("expected_vanessa_sha256",prepared.get("expected_vanessa_sha256"));return out;
 }
 public static Map<String,Object> profileDefaults(Map<String,Object> profile){return profileDefaults(profile,()->{try{return ensure(DependencyPackages::stopped,x->{});}catch(Exception unavailable){throw new IllegalStateException("Vanessa package unavailable; inspect package_status/prepare_package",unavailable);}});}
}
