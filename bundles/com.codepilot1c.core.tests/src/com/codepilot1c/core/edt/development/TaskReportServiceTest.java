// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class TaskReportServiceTest {
 static class Backend implements TaskReportService.Backend {
  boolean missing,badRun;int reads;
  public Map<String,Object> changes(String p,List<String>f,String b,String r){if(badRun)throw new IllegalArgumentException("Foreign run");return Map.of("project",p,"new_errors","unknown_without_baseline","runtime_verified",false);}
  public Map<String,Object> data(String p,String id){reads++;if(missing)throw new IllegalStateException("Unavailable");return Map.of("data_run_id",id,"total",2,"state","closed","data_may_remain",true,"database_cleanup_verified",false,"records",List.of("private value"));}
  public Map<String,Object> form(String id){if(missing)throw new IllegalStateException("Missing");return Map.of("snapshot_id",id,"window","Fixture");}
 }
 @Test public void closedLedgerNeverImpliesCleanDatabaseAndNoRecordValuesLeak()throws Exception{var b=new Backend();var out=new TaskReportService(b).report("P",null,null,null,List.of("data"),List.of("form"));assertEquals(false,out.get("runtime_verified"));assertEquals("not_verified",out.get("scenario_sources_match"));Map<?,?>d=(Map<?,?>)((List<?>)out.get("test_data")).get(0);assertEquals(true,d.get("data_may_remain"));assertEquals(false,d.get("database_cleanup_verified"));assertFalse(d.containsKey("records"));Map<?,?>f=(Map<?,?>)((List<?>)out.get("form_observations")).get(0);assertEquals(false,f.get("visually_reviewed"));assertEquals(false,f.get("project_binding_verified"));assertEquals(1,b.reads);}
 @Test public void missingEvidenceIsUnknownNotPassed()throws Exception{var b=new Backend();b.missing=true;var out=new TaskReportService(b).report("P",null,null,null,List.of("data"),List.of("form"));assertEquals(List.of("test_data:data","form:form"),out.get("unknown_sections"));}
 @Test public void foreignRunIsFatalAndDoesNotReadLedgers(){var b=new Backend();b.badRun=true;assertThrows(IllegalArgumentException.class,()->new TaskReportService(b).report("P",null,null,"foreign",List.of("data"),List.of()));assertEquals(0,b.reads);}
}
