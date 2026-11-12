package com.codepilot1c.core.edt.development;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.osgi.framework.FrameworkUtil;
import com.google.gson.Gson;
import com.codepilot1c.core.logging.LogSanitizer;

/** Fixed bundled script, argv-only invocation and bounded execution/output. */
public final class ManagedVanessaProcess {
    private final long lifecycle;
    private final Path script;
    private final Map<Path,byte[]> expectedResources;
    private final Map<String,String> runScopes=new HashMap<>();
    public ManagedVanessaProcess(Path storage) throws Exception {
        lifecycle=RuntimeProcessLifecycle.global().epoch();
        Files.createDirectories(storage);script=storage.resolve("managed-vanessa.ps1");
        var bundle=FrameworkUtil.getBundle(ManagedVanessaProcess.class);
        var expected=new LinkedHashMap<Path,byte[]>();
        for(var resource:Map.of("run-vanessa-debug.ps1","managed-vanessa.ps1","managed-vanessa-core.ps1","managed-vanessa-core.ps1").entrySet()){
            var entry=bundle.getEntry("scripts/"+resource.getKey());if(entry==null)throw new IllegalStateException("Managed Vanessa resource is missing: "+resource.getKey());
            byte[] bytes;try(var input=entry.openStream()){bytes=input.readAllBytes();}
            Path file=storage.resolve(resource.getValue());if(Files.isSymbolicLink(file))throw new IllegalStateException("Managed controller resource changed");
            if(!Files.exists(file)||!Arrays.equals(bytes,Files.readAllBytes(file))){
                Path temporary=Files.createTempFile(storage,"managed-",".ps1");
                try{Files.write(temporary,bytes);try{Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}}
                finally{Files.deleteIfExists(temporary);}
            }
            expected.put(file,bytes);
        }
        expectedResources=Map.copyOf(expected);
    }
    ManagedVanessaProcess(Path fixedScript,long epoch)throws Exception {script=fixedScript;lifecycle=epoch;expectedResources=Map.of(script,Files.readAllBytes(script));}
    private void verifyResources()throws Exception {
        for(var resource:expectedResources.entrySet())if(Files.isSymbolicLink(resource.getKey())||!Arrays.equals(resource.getValue(),Files.readAllBytes(resource.getKey())))throw new IllegalStateException("Managed controller resource changed; no process dispatched");
    }
    @SuppressWarnings("unchecked") public synchronized Map<String,Object> call(String action,Path workspace,Map<String,String> parameters) throws Exception {
        verifyResources();String shell=powerShell();
        List<String> command=new ArrayList<>(List.of(shell,"-NoLogo","-NoProfile","-NonInteractive","-File",script.toString(),"-Action",action,"-Workspace",workspace.toString()));
        for(var entry:parameters.entrySet()) {
            if(entry.getKey().equals("TargetPassword"))continue;
            command.add("-"+entry.getKey());
            if(!Set.of("VisualCheckpoint","TrustDedicatedManager").contains(entry.getKey())){command.add(entry.getValue());}
        }
        if(parameters.containsKey("TargetPassword"))command.add("-ReadAccessFromStdin");
        Path output=Files.createTempFile(script.getParent(),"managed-output-",".log");
        String scope=null;
        if(action.equals("Start")) {
            String runId=parameters.get("RunId");if(runId==null||runId.isBlank())throw new IllegalArgumentException("Managed Start requires RunId");
            scope=output.toAbsolutePath()+"/managed-run";String helper=output.toAbsolutePath()+"/powershell-helper";
            List<String> teardown=List.of(shell,"-NoLogo","-NoProfile","-NonInteractive","-File",script.toString(),"-Action","Stop","-Workspace",workspace.toString(),"-RunId",runId);
            RuntimeProcessLifecycle.global().own(lifecycle,scope,()->{
                RuntimeProcessLifecycle.global().closeOwned(helper);verifyResources();
                Path receipt=Files.createTempFile(script.getParent(),"managed-teardown-",".log");
                try{Map<String,Object> stopped=execute(teardown,receipt,null,lifecycle,false);if(!Set.of("stopped","absent").contains(stopped.get("Status")))throw new IllegalStateException("Managed run cleanup identity could not be confirmed");}
                finally{Files.deleteIfExists(receipt);}
            });
            runScopes.put(workspace.toAbsolutePath().normalize()+"/"+runId,scope);
        }
        try{Map<String,Object> result=execute(command,output,parameters.containsKey("TargetPassword")?new Gson().toJson(Map.of("target_password",parameters.get("TargetPassword"))):null,lifecycle);
            if(action.equals("Stop")&&parameters.containsKey("RunId")&&Set.of("stopped","absent").contains(result.get("Status"))){String key=runScopes.remove(workspace.toAbsolutePath().normalize()+"/"+parameters.get("RunId"));if(key!=null)RuntimeProcessLifecycle.global().forget(key);}
            return result;
        }finally{try{Files.deleteIfExists(output);}catch(java.io.IOException ignored){/* A failed child may still own its redirected file; no pipe read is blocked. */}}
    }
    @SuppressWarnings("unchecked") static Map<String,Object> execute(List<String> command,Path output) throws Exception {
        return execute(command,output,null);
    }
    @SuppressWarnings("unchecked") static Map<String,Object> execute(List<String> command,Path output,String secretInput) throws Exception {
        return execute(command,output,secretInput,RuntimeProcessLifecycle.global().epoch());
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> execute(List<String> command,Path output,String secretInput,long lifecycle) throws Exception {
        return execute(command,output,secretInput,lifecycle,true);
    }
    private static Map<String,Object> execute(List<String> command,Path output,String secretInput,long lifecycle,boolean tracked) throws Exception {
        ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        Map<String,String> environment=RunnerEnvironment.isolated(builder.environment());builder.environment().clear();builder.environment().putAll(environment);
        builder.environment().put("POWERSHELL_TELEMETRY_OPTOUT","1");builder.environment().put("POWERSHELL_UPDATECHECK","Off");
        String owned=output.toAbsolutePath()+"/powershell-helper";
        Process process=tracked?RuntimeProcessLifecycle.global().launch(lifecycle,()->{var child=builder.start();RuntimeProcessLifecycle.global().ownProcess(lifecycle,owned,child);return child;}):builder.start();
        try{
        try(var stdin=process.getOutputStream()){if(secretInput!=null)stdin.write(secretInput.getBytes(StandardCharsets.UTF_8));}
        if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("Managed Vanessa control timed out; inspect run status before retrying");}
        byte[] bytes;try(var stream=Files.newInputStream(output)){bytes=stream.readNBytes(1024*1024+1);}
        if(bytes.length>1024*1024){throw new IllegalStateException("Managed runner output limit exceeded");}
        String text=new String(bytes,StandardCharsets.UTF_8).replace("\uFEFF","").strip();
        if(process.exitValue()!=0){throw new IllegalStateException(LogSanitizer.redactSecrets(text));}
        Object parsed=new Gson().fromJson(text,Object.class);
        if(!(parsed instanceof Map<?,?>)){throw new IllegalStateException("Managed runner did not return a JSON object");}
        return (Map<String,Object>)parsed;
        }finally{if(tracked)RuntimeProcessLifecycle.global().closeOwned(owned);else if(process.isAlive()){process.destroyForcibly();process.waitFor(3,TimeUnit.SECONDS);}}
    }
    public static final class PowerShellUnavailable extends IllegalStateException {
        public PowerShellUnavailable(){super("Prepare the pinned portable PowerShell package or provide an installed PowerShell7");}
        public Map<String,Object> result(){return Map.of("state","needs_powershell","ready",false,"execution_state","not_dispatched","outcome_unknown",false,"process_started",false,"trust_changed",false,"next_call",RuntimeConsentPolicy.call("edt_runner_profile",Map.of("action","prepare_powershell")));}
    }
    static Optional<String> installedPowerShell(String path,String programFiles) {
        var candidates=new LinkedHashSet<Path>();
        if(path!=null)for(String directory:path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))try{
            if(directory.isBlank())continue;Path folder=Path.of(directory);if(folder.isAbsolute())candidates.add(folder.resolve("pwsh.exe"));
        }catch(InvalidPathException invalid){/* Invalid PATH entries are not executable candidates. */}
        try{Path folder=Path.of(programFiles);if(folder.isAbsolute())candidates.add(folder.resolve("PowerShell/7/pwsh.exe"));}catch(InvalidPathException invalid){}
        return candidates.stream().filter(Files::isRegularFile).map(Path::toString).findFirst();
    }
    static String powerShell() throws Exception {
        var installed=installedPowerShell(System.getenv("PATH"),System.getenv().getOrDefault("ProgramFiles","C:/Program Files"));
        if(installed.isPresent())return installed.orElseThrow();
        var managed=VerifiedDependencyPackage.powerShell().status();
        if(Boolean.TRUE.equals(managed.get("ready")))return managed.get("path").toString();
        throw new PowerShellUnavailable();
    }
}
