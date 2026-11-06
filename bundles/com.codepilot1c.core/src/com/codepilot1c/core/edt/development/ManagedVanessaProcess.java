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
    private final Path script;
    public ManagedVanessaProcess(Path storage) throws Exception {
        Files.createDirectories(storage);script=storage.resolve("managed-vanessa.ps1");
        var bundle=FrameworkUtil.getBundle(ManagedVanessaProcess.class);
        var entry=bundle.getEntry("scripts/run-vanessa-debug.ps1");
        if(entry==null){throw new IllegalStateException("Managed Vanessa script is missing from the plugin");}
        byte[] bytes;try(var input=entry.openStream()){bytes=input.readAllBytes();}
        if(!Files.exists(script) || !Arrays.equals(bytes,Files.readAllBytes(script))){
            Path temporary=Files.createTempFile(storage,"managed-",".ps1");Files.write(temporary,bytes);
            try{Files.move(temporary,script,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,script,StandardCopyOption.REPLACE_EXISTING);}
        }
    }
    @SuppressWarnings("unchecked") public synchronized Map<String,Object> call(String action,Path workspace,Map<String,String> parameters) throws Exception {
        List<String> command=new ArrayList<>(List.of(powerShell(),"-NoLogo","-NoProfile","-NonInteractive","-File",script.toString(),"-Action",action,"-Workspace",workspace.toString()));
        for(var entry:parameters.entrySet()) {
            command.add("-"+entry.getKey());
            if(!Set.of("VisualCheckpoint","TrustDedicatedManager").contains(entry.getKey())){command.add(entry.getValue());}
        }
        Path output=Files.createTempFile(script.getParent(),"managed-output-",".log");
        try{return execute(command,output);}finally{try{Files.deleteIfExists(output);}catch(java.io.IOException ignored){/* A failed child may still own its redirected file; no pipe read is blocked. */}}
    }
    @SuppressWarnings("unchecked") static Map<String,Object> execute(List<String> command,Path output) throws Exception {
        ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        Map<String,String> environment=RunnerEnvironment.isolated(builder.environment());builder.environment().clear();builder.environment().putAll(environment);
        Process process=builder.start();
        if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("Managed Vanessa control timed out; inspect run status before retrying");}
        byte[] bytes;try(var stream=Files.newInputStream(output)){bytes=stream.readNBytes(1024*1024+1);}
        if(bytes.length>1024*1024){throw new IllegalStateException("Managed runner output limit exceeded");}
        String text=new String(bytes,StandardCharsets.UTF_8).replace("\uFEFF","").strip();
        if(process.exitValue()!=0){throw new IllegalStateException(LogSanitizer.redactSecrets(text));}
        Object parsed=new Gson().fromJson(text,Object.class);
        if(!(parsed instanceof Map<?,?>)){throw new IllegalStateException("Managed runner did not return a JSON object");}
        return (Map<String,Object>)parsed;
    }
    static String powerShell() {
        String path=System.getenv("PATH");
        if(path!=null){for(String directory:path.split(java.io.File.pathSeparator)){Path candidate=Path.of(directory,"pwsh.exe");if(Files.isRegularFile(candidate)){return candidate.toString();}}}
        Path conventional=Path.of(System.getenv().getOrDefault("ProgramFiles","C:/Program Files"),"PowerShell","7","pwsh.exe");
        if(Files.isRegularFile(conventional)){return conventional.toString();}
        throw new IllegalStateException("PowerShell 7 (pwsh.exe) is required for managed Vanessa execution");
    }
}
