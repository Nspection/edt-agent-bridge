package com.codepilot1c.core.tools.development;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.RunnerProfileStore;
@ToolMeta(name="edt_runner_profile",category="qa",mutating=true,tags={"workspace","edt"})
public final class EdtRunnerProfileTool extends AbstractTool {
    @Override public String getDescription(){return "Get or save the project's reusable automation paths. Start with action=get, profile_id=default: configured=false/state=needs_setup means one-time provisioning is required, not repeated client launches or per-library approvals. put stores verified absolute paths and expected_vanessa_sha256 locally in the EDT workspace. No credentials or arbitrary launch commands are stored. No client is started and no security setting is changed. Use edt_runner_prepare to preview/check trust, edt_runtime_catalog for live Vanessa readiness, then edt_development_run_start with profile_id.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"project":{"type":"string"},"profile_id":{"type":"string","default":"default"},"action":{"type":"string","enum":["get","put"],"default":"get"},"profile":{"type":"object","description":"For put: platform_bin, target (infobase_path OR server_address+server_infobase), manager_infobase_path, vanessa_epf, params_template, expected_vanessa_sha256 are required; user_name, manager_user_name, configuration_name, timeout_s, visual_checkpoint are optional. Paths must be absolute."}},"required":["project"]}
        """;}
    @SuppressWarnings("unchecked") @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{var args=p.getRaw();var store=RunnerProfileStore.workspace();String action=(String)args.getOrDefault("action","get");Map<String,Object> result;if(action.equals("get")){result=store.get((String)args.get("project"),(String)args.get("profile_id"));}else if(action.equals("put")){if(!(args.get("profile") instanceof Map<?,?>)){throw new IllegalArgumentException("profile is required for put");}result=store.put((String)args.get("project"),(String)args.get("profile_id"),(Map<String,Object>)args.get("profile"));}else{throw new IllegalArgumentException("Unknown profile action");}return ToolResult.success(new Gson().toJson(result));}catch(Exception e){return ToolResult.failure("edt_runner_profile: "+e.getMessage());}});}
}
