// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;
public class AgentBriefTest {
    private Map<String,Object> run() {
        Map<String,Object> r=new LinkedHashMap<>();r.put("run_id","dev-test");r.put("phase","awaiting_visual_review");r.put("terminal",false);r.put("event_cursor",45L);
        List<Map<String,Object>> events=new ArrayList<>();for(long i=1;i<=45;i++)events.add(Map.of("sequence",i,"phase","running","payload","SECRET"));r.put("events",events);
        r.put("managed",Map.of("Progress",Map.of("CurrentStep","Open form","Checkpoint",Map.of("Id","gate","RunId","va","Screenshot","a.png","ScreenshotSha256","hash","code","SECRET")),"Report",Map.of("Tests",2,"Failures",1,"Errors",0,"Log","SECRET"),"Credentials","SECRET"));
        r.put("debug",Map.of("variables","SECRET"));return r;
    }
    @Test public void pageCursorDoesNotSkipRemainingEvents(){var r=run();var first=AgentBrief.run(r,0);assertEquals(20L,first.get("event_cursor"));assertEquals(true,first.get("has_more_events"));assertEquals(45L,first.get("latest_cursor"));var second=AgentBrief.run(r,20);assertEquals(40L,second.get("event_cursor"));var last=AgentBrief.run(r,40);assertEquals(45L,last.get("event_cursor"));assertEquals(false,last.get("has_more_events"));assertEquals(5,((List<?>)last.get("events")).size());}
    @Test public void unchangedPollIsSmallAndPreservesCheckpointAndFailureCounts(){var r=AgentBrief.run(run(),45);assertEquals(List.of(),r.get("events"));assertEquals("hash",((Map<?,?>)r.get("checkpoint")).get("ScreenshotSha256"));assertEquals(1,((Map<?,?>)r.get("report")).get("Failures"));assertEquals("edt_development_run_continue",r.get("next_tool"));assertFalse(r.toString().contains("SECRET"));}
    @Test public void invalidCursorsAreRejected(){assertThrows(IllegalArgumentException.class,()->AgentBrief.run(run(),-1));assertThrows(IllegalArgumentException.class,()->AgentBrief.run(run(),46));}
    @Test public void retainedEventGapIsExplicit(){var r=run();r.put("events",List.of(Map.of("sequence",45L,"phase","running")));assertEquals(true,AgentBrief.run(r,0).get("events_gap"));assertEquals(false,AgentBrief.run(r,44).get("events_gap"));}
    @Test public void debuggerAndRestartHaveExplicitNextSteps(){var r=run();r.put("phase","paused_by_debugger");assertEquals("edt_debug_inspect",AgentBrief.run(r,0).get("next_tool"));r.put("phase","interrupted_by_restart");assertEquals("edt_development_run_status",AgentBrief.run(r,0).get("next_tool"));}
    @Test public void compactOperationNeverDumpsResult(){var r=AgentBrief.operation(Map.of("state","FAILED","error","update failed","result",Map.of("BSL","SECRET"),"progress",Map.of("stage","verify","history",List.of("SECRET"))));assertEquals("update failed",r.get("error"));assertEquals(true,r.get("result_available"));assertFalse(r.toString().contains("SECRET"));}
    @Test public void shortEntryRejectsIncompleteOrConflictingRequestsBeforeStartingEdt()throws Exception {
        var tool=new com.codepilot1c.core.tools.development.EdtCheckTool();
        assertFalse(tool.execute(Map.of("action","start","project","Fixture","idempotency_key","k")).get().isSuccess());
        assertFalse(tool.execute(Map.of("action","start","project","Fixture","idempotency_key","k","update_db",false,"run_id","foreign")).get().isSuccess());
        assertFalse(tool.execute(Map.of("action","status","run_id","dev-x","update_db",true)).get().isSuccess());
        assertFalse(tool.execute(Map.of("action","cancel","run_id","dev-x","after_cursor",0)).get().isSuccess());
        assertFalse(tool.execute(Map.of("action","status","run_id","dev-x","after_cursor",-1)).get().isSuccess());
    }
    @Test public void largeErrorAndStepAreBoundedWithoutBreakingUnicode(){
        var input=run();input.put("error","x".repeat(1023)+"\uD83D\uDE00"+"suffix");
        input.put("managed",Map.of("Progress",Map.of("CurrentStep","x".repeat(10000))));
        var brief=AgentBrief.run(input,45);assertEquals(true,brief.get("error_truncated"));
        assertEquals(1023,brief.get("error").toString().length());
        var progress=(Map<?,?>)brief.get("progress");assertEquals(320,progress.get("CurrentStep").toString().length());assertEquals(true,progress.get("CurrentStep_truncated"));
        assertTrue(input.get("error").toString().endsWith("suffix"));
        var operation=AgentBrief.operation(Map.of("error","e".repeat(5000),"progress",Map.of("message","m".repeat(5000))));
        assertEquals(1024,operation.get("error").toString().length());assertEquals(true,operation.get("error_truncated"));
    }
}
