package com.codepilot1c.core.tools.development;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.eclipse.core.resources.ResourcesPlugin;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.development.ManagedVanessaProcess;
import com.codepilot1c.core.edt.development.RunnerProfileStore;
@ToolMeta(name="edt_runner_prepare",category="qa",mutating=true,tags={"workspace","edt"})
public final class EdtRunnerPrepareTool extends AbstractTool {
    @Override public String getDescription(){return "Use project/profile_id from edt_runner_profile, or explicit paths. Prepare a dedicated Vanessa TestManager base for unattended opening of external processing libraries. Preview by default; apply=true adds an exact manager-base rule to the selected platform version's 1C configuration, preserving other rules and a backup. Requires a verified SHA256 of Vanessa EPF. Manager and target bases must be different. Trust applies to all external processing in that dedicated manager base; do not use a business database as manager. Restart the managed session afterward. Does not install extensions or suppress unrelated prompts.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"project":{"type":"string"},"profile_id":{"type":"string"},"platform_bin":{"type":"string"},"infobase_path":{"type":"string"},"manager_infobase_path":{"type":"string"},"vanessa_epf":{"type":"string"},"expected_vanessa_sha256":{"type":"string","pattern":"^[a-fA-F0-9]{64}$"},"apply":{"type":"boolean","default":false}},"anyOf":[{"required":["project"]},{"required":["platform_bin","infobase_path","manager_infobase_path","vanessa_epf","expected_vanessa_sha256"]}]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{
        var storage=ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/development");
        Map<String,Object> parameters=p.getRaw().containsKey("project")?RunnerProfileStore.workspace().resolve(p.getRaw()):p.getRaw();
        RunnerProfileStore.ensureSeparateBases(java.nio.file.Path.of((String)parameters.get("manager_infobase_path")),java.nio.file.Path.of((String)parameters.get("infobase_path")));
        Map<String,String> args=new LinkedHashMap<>();args.put("PlatformBin",(String)parameters.get("platform_bin"));args.put("Infobase",(String)parameters.get("infobase_path"));args.put("ManagerInfobase",(String)parameters.get("manager_infobase_path"));args.put("VanessaEpf",(String)parameters.get("vanessa_epf"));args.put("ExpectedVanessaSha256",(String)parameters.get("expected_vanessa_sha256"));if(Boolean.TRUE.equals(parameters.get("apply"))){args.put("TrustDedicatedManager","");}
        return ToolResult.success(new Gson().toJson(new ManagedVanessaProcess(storage).call("Prepare",storage,args)));
    }catch(Exception e){return ToolResult.failure("edt_runner_prepare: "+e.getMessage());}});}
}
