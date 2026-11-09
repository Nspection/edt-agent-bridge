// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;import java.nio.charset.StandardCharsets;import java.time.*;
import com.google.gson.*;import com.codepilot1c.core.tools.ToolResult;
/** Explicit bounded views. Details are ephemeral in-memory receipts, never files or replayable calls. */
public final class AgentResponses {
 public static final Gson JSON=new GsonBuilder().serializeNulls().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
 private static final Map<String,Entry> CACHE=new LinkedHashMap<>();private static int storedBytes;
 private record Entry(String text,int bytes,Instant expires){}
 private static final Set<String> KEEP=Set.of("success","state","phase","terminal","ready","timed_out","job_id","session_id","step_key","batch_key","host_id","run_id","assertion_id","snapshot_id","check_id","plan_sha256","catalog_sha256","not_modified","has_more","has_more_events","events_gap","binding_current","live_client_binding_verified","business_result_verified","postcondition_verified","configuration_synchronized","file_comparison_complete","unknown_sections","outcome_unknown","partial_restore","truncated","reason","error","stage","retry_safe","result_available","cleaned","all_tracked_resources_cleaned","blockers","missing_tools","missing_names","execution_state","condition_kind","data_run_id","set_id","owned_base_id","base_uuid","uuid","project","equality");
 private AgentResponses(){}
 private static boolean evidence(String key,Object value){return KEEP.contains(key)||value instanceof Boolean||key.endsWith("_truncated")||key.startsWith("unknown_")||key.endsWith("_scope")||key.equals("scenario_sources_match");}
 private static final Set<String> CONTAINERS=Set.of("result","execution","failure","changes","scenario","sources","last_status","results","batches","database","model","runner","debug","progress","test_data","form_observations");
 private static final Set<String> OMIT_PAYLOAD=Set.of("code","parameters","arguments","value","items","rows","schema","tools","content","text","log","logs","stdout","stderr");
 /** Outcome projections never return raw payload at a structural or size limit. */
 public static Object evidenceTree(Object value,int depth){
  if(depth>=16)return Map.of("evidence_truncated",true,"evidence_complete",false);
  if(value instanceof Map<?,?> map){
   Map<String,Object> out=new LinkedHashMap<>();
   for(var e:map.entrySet()){
    String key=String.valueOf(e.getKey());Object v=e.getValue();if(OMIT_PAYLOAD.contains(key.toLowerCase(Locale.ROOT)))continue;
    if(v instanceof List<?> list&&evidence(key,v)){
     List<Object> known=new ArrayList<>();for(Object item:list.subList(0,Math.min(128,list.size()))){if(item instanceof String text&&text.length()>1024){known.add(clip(text));out.put("evidence_truncated",true);out.put("evidence_complete",false);}else if(item instanceof Map<?,?>||item instanceof List<?>)known.add(evidenceTree(item,depth+1));else known.add(item);}out.put(key,known);if(list.size()>128){out.put("evidence_truncated",true);out.put("evidence_complete",false);}
    }else if(v instanceof Map<?,?>||v instanceof List<?>){Object nested=evidenceTree(v,depth+1);if(nested instanceof Map<?,?> m&&!m.isEmpty()||nested instanceof List<?> l&&!l.isEmpty())out.put(key,nested);}
    else if(evidence(key,v)){if(v instanceof String text&&text.length()>1024){out.put(key,clip(text));out.put(key+"_truncated",true);out.put("evidence_complete",false);}else out.put(key,v);}
   }return out;
  }
  if(value instanceof List<?> list){List<Object> rows=new ArrayList<>();boolean present=false;for(Object item:list.subList(0,Math.min(128,list.size()))){Object nested=evidenceTree(item,depth+1);if(nested==null)nested=Map.of();if(nested instanceof Map<?,?> m&&!m.isEmpty()||nested instanceof List<?> l&&!l.isEmpty())present=true;rows.add(nested);}if(list.size()>128){rows.add(Map.of("evidence_truncated",true,"evidence_complete",false));present=true;}return present?rows:List.of();}
  return null;
 }
 private static String clip(String text){int end=1024;if(Character.isHighSurrogate(text.charAt(end-1)))end--;return text.substring(0,end);}
 public static String schema(String raw){var s=JsonParser.parseString(raw).getAsJsonObject();s.getAsJsonObject("properties").add("response",JsonParser.parseString("""
 {"type":"object","additionalProperties":false,"properties":{"view":{"enum":["summary","details"]},"fields":{"type":"array","maxItems":32,"items":{"type":"string"}},"max_bytes":{"type":"integer","minimum":1024,"maximum":131072}}}
 """));return JSON.toJson(s);}
 public static int integer(Map<String,Object> args,String key,int fallback,int min,int max){Object value=args.getOrDefault(key,fallback);if(!(value instanceof Number number))throw new IllegalArgumentException(key+" must be an integer");int result;try{result=new java.math.BigDecimal(number.toString()).intValueExact();}catch(ArithmeticException|NumberFormatException e){throw new IllegalArgumentException(key+" must be an integer within bounds");}if(result<min||result>max)throw new IllegalArgumentException(key+" outside bounds");return result;}
 public static void validateOptions(Object raw){
  if(raw==null)throw new IllegalArgumentException("response must be an object");if(!(raw instanceof Map<?,?> options))throw new IllegalArgumentException("response must be an object");
  for(Object k:options.keySet())if(!Set.of("view","fields","max_bytes").contains(k))throw new IllegalArgumentException("Unknown response option");
  if(options.containsKey("view")&&!Set.of("summary","details").contains(options.get("view")))throw new IllegalArgumentException("Invalid response view");
  if(options.containsKey("max_bytes")){Map<String,Object> check=new HashMap<>();check.put("max_bytes",options.get("max_bytes"));integer(check,"max_bytes",16384,1024,131072);}
  if(options.containsKey("fields")&&(!(options.get("fields") instanceof List<?> fields)||fields.size()>32||fields.stream().anyMatch(x->!(x instanceof String))))throw new IllegalArgumentException("Invalid response fields");
 }
 public static ToolResult success(Map<String,Object> value,Map<String,Object> args){return ToolResult.success(JSON.toJson(view(value,TaskSessionService.object(args.get("response")))));}
 public static Map<String,Object> view(Map<String,Object> value,Map<String,Object> options){
  if(options.isEmpty())return value;
  validateOptions(options);
  for(String k:options.keySet())if(!Set.of("view","fields","max_bytes").contains(k))throw new IllegalArgumentException("Unknown response option");
  String mode=(String)options.getOrDefault("view","details");if(!Set.of("summary","details").contains(mode))throw new IllegalArgumentException("Invalid response view");
  int max=integer(options,"max_bytes",16384,1024,131072);if(max<1024||max>131072)throw new IllegalArgumentException("max_bytes must be 1024..131072");
  Set<String> selected=new LinkedHashSet<>();if(options.containsKey("fields")){if(!(options.get("fields") instanceof List<?> fields)||fields.size()>32||fields.stream().anyMatch(x->!(x instanceof String)))throw new IllegalArgumentException("Invalid response fields");for(Object f:fields)selected.add((String)f);}
  Map<String,Object> data=new LinkedHashMap<>();Set<String> protectedContainers=new HashSet<>();
  for(var e:value.entrySet()){
   String key=e.getKey();Object item=e.getValue();
   if(evidence(key,item)||(!selected.isEmpty()?selected.contains(key):mode.equals("details")))data.put(key,item);
   else if(CONTAINERS.contains(key)){Object nested=evidenceTree(item,0);if(nested instanceof Map<?,?> m&&!m.isEmpty()||nested instanceof List<?> l&&!l.isEmpty()){data.put(key,nested);protectedContainers.add(key);}}
  }
  // Explicit fields/details mode must not make nested failures removable by the byte limiter.
  for(String key:CONTAINERS)if(data.containsKey(key)){Object nested=evidenceTree(data.get(key),0);if(nested instanceof Map<?,?> m&&!m.isEmpty()||nested instanceof List<?> l&&!l.isEmpty())protectedContainers.add(key);}
  List<String> omitted=new ArrayList<>();for(String k:value.keySet())if(!data.containsKey(k)||!Objects.equals(data.get(k),value.get(k)))omitted.add(k);
  Map<String,Object> meta=new LinkedHashMap<>();meta.put("view",mode);meta.put("truncated",false);Map<String,Object> out=new LinkedHashMap<>();out.put("data",data);out.put("response",meta);
  boolean needsDetails=!omitted.isEmpty()||bytes(out)>max;String id=needsDetails?remember(value):null;if(id!=null){meta.put("details_id",id);meta.put("details_tool","edt_result");}else if(needsDetails){meta.put("details_available",false);meta.put("reason","size_limit_use_original_status_or_pagination");}
  while(bytes(out)>max&&!data.isEmpty()){
   String largest=data.keySet().stream().filter(k->!evidence(k,data.get(k))&&!protectedContainers.contains(k)).max(Comparator.comparingInt(k->bytes(data.get(k)))).orElse(null);
   if(largest==null)return detailsRequired(id,mode);data.remove(largest);omitted.add(largest);meta.put("truncated",true);
  }
  if(!omitted.isEmpty()){meta.put("omitted_fields",omitted);meta.put("complete",false);}else meta.put("complete",true);
  // Metadata also counts toward the caller's wire budget.
  while(bytes(out)>max){meta.remove("omitted_fields");meta.put("omitted_field_count",omitted.size());String largest=data.keySet().stream().filter(k->!evidence(k,data.get(k))&&!protectedContainers.contains(k)).max(Comparator.comparingInt(k->bytes(data.get(k)))).orElse(null);if(largest==null)return detailsRequired(id,mode);data.remove(largest);omitted.add(largest);meta.put("truncated",true);meta.put("complete",false);}
  return out;
 }
 private static Map<String,Object> detailsRequired(String id,String mode){Map<String,Object> meta=new LinkedHashMap<>();meta.put("view",mode);meta.put("truncated",true);meta.put("complete",false);if(id!=null){meta.put("details_id",id);meta.put("details_tool","edt_result");}else{meta.put("details_available",false);meta.put("reason","size_limit_use_original_status_or_pagination");}return Map.of("data",Map.of("state","details_required"),"response",meta);}
 private static int bytes(Object o){return JSON.toJson(o).getBytes(StandardCharsets.UTF_8).length;}
 private static synchronized String remember(Object value){prune();String text=JSON.toJson(value);int n=text.getBytes(StandardCharsets.UTF_8).length;if(n>2*1024*1024)return null;while(CACHE.size()>=64||storedBytes+n>16*1024*1024)remove(CACHE.keySet().iterator().next());String id="detail-"+UUID.randomUUID();CACHE.put(id,new Entry(text,n,Instant.now().plusSeconds(1800)));storedBytes+=n;return id;}
 private static void remove(String id){var e=CACHE.remove(id);if(e!=null)storedBytes-=e.bytes;}
 private static void prune(){var ids=CACHE.entrySet().stream().filter(e->e.getValue().expires.isBefore(Instant.now())).map(Map.Entry::getKey).toList();ids.forEach(AgentResponses::remove);}
 public static synchronized Map<String,Object> read(String id,int offset,int limit){prune();var entry=CACHE.get(id);if(entry==null)throw new IllegalArgumentException("Details expired or unavailable after restart");String text=entry.text;if(offset<0||offset>text.length()||limit<2||limit>8000||(offset>0&&offset<text.length()&&Character.isLowSurrogate(text.charAt(offset))))throw new IllegalArgumentException("Invalid details range");int end=Math.min(text.length(),offset+limit);if(end<text.length()&&end>offset&&Character.isHighSurrogate(text.charAt(end-1)))end--;return Map.of("details_id",id,"text",text.substring(offset,end),"offset",offset,"next_offset",end,"has_more",end<text.length(),"format","json_text","offset_unit","utf16","observation_only",true);}
}
