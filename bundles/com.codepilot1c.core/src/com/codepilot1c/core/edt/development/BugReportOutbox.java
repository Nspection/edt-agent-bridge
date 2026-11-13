// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.nio.file.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.google.gson.*;
/** Durable single-owner claim precedes POST. Missing acknowledgement is never replayable. */
public final class BugReportOutbox {
 static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
 private static final Object GATE=new Object();private final Path root;
 public BugReportOutbox(Path root)throws Exception{this.root=root;Files.createDirectories(root);if(Files.isSymbolicLink(root))throw new IllegalStateException("Report storage unavailable");}
 interface Task<T>{T run()throws Exception;}
 <T>T locked(Task<T> task)throws Exception{synchronized(GATE){if(Files.isSymbolicLink(root.resolve(".lock")))throw new IllegalStateException("Report storage changed");try(var channel=FileChannel.open(root.resolve(".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()){return task.run();}}}
 static String hash(String text)throws Exception{return DirectClientJournal.hash(text);}
 static String text(Map<String,Object> map,String key){Object value=map.get(key);return value instanceof String s?s:"";}
 private Path path(String id){if(id==null||!id.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid report_id");return root.resolve(id+".json");}
 @SuppressWarnings("unchecked") Map<String,Object> read(String id)throws Exception{Path file=path(id);if(Files.isSymbolicLink(file)||Files.size(file)>65536)throw new IllegalStateException("Invalid report record");var row=JSON.fromJson(Files.readString(file),LinkedHashMap.class);validate(row,id);return row;}
 void write(Map<String,Object> row)throws Exception{
  Path target=path(text(row,"report_id")),temp=root.resolve(target.getFileName()+".tmp");if(Files.isSymbolicLink(target)||Files.isSymbolicLink(temp))throw new IllegalStateException("Report storage changed");
  byte[] bytes=JSON.toJson(row).getBytes(StandardCharsets.UTF_8);if(bytes.length>65536)throw new IllegalStateException("Report size limit");
  try(var channel=FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
  Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
 }
 private static String identity(Map<String,Object> envelope){var stable=new TreeMap<>(envelope);for(String volatileField:List.of("elapsed_ms","operation_timeout_s","pending_replies","failed_step_index"))stable.remove(volatileField);return JSON.toJson(stable);}
 private static String title(Map<String,Object> envelope){return "[Agent report] "+envelope.get("component")+": "+envelope.getOrDefault("failure_kind","diagnostic")+" ("+envelope.get("bridge_version")+")";}
 private static String body(Map<String,Object> envelope,String marker){return "Agent-generated technical report. Source: "+envelope.get("source")+".\n\n```json\n"+JSON.toJson(new TreeMap<>(envelope))+"\n```\n\n<!-- edt-agent-report:"+marker+" -->\n";}
 @SuppressWarnings("unchecked") private static void validate(Map<String,Object> row,String id)throws Exception{
  if(row==null||!id.equals(row.get("report_id"))||!text(row,"marker").matches("[a-f0-9-]{36}")||!text(row,"account_id").matches("[0-9]{0,20}")||!Set.of("prepared","sending","submitted","delivery_unknown").contains(text(row,"state"))||!(row.get("envelope") instanceof Map<?,?> envelope))throw new IllegalStateException("Invalid report record");
  var data=(Map<String,Object>)envelope;String source=text(data,"source");if(!Set.of("verified_local_operation_journal","agent_reported").contains(source))throw new IllegalStateException("Invalid report source");
  var sanitized=BugReportEnvelope.collect(text(data,"component"),text(data,"bridge_version"),data,source.equals("verified_local_operation_journal"));
  if(!com.google.gson.JsonParser.parseString(JSON.toJson(sanitized)).equals(com.google.gson.JsonParser.parseString(JSON.toJson(data)))||!title(sanitized).equals(row.get("title"))||!body(sanitized,text(row,"marker")).equals(row.get("body"))||!hash(text(row,"title")+"\n"+text(row,"body")).equals(row.get("payload_sha256"))||!hash(text(row,"account_id")+"\n"+identity(sanitized)).equals(id))throw new IllegalStateException("Report contents changed; no POST allowed");
 }
 public Map<String,Object> prepare(Map<String,Object> envelope,String account,String revision)throws Exception{return locked(()->{
  String payload=JSON.toJson(new TreeMap<>(envelope));String id=hash(account+"\n"+identity(envelope));Path target=path(id);
  if(Files.exists(target)){var prior=read(id);if(!"prepared".equals(prior.get("state"))||revision.equals(prior.get("policy_revision")))return view(prior);}
  if(!Files.exists(target))try(var paths=Files.list(root)){long pending=0;for(var iterator=paths.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).iterator();iterator.hasNext();){var file=iterator.next();var record=read(file.getFileName().toString().substring(0,64));if(!"submitted".equals(record.get("state"))&&++pending>=200)throw new IllegalStateException("Pending report limit; reconcile unknown receipts before preparing more");}}
  String marker=UUID.randomUUID().toString();String title=title(envelope);
  String body=body(envelope,marker);
  var row=new LinkedHashMap<String,Object>();row.put("report_id",id);row.put("envelope",envelope);row.put("marker",marker);row.put("account_id",account);row.put("policy_revision",revision);row.put("title",title);row.put("body",body);row.put("payload_sha256",hash(title+"\n"+body));row.put("state","prepared");row.put("created_at",java.time.Instant.now().toString());write(row);return view(row);
 });}
 public Map<String,Object> status(String id)throws Exception{return locked(()->view(read(id)));}
 static Map<String,Object> view(Map<String,Object> row){var out=new LinkedHashMap<String,Object>();for(String key:List.of("report_id","account_id","policy_revision","title","body","payload_sha256","state","created_at","dispatch_day","reconcile_required","issue_number","issue_url"))if(row.containsKey(key))out.put(key,row.get(key));if("sending".equals(out.get("state")))out.put("state","delivery_unknown");out.put("target","Nspection/edt-agent-bridge");out.put("retry_safe",false);return out;}
}
