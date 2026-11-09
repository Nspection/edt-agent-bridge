// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.nio.file.*;
import com.google.gson.*;
public class RuntimeFormServiceTest {
 static class Backend implements RuntimeFormService.Backend {
  String window="Fixture",tool;Map<String,Object>args;int writes,reads;boolean missing,failed,changeWindow;
  public Map<String,Object> catalog(String e){List<Map<String,Object>> tools=new ArrayList<>();for(String name:List.of("get_form_analysis","get_form_element_data","get_window_list_testclient","manage_form_elements")){
   if(missing)continue;JsonObject schema=new JsonObject(),props=new JsonObject();for(String k:List.of("format","compact","max_depth","only_visible","only_table","only_group","element_name","action","table_name","row_criteria","value"))props.add(k,new JsonObject());schema.add("properties",props);tools.add(Map.of("name",name,"schema",schema));}return Map.of("tools",tools);}
  public Map<String,Object> observe(String e,String t,Map<String,Object>a){reads++;if(t.equals("get_active_window_data"))return Map.of("success",true,"content",List.of(Map.of("type","text","text",window)));tool=t;args=a;if(changeWindow)window="Other";return Map.of("success",!failed,"content",List.of(Map.of("type","text","text","line one\nline two\nline three")));}
  public Map<String,Object> action(String e,String t,Map<String,Object>a,String k,String w){writes++;tool=t;args=a;assertEquals("Fixture",w);return Map.of("job_id","job-1");}
 }
 static class Fixture implements AutoCloseable {Path root=Files.createTempDirectory("form-tools-");Backend backend=new Backend();RuntimeFormService service=new RuntimeFormService(root,backend);Fixture()throws Exception{}public void close()throws Exception{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}}
 @Test public void scopedCaptureAndDetailsDoNotRepeatRemoteObservation()throws Exception{try(var f=new Fixture()){var page=f.service.capture(Map.of("group","Main","limit",1));assertEquals("get_form_analysis",f.backend.tool);assertEquals("elements",f.backend.args.get("format"));assertEquals("Main",f.backend.args.get("only_group"));assertEquals(3,f.backend.args.get("max_depth"));assertEquals(true,page.get("has_more"));int count=f.backend.reads;var detail=f.service.details(Map.of("snapshot_id",page.get("snapshot_id"),"offset",1,"limit",2));assertEquals(List.of("line two","line three"),detail.get("items"));assertEquals(count,f.backend.reads);assertEquals(0,f.backend.writes);}}
 @Test public void windowChangeRejectsSnapshot()throws Exception{try(var f=new Fixture()){f.backend.changeWindow=true;assertThrows(IllegalStateException.class,()->f.service.capture(Map.of()));try(var files=Files.list(f.root)){assertEquals(0,files.count());}}}
 @Test public void unsupportedCapabilityDoesNotDispatch()throws Exception{try(var f=new Fixture()){f.backend.missing=true;assertThrows(IllegalStateException.class,()->f.service.capture(Map.of()));assertEquals(0,f.backend.writes);}}
 @Test public void failedRemoteObservationIsNeverCleanSnapshot()throws Exception{try(var f=new Fixture()){f.backend.failed=true;assertThrows(IllegalStateException.class,()->f.service.capture(Map.of()));}}
 @Test public void actionsUseSemanticNamesAndKeepEmptyInput()throws Exception{try(var f=new Fixture()){var result=f.service.act(Map.of("action","input","element","Name","value","","expected_window","Fixture","idempotency_key","one"));assertEquals("clear_field",f.backend.args.get("action"));assertFalse(f.backend.args.containsKey("value"));assertEquals(false,result.get("postcondition_verified"));f.service.act(Map.of("action","row","table","Items","row_criteria","Code=001","expected_window","Fixture","idempotency_key","two"));assertEquals("navigate_to_row",f.backend.args.get("action"));assertEquals("Items",f.backend.args.get("table_name"));}}
 @Test public void unsupportedOrContradictoryParametersNeverWrite()throws Exception{try(var f=new Fixture()){for(var a:List.<Map<String,Object>>of(Map.of("action","input","element","Name","expected_window","Fixture","idempotency_key","one"),Map.of("action","tab","element","Main","value","bad","expected_window","Fixture","idempotency_key","one"),Map.of("action","click","element","Save","expected_window","Fixture","idempotency_key","one"),Map.of("action","command","element","Save","idempotency_key","one")))assertThrows(IllegalArgumentException.class,()->f.service.act(a));assertEquals(0,f.backend.writes);}}
 @Test public void pathTraversalAndUnboundedPagesRejected()throws Exception{try(var f=new Fixture()){assertThrows(IllegalArgumentException.class,()->f.service.details(Map.of("snapshot_id","../secret")));assertThrows(IllegalArgumentException.class,()->f.service.capture(Map.of("limit",101)));assertThrows(IllegalArgumentException.class,()->f.service.capture(Map.of("max_depth",0)));assertThrows(IllegalArgumentException.class,()->f.service.capture(Map.of("view","values","table","Items")));}}
 @Test public void windowGuardRejectsUnstructuredOrFailedIdentity(){assertThrows(IllegalStateException.class,()->RuntimeFormService.windowText(Map.of("success",false)));assertThrows(IllegalStateException.class,()->RuntimeFormService.windowText(Map.of("success",true,"content",List.of(Map.of("type","json","data",Map.of("window","Fixture"))))));}
 @Test public void windowsListDoesNotRequireAnActiveWindow()throws Exception{try(var f=new Fixture()){var result=f.service.capture(Map.of("view","windows"));assertEquals(1,f.backend.reads);assertFalse(result.containsKey("window"));}}
 @Test public void terminalActionRetryDoesNotRequireCatalog()throws Exception{try(var f=new Fixture()){f.backend.missing=true;var result=f.service.act(Map.of("action","command","element","Save","expected_window","Fixture","idempotency_key","cached"));assertEquals("job-1",result.get("job_id"));assertEquals(1,f.backend.writes);}}
 @Test public void whitespaceChoiceIsRejectedBeforeDispatch()throws Exception{try(var f=new Fixture()){assertThrows(IllegalArgumentException.class,()->f.service.act(Map.of("action","choose_value","element","Name","value","  ","expected_window","Fixture","idempotency_key","one")));assertEquals(0,f.backend.writes);}}
 @Test public void changeWaitKeepsScopeAndRejectsAnotherEndpoint()throws Exception{try(var f=new Fixture()){
  String endpoint="http://127.0.0.1:9874/mcp";String baseline=(String)f.service.capture(Map.of("endpoint",endpoint,"group","Main","limit",1)).get("snapshot_id");
  assertEquals(false,f.service.changed(baseline,endpoint).get("changed"));assertEquals("Main",f.backend.args.get("only_group"));
  f.backend.window="New window";var delta=f.service.changed(baseline,endpoint);assertEquals(true,delta.get("changed"));assertEquals(true,delta.get("delta_is_summary"));assertEquals(false,delta.get("postcondition_verified"));
  assertThrows(IllegalArgumentException.class,()->f.service.changed(baseline,"http://127.0.0.1:9875/mcp"));assertEquals(0,f.backend.writes);
 }}

 @Test public void missingOrUnsupportedContentNeverCreatesAnEmptySnapshot()throws Exception {
  for(Map<String,Object> response:List.<Map<String,Object>>of(Map.of("success",true),Map.of("success",true,"content",List.of()),Map.of("success",true,"content",List.of(Map.of("type","image","data","unsupported")))))try(var f=new Fixture()){
   RuntimeFormService.Backend backend=new Backend(){public Map<String,Object> observe(String e,String t,Map<String,Object>a){if(t.equals("get_active_window_data"))return super.observe(e,t,a);return response;}};
   assertThrows(IllegalStateException.class,()->new RuntimeFormService(f.root,backend).capture(Map.of("view","messages")));try(var files=Files.list(f.root)){assertEquals(0L,files.count());}
  }
 }
 @Test public void artificialLineChunksAreExplicitlyMarked()throws Exception{try(var f=new Fixture()){
  var backend=new Backend(){public Map<String,Object> observe(String e,String t,Map<String,Object>a){if(t.equals("get_active_window_data"))return super.observe(e,t,a);return Map.of("success",true,"content",List.of(Map.of("type","text","text","A".repeat(1198)+"TARGET")));}};
  var result=new RuntimeFormService(f.root,backend).capture(Map.of("view","element","element","Name","limit",100));assertEquals(true,result.get("text_segmented"));assertEquals(false,result.get("has_more"));
 }}

 @Test public void exactDeltaPreservesDuplicatesAndOrder(){
  var before=Map.<String,Object>of("snapshot_id","old","window","W","lines",List.of("A","same","same","Z"));var after=Map.<String,Object>of("snapshot_id","new","window","W","lines",List.of("A","same","new","Z"));
  var d=RuntimeFormService.deltaPage(before,after,0,20);assertEquals(2,d.get("start_line"));assertEquals(1,d.get("delete_count"));assertEquals(List.of("new"),d.get("inserted_lines"));assertEquals(true,d.get("delta_exact"));assertEquals(false,d.get("has_more"));
  var removed=RuntimeFormService.deltaPage(before,Map.of("snapshot_id","n","window","W","lines",List.of("A","same","Z")),0,20);assertEquals(1,removed.get("delete_count"));assertEquals(List.of(),removed.get("inserted_lines"));assertEquals(true,removed.get("changed"));
 }
 @Test public void deltaReusesBaselineScopeAndRejectsCrossEndpoint()throws Exception{try(var f=new Fixture()){
  String id=(String)f.service.capture(Map.of("group","Main")).get("snapshot_id");var d=f.service.delta(Map.of("baseline_snapshot_id",id));assertEquals(false,d.get("changed"));assertEquals("Main",f.backend.args.get("only_group"));
  assertThrows(IllegalArgumentException.class,()->f.service.delta(Map.of("baseline_snapshot_id",id,"endpoint","http://127.0.0.1:9998/mcp")));
 }}
 @Test public void pagedDeltaNeverClaimsFullPatch(){var a=Map.<String,Object>of("snapshot_id","a","window","W","lines",List.of("old"));var b=Map.<String,Object>of("snapshot_id","b","window","W","lines",List.of("one","two"));assertEquals(false,RuntimeFormService.deltaPage(a,b,0,1).get("delta_exact"));assertEquals(false,RuntimeFormService.deltaPage(a,b,1,20).get("delta_exact"));assertEquals(true,RuntimeFormService.deltaPage(a,b,1,20).get("delta_is_summary"));assertEquals(true,RuntimeFormService.deltaPage(a,b,0,20).get("delta_exact"));}
}
