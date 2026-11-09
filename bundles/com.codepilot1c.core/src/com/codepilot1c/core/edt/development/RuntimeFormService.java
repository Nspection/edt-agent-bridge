// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import com.google.gson.*;

/** Bounded semantic observations; intentionally preserves Vanessa text instead of inventing a DOM. */
public final class RuntimeFormService {
 public interface Backend {
  Map<String,Object> observe(String endpoint,String tool,Map<String,Object> args)throws Exception;
  Map<String,Object> action(String endpoint,String tool,Map<String,Object> args,String key,String window)throws Exception;
  Map<String,Object> catalog(String endpoint)throws Exception;
  default Map<String,Object> actionBound(String endpoint,String tool,Map<String,Object> args,String key,String window,Map<String,Object> proof)throws Exception{throw new IllegalStateException("Bound action unavailable");}
 }
 public static final class NativeBackend implements Backend {
  public Map<String,Object> observe(String e,String t,Map<String,Object>a)throws Exception{return VanessaMcpBridge.getInstance().observe(e,null,t,a);}
  public Map<String,Object> action(String e,String t,Map<String,Object>a,String k,String w)throws Exception{return VanessaMcpBridge.getInstance().actionGuarded(e,t,a,k,w);}
  public Map<String,Object> actionBound(String e,String t,Map<String,Object>a,String k,String w,Map<String,Object> proof)throws Exception{return VanessaMcpBridge.getInstance().actionGuarded(e,t,a,k,w,proof);}
  public Map<String,Object> catalog(String e)throws Exception{return VanessaMcpBridge.getInstance().catalog(e,null);}
 }
 private static final Gson JSON=new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
 private final Path root;private final Backend backend;
 public RuntimeFormService(Path root,Backend backend)throws Exception{this.root=root;this.backend=backend;Files.createDirectories(root);}
 public static RuntimeFormService workspace()throws Exception{return new RuntimeFormService(org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/form-snapshots"),new NativeBackend());}
 public static String endpoint(Map<String,Object>a){return VanessaMcpBridge.localEndpoint((String)a.getOrDefault("endpoint","http://127.0.0.1:9874/mcp"));}
 public static String required(Map<String,Object>a,String key){Object x=a.get(key);if(!(x instanceof String s)||s.isBlank()||s.length()>4096)throw new IllegalArgumentException(key+" is required (max 4096)");return s;}
 private static void allowed(Map<String,Object>a,String...keys){Set<String> set=new HashSet<>(List.of(keys));for(String k:a.keySet())if(!set.contains(k))throw new IllegalArgumentException("Unexpected parameter: "+k);}
 public static String windowText(Map<String,Object> value){
  if(!Boolean.TRUE.equals(value.get("success")))throw new IllegalStateException("Active window is unavailable; observe the TestClient first");
  if(value.get("content") instanceof List<?> rows && rows.size()==1 && rows.get(0) instanceof Map<?,?> row && "text".equals(row.get("type")) && row.get("text") instanceof String s && !s.isBlank())return s.strip();
  throw new IllegalStateException("Unsupported active-window response; use edt_runtime_observe");
 }
 private void supports(String e,String tool,Map<String,Object>a)throws Exception{
  Map<String,Object> catalog=backend.catalog(e);Object tools=catalog.get("tools");
  if(tools instanceof List<?> list)for(Object item:list)if(item instanceof Map<?,?> row&&tool.equals(row.get("name"))){
   Object raw=row.get("schema");JsonElement schema=raw instanceof JsonElement x?x:JSON.toJsonTree(raw);
   if(!schema.isJsonObject())throw new IllegalStateException("Missing Vanessa schema");JsonObject object=schema.getAsJsonObject();
   if(object.has("properties"))for(String k:a.keySet())if(!object.getAsJsonObject("properties").has(k))throw new IllegalStateException("Installed Vanessa lacks parameter "+k+"; use edt_runtime_catalog");
   if(object.has("required"))for(JsonElement k:object.getAsJsonArray("required"))if(!a.containsKey(k.getAsString()))throw new IllegalStateException("Installed Vanessa requires "+k.getAsString());return;
  }
  throw new IllegalStateException("Installed Vanessa lacks "+tool+"; use edt_runtime_catalog");
 }
 public Map<String,Object> capture(Map<String,Object>a)throws Exception{
  allowed(a,"endpoint","view","element","table","group","max_depth","only_visible","offset","limit");String e=endpoint(a),view=(String)a.getOrDefault("view","elements"),tool;Map<String,Object> args=new LinkedHashMap<>();
  switch(view){
   case "elements","values","messages"->{tool="get_form_analysis";args.put("format",view.equals("values")?"gherkin":view);if(view.equals("elements")){args.put("compact",true);args.put("max_depth",a.getOrDefault("max_depth",3));args.put("only_visible",a.getOrDefault("only_visible",false));for(String k:List.of("table","group"))if(a.containsKey(k))args.put("only_"+k,required(a,k));}else for(String k:List.of("table","group","max_depth","only_visible"))if(a.containsKey(k))throw new IllegalArgumentException(k+" only applies to elements");}
   case "element"->{tool="get_form_element_data";args.put("element_name",required(a,"element"));}
   case "windows"->tool="get_window_list_testclient";
   default->throw new IllegalArgumentException("Unknown form view");
  }
  if(!view.equals("element")&&a.containsKey("element"))throw new IllegalArgumentException("element only applies to element view");
  if(!view.equals("elements"))for(String k:List.of("table","group","max_depth","only_visible"))if(a.containsKey(k))throw new IllegalArgumentException(k+" only applies to elements");
  if(a.containsKey("max_depth")){int depth=integer(a,"max_depth",3);if(depth<1||depth>20)throw new IllegalArgumentException("max_depth must be 1..20");}
  if(a.containsKey("only_visible") && !(a.get("only_visible") instanceof Boolean))throw new IllegalArgumentException("only_visible must be boolean");
  pageArguments(a);supports(e,tool,args);String before=view.equals("windows")?null:windowText(backend.observe(e,"get_active_window_data",Map.of("type","window_caption")));
  Map<String,Object> result=backend.observe(e,tool,args);if(!Boolean.TRUE.equals(result.get("success")))throw new IllegalStateException("Vanessa observation failed; use edt_runtime_observe for full diagnostics");
  String after=view.equals("windows")?null:windowText(backend.observe(e,"get_active_window_data",Map.of("type","window_caption")));if(before!=null&&!before.equals(after))throw new IllegalStateException("Active window changed during capture; observe again");
  if(!(result.get("content") instanceof List<?> content)||content.isEmpty())throw new IllegalStateException("Vanessa returned no supported form content");
  for(Object item:content)if(!(item instanceof Map<?,?> row)||!("text".equals(row.get("type"))&&row.get("text") instanceof String)&&!("json".equals(row.get("type"))&&row.get("data")!=null))throw new IllegalStateException("Unsupported Vanessa form content");
  boolean segmented=content.stream().anyMatch(item->{var row=(Map<?,?>)item;String text="text".equals(row.get("type"))?(String)row.get("text"):JSON.toJson(row.get("data"));return Arrays.stream(text.split("\\R",-1)).anyMatch(line->line.length()>1200);});
  List<String> lines=new ArrayList<>();if(result.get("content") instanceof List<?> list)for(Object item:list)if(item instanceof Map<?,?> row){String text="text".equals(row.get("type"))?String.valueOf(row.get("text")):"json".equals(row.get("type"))?JSON.toJson(row.get("data")):"";for(String line:text.split("\\R",-1)){for(int pos=0;pos<line.length();){int end=Math.min(pos+1200,line.length());if(end<line.length()&&Character.isHighSurrogate(line.charAt(end-1)))end--;lines.add(line.substring(pos,end));pos=end;}if(line.isEmpty())lines.add("");}}
  String id="form-"+UUID.randomUUID();Map<String,Object> doc=new LinkedHashMap<>();doc.put("snapshot_id",id);doc.put("text_segmented",segmented);doc.put("endpoint",e);doc.put("view",view);if(before!=null)doc.put("window",before);doc.put("captured_at",Instant.now().toString());doc.put("lines",lines);doc.put("source_tool",tool);Map<String,Object> query=new LinkedHashMap<>(a);query.keySet().removeAll(Set.of("endpoint","offset","limit"));doc.put("query",query);String text=JSON.toJson(doc);if(text.getBytes(StandardCharsets.UTF_8).length>2*1024*1024)throw new IllegalStateException("Form exceeds snapshot limit; use group/table/max_depth");Files.writeString(root.resolve(id+".json"),text,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);return page(doc,integer(a,"offset",0),integer(a,"limit",30));
 }
 public Map<String,Object> details(Map<String,Object>a)throws Exception{allowed(a,"snapshot_id","offset","limit");pageArguments(a);return page(load(required(a,"snapshot_id")),integer(a,"offset",0),integer(a,"limit",30));}
 @SuppressWarnings("unchecked") private Map<String,Object> load(String id)throws Exception{if(!id.matches("form-[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid snapshot ID");Path file=root.resolve(id+".json");if(Files.isSymbolicLink(file)||Files.size(file)>2*1024*1024)throw new IllegalArgumentException("Invalid snapshot file");return JSON.fromJson(Files.readString(file,StandardCharsets.UTF_8),LinkedHashMap.class);}
 public Map<String,Object> evidence(String id)throws Exception{return AgentBrief.select(load(id),"snapshot_id","captured_at","window","view","source_tool");}
 private static int integer(Map<String,Object>a,String k,int fallback){if(!a.containsKey(k))return fallback;try{return new java.math.BigDecimal(a.get(k).toString()).intValueExact();}catch(Exception e){throw new IllegalArgumentException(k+" must be an integer");}}
 private static void pageArguments(Map<String,Object>a){if(integer(a,"offset",0)<0||integer(a,"limit",30)<1||integer(a,"limit",30)>100)throw new IllegalArgumentException("Invalid page");}
 private static Map<String,Object> page(Map<String,Object>doc,int offset,int limit){List<?> rows=(List<?>)doc.get("lines");int from=Math.min(offset,rows.size()),to=(int)Math.min((long)offset+limit,rows.size());Map<String,Object> out=AgentBrief.select(doc,"snapshot_id","captured_at","window","view","source_tool","text_segmented");out.put("format","vanessa_semantic_text");out.put("items",rows.subList(from,to));out.put("total",rows.size());out.put("offset",offset);out.put("has_more",to<rows.size());out.put("next_offset",to);out.put("next_tool","edt_form_state");out.put("live_state",false);return out;}
 /** Compare identical observation scopes; returns only bounded changed lines and a snapshot reference. */
 public Map<String,Object> delta(Map<String,Object>a)throws Exception {
  allowed(a,"endpoint","baseline_snapshot_id","offset","limit");pageArguments(a);String baseline=required(a,"baseline_snapshot_id");var before=load(baseline);
  String e=a.containsKey("endpoint")?endpoint(a):(String)before.get("endpoint");if(!e.equals(before.get("endpoint")))throw new IllegalArgumentException("Baseline belongs to another endpoint");
  if(!(before.get("query") instanceof Map<?,?> scope))throw new IllegalArgumentException("Capture a new scoped baseline");
  Map<String,Object> query=new LinkedHashMap<>();scope.forEach((k,v)->query.put(String.valueOf(k),v));query.put("endpoint",e);query.put("limit",1);
  var fresh=capture(query);return deltaPage(before,load((String)fresh.get("snapshot_id")),integer(a,"offset",0),integer(a,"limit",20));
 }
 static Map<String,Object> deltaPage(Map<String,Object> before,Map<String,Object> after,int offset,int limit){
  List<?> old=(List<?>)before.get("lines"),now=(List<?>)after.get("lines");int prefix=0,suffix=0;
  while(prefix<Math.min(old.size(),now.size())&&Objects.equals(old.get(prefix),now.get(prefix)))prefix++;
  while(suffix<Math.min(old.size(),now.size())-prefix&&Objects.equals(old.get(old.size()-1-suffix),now.get(now.size()-1-suffix)))suffix++;
  int total=now.size()-prefix-suffix,from=Math.min(offset,total),to=(int)Math.min((long)from+limit,total);
  var out=new LinkedHashMap<String,Object>();out.put("baseline_snapshot_id",before.get("snapshot_id"));out.put("snapshot_id",after.get("snapshot_id"));out.put("changed",!old.equals(now)||!Objects.equals(before.get("window"),after.get("window")));out.put("window_changed",!Objects.equals(before.get("window"),after.get("window")));out.put("window",after.getOrDefault("window",""));
  out.put("start_line",prefix);out.put("delete_count",old.size()-prefix-suffix);out.put("insert_count",total);out.put("inserted_lines",now.subList(prefix+from,prefix+to));out.put("offset",offset);out.put("has_more",to<total);out.put("next_offset",to<total?to:total);out.put("delta_exact",from==0&&to>=total);out.put("delta_is_summary",from>0||to<total);out.put("postcondition_verified",false);return out;
 }
 public Map<String,Object> changed(String baseline,String endpoint)throws Exception {
  var before=load(baseline);if(!Objects.equals(endpoint,before.get("endpoint")))throw new IllegalArgumentException("Snapshot belongs to another endpoint");
  if(!(before.get("query") instanceof Map<?,?> scope))throw new IllegalArgumentException("Snapshot predates scope recording; capture a new baseline");
  Map<String,Object> args=new LinkedHashMap<>();scope.forEach((k,v)->args.put(String.valueOf(k),v));args.put("endpoint",endpoint);args.put("limit",1);
  var page=capture(args);var after=load((String)page.get("snapshot_id"));boolean changed=!Objects.equals(before.get("window"),after.get("window"))||!Objects.equals(before.get("lines"),after.get("lines"));
  List<?> old=(List<?>)before.get("lines"),now=(List<?>)after.get("lines");Set<?> oldLines=new HashSet<>(old),newLines=new HashSet<>(now);
  var out=new LinkedHashMap<String,Object>();out.put("changed",changed);out.put("baseline_snapshot_id",baseline);out.put("snapshot_id",page.get("snapshot_id"));out.put("window",after.getOrDefault("window",""));out.put("added_lines",now.stream().filter(x->!oldLines.contains(x)).limit(20).toList());out.put("removed_lines",old.stream().filter(x->!newLines.contains(x)).limit(20).toList());out.put("delta_is_summary",true);out.put("postcondition_verified",false);return out;
 }
 public Map<String,Object> act(Map<String,Object>a)throws Exception{
  allowed(a,"endpoint","action","element","table","value","row_criteria","idempotency_key","expected_window","client_binding");String e=endpoint(a),action=required(a,"action"),window=required(a,"expected_window"),key=required(a,"idempotency_key");if(key.length()>200)throw new IllegalArgumentException("idempotency_key max 200");
  Map<String,Object> args=new LinkedHashMap<>();String va=switch(action){case "input"->"input_text";case "choose_value"->"set_value";case "command"->"click_button";case "tab"->"switch_tab";case "row"->"navigate_to_row";case "check"->"set_checkbox";case "uncheck"->"unset_checkbox";default->throw new IllegalArgumentException("Unsupported form action");};args.put("action",va);
  if(action.equals("row")){args.put("table_name",required(a,"table"));args.put("row_criteria",required(a,"row_criteria"));if(a.containsKey("element"))throw new IllegalArgumentException("row uses table and row_criteria");}else{args.put("element_name",required(a,"element"));if(a.containsKey("row_criteria"))throw new IllegalArgumentException("row_criteria only applies to row");if(a.containsKey("table")){if(!Set.of("input","choose_value","command").contains(action))throw new IllegalArgumentException("table does not apply to this action");args.put("table_name",required(a,"table"));}}
  if(Set.of("input","choose_value").contains(action)){if(!(a.get("value") instanceof String))throw new IllegalArgumentException("value must be text (empty allowed)");if(((String)a.get("value")).length()>4096)throw new IllegalArgumentException("value too long");if(action.equals("input")&&((String)a.get("value")).isEmpty())args.put("action","clear_field");else{if(action.equals("choose_value")&&((String)a.get("value")).isBlank())throw new IllegalArgumentException("choose_value requires a nonempty representation");args.put("value",a.get("value"));}}else if(a.containsKey("value"))throw new IllegalArgumentException("value only applies to input/choose_value");
  var out=new LinkedHashMap<>(a.containsKey("client_binding")?backend.actionBound(e,"manage_form_elements",args,key,window,TaskSessionService.object(a.get("client_binding"))):backend.action(e,"manage_form_elements",args,key,window));out.put("postcondition_verified",false);out.put("verify_with","edt_form_state");return out;
 }
}
