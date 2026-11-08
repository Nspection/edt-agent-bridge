// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
import com.google.gson.*;
/** Read-only aggregation: never advances a data set or claims sources match a historical run. */
public final class TaskReportService {
 public interface Backend {
  Map<String,Object> changes(String project,List<String> files,String baseline,String run)throws Exception;
  Map<String,Object> data(String project,String id)throws Exception;
  Map<String,Object> form(String id)throws Exception;
 }
 public static class NativeBackend implements Backend {
  public Map<String,Object> changes(String p,List<String>f,String b,String r)throws Exception{return ChangeCheckService.workspace().changes(p,f,b,r);}
  public Map<String,Object> data(String p,String id)throws Exception{var base=new com.codepilot1c.core.edt.runtime.EdtRuntimeService().resolveDefaultInfobase(p);if(base==null)throw new IllegalArgumentException("No project application");var path=org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/test-data");var value=new TestDataLedger(path).evidence(id,new TestDataLedger.Target(p,base.getUuid().toString()));return new Gson().fromJson(value,LinkedHashMap.class);}
  public Map<String,Object> form(String id)throws Exception{return RuntimeFormService.workspace().evidence(id);}
 }
 private final Backend backend;public TaskReportService(Backend backend){this.backend=backend;}
 public Map<String,Object> report(String project,List<String>files,String baseline,String run,List<String>dataIds,List<String>formIds)throws Exception{
  if(project==null||project.isBlank()||dataIds.size()>10||formIds.size()>10)throw new IllegalArgumentException("Explicit project; max 10 data journals and 10 form snapshots");
  // Bad project/run/baseline is an error, not a partial clean report.
  var check=backend.changes(project,files,baseline,run);Map<String,Object> out=new LinkedHashMap<>();out.put("project",project);out.put("changes",check);out.put("runtime_verified",false);out.put("scenario_sources_match","not_verified");out.put("writes_database",false);
  List<Map<String,Object>>data=new ArrayList<>(),forms=new ArrayList<>();List<String>unknown=new ArrayList<>();
  for(String id:dataIds){try{var entry=backend.data(project,id);var row=AgentBrief.select(entry,"data_run_id","state","revision","total","pending_creations","pending_cleanups","data_may_remain","database_cleanup_verified","cleanup_outcomes");row.put("details_tool","edt_test_data");data.add(row);}catch(Exception e){data.add(Map.of("data_run_id",id,"state","unknown","details_tool","edt_test_data"));unknown.add("test_data:"+id);}}
  for(String id:formIds){try{var row=new LinkedHashMap<>(backend.form(id));row.put("project_binding_verified",false);row.put("visually_reviewed",false);row.put("details_tool","edt_form_state");forms.add(row);}catch(Exception e){forms.add(Map.of("snapshot_id",id,"state","unknown"));unknown.add("form:"+id);}}
  out.put("test_data",data);out.put("form_observations",forms);out.put("unknown_sections",unknown);out.put("cleanup_scope","Only explicitly supplied journals; host/project/database/launcher cleanup not certified");out.put("next_tool","edt_check");return out;
 }
}
