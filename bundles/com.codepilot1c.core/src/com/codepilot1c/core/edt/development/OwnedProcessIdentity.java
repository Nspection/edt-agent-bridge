// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
/** A reused PID proves the original exited; unavailable identity never authorizes a signal. */
final class OwnedProcessIdentity {
 enum State { GONE, ORIGINAL, UNKNOWN }
 static State classify(boolean alive,Optional<Instant> actual,Instant expected){
  if(!alive)return State.GONE;
  if(actual.isEmpty())return State.UNKNOWN;
  return actual.get().equals(expected)?State.ORIGINAL:State.GONE;
 }
 static boolean original(ProcessHandle process,Instant expected){
  State state=classify(process.isAlive(),process.info().startInstant(),expected);
  if(state==State.UNKNOWN)throw new IllegalStateException("Owned process identity unavailable; no signal sent");
  return state==State.ORIGINAL;
 }
 static boolean nativeSession(List<String> arguments,Path expected){
  int options=0;boolean matches=false;
  for(int i=0;i<arguments.size();i++){
   String arg=arguments.get(i),value=null;
   if(Set.of("/N","/P","/WSN","/WSP","/UC").contains(arg.toUpperCase(Locale.ROOT))){if(i+1>=arguments.size())return false;i++;continue;}
   if(arg.equalsIgnoreCase("/C")||arg.equalsIgnoreCase("-C")){if(i+1<arguments.size())value=arguments.get(++i);else return false;}
   else if(arg.length()>2&&(arg.regionMatches(true,0,"/C",0,2)||arg.regionMatches(true,0,"-C",0,2)))value=arg.substring(2);
   if(value!=null){options++;if(value.startsWith("EDTAgentHost="))try{matches=Path.of(value.substring(13)).toAbsolutePath().normalize().equals(expected.toAbsolutePath().normalize());}catch(RuntimeException invalid){return false;}}
  }
  return options==1&&matches;
 }
 static Optional<ProcessHandle> recoveredNative(long pid,Instant expected,Path session)throws Exception {
  var candidate=ProcessHandle.of(pid);if(candidate.isEmpty()||!original(candidate.get(),expected))return Optional.empty();
  var process=candidate.get();var info=process.info();String executable=info.command().orElseThrow(()->new IllegalStateException("Owned executable unavailable"));
  List<String> arguments=info.arguments().map(List::of).orElse(null);
  if(arguments==null)arguments=ClientBindingVerifier.windowsArguments(pid,executable,expected);
  if(!nativeSession(arguments,session))throw new IllegalStateException("Native session argument differs; no signal sent");
  return original(process,expected)?Optional.of(process):Optional.empty();
 }
}
