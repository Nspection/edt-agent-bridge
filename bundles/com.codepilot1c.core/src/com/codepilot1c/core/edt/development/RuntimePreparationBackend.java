// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
import java.util.function.Consumer;
import com.codepilot1c.core.edt.infobase.*;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.mcp.host.*;

/** Fixed existing operations only; never changes trust, updates a DB or attaches to user clients. */
public final class RuntimePreparationBackend implements RuntimePreparationService.Backend {
    private static void require(String tool){var cfg=McpHostConfigStore.getInstance().load();if(cfg.getMutationPolicy()!=McpHostConfig.MutationPolicy.ALLOW||!new DefaultMcpToolExposurePolicy(cfg).isExposed(tool))throw new IllegalStateException("Required operation is not allowed: "+tool);}
    public Map<String,Object> binding(String project)throws Exception{
        require("edt_infobase_state");new NativeInfobaseGateway().project(project);var runtime=new EdtRuntimeService();
        com._1c.g5.v8.dt.platform.services.model.InfobaseReference base;try{base=runtime.resolveDefaultInfobase(project);}catch(EdtRuntimeService.UnboundInfobaseException missing){return Map.of("project",project,"base_uuid","","configuration_synchronized",false);}
        if(base==null)return Map.of("project",project,"base_uuid","","configuration_synchronized",false);
        var d=new RegisteredInfobaseGateway().describe(project,base.getUuid().toString());var r=new LinkedHashMap<String,Object>();for(String k:List.of("project","project_path","context","base_uuid","target_identity_sha256","primary_uuid"))r.put(k,d.get(k));r.put("configuration_synchronized",Boolean.TRUE.equals(runtime.synchronizationState(project).get("configuration_synchronized")));return r;
    }
    public Map<String,Object> refreshBinding(String project)throws Exception{
        require("edt_infobase_refresh");var result=new NativeInfobaseGateway().refresh(project,new org.eclipse.core.runtime.NullProgressMonitor());var state=new LinkedHashMap<>(binding(project));if(!Boolean.TRUE.equals(result.get("configuration_synchronized")))state.put("configuration_synchronized",false);return state;
    }
    public Map<String,Object> route(Map<String,Object>a)throws Exception{
        if(McpRuntimeRouting.usesPythonProbe(a))require("edt_test_client");return McpRuntimeRouting.plan(a);
    }
    public Map<String,Object> environment()throws Exception{require("edt_test_client");return PythonEnvironmentService.workspace().inspect(null);}
    public Map<String,Object> prepareEnvironment(Consumer<String> reserved)throws Exception{require("edt_test_client");return PythonEnvironmentService.workspace().prepare(Map.of(),p->{if("creating_venv".equals(p.get("stage")))reserved.accept((String)p.get("environment_id"));});}
    public Map<String,Object> startClient(Map<String,Object> request,String env,Consumer<String> reserved)throws Exception{require("edt_test_client");var args=new LinkedHashMap<String,Object>(Map.of("project",request.get("project"),"environment_id",env));for(String k:List.of("client_kind","readiness_timeout_s"))if(request.containsKey(k))args.put(k,request.get(k));return new DirectTestClientService().start(args,reserved);}
    public Map<String,Object> nativePackage()throws Exception{require("edt_runtime_host");return NativePackageManager.getInstance().ensure();}
    public Map<String,Object> prepareHost(Map<String,Object>a,Consumer<String> reserved)throws Exception{require("edt_runtime_host");if(!"external".equals(a.get("mode")))throw new IllegalArgumentException("Unified preparation supports external host only");return new NativeHostService().prepare(a,reserved);}
    public void startHost(String id)throws Exception{require("edt_runtime_host");new NativeHostService().start(id);}
    private static String resource(List<Map<String,Object>>rs,String kind){return rs.stream().filter(r->kind.equals(r.get("kind"))).map(r->(String)r.get("id")).findFirst().orElseThrow(()->new IllegalStateException("Resource identity not journalled"));}
    public Map<String,Object> ready(String engine,Map<String,Object>a,List<Map<String,Object>>rs)throws Exception{
        if(engine.equals("testclient")){require("edt_test_client");var c=new DirectTestClientService().status(resource(rs,"testclient"));boolean ready=Boolean.TRUE.equals(c.get("connected"))&&Boolean.TRUE.equals(c.get("live_client_binding_verified"))&&Boolean.TRUE.equals(c.get("ui_ready"))&&a.get("base_uuid").equals(c.get("base_uuid"));return Map.of("ready",ready,"client_id",c.get("client_id"),"transport","testclient","connected",ready);}
        if(engine.equals("native")){require("edt_runtime_host");String id=resource(rs,"host");var service=new NativeHostService();var target=service.testDataTarget(id,false);if(!a.get("project").equals(target.project())||!a.get("base_uuid").equals(target.baseUuid()))throw new IllegalStateException("Host target changed");var s=service.status(id);return nativeReadiness(id,s);}
        require("edt_runner_profile");require("edt_runtime_catalog");var profile=RunnerProfileStore.workspace().get((String)a.get("project"),(String)a.get("profile_id"));
        if(!Boolean.TRUE.equals(profile.get("configured")))return Map.of("ready",false,"reason","runner_profile_missing","next_call",Map.of("tool","edt.run","arguments",Map.of("op","edt_runner_profile","args",Map.of("action","get","project",a.get("project"),"profile_id",a.getOrDefault("profile_id","default")))));
        // Validate saved profile target before any connection to a manager endpoint.
        new TaskSessionBackend().binding((String)a.get("project"),(String)a.getOrDefault("profile_id","default"));
        int port=RunnerProfileStore.managerMcpPort(TaskSessionService.object(profile.get("profile")));String endpoint="http://127.0.0.1:"+(port==0?9874:port)+"/mcp";
        var catalog=VanessaMcpBridge.getInstance().catalog(endpoint,null);var proof=new TaskSessionBackend().verifyClient((String)a.get("project"),endpoint);boolean ready=!Boolean.FALSE.equals(catalog.get("success"))&&Boolean.TRUE.equals(proof.get("live_client_binding_verified"));return Map.of("ready",ready,"endpoint",endpoint,"borrowed",true,"reason",ready?"verified_existing_manager_and_client":"prepare_saved_runner_and_verify_client");
    }
    static Map<String,Object> nativeReadiness(String id,Map<String,Object> status){
        var channel=TaskSessionService.object(status.get("channel"));var compatibility=TaskSessionService.object(status.get("compatibility"));
        boolean compatible=Boolean.TRUE.equals(compatibility.get("compatible")),blocked=Boolean.TRUE.equals(status.get("execution_blocked_by_refresh"));
        boolean ready="running".equals(status.get("state"))&&Boolean.TRUE.equals(channel.get("connected"))&&compatible&&!blocked;
        var out=new LinkedHashMap<String,Object>();out.put("ready",ready);out.put("host_id",id);out.put("state",!compatible?"native_refresh_required":blocked?"refresh_pending":status.get("state"));out.put("startup_phase",status.getOrDefault("startup_phase","pending"));out.put("connected",Boolean.TRUE.equals(channel.get("connected")));out.put("compatibility",compatibility);
        if(!compatible)out.put("next_call",Map.of("tool","edt.run","arguments",Map.of("op","edt_runtime_host","args",Map.of("action","ensure_current","host_id",id))));return out;
    }
    public Map<String,Object> cleanup(String kind,String id)throws Exception{return switch(kind){case "testclient"->{require("edt_test_client");yield new DirectTestClientService().cleanup(id);}case "python_environment"->{require("edt_test_client");yield PythonEnvironmentService.workspace().cleanup(id);}case "host"->{require("edt_runtime_host");yield new NativeHostService().cleanup(id);}default->throw new IllegalArgumentException("Unsupported owned preparation resource");};}
}
