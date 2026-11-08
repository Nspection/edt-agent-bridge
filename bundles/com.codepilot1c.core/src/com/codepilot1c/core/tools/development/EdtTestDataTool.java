// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.eclipse.core.resources.ResourcesPlugin;
import com.codepilot1c.core.edt.development.TestDataLedger;
import com.codepilot1c.core.edt.development.TestDataCreateService;
import com.codepilot1c.core.edt.development.TestDataCleanupService;
import com.codepilot1c.core.edt.development.TestDataSetService;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.tools.*;

@ToolMeta(name="edt_test_data",category="qa",mutating=true,tags={"workspace","edt","sensitive"})
public final class EdtTestDataTool extends AbstractTool {
    public String getDescription(){return "Named sets: create_set items use alias/metadata_fqn/fields and {ref:alias} for confirmed dependencies. Cycles/unknown aliases rejected before dispatch. Default auto_advance=false: advance_set submits one node, set_status polls only; explicit true permits status to submit the next node. Each node is its own transaction, partial sets remain visible. preview_set_cleanup/cleanup_set then advance_set or set_status clean in reverse order; retained dependent blocks its ancestors. pause_set stops future automatic dispatch without cancelling the current operation; resume_set re-enables it. advance_set always supports one explicit step. Primitive create/runtime tools remain available. Test-data journal bound to the project's current EDT base UUID. begin/register/list/preview_cleanup/close manage the local ledger. register is an unverified agent declaration. create WRITES one new Catalog item or unposted Document through an ALREADY RUNNING native host, with preset UUID and fixed transactional recipe; strings/numbers/booleans/typed dates only, no arbitrary code, references or tabular sections. Poll create_status: native receipt must match intent/nonce/reference/version before ownership becomes native_host_confirmed. Pending/unknown creation blocks journal close; never replay it with a new key without investigation. Host/base setup and authorization are separate. Native business handlers run and can have side effects outside this directly tracked object. cleanup requires explicit cleanup_mode=mark_deletion and a fresh preview_cleanup hash: marks one native-confirmed unchanged record, skips changed/predefined/posted objects. Poll cleanup_status. Never physically deletes, clears references or claims all database data removed; close closes the local journal only.";}
    public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"required":["action","project"],"properties":{
          "action":{"type":"string","enum":["begin","register","list","preview_cleanup","close","create","create_status","cleanup","cleanup_status","create_set","set_status","advance_set","preview_set_cleanup","cleanup_set","pause_set","resume_set"]},
          "project":{"type":"string"},"idempotency_key":{"type":"string","maxLength":200},"label":{"type":"string","maxLength":200},
          "data_run_id":{"type":"string"},"expected_revision":{"type":"integer","minimum":0},
          "records":{"type":"array","minItems":1,"maxItems":100,"items":{"type":"object","additionalProperties":false,"required":["metadata_fqn","uuid"],"properties":{
             "metadata_fqn":{"type":"string","maxLength":140},"uuid":{"type":"string","maxLength":36},
             "expected_version":{"type":"string","maxLength":4096},"source_request_id":{"type":"string","maxLength":200}}}},
          "offset":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":100,"default":50},
          "expected_plan_sha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},
          "set_id":{"type":"string"},"auto_advance":{"type":"boolean","default":false},"items":{"type":"array","minItems":1,"maxItems":20,"items":{"type":"object","additionalProperties":false,"required":["alias","metadata_fqn","fields"],"properties":{"alias":{"type":"string","pattern":"^[A-Za-z][A-Za-z0-9_]{0,63}$"},"metadata_fqn":{"type":"string"},"fields":{"type":"object","maxProperties":32}}}},
          "record_id":{"type":"string","pattern":"^[a-f0-9]{64}$"},"cleanup_id":{"type":"string"},"cleanup_mode":{"const":"mark_deletion"},
          "host_id":{"type":"string","pattern":"^[a-f0-9]{32}$"},"creation_id":{"type":"string"},"metadata_fqn":{"type":"string","maxLength":140},
          "fields":{"type":"object","maxProperties":32,"additionalProperties":{"anyOf":[{"type":"string","maxLength":4096},{"type":"number"},{"type":"boolean"},{"type":"object","additionalProperties":false,"required":["type","value"],"properties":{"type":{"const":"date"},"value":{"type":"string","pattern":"^[0-9]{14}$"}}}]}}
        }}
        """;}
    @SuppressWarnings("unchecked") protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{
        try {
            String action=p.requireString("action"),project=p.requireString("project");
            Set<String> allowed=new HashSet<>(Set.of("action","project"));
            allowed.addAll(switch(action){case "begin"->Set.of("idempotency_key","label");case "register"->Set.of("data_run_id","expected_revision","records");case "list","preview_cleanup"->Set.of("data_run_id","offset","limit");case "close"->Set.of("data_run_id","expected_plan_sha256");case "create"->Set.of("data_run_id","expected_revision","idempotency_key","host_id","metadata_fqn","fields");case "create_status"->Set.of("data_run_id","creation_id");case "cleanup"->Set.of("data_run_id","idempotency_key","host_id","record_id","expected_plan_sha256","cleanup_mode");case "cleanup_status"->Set.of("data_run_id","cleanup_id");case "create_set"->Set.of("data_run_id","idempotency_key","host_id","items","auto_advance");case "set_status","advance_set","preview_set_cleanup","pause_set","resume_set"->Set.of("data_run_id","set_id");case "cleanup_set"->Set.of("data_run_id","set_id","expected_plan_sha256","cleanup_mode");default->throw new IllegalArgumentException("Unknown action");});
            for(String key:p.getRaw().keySet())if(!allowed.contains(key))throw new IllegalArgumentException(action+" does not accept "+key);
            var base=new EdtRuntimeService().resolveDefaultInfobase(project);if(base==null)throw new IllegalStateException("Bind the intended infobase through EDT first");
            var target=new TestDataLedger.Target(project,base.getUuid().toString());
            var ledger=new TestDataLedger(ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/test-data"));
            com.google.gson.JsonObject result;
            if(action.equals("begin"))result=ledger.begin(target,p.requireString("idempotency_key"),p.requireString("label"));
            else {
                String id=p.requireString("data_run_id");
                result=switch(action){
                    case "create_set","set_status","advance_set","preview_set_cleanup","cleanup_set","pause_set","resume_set" -> {
                        var service=new TestDataSetService(ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/test-data-sets"),ledger,new TestDataCreateService.NativeBackend());
                        if(action.equals("create_set")){Object raw=p.getRaw().get("items");if(!(raw instanceof List<?> list))throw new IllegalArgumentException("items must be an array");List<Map<String,Object>> items=new ArrayList<>();for(Object item:list){if(!(item instanceof Map<?,?>))throw new IllegalArgumentException("Named item must be an object");items.add((Map<String,Object>)item);}yield service.prepare(id,target,p.requireString("idempotency_key"),p.requireString("host_id"),items,Boolean.TRUE.equals(p.getRaw().get("auto_advance")));}
                        String set=p.requireString("set_id");yield switch(action){case "pause_set","resume_set"->service.pause(set,id,target,action.equals("pause_set"));case "advance_set"->service.advance(set,id,target);case "set_status"->service.status(set,id,target);case "preview_set_cleanup"->service.preview(set,id,target);default->{if(!"mark_deletion".equals(p.requireString("cleanup_mode")))throw new IllegalArgumentException("Only explicit mark_deletion is supported");yield service.cleanup(set,id,target,p.requireString("expected_plan_sha256"));}};
                    }
                    case "register" -> {
                        Object raw=p.getRaw().get("records");if(!(raw instanceof List<?> list))throw new IllegalArgumentException("records must be an array");
                        List<Map<String,Object>> records=new ArrayList<>();for(Object entry:list){if(!(entry instanceof Map<?,?>))throw new IllegalArgumentException("Record must be an object");records.add((Map<String,Object>)entry);}
                        yield ledger.register(id,target,p.requireLong("expected_revision"),records);
                    }
                    case "create" -> {
                        Object fields=p.getRaw().get("fields");if(!(fields instanceof Map<?,?>))throw new IllegalArgumentException("fields must be an object");
                        yield new TestDataCreateService(ledger,new TestDataCreateService.NativeBackend()).create(id,target,p.requireLong("expected_revision"),p.requireString("idempotency_key"),p.requireString("host_id"),p.requireString("metadata_fqn"),(Map<String,Object>)fields);
                    }
                    case "cleanup" -> {if(!"mark_deletion".equals(p.requireString("cleanup_mode")))throw new IllegalArgumentException("Only explicit mark_deletion is supported");yield new TestDataCleanupService(ledger,new TestDataCreateService.NativeBackend()).start(id,target,p.requireString("idempotency_key"),p.requireString("host_id"),p.requireString("record_id"),p.requireString("expected_plan_sha256"));}
                    case "cleanup_status" -> new TestDataCleanupService(ledger,new TestDataCreateService.NativeBackend()).status(id,target,p.requireString("cleanup_id"));
                    case "create_status" -> new TestDataCreateService(ledger,new TestDataCreateService.NativeBackend()).status(id,target,p.requireString("creation_id"));
                    case "list" -> ledger.list(id,target,integer(p,"offset",0),integer(p,"limit",50));
                    case "preview_cleanup" -> ledger.preview(id,target,integer(p,"offset",0),integer(p,"limit",50));
                    case "close" -> ledger.close(id,target,p.requireString("expected_plan_sha256"));
                    default -> throw new IllegalArgumentException("Unknown action");
                };
            }
            return ToolResult.success(result.toString());
        }catch(Exception e){return ToolResult.failure("edt_test_data: "+e.getMessage());}
    });}
    private static int integer(ToolParameters p,String name,int fallback){return p.getRaw().containsKey(name)?Math.toIntExact(p.requireLong(name)):fallback;}
}
