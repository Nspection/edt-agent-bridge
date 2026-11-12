// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.util.*;
import com.google.gson.*;

/** Persistent intent -> one native request -> validated creation receipt. */
public final class TestDataCreateService {
    public interface Backend {
        TestDataLedger.Target target(String hostId,boolean requireRunning) throws Exception;
        Map<String,Object> submit(String hostId,String key,String code,Map<String,Object> parameters) throws Exception;
        Map<String,Object> result(String hostId,String requestId) throws Exception;
    }
    public static final class NativeBackend implements Backend {
        private final NativeHostService host;
        public NativeBackend()throws Exception {host=new NativeHostService();}
        public TestDataLedger.Target target(String id,boolean running)throws Exception{return host.testDataTarget(id,running);}
        public Map<String,Object> submit(String id,String key,String code,Map<String,Object> parameters)throws Exception{return host.execute(id,Map.of("idempotency_key",key,"execution_mode","execute","context","server","code",code,"parameters",parameters));}
        public Map<String,Object> result(String id,String request)throws Exception{return host.result(id,request);}
    }
    private final TestDataLedger ledger;
    private final Backend backend;
    public TestDataCreateService(TestDataLedger ledger,Backend backend){this.ledger=ledger;this.backend=backend;}
    public JsonObject create(String runId,TestDataLedger.Target target,long revision,String key,String hostId,String fqn,Map<String,Object> fields)throws Exception {
        return createInternal(runId,target,revision,key,hostId,TestDataCreatePlan.normalize(fqn,fields));
    }
    JsonObject createLinked(String runId,TestDataLedger.Target target,long revision,String key,String hostId,String fqn,Map<String,Object> fields)throws Exception {
        JsonObject spec=TestDataCreatePlan.normalizeLinked(fqn,fields);
        for(Object raw:fields.values())if(raw instanceof Map<?,?> ref && "reference".equals(ref.get("type"))){boolean found=false;for(int offset=0;offset<1000;offset+=100){JsonObject page=ledger.list(runId,target,offset,100);for(JsonElement e:page.getAsJsonArray("records")){JsonObject row=e.getAsJsonObject();if("native_host_confirmed".equals(row.get("ownership").getAsString()) && row.get("uuid").getAsString().equals(ref.get("uuid")) && row.get("metadata_fqn").getAsString().equals(ref.get("metadata_fqn")) && row.get("expected_version").getAsString().equals(ref.get("expected_version")))found=true;}if(!page.get("has_more").getAsBoolean())break;}if(!found)throw new IllegalArgumentException("Linked value must come from a confirmed record in this ledger");}
        return createInternal(runId,target,revision,key,hostId,spec);
    }
    private JsonObject createInternal(String runId,TestDataLedger.Target target,long revision,String key,String hostId,JsonObject spec)throws Exception {
        if(revision<0 || hostId==null || !hostId.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Valid host_id and nonnegative revision required");
        String code=TestDataCreatePlan.code(spec);
        JsonObject old=ledger.findCreation(runId,target,key,hostId,spec);
        if(old!=null && !old.get("state").getAsString().equals("prepared"))return status(runId,target,old.get("creation_id").getAsString());
        checkTarget(target,backend.target(hostId,true));
        JsonObject reserved=ledger.reserveCreation(runId,target,revision,key,hostId,spec);String creationId=reserved.get("creation_id").getAsString();
        JsonObject claim=ledger.claimCreation(runId,target,creationId);
        if(!claim.get("claim_acquired").getAsBoolean())return ledger.creationView(runId,target,creationId);
        Map<String,Object> parameters=new LinkedHashMap<>();
        for(String field:List.of("creation_id","nonce","metadata_fqn","uuid"))parameters.put(field,claim.get(field).getAsString());
        parameters.put("fields",TestDataCreatePlan.fieldValues(spec.getAsJsonObject("fields")));
        Map<String,Object> submitted;
        try{submitted=backend.submit(hostId,claim.get("host_request_key").getAsString(),code,parameters);}
        catch(Exception e){return ledger.unknownCreation(runId,target,creationId,"submission_outcome_unknown");}
        if(NativeExecutionGate.notDispatched(submitted))return ledger.rejectCreation(runId,target,creationId,submitted);
        if(submitted==null || !(submitted.get("request_id") instanceof String requestId))return ledger.unknownCreation(runId,target,creationId,"missing_native_request_receipt");
        // Persistence failure here deliberately leaves submitting intent: never resubmit it blindly.
        return ledger.attachCreationRequest(runId,target,creationId,requestId);
    }
    public JsonObject status(String runId,TestDataLedger.Target target,String creationId)throws Exception {
        JsonObject op=ledger.creation(runId,target,creationId);String state=op.get("state").getAsString();
        if(Set.of("completed","failed").contains(state) || !op.has("request_id"))return ledger.creationView(runId,target,creationId);
        String hostId=op.get("host_id").getAsString(),requestId=op.get("request_id").getAsString();
        checkTarget(target,backend.target(hostId,false));
        Map<String,Object> result;
        try{result=backend.result(hostId,requestId);}catch(Exception e){return ledger.unknownCreation(runId,target,creationId,"native_result_unavailable");}
        if(result==null || !requestId.equals(result.get("request_id")))return ledger.unknownCreation(runId,target,creationId,"native_request_mismatch");
        if(!Boolean.TRUE.equals(result.get("terminal")))return ledger.creationView(runId,target,creationId);
        if(!"completed".equals(result.get("state")) || !(result.get("execution") instanceof Map<?,?> execution) || !Boolean.TRUE.equals(execution.get("success")))return ledger.unknownCreation(runId,target,creationId,"native_execution_outcome_unknown");
        JsonElement value=new Gson().toJsonTree(execution.get("value"));
        if(!value.isJsonObject())return ledger.unknownCreation(runId,target,creationId,"missing_creation_receipt");
        try {return ledger.finishCreation(runId,target,creationId,requestId,value.getAsJsonObject());}
        catch(RuntimeException e){return ledger.unknownCreation(runId,target,creationId,"creation_receipt_mismatch");}
    }
    private static void checkTarget(TestDataLedger.Target expected,TestDataLedger.Target actual){if(!expected.equals(actual))throw new IllegalStateException("Native host belongs to another project or infobase");}
}
