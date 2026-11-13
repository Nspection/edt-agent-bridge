// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.*;
/** Fixed GitHub origin/repository. Credential data never leaves this transport. */
public final class BugReportGitHub {
 public record Account(String id,String login){}
 public record Receipt(long number,String url){}
 public interface Session {Account account();Receipt post(String title,String body)throws Exception;Optional<Receipt> find(String marker,String body)throws Exception;}
 private final String namespace;
 public BugReportGitHub(Path workspace)throws Exception{namespace=namespaceFor(workspace);}
 static String namespaceFor(Path workspace)throws Exception{return "edt-agent-bridge-"+DirectClientJournal.hash(workspace.toRealPath().toString().toLowerCase(java.util.Locale.ROOT)).substring(0,24);}
 static String loginHint(String value){if(value==null||value.isBlank())return "";if(!value.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}"))throw new IllegalArgumentException("Invalid GitHub login");return value;}

 private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
 private static final String API="https://api.github.com",ISSUES="/repos/Nspection/edt-agent-bridge/issues";
 private static Path executable(){
  for(String variable:List.of("ProgramW6432","ProgramFiles")){String value=System.getenv(variable);if(value!=null)for(String architecture:List.of("ucrt64","mingw64")){Path path=Path.of(value,"Git",architecture,"bin","git-credential-manager.exe");if(Files.isRegularFile(path)&&!Files.isSymbolicLink(path))return path;}}
  throw new IllegalStateException("Install Git for Windows with Git Credential Manager to enable GitHub reports");
 }
 private String gcm(List<String> arguments,boolean interactive,String input)throws Exception{
  var command=new ArrayList<String>();command.add(executable().toString());command.addAll(arguments);var builder=new ProcessBuilder(command).directory(executable().getParent().toFile());
  var env=builder.environment();env.keySet().removeIf(k->k.startsWith("GCM_")||k.startsWith("GIT_CONFIG")||k.startsWith("GIT_TRACE")||k.equals("GIT_CURL_VERBOSE")||k.equals("GIT_DIR")||k.equals("GIT_WORK_TREE")||k.equals("GIT_ASKPASS")||k.equals("SSH_ASKPASS"));
  env.put("GCM_NAMESPACE",namespace);env.put("GCM_CREDENTIAL_STORE","wincredman");env.put("GCM_INTERACTIVE",interactive?"Always":"Never");env.put("GIT_TERMINAL_PROMPT","0");env.put("GIT_CONFIG_NOSYSTEM","1");env.put("GIT_CONFIG_GLOBAL","NUL");env.put("GIT_CONFIG_COUNT","0");
  builder.redirectError(ProcessBuilder.Redirect.DISCARD);Process process=builder.start();
  var output=CompletableFuture.supplyAsync(()->{try(var stream=process.getInputStream()){byte[] bytes=stream.readNBytes(65537);if(bytes.length>65536)throw new IllegalStateException("Credential response exceeds limit");return new String(bytes,StandardCharsets.UTF_8);}catch(Exception failure){throw new CompletionException(new IllegalStateException("Credential manager unavailable"));}});
  try{try(var stream=process.getOutputStream()){stream.write(input.getBytes(StandardCharsets.UTF_8));}if(!process.waitFor(interactive?180:20,TimeUnit.SECONDS))throw new IllegalStateException("GitHub authorization timed out");String result=output.get(5,TimeUnit.SECONDS);if(process.exitValue()!=0)throw new IllegalStateException("GitHub authorization required in EDT preferences");return result;}finally{if(process.isAlive())process.destroyForcibly();output.cancel(true);}
 }
 public void login(String login)throws Exception{var args=new ArrayList<>(List.of("github","login","--url","https://github.com","--browser"));String hint=loginHint(login);if(!hint.isBlank()){args.add("--username");args.add(hint);}gcm(args,true,"");}
 private String token(String login)throws Exception{String hint=loginHint(login);String value=gcm(List.of("get"),false,"protocol=https\nhost=github.com\n"+(hint.isBlank()?"":"username="+hint+"\n")+"\n");for(String line:value.split("\r?\n"))if(line.startsWith("password=")&&line.length()>9)return line.substring(9);throw new IllegalStateException("GitHub authorization required in EDT preferences");}
 private JsonElement request(String method,String path,String body,String token)throws Exception{
  if(!path.equals("/user")&&!path.equals(ISSUES)&&!path.matches(ISSUES+"\\?state=all&per_page=100&page=[1-5]"))throw new IllegalArgumentException("Unsupported report endpoint");
  var builder=HttpRequest.newBuilder(URI.create(API+path)).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+token).header("Accept","application/vnd.github+json").header("X-GitHub-Api-Version","2022-11-28").header("User-Agent","EDT-Agent-Bridge-Bug-Reports");
  if(method.equals("POST"))builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));else builder.GET();
  var exchange=HTTP.sendAsync(builder.build(),info->new BugReportHttpBody(262144));
  var response=BugReportHttpBody.await(exchange,30,TimeUnit.SECONDS);byte[] bytes=response.body();
  if(response.statusCode()!=(method.equals("POST")?201:200))throw new IllegalStateException("GitHub request was not acknowledged (HTTP "+response.statusCode()+")");
  return JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8));
 }
 public Session authenticate(String login)throws Exception {
  String credential=token(login);Account account=account(credential);
  return new Session(){
   public Account account(){return account;}
   public Receipt post(String title,String body)throws Exception{var json=new JsonObject();json.addProperty("title",title);json.addProperty("body",body);return receipt(request("POST",ISSUES,json.toString(),credential).getAsJsonObject());}
   public Optional<Receipt> find(String marker,String body)throws Exception{return findWithCredential(body,account.id(),credential);}
   @Override public String toString(){return "GitHub report session (credentials redacted)";}
  };
 }
 private Account account(String token)throws Exception{var user=request("GET","/user",null,token).getAsJsonObject();String id=user.get("id").getAsString(),login=user.get("login").getAsString();if(!id.matches("[0-9]{1,20}")||!login.matches("[A-Za-z0-9-]{1,39}"))throw new IllegalStateException("Invalid GitHub account response");return new Account(id,login);}
 static Receipt receipt(JsonObject json){long number=json.get("number").getAsLong();String url=json.get("html_url").getAsString();if(number<1||!url.equals("https://github.com/Nspection/edt-agent-bridge/issues/"+number))throw new IllegalStateException("Invalid GitHub issue acknowledgement");return new Receipt(number,url);}
 /** Bounded reconciliation; absence never proves that POST failed. */
 private Optional<Receipt> findWithCredential(String body,String account,String credential)throws Exception{
  Receipt found=null;
  for(int page=1;page<=5;page++){var rows=request("GET",ISSUES+"?state=all&per_page=100&page="+page,null,credential).getAsJsonArray();for(var item:rows){var row=item.getAsJsonObject();if(row.has("pull_request"))continue;if(row.has("body")&&!row.get("body").isJsonNull()&&body.equals(row.get("body").getAsString())&&account.equals(row.getAsJsonObject("user").get("id").getAsString())){if(found!=null)throw new IllegalStateException("Multiple report acknowledgements require manual review");found=receipt(row);}}if(rows.size()<100)break;}
  return Optional.ofNullable(found);
 }
}
