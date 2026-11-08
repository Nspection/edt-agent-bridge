// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
import java.nio.charset.StandardCharsets;
import com.google.gson.*;

/** Version-guarded deletion mark only. Never physically deletes or rewrites references. */
public final class TestDataCleanupService {
    private final TestDataLedger ledger;private final TestDataCreateService.Backend backend;
    public TestDataCleanupService(TestDataLedger ledger,TestDataCreateService.Backend backend){this.ledger=ledger;this.backend=backend;}
    public JsonObject start(String run,TestDataLedger.Target target,String key,String host,String record,String plan)throws Exception {
        JsonObject old=ledger.existingCleanup(run,target,key,host,record);if(old!=null&&!"prepared".equals(old.get("state").getAsString()))return status(run,target,old.get("cleanup_id").getAsString());
        if(!target.equals(backend.target(host,true)))throw new IllegalStateException("Cleanup host belongs to another project/base");
        JsonObject op=ledger.reserveCleanup(run,target,key,host,record,plan);String id=op.get("cleanup_id").getAsString();
        if(!"prepared".equals(op.get("state").getAsString()))return status(run,target,id);
        String code=code(op.getAsJsonObject("record").get("metadata_fqn").getAsString());
        op=ledger.claimCleanup(run,target,id);if(!op.get("claim_acquired").getAsBoolean())return ledger.cleanupView(run,target,id);
        Map<String,Object> parameters=new LinkedHashMap<>();for(String name:List.of("cleanup_id","nonce","record_id"))parameters.put(name,op.get(name).getAsString());
        for(String name:List.of("uuid","expected_version"))parameters.put(name,op.getAsJsonObject("record").get(name).getAsString());
        Map<String,Object> result;
        try{result=backend.submit(host,op.get("host_request_key").getAsString(),code,parameters);}catch(Exception e){return ledger.unknownCleanup(run,target,id,"submission_outcome_unknown");}
        if(result==null || !(result.get("request_id") instanceof String request))return ledger.unknownCleanup(run,target,id,"missing_native_request_receipt");
        return ledger.cleanupRequest(run,target,id,request);
    }
    public JsonObject status(String run,TestDataLedger.Target target,String id)throws Exception {
        JsonObject op=ledger.cleanup(run,target,id);if(Set.of("completed","skipped","failed").contains(op.get("state").getAsString()) || !op.has("request_id"))return ledger.cleanupView(run,target,id);
        String host=op.get("host_id").getAsString(),request=op.get("request_id").getAsString();if(!target.equals(backend.target(host,false)))throw new IllegalStateException("Cleanup host belongs to another project/base");
        Map<String,Object> result;try{result=backend.result(host,request);}catch(Exception e){return ledger.unknownCleanup(run,target,id,"native_result_unavailable");}
        if(result==null || !request.equals(result.get("request_id")))return ledger.unknownCleanup(run,target,id,"native_request_mismatch");
        if(!Boolean.TRUE.equals(result.get("terminal")))return ledger.cleanupView(run,target,id);
        if(!"completed".equals(result.get("state")) || !(result.get("execution") instanceof Map<?,?> execution) || !Boolean.TRUE.equals(execution.get("success")))return ledger.unknownCleanup(run,target,id,"native_execution_outcome_unknown");
        JsonElement value=new Gson().toJsonTree(execution.get("value"));if(!value.isJsonObject())return ledger.unknownCleanup(run,target,id,"missing_cleanup_receipt");
        try{return ledger.finishCleanup(run,target,id,request,value.getAsJsonObject());}catch(RuntimeException e){return ledger.unknownCleanup(run,target,id,"cleanup_receipt_mismatch");}
    }
    public static String code(String fqn)throws Exception {
        TestDataCreatePlan.normalize(fqn,Map.of());boolean catalog=fqn.startsWith("Catalog.");String source;
        try(var stream=TestDataCleanupService.class.getResourceAsStream("/resources/agent-host/TestDataCleanup.bsl")){if(stream==null)throw new IllegalStateException("Cleanup recipe missing");source=new String(stream.readAllBytes(),StandardCharsets.UTF_8);}
        return source.replace("__MANAGER__",catalog?"Справочники":"Документы").replace("__NAME__",fqn.substring(fqn.indexOf('.')+1)).replace("__TABLE__",(catalog?"Справочник.":"Документ.")+fqn.substring(fqn.indexOf('.')+1)).replace("__MARK__",catalog?"УстановитьПометкуУдаления(Истина, Ложь)":"УстановитьПометкуУдаления(Истина)").replace("__SPECIAL_CHECK__",catalog?"Если ОбъектДанных.Предопределенный Тогда ОтветУборки.outcome = \"predefined\"; КонецЕсли;":"Если ОбъектДанных.Проведен Тогда ОтветУборки.outcome = \"posted\"; КонецЕсли;");
    }
}
