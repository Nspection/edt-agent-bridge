// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;

/** Separates observational preflight from the transport's durable claim boundary. */
final class NativeExecutionGate {
    interface History {Optional<Map<String,Object>> previous()throws Exception;}
    interface Dispatch {Map<String,Object> send()throws Exception;}
    interface Preflight {Dispatch validate()throws Exception;}
    static boolean notDispatched(Map<String,Object> result){
        return result!=null&&Boolean.FALSE.equals(result.get("success"))&&Boolean.TRUE.equals(result.get("terminal"))&&Boolean.FALSE.equals(result.get("outcome_unknown"))&&"not_dispatched".equals(result.get("execution_state"))&&!result.containsKey("request_id")&&Set.of("failed","blocked","awaiting_consent").contains(Objects.toString(result.get("state"),""));
    }
    static Map<String,Object> rejectionProof(Map<String,Object> result){
        if(!notDispatched(result))throw new IllegalArgumentException("Exact Native pre-dispatch rejection required");
        return Map.of("success",false,"terminal",true,"outcome_unknown",false,"execution_state","not_dispatched","state","failed");
    }
    static List<String> readiness(Map<String,Object> observed,boolean consent,boolean baseCurrent,boolean refresh,boolean cleanup){
        var blockers=new ArrayList<String>();if(!consent)blockers.add("consent_or_startup_question");if(!baseCurrent)blockers.add("base_binding_changed");if(!"running".equals(observed.get("state")))blockers.add("host_not_running");if(!Boolean.TRUE.equals(TaskSessionService.object(observed.get("compatibility")).get("compatible")))blockers.add("native_refresh_required");if(refresh)blockers.add("refresh_in_progress");if(cleanup)blockers.add("cleanup_in_progress");return List.copyOf(blockers);
    }
    private NativeExecutionGate(){}
    static Map<String,Object> execute(History history,Preflight preflight)throws Exception {
        var previous=history.previous();if(previous.isPresent())return previous.orElseThrow();
        return execute(preflight);
    }
    static Map<String,Object> execute(Preflight preflight)throws Exception {
        final Dispatch dispatch;
        try{dispatch=preflight.validate();}
        catch(NativeHostService.CompatibilityRequired blocked){
            var result=new LinkedHashMap<>(blocked.result());result.put("success",false);result.put("terminal",true);result.put("outcome_unknown",false);return result;
        }
        catch(RuntimeConsentPolicy.Required required){
            var result=new LinkedHashMap<>(required.result());result.put("success",false);result.put("terminal",true);result.put("execution_state","not_dispatched");result.put("outcome_unknown",false);return result;
        }
        catch(Exception rejected){return Map.of("success",false,"terminal",true,"state","failed","execution_state","not_dispatched","outcome_unknown",false,"reason","native_execution_preflight_failed","error_type",rejected.getClass().getSimpleName());}
        // Any failure from send can occur after persistence. Never label it not_dispatched.
        return dispatch.send();
    }
}
