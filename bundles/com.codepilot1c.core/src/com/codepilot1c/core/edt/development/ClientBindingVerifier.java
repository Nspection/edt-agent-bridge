// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
import java.nio.file.Path;
import java.time.Instant;
import com.codepilot1c.core.edt.infobase.InfobasePaths;
/** Fresh local-process correlation. Never accepts a saved profile or a window caption as proof alone. */
public final class ClientBindingVerifier {
 public interface Backend {
  Map<String,Object> state()throws Exception;
  String profiles()throws Exception;
  Map<String,Object> process(long pid)throws Exception;
 }
 private ClientBindingVerifier(){}
 public static String targetHash(String connection){
  var parts=new HashMap<String,String>();var matcher=java.util.regex.Pattern.compile("(?:^|;)\\s*(File|Srvr|Ref)\\s*=\\s*\"([^\"]+)\"\\s*(?=;|$)",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(connection);
  while(matcher.find())if(parts.put(matcher.group(1).toLowerCase(Locale.ROOT),matcher.group(2))!=null)throw new IllegalArgumentException("Ambiguous base identity");
  if(parts.size()==1&&parts.containsKey("file"))return InfobasePaths.hash(InfobasePaths.connectionIdentity("File=\""+parts.get("file")+"\";"));
  if(parts.size()==2&&parts.containsKey("srvr")&&parts.containsKey("ref"))return InfobasePaths.hash("server:"+parts.get("srvr").toLowerCase(Locale.ROOT)+"/"+parts.get("ref").toLowerCase(Locale.ROOT));
  throw new IllegalArgumentException("Unsupported base identity");
 }
 public static String processTarget(List<String> args){
  String file=null,server=null,connection=null;
  for(int i=0;i<args.size();i++){String value=args.get(i);String key=value.toLowerCase(Locale.ROOT);
   if(key.equals("/f")||key.equals("/s")||key.equals("/ibconnectionstring")){if(++i>=args.size())throw new IllegalArgumentException("Missing process target");value=args.get(i);}
   else if(key.startsWith("/ibconnectionstring")){value=value.substring(19);key="/ibconnectionstring";}
   else if(key.startsWith("/f")){value=value.substring(2);key="/f";}
   else if(key.startsWith("/s")&&!key.startsWith("/server")){value=value.substring(2);key="/s";}
   else continue;
   if(key.equals("/f")){if(file!=null)throw new IllegalArgumentException("Ambiguous process target");file=value;}
   if(key.equals("/s")){if(server!=null)throw new IllegalArgumentException("Ambiguous process target");server=value;}
   if(key.equals("/ibconnectionstring")){if(connection!=null)throw new IllegalArgumentException("Ambiguous process target");connection=value;}
  }
  if((file!=null?1:0)+(server!=null?1:0)+(connection!=null?1:0)!=1)throw new IllegalArgumentException("Explicit unique process target required");
  if(connection!=null)return targetHash(connection);
  if(file!=null)return targetHash("File=\""+file+"\";");
  int slash=server.indexOf('\\');if(slash<1||slash==server.length()-1)throw new IllegalArgumentException("Invalid server process target");
  return targetHash("Srvr=\""+server.substring(0,slash)+"\";Ref=\""+server.substring(slash+1)+"\";");
 }
 static Map<String,String> profile(String text,String name){
  Map<String,String> selected=null;boolean active=false;int matches=0;
  for(String line:text.split("\\R")){
   if(line.startsWith("## ")){active=line.equals("## Профиль: "+name)||line.equals("## Profile: "+name);if(active){matches++;selected=new HashMap<>();}continue;}
   if(active&&line.startsWith("|")){String[] cells=line.split("\\|",-1);if(cells.length!=4)throw new IllegalArgumentException("Unsupported profile response");String k=cells[1].strip(),v=cells[2].strip();if(selected.put(k,v)!=null)throw new IllegalArgumentException("Duplicate profile field");}
  }
  if(matches!=1)throw new IllegalArgumentException("Active profile not uniquely identified");return selected;
 }
 private static long profilePid(Map<String,String> p){String text=field(p,"PID","PID");if(!text.matches("[0-9]+(?:[ \u00a0\u202f][0-9]{3})*"))throw new IllegalArgumentException("Invalid client PID");return Long.parseLong(text.replaceAll("[ \u00a0\u202f]",""));}
 private static boolean connectedProfile(Map<String,String> p){return Set.of("✅ подключён","✅ connected").contains(field(p,"Статус","Status"));}
 private static String field(Map<String,String> p,String ru,String en){String s=p.getOrDefault(ru,p.get(en));if(s==null||s.isBlank())throw new IllegalArgumentException("Missing profile field");return s;}
 public static Map<String,Object> verify(String expected,Backend backend){
  String stage="state";try{
   var before=backend.state();if(!Boolean.TRUE.equals(before.get("testClientConnected")))return failed("test_client_disconnected");
   String name=(String)before.get("testClientProfile");if(name==null||name.isBlank()||name.contains("\n")||name.contains("|"))return failed("active_profile_unavailable");
   stage="profile_response";String profiles=backend.profiles();stage="profile_parse";var p=profile(profiles,name);stage="profile_status";String status=field(p,"Статус","Status");if(!Set.of("✅ подключён","✅ connected").contains(status))return failed("profile_not_connected");
   stage="profile_target";if(!expected.equals(targetHash(field(p,"Строка подключения","Connection string"))))return failed("profile_targets_another_base");
   stage="profile_pid";long pid=profilePid(p);if(pid<=0)return failed("client_pid_unavailable");
   stage="process";var process=backend.process(pid);if(!Set.of("1cv8.exe","1cv8c.exe").contains(process.get("name")))return failed("unsupported_client_process");
   if(!expected.equals(process.get("base_identity_sha256")))return failed("live_process_targets_another_base");
   String started=(String)process.get("started_at");Instant.parse(started);
   stage="recheck";var after=backend.state();var active=profile(backend.profiles(),name);if(!connectedProfile(active)||profilePid(active)!=pid||!expected.equals(targetHash(field(active,"Строка подключения","Connection string"))))return failed("active_profile_changed_during_verification");var again=backend.process(pid);
   if(!before.equals(after)||!process.equals(again))return failed("client_changed_during_verification");
   return Map.of("live_client_binding_verified",true,"process_id",pid,"process_started_at",started,"profile",name,"base_identity_sha256",expected,"verified_at",Instant.now().toString(),"proof_kind","local_process_correlation");
  }catch(Exception unavailable){return Map.of("live_client_binding_verified",false,"reason","client_identity_unavailable","stage",stage);}catch(LinkageError unavailable){return failed("process_identity_backend_unavailable");}
 }
 public static Map<String,Object> failed(String reason){return Map.of("live_client_binding_verified",false,"reason",reason);}
 private enum ProcessProperty {PROCESSID,EXECUTABLEPATH,CREATIONDATE,COMMANDLINE}
 static List<String> windowsArguments(long pid,String executable,Instant started)throws Exception {
  if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows"))throw new IllegalStateException("Process arguments unavailable");
  var ole=com.sun.jna.platform.win32.Ole32.INSTANCE;int hr=ole.CoInitializeEx(com.sun.jna.Pointer.NULL,0).intValue();if(hr<0)throw new IllegalStateException("Process COM identity unavailable");
  try{
   var query=new com.sun.jna.platform.win32.COM.WbemcliUtil.WmiQuery<ProcessProperty>("Win32_Process WHERE ProcessId="+pid,ProcessProperty.class);var rows=query.execute(5000);if(rows.getResultCount()!=1)throw new IllegalStateException("Process identity unavailable");
   String path=(String)rows.getValue(ProcessProperty.EXECUTABLEPATH,0),created=(String)rows.getValue(ProcessProperty.CREATIONDATE,0),command=(String)rows.getValue(ProcessProperty.COMMANDLINE,0);Object processId=rows.getValue(ProcessProperty.PROCESSID,0);
   if(created==null||!created.matches("[0-9]{14}\\.[0-9]{6}[+-][0-9]{3}"))throw new IllegalStateException("Process creation time unavailable");
   int minutes=Integer.parseInt(created.substring(22));if(created.charAt(21)=='-')minutes=-minutes;
   Instant actual=java.time.LocalDateTime.parse(created.substring(0,21),java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss.SSSSSS")).toInstant(java.time.ZoneOffset.ofTotalSeconds(minutes*60));
   if(!(processId instanceof Number number)||number.longValue()!=pid||path==null||!java.nio.file.Files.isSameFile(Path.of(path),Path.of(executable))||!actual.truncatedTo(java.time.temporal.ChronoUnit.MILLIS).equals(started.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)))throw new IllegalStateException("Process identity changed");
   return windowsArgv(command);
  }finally{ole.CoUninitialize();}
 }
 static List<String> windowsArgv(String command){
  if(command==null||command.isBlank()||command.length()>32768)throw new IllegalArgumentException("Missing process command");
  var shell=com.sun.jna.NativeLibrary.getInstance("shell32");var kernel=com.sun.jna.NativeLibrary.getInstance("kernel32");var count=new com.sun.jna.Memory(4);count.clear();
  var ptr=shell.getFunction("CommandLineToArgvW",com.sun.jna.Function.ALT_CONVENTION).invokePointer(new Object[]{new com.sun.jna.WString(command),count});if(ptr==null)throw new IllegalStateException("Process arguments unavailable");
  try{int n=count.getInt(0);if(n<1||n>512)throw new IllegalArgumentException("Process arguments bound");var values=new ArrayList<String>();for(int i=1;i<n;i++)values.add(ptr.getPointer((long)i*com.sun.jna.Native.POINTER_SIZE).getWideString(0));return values;}finally{kernel.getFunction("LocalFree",com.sun.jna.Function.ALT_CONVENTION).invokePointer(new Object[]{ptr});}
 }
 public static Map<String,Object> localProcess(long pid)throws Exception {
  var handle=ProcessHandle.of(pid).filter(ProcessHandle::isAlive).orElseThrow();var info=handle.info();String name=Path.of(info.command().orElseThrow()).getFileName().toString().toLowerCase(Locale.ROOT);
  String executable=info.command().orElseThrow();Instant started=info.startInstant().orElseThrow();List<String> args=info.arguments().map(List::of).orElse(null);if(args==null)args=windowsArguments(pid,executable,started);return Map.of("name",name,"started_at",started.toString(),"base_identity_sha256",processTarget(args));
 }
}
