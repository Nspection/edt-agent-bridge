// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.math.BigDecimal;import com.google.gson.*;
/** One bounded read-query batch through an already-owned native host; typed assertions stay separate from execution success. */
public final class DataAssertionService {
 public interface Backend {Map<String,Object> target(String project,String host,boolean running)throws Exception;Map<String,Object> submit(String host,String key,List<Map<String,Object>> checks)throws Exception;Map<String,Object> result(String host,String request)throws Exception;}
 private static final Gson JSON=new GsonBuilder().serializeNulls().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
 private static final Map<String,Object> LOCKS=new java.util.concurrent.ConcurrentHashMap<>();private final Path root;private final Backend backend;
 public DataAssertionService(Path root,Backend backend)throws Exception{this.root=root.toAbsolutePath().normalize();this.backend=backend;Files.createDirectories(this.root);}
 public static DataAssertionService workspace()throws Exception{return new DataAssertionService(org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/data-assertions"),new Backend(){
  public Map<String,Object> target(String project,String host,boolean running)throws Exception{var t=new NativeHostService().assertionTarget(host,running);var b=new com.codepilot1c.core.edt.runtime.EdtRuntimeService().resolveDefaultInfobase(project);if(!project.equals(t.get("project"))||b==null||!b.getUuid().toString().equals(t.get("base_uuid")))throw new IllegalStateException("Host belongs to another project/base");return t;}
  public Map<String,Object> submit(String host,String key,List<Map<String,Object>> checks)throws Exception{return new NativeHostService().execute(host,Map.of("idempotency_key",key,"execution_mode","execute","context","server","code",program(),"parameters",Map.of("checks",checks)));}
  public Map<String,Object> result(String host,String request)throws Exception{return new NativeHostService().result(host,request);}
 });}
 private Path path(String id){if(id==null||!id.matches("assert-[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid assertion_id");return root.resolve(id+".json");}
 private Object lock(String id){return LOCKS.computeIfAbsent(root+":"+id,k->new Object());}
 private void save(String id,Map<String,Object> doc)throws Exception{Path file=path(id);if(Files.isSymbolicLink(file))throw new IllegalStateException("Linked assertion state");Path temp=Files.createTempFile(root,"assert-",".tmp");try{Files.writeString(temp,JSON.toJson(doc),StandardCharsets.UTF_8);Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}}
 @SuppressWarnings("unchecked") private Map<String,Object> load(String id)throws Exception{Path file=path(id);if(Files.isSymbolicLink(file)||Files.size(file)>1024*1024)throw new IllegalStateException("Invalid assertion state");return JSON.fromJson(Files.readString(file,StandardCharsets.UTF_8),LinkedHashMap.class);}
 static List<Map<String,Object>> validate(Object raw){
  if(!(raw instanceof List<?> list)||list.isEmpty()||list.size()>32)throw new IllegalArgumentException("checks must contain 1..32 entries");var checks=new ArrayList<Map<String,Object>>();var ids=new HashSet<String>();
  for(var item:list){var m=TaskSessionService.object(item);if(!Set.of("id","query","parameters","kind","expected").containsAll(m.keySet()))throw new IllegalArgumentException("Unknown check property");String id=RuntimeFormService.required(m,"id");if(!id.matches("[A-Za-z0-9_.-]{1,80}")||!ids.add(id))throw new IllegalArgumentException("Unique bounded check ids required");RuntimeFormService.required(m,"query");String kind=RuntimeFormService.required(m,"kind");if(!Set.of("scalar_equals","row_count_equals").contains(kind)||!m.containsKey("expected"))throw new IllegalArgumentException("Typed expectation required");Object expected=m.get("expected");if(expected!=null&&!(expected instanceof String||expected instanceof Number||expected instanceof Boolean))throw new IllegalArgumentException("Expected a JSON scalar");if(kind.equals("row_count_equals")){try{int n=new BigDecimal(String.valueOf(expected)).intValueExact();if(n<0||n>1000)throw new IllegalArgumentException();}catch(Exception e){throw new IllegalArgumentException("Row count must be an integer 0..1000");}}
   var parameters=m.containsKey("parameters")?TaskSessionService.object(m.get("parameters")):Map.<String,Object>of();if(m.containsKey("parameters")&&!(m.get("parameters") instanceof Map<?,?>))throw new IllegalArgumentException("parameters must be object");for(Object value:parameters.values())if(value!=null&&!(value instanceof String||value instanceof Number||value instanceof Boolean))throw new IllegalArgumentException("Query parameters must be JSON scalars");
   var check=new LinkedHashMap<>(m);check.put("parameters",parameters);checks.add(check);
  }
  if(JSON.toJson(checks).getBytes(StandardCharsets.UTF_8).length>64*1024)throw new IllegalArgumentException("Assertion batch exceeds 64 KiB");return checks;
 }
 public Map<String,Object> start(String project,String host,String key,Object raw)throws Exception{
  var checks=validate(raw);if(key==null||key.isBlank()||key.length()>128)throw new IllegalArgumentException("idempotency_key required, max 128");String id="assert-"+TaskSessionService.fingerprint(List.of(project,key));String fingerprint=TaskSessionService.fingerprint(List.of(project,host,checks));
  synchronized(lock(id)){var target=backend.target(project,host,true);if(Files.exists(path(id))){var old=load(id);if(!fingerprint.equals(old.get("fingerprint"))||!target.equals(TaskSessionService.object(old.get("target"))))throw new IllegalStateException("Assertion key changed target or checks");return view(id,old,false);}
   var doc=new LinkedHashMap<String,Object>();doc.put("project",project);doc.put("host_id",host);doc.put("target",target);doc.put("fingerprint",fingerprint);doc.put("checks",checks);save(id,doc);
   var payload=new ArrayList<Map<String,Object>>();for(var c:checks)payload.add(Map.of("id",c.get("id"),"query",c.get("query"),"parameters",c.get("parameters")));
   var submitted=backend.submit(host,id,payload);if(NativeExecutionGate.notDispatched(submitted)){doc.put("dispatch_rejection",NativeExecutionGate.rejectionProof(submitted));save(id,doc);return view(id,doc,false);}if(submitted.get("request_id") instanceof String request){doc.put("request_id",request);save(id,doc);}else throw new IllegalStateException("Native host did not return request id");return view(id,doc,false);
  }
 }
 public Map<String,Object> status(String id)throws Exception{synchronized(lock(id)){var doc=load(id);if(!TaskSessionService.object(doc.get("target")).equals(backend.target((String)doc.get("project"),(String)doc.get("host_id"),false)))throw new IllegalStateException("Assertion target changed");return view(id,doc,true);}}
 private Map<String,Object> view(String id,Map<String,Object> doc,boolean poll)throws Exception {
  var out=new LinkedHashMap<String,Object>();out.put("assertion_id",id);out.put("host_id",doc.get("host_id"));out.put("business_result_verified",false);out.put("state",doc.containsKey("request_id")?"submitted":"outcome_unknown");
  if(doc.containsKey("dispatch_rejection")){if(doc.containsKey("request_id")||!NativeExecutionGate.notDispatched(TaskSessionService.object(doc.get("dispatch_rejection"))))throw new IllegalStateException("Invalid pre-dispatch assertion evidence");out.putAll(TaskSessionService.object(doc.get("dispatch_rejection")));return out;}
  if(doc.get("request_id") instanceof String request){out.put("request_id",request);if(poll){var operation=backend.result((String)doc.get("host_id"),request);out.put("state",operation.get("state"));for(String k:List.of("terminal","outcome_unknown"))if(operation.containsKey(k))out.put(k,operation.get(k));var execution=TaskSessionService.object(operation.get("execution"));if("completed".equals(operation.get("state"))&&Boolean.TRUE.equals(execution.get("success"))){var report=evaluate((List<?>)doc.get("checks"),execution.get("value"));out.putAll(report);}}}return out;
 }
 static boolean equalScalar(Object a,Object b){if(a instanceof Number x&&b instanceof Number y)return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString()))==0;return Objects.equals(a,b);}
 static Map<String,Object> evaluate(List<?> checks,Object raw){
  if(!(raw instanceof List<?> rows)||rows.size()!=checks.size())throw new IllegalStateException("Incomplete assertion observations");var passed=new ArrayList<String>();var failed=new ArrayList<Map<String,Object>>();
  for(int i=0;i<checks.size();i++){var c=TaskSessionService.object(checks.get(i));var r=TaskSessionService.object(rows.get(i));if(!Objects.equals(c.get("id"),r.get("id")))throw new IllegalStateException("Observation identity mismatch");boolean scalar=c.get("kind").equals("scalar_equals"),complete=Boolean.FALSE.equals(r.get("truncated"));Object actual=scalar?r.get("scalar"):r.get("row_count");boolean typed=scalar?r.containsKey("scalar")&&Boolean.TRUE.equals(r.get("scalar_supported"))&&equalScalar(r.get("row_count"),1)&&equalScalar(r.get("column_count"),1):r.get("row_count") instanceof Number;boolean ok=complete&&typed&&equalScalar(actual,c.get("expected"));if(ok)passed.add((String)c.get("id"));else{var f=new LinkedHashMap<String,Object>();f.put("id",c.get("id"));f.put("expected",c.get("expected"));f.put("actual",actual);f.put("observation_complete",complete&&typed);failed.add(f);}}
  return Map.of("state",failed.isEmpty()?"passed":"failed","terminal",true,"outcome_unknown",false,"business_result_verified",failed.isEmpty(),"total",checks.size(),"passed_ids",passed,"failures",failed);
 }
 static String program(){return """
  Результат = Новый Массив;
  Для Каждого BridgeCheck Из ПараметрыВызова.checks Цикл
    BridgeQuery = Новый Запрос; BridgeQuery.Текст = BridgeCheck.query;
    Для Каждого BridgeParameter Из BridgeCheck.parameters Цикл
      BridgeQuery.УстановитьПараметр(BridgeParameter.Ключ, BridgeParameter.Значение);
    КонецЦикла;
    BridgeTable = BridgeQuery.Выполнить(); BridgeSelection = BridgeTable.Выбрать();
    BridgeCount = 0; BridgeScalar = Неопределено; BridgeSupported = Истина; BridgeTruncated = Ложь;
    Пока BridgeSelection.Следующий() Цикл
      BridgeCount = BridgeCount + 1;
      Если BridgeCount > 1000 Тогда BridgeTruncated = Истина; Прервать; КонецЕсли;
      Если BridgeCount = 1 И BridgeTable.Колонки.Количество() = 1 Тогда
        BridgeScalar = BridgeSelection[0]; BridgeType = ТипЗнч(BridgeScalar);
        BridgeSupported = BridgeType = Тип("Строка") ИЛИ BridgeType = Тип("Число") ИЛИ BridgeType = Тип("Булево") ИЛИ BridgeType = Тип("Null") ИЛИ BridgeType = Тип("Неопределено");
        Если НЕ BridgeSupported ИЛИ BridgeType = Тип("Null") Тогда BridgeScalar = Неопределено; КонецЕсли;
      КонецЕсли;
    КонецЦикла;
    Результат.Добавить(Новый Структура("id,row_count,column_count,scalar,scalar_supported,truncated", BridgeCheck.id, BridgeCount, BridgeTable.Колонки.Количество(), BridgeScalar, BridgeSupported, BridgeTruncated));
  КонецЦикла;
  """;}
}
