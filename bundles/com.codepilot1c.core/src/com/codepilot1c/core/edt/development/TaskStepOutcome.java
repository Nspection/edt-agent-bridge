// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;

/** Job delivery and domain completion are separate facts. Unknown always wins. */
public final class TaskStepOutcome {
    private TaskStepOutcome() {}
    public static boolean requiresCollection(Map<String,Object> receipt) {
        return Boolean.FALSE.equals(receipt.get("terminal")) || Boolean.TRUE.equals(receipt.get("outcome_unknown"))
            || Set.of("job_id","request_id","assertion_id","creation_id","cleanup_id","set_id","run_id").stream().anyMatch(receipt::containsKey);
    }
    public static boolean knownDomainTerminal(String tool,Map<String,Object> payload,Outcome outcome) {
        if(!outcome.done() || Boolean.TRUE.equals(payload.get("outcome_unknown")))return false;
        if(tool.equals("edt_check"))return Boolean.TRUE.equals(payload.get("terminal"))&&Set.of("completed","failed","cancelled").contains(Objects.toString(payload.get("phase"),""));
        if(!Set.of("edt_runtime_host","edt_test_data","edt_data_assert").contains(tool))return false;
        if(!Set.of("completed","failed","skipped","completed_with_skips","stopped","client_exited").contains(Objects.toString(payload.get("state"),"")))return false;
        return Boolean.TRUE.equals(payload.get("terminal")) || payload.get("execution_success") instanceof Boolean || TaskSessionService.object(payload.get("execution")).get("success") instanceof Boolean;
    }
    public record Outcome(String state,boolean done,boolean success) {}
    public static Outcome classify(String tool,String action,Map<String,Object> payload,boolean jobDone,boolean jobSuccess) {
        String state=Objects.toString(payload.get("state"),"");
        if(Boolean.TRUE.equals(payload.get("outcome_unknown")) || Set.of("outcome_unknown","interrupted_by_restart").contains(state) || "interrupted_by_restart".equals(payload.get("phase")))return new Outcome("outcome_unknown",false,false);
        if(Boolean.FALSE.equals(payload.get("consent_valid")) || state.equals("blocked") || state.startsWith("needs_") || state.startsWith("awaiting_") || state.equals("manual_required") || payload.containsKey("decision"))return new Outcome("blocked",true,false);
        if(!jobDone || Boolean.FALSE.equals(payload.get("terminal")))return new Outcome("running",false,false);
        if(!jobSuccess)return new Outcome("failed",true,false);
        if(tool.equals("edt_runtime_host")) {
            if(action.equals("execute")) {
                if(Set.of("queued","delivered","running").contains(state))return new Outcome("running",false,false);
                boolean success="completed".equals(state) && (Boolean.TRUE.equals(payload.get("execution_success")) || Boolean.TRUE.equals(TaskSessionService.object(payload.get("execution")).get("success")));
                return new Outcome(success?"completed":"failed",true,success);
            }
            if(action.equals("start")) {
                if(Set.of("prepared","starting").contains(state) || state.equals("running")&&!Boolean.TRUE.equals(payload.get("execution_allowed")))return new Outcome("running",false,false);
                boolean success=state.equals("running")&&Boolean.TRUE.equals(payload.get("execution_allowed"));return new Outcome(success?"completed":"failed",true,success);
            }
            if(action.equals("prepare")){boolean success=state.equals("prepared")&&payload.get("host_id") instanceof String;return new Outcome(success?"completed":"failed",true,success);}
        }
        if(tool.equals("edt_test_data")) {
            if(payload.containsKey("set_id")) {
                if(Set.of("ready","running","paused").contains(state))return new Outcome("blocked",false,false);
                boolean success=state.equals("completed");return new Outcome(success?"completed":"failed",true,success);
            }
            if(payload.containsKey("creation_id")){boolean success=state.equals("completed")&&Boolean.TRUE.equals(payload.get("confirmed"));return new Outcome(success?"completed":"failed",true,success);}
            if(payload.containsKey("cleanup_id")){boolean success=state.equals("completed");return new Outcome(success?"completed":"failed",true,success);}
        }
        if(Set.of("queued","pending","starting","running").contains(state))return new Outcome("running",false,false);
        boolean success=!Boolean.FALSE.equals(payload.get("success"))&&!Boolean.FALSE.equals(payload.get("execution_success"))&&!Set.of("failed","cancelled","rejected","transport_failed","initialization_failed").contains(state);
        return new Outcome(success?"completed":"failed",true,success);
    }
}
