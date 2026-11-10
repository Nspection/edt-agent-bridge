// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.*;
import com.google.gson.*;
import org.eclipse.core.resources.ResourcesPlugin;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;

/** Workspace-owned immutable virtual environments. No global installs or implicit downloads on start. */
public final class PythonEnvironmentService {
    static final Gson JSON=new GsonBuilder().serializeNulls().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
    static final Map<String,String> PACKAGES=Map.of("pywin32","312","cryptography","50.0.2","cffi","2.1.1","pycparser","3.12");
    private static final Map<Path,ReentrantLock> LOCKS=new ConcurrentHashMap<>();
    private static final Map<Path,Preparation> PREPARATIONS=new HashMap<>();
    private record Preparation(String job,String source){}
    record Output(int exit,String text){}
    static final class PreparationUnproven extends IOException {PreparationUnproven(String message,Throwable cause){super(message,cause);}}
    interface Runner {Output run(List<String> command,Path cwd,Map<String,String> environment,int timeout)throws Exception;}
    private final Path root;
    private final String workspaceIdentity;
    private final Map<String,String> environment;
    private final Runner runner;
    private final Predicate<String> inUse;
    private final Supplier<List<Path>> registry;
    private final byte[] requirements;
    private final ReentrantLock lock;
    static final String PROBE="""
import sys,json,platform,struct,sysconfig
result={'implementation':platform.python_implementation(),'version':list(sys.version_info[:3]),'bits':struct.calcsize('P')*8,'platform':sys.platform,'architecture':sysconfig.get_platform().lower(),'free_threaded':bool(sysconfig.get_config_var('Py_GIL_DISABLED')),'executable':sys.executable,'base_executable':getattr(sys,'_base_executable',sys.executable),'prefix':sys.prefix,'base_prefix':sys.base_prefix}
if len(sys.argv)>1:
 import importlib.metadata as m
 import win32api,pythoncom,win32com.client
 from cryptography.hazmat.primitives.ciphers.aead import AESGCM
 import cffi,pycparser
 AESGCM.generate_key(bit_length=128)
 result['packages']={n:m.version(n) for n in ['pywin32','cryptography','cffi','pycparser']}
print(json.dumps(result))
""";
    public static PythonEnvironmentService workspace()throws Exception{
        Path workspace=ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().toRealPath();
        String identity=hash(workspace.toString().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        String local=System.getenv("LOCALAPPDATA");if(local==null||!Path.of(local).isAbsolute())throw new IOException("LOCALAPPDATA is unavailable");
        Path root=Path.of(local).resolve("EDT-Agent-Bridge/py").resolve(identity.substring(0,16));
        if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows"))throw new IllegalStateException("Managed Python environments support Windows x64 only");
        byte[] requirements;
        try(var input=PythonEnvironmentService.class.getResourceAsStream("/resources/test-client/python-requirements.lock")){if(input==null)throw new IOException("Packaged Python lock missing");requirements=input.readAllBytes();}
        return new PythonEnvironmentService(root,System.getenv(),PythonEnvironmentService::runProcess,DirectTestClientService::pythonEnvironmentInUse,PythonEnvironmentService::registeredPythons,requirements,identity);
    }
    PythonEnvironmentService(Path root,Map<String,String> environment,Runner runner,Predicate<String> inUse,Supplier<List<Path>> registry,byte[] requirements,String workspaceIdentity){
        this.root=root.toAbsolutePath().normalize();this.workspaceIdentity=workspaceIdentity;this.environment=RunnerEnvironment.isolated(environment);this.runner=runner;this.inUse=inUse;this.registry=registry;this.requirements=requirements.clone();lock=LOCKS.computeIfAbsent(this.root,k->new ReentrantLock());
    }
    static String hash(byte[] data)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    static String hash(Path path)throws Exception{
        var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(path)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)digest.update(b,0,n);}return HexFormat.of().formatHex(digest.digest());
    }
    private void rootCheck()throws Exception{
        if(Files.exists(root)){
            if(Files.isSymbolicLink(root)||!root.toRealPath().equals(root))throw new IOException("Environment storage moved; preserve for review");
            var owner=read(root.resolve("workspace.json"));if(!"edt-agent-bridge-python-workspace".equals(owner.get("owner"))||!workspaceIdentity.equals(owner.get("workspace_identity")))throw new IOException("Environment workspace ownership changed");
        }
    }
    private Path folder(String id){if(id==null||!id.matches("[a-f0-9]{24}"))throw new IllegalArgumentException("Invalid environment_id");return root.resolve(id);}
    @SuppressWarnings("unchecked") private Map<String,Object> read(Path path)throws Exception{
        if(Files.isSymbolicLink(path)||Files.size(path)>2*1024*1024)throw new IOException("Environment record changed");return JSON.fromJson(Files.readString(path,StandardCharsets.UTF_8),LinkedHashMap.class);
    }
    private void write(Path path,Map<String,Object> data)throws Exception{
        Path tmp=path.resolveSibling(path.getFileName()+".tmp");if(Files.isSymbolicLink(path)||Files.isSymbolicLink(tmp))throw new IOException("Environment record moved");Files.writeString(tmp,JSON.toJson(data),StandardCharsets.UTF_8);Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    private Map<String,String> childEnvironment(Path directory){var env=new LinkedHashMap<>(environment);env.put("TEMP",directory.resolve("temp").toString());env.put("TMP",directory.resolve("temp").toString());return env;}
    @SuppressWarnings("unchecked") private Map<String,Object> probe(Path python,boolean dependencies,Path directory)throws Exception{
        if(!Files.isRegularFile(python)||!python.getFileName().toString().equalsIgnoreCase("python.exe")||python.toString().toLowerCase(Locale.ROOT).contains("windowsapps"))throw new IOException("A real python.exe is required; WindowsApps aliases are unsupported");
        var cmd=new ArrayList<>(List.of(python.toString(),"-I","-B","-c",PROBE));if(dependencies)cmd.add("dependencies");
        var out=runner.run(cmd,directory,dependencies?childEnvironment(directory):environment,15);if(out.exit()!=0)throw new IOException("Python probe failed");
        Map<String,Object> data=JSON.fromJson(out.text(),LinkedHashMap.class);
        if(data==null||!"CPython".equals(data.get("implementation"))||!"win32".equals(data.get("platform"))||!"win-amd64".equals(data.get("architecture"))||!(data.get("bits") instanceof Number n)||n.intValue()!=64||!Boolean.FALSE.equals(data.get("free_threaded")))throw new IOException("Standard Windows x64 CPython is required");
        var version=(List<?>)data.get("version");if(version.size()!=3||((Number)version.get(0)).intValue()!=3||((Number)version.get(1)).intValue()<11||((Number)version.get(1)).intValue()>14)throw new IOException("Supported CPython versions are3.11 through3.14");
        if(dependencies&&!PACKAGES.equals(data.get("packages")))throw new IOException("Managed dependency versions differ from the lock");return data;
    }
    private record Base(Path python,Map<String,Object> probe,String sha,String id){}
    private Base base(Object explicit)throws Exception{
        var candidates=new LinkedHashSet<Path>();
        if(explicit!=null){if(!(explicit instanceof String text)||text.isBlank()||!Path.of(text).isAbsolute())throw new IllegalArgumentException("python_path must be absolute");candidates.add(Path.of(text));}
        else{
            String path=environment.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("PATH")).map(Map.Entry::getValue).findFirst().orElse("");
            for(String entry:path.split(java.util.regex.Pattern.quote(File.pathSeparator)))try{if(!entry.isBlank())candidates.add(Path.of(entry).resolve("python.exe"));}catch(InvalidPathException ignored){}
            candidates.addAll(registry.get());
        }
        int attempted=0;
        for(Path candidate:candidates){if(attempted>=8)break;if(!Files.isRegularFile(candidate)||candidate.toString().toLowerCase(Locale.ROOT).contains("windowsapps"))continue;attempted++;
            try{Path executable=candidate.toRealPath();var info=probe(executable,false,executable.getParent());Path base=Path.of((String)info.get("base_executable")).toRealPath();var actual=probe(base,false,base.getParent());String sha=hash(base);String id=hash((base.toString().toLowerCase(Locale.ROOT)+"|"+sha+"|"+JSON.toJson(actual.get("version"))+"|"+hash(requirements)).getBytes(StandardCharsets.UTF_8)).substring(0,24);return new Base(base,actual,sha,id);}catch(Exception unavailable){if(explicit!=null)throw new IllegalStateException("Selected Python is unavailable or unsupported",unavailable);}
        }
        throw new IllegalStateException("needs_setup: no supported CPython found in PATH/Windows registration; install Python3.11-3.14 x64 or supply python_path");
    }
    private static List<Path> registeredPythons(){
        var result=new ArrayList<Path>();
        for(var hive:List.of(com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER,com.sun.jna.platform.win32.WinReg.HKEY_LOCAL_MACHINE))for(String base:List.of("SOFTWARE\\Python\\PythonCore","SOFTWARE\\WOW6432Node\\Python\\PythonCore"))try{
            if(com.sun.jna.platform.win32.Advapi32Util.registryKeyExists(hive,base))for(String tag:com.sun.jna.platform.win32.Advapi32Util.registryGetKeys(hive,base))try{String key=base+"\\"+tag+"\\InstallPath";String value=com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(hive,key,"");result.add(Path.of(value).resolve("python.exe"));}catch(Exception unavailable){}
        }catch(Exception unavailable){}return result;
    }
    private String activeId()throws Exception{Path p=root.resolve("active.json");if(!Files.isRegularFile(p))return null;String id=(String)read(p).get("environment_id");folder(id);return id;}
    private void activate(String id)throws Exception{write(root.resolve("active.json"),Map.of("environment_id",id));}
    private Map<String,Object> inventory(Path directory)throws Exception{
        var files=new TreeMap<String,String>();var dirs=new TreeSet<String>();long total=0;
        try(var walk=Files.walk(directory)){var paths=walk.limit(15001).toList();if(paths.size()>15000)throw new IOException("Environment file limit exceeded");
            for(Path p:paths){if(Files.isSymbolicLink(p)||!p.toRealPath().startsWith(directory.toRealPath()))throw new IOException("Environment path changed");String name=directory.relativize(p).toString().replace('\\','/');if(name.isEmpty()||name.equals("manifest.json"))continue;
                if(Files.isRegularFile(p)){long size=Files.size(p);total+=size;if(size>128L*1024*1024||total>512L*1024*1024)throw new IOException("Environment exceeds size limits");files.put(name,hash(p));}
                else if(Files.isDirectory(p))dirs.add(name);else throw new IOException("Unknown environment file type");
            }
        }return Map.of("files",files,"directories",new ArrayList<>(dirs));
    }
    private Map<String,Object> manifest(String id)throws Exception{rootCheck();Path directory=folder(id);if(Files.isSymbolicLink(directory)||!directory.toRealPath().startsWith(root.toRealPath()))throw new IOException("Environment moved");var m=read(directory.resolve("manifest.json"));if(!"edt-agent-bridge-python".equals(m.get("owner"))||!id.equals(m.get("environment_id")))throw new IOException("Environment ownership changed");return m;}
    private void verifyInventory(String id,Map<String,Object> m)throws Exception{var actual=inventory(folder(id));if(!Objects.equals(actual.get("files"),m.get("files"))||!Objects.equals(actual.get("directories"),m.get("directories")))throw new IOException("Environment files changed; preserve for review");}
    private Map<String,Object> ready(String id)throws Exception{
        var m=manifest(id);if(!"ready".equals(m.get("state")))throw new IOException("Environment is incomplete; inspect and clean its owned files before preparing again");
        if(!hash(requirements).equals(m.get("lock_sha256")))throw new IOException("Environment uses a different lock");verifyInventory(id,m);
        Path source=Path.of((String)m.get("base_python"));if(!Files.isRegularFile(source)||!hash(source).equals(m.get("base_sha256")))throw new IOException("Base Python changed");
        Path python=folder(id).resolve("venv/Scripts/python.exe");var info=probe(python,true,folder(id));if(!Path.of((String)info.get("prefix")).toRealPath().equals(folder(id).resolve("venv").toRealPath())||!Objects.equals(info.get("version"),m.get("python_version")))throw new IOException("Managed Python prefix/version changed");
        return Map.of("state","ready","ready",true,"environment_id",id,"python_path",python.toString(),"python_version",info.get("version"),"packages",PACKAGES,"lock_sha256",m.get("lock_sha256"));
    }
    public Map<String,Object> inspect(String requested){
        if(!lock.tryLock()){synchronized(PREPARATIONS){var p=PREPARATIONS.get(root);if(p!=null){var status=BackgroundJobRegistry.getInstance().getStatus(p.job());if(status.isPresent()&&!Set.of("DONE","FAILED").contains(status.get().getState().name()))return Map.of("state","busy","ready",false,"job_id",p.job(),"next_tool","edt_operation_status");}}return Map.of("state","busy","ready",false,"next_action","wait_for_current_operation");}
        try{return inspectLocked(requested);}finally{lock.unlock();}
    }
    private Map<String,Object> inspectLocked(String requested){
        String id=requested;
        try{
            rootCheck();if(id==null)id=activeId();
            if(id==null){
                synchronized(PREPARATIONS){var prep=PREPARATIONS.get(root);if(prep!=null){var job=BackgroundJobRegistry.getInstance().getStatus(prep.job());if(job.isPresent()&&!Set.of("DONE","FAILED").contains(job.get().getState().name()))return Map.of("state","preparing","ready",false,"job_id",prep.job(),"next_tool","edt_operation_status");}}
                if(Files.isDirectory(root))try(var dirs=Files.list(root)){var ids=dirs.filter(Files::isDirectory).map(p->p.getFileName().toString()).filter(n->n.matches("[a-f0-9]{24}")).limit(5).toList();if(ids.size()==1)id=ids.get(0);else if(!ids.isEmpty())return Map.of("state","needs_selection","ready",false,"environment_ids",ids,"next_action","environment_status_with_environment_id");}
                if(id==null)return Map.of("state","needs_setup","ready",false,"next_action","prepare_environment");
            }
            var m=manifest(id);if(!"ready".equals(m.get("state")))return Map.of("state",String.valueOf(m.get("state")),"ready",false,"environment_id",id,"next_action",Set.of("failed","cleaning").contains(m.get("state"))?"cleanup_environment":"inspect_original_preparation");
            return ready(id);
        }catch(Exception changed){var out=new LinkedHashMap<String,Object>(Map.of("state","needs_attention","ready",false,"reason","environment_unavailable_or_changed","next_action","inspect_owned_files_or_prepare_environment"));if(id!=null&&id.matches("[a-f0-9]{24}"))out.put("environment_id",id);return out;}
    }
    public Map<String,Object> prepare(Map<String,Object> args,Consumer<Map<String,Object>> progress)throws Exception{
        if(!lock.tryLock())throw new IllegalStateException("Python environment busy; inspect/poll the original operation");
        try{
            rootCheck();progress.accept(Map.of("stage","finding_python"));Base source=base(args.get("python_path"));Path directory=folder(source.id());
            if(Files.exists(directory)){var result=new LinkedHashMap<>(ready(source.id()));activate(source.id());result.put("reused",true);result.put("created",false);return result;}
            if(Files.isDirectory(root))try(var dirs=Files.list(root)){if(dirs.filter(Files::isDirectory).count()>=4)throw new IllegalStateException("Environment limit reached; cleanup an unused owned environment first");}
            Files.createDirectories(root);if(Files.isSymbolicLink(root)||!root.toRealPath().equals(root))throw new IOException("Environment storage moved");if(!Files.exists(root.resolve("workspace.json")))write(root.resolve("workspace.json"),Map.of("owner","edt-agent-bridge-python-workspace","workspace_identity",workspaceIdentity));rootCheck();Files.createDirectory(directory);Files.createDirectory(directory.resolve("temp"));
            var m=new LinkedHashMap<String,Object>();m.put("owner","edt-agent-bridge-python");m.put("environment_id",source.id());m.put("base_python",source.python().toString());m.put("base_sha256",source.sha());m.put("python_version",source.probe().get("version"));m.put("lock_sha256",hash(requirements));m.put("state","preparing");write(directory.resolve("manifest.json"),m);
            try{
                Files.write(directory.resolve("requirements.lock"),requirements);progress.accept(Map.of("stage","creating_venv","environment_id",source.id()));
                checked(List.of(source.python().toString(),"-I","-B","-m","venv","--copies",directory.resolve("venv").toString()),directory,120);
                Path python=directory.resolve("venv/Scripts/python.exe");progress.accept(Map.of("stage","installing_locked_wheels","environment_id",source.id()));
                checked(List.of(python.toString(),"-I","-B","-m","pip","--isolated","--disable-pip-version-check","install","--no-input","--no-cache-dir","--only-binary=:all:","--require-hashes","--no-deps","--no-compile","--index-url","https://pypi.org/simple","-r",directory.resolve("requirements.lock").toString()),directory,240);
                checked(List.of(python.toString(),"-I","-B","-m","pip","--isolated","--disable-pip-version-check","check"),directory,30);
                progress.accept(Map.of("stage","verifying","environment_id",source.id()));var probe=probe(python,true,directory);if(!Path.of((String)probe.get("prefix")).toRealPath().equals(directory.resolve("venv").toRealPath()))throw new IOException("Created venv has unexpected prefix");
                m.putAll(inventory(directory));m.put("state","ready");write(directory.resolve("manifest.json"),m);activate(source.id());var result=new LinkedHashMap<>(ready(source.id()));result.put("reused",false);result.put("created",true);return result;
            }catch(Exception failure){
                boolean unproven=failure instanceof PreparationUnproven||failure instanceof InterruptedException;
                m.put("state",unproven?"interrupted":"failed");if(unproven){m.remove("files");m.remove("directories");}
                try{if(!unproven)m.putAll(inventory(directory));write(directory.resolve("manifest.json"),m);}catch(Exception preserve){failure.addSuppressed(preserve);}
                if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
                throw new IllegalStateException("environment_id="+source.id()+" preparation "+(unproven?"interrupted; subprocess termination is unproven, automatic cleanup refused":"failed; inspect install.log and original job, cleanup preserves changed files"),failure);
            }
        }finally{lock.unlock();}
    }
    private void checked(List<String> command,Path directory,int timeout)throws Exception{
        Output out=runner.run(command,directory,childEnvironment(directory),timeout);Files.writeString(directory.resolve("install.log"),out.text()+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);if(Files.size(directory.resolve("install.log"))>256*1024)throw new IOException("Installation log limit exceeded");if(out.exit()!=0)throw new IOException("Python preparation subprocess failed");
    }
    public record Selection(Path python,String environmentId,ReentrantLock held) implements AutoCloseable{public void close(){if(held!=null)held.unlock();}}
    public Selection select(Map<String,Object> args)throws Exception{
        if(args.containsKey("python_path")){if(args.containsKey("environment_id"))throw new IllegalArgumentException("Choose python_path or environment_id");Path p=Path.of(RuntimeExecutionPlan.required(args,"python_path")).toRealPath();if(!Files.isRegularFile(p)||!p.getFileName().toString().equalsIgnoreCase("python.exe"))throw new IllegalArgumentException("Explicit isolated python.exe required");if(p.startsWith(root)){
            Path relative=root.relativize(p);if(relative.getNameCount()!=4||!relative.getName(1).toString().equalsIgnoreCase("venv")||!relative.getName(2).toString().equalsIgnoreCase("Scripts"))throw new IllegalArgumentException("Unexpected path inside managed storage");
            return select(Map.of("environment_id",relative.getName(0).toString()));
        }
        if(p.toString().replace('\\','/').toLowerCase(Locale.ROOT).contains("/edt-agent-bridge/py/"))throw new IllegalArgumentException("Cannot borrow another workspace's managed Python");return new Selection(p,null,null);}
        if(!lock.tryLock())throw new IllegalStateException("Python environment busy; wait for the existing preparation/start");
        try{String id=args.containsKey("environment_id")?RuntimeExecutionPlan.required(args,"environment_id"):activeId();if(id==null)throw new IllegalStateException("needs_setup: run edt_test_client prepare_environment once");var info=ready(id);return new Selection(Path.of((String)info.get("python_path")),id,lock);}catch(Exception error){lock.unlock();throw error;}
    }
    public Map<String,Object> cleanup(String id)throws Exception{
        if(!lock.tryLock())throw new IllegalStateException("Environment busy; poll the original operation");
        try{var m=manifest(id);if(inUse.test(id))throw new IllegalStateException("Environment is in use by an owned client; stop/cleanup that client first");if(!Set.of("ready","failed","cleaning").contains(m.get("state")))throw new IllegalStateException("Incomplete preparation: inspect original process before cleanup");
            if("cleaning".equals(m.get("state"))){var actual=inventory(folder(id));if(!(m.get("files") instanceof Map<?,?> expected)||!(actual.get("files") instanceof Map<?,?> remaining)||!expected.entrySet().containsAll(remaining.entrySet())||!(m.get("directories") instanceof List<?> dirs)||!dirs.containsAll((List<?>)actual.get("directories")))throw new IOException("Residual environment changed; preserve for review");}else verifyInventory(id,m);
            Path directory=folder(id);m.put("state","cleaning");write(directory.resolve("manifest.json"),m);
            // Delete only the recorded inventory. A newly added file is never swept up
            // by a second recursive walk; nonempty directories then remain for review.
            var files=(Map<?,?>)m.get("files");var directories=(List<?>)m.get("directories");
            var ownedFiles=new ArrayList<Path>();var ownedDirs=new ArrayList<Path>();
            for(Object name:files.keySet())ownedFiles.add(ownedPath(directory,String.valueOf(name)));
            for(Object name:directories)ownedDirs.add(ownedPath(directory,String.valueOf(name)));
            for(Path p:ownedFiles){String name=directory.relativize(p).toString().replace('\\','/');if(Files.exists(p)){if(!Files.isRegularFile(p)||!hash(p).equals(files.get(name)))throw new IOException("Owned file changed during cleanup");deleteOwned(p);}}
            for(Path p:ownedDirs.stream().sorted(Comparator.reverseOrder()).toList())if(Files.exists(p)){ownedPath(directory,directory.relativize(p).toString());deleteOwned(p);}
            if(id.equals(activeId()))deleteOwned(root.resolve("active.json"));deleteOwned(directory.resolve("manifest.json"));
            try{deleteOwned(directory);}catch(Exception locked){write(directory.resolve("manifest.json"),m);throw locked;}return Map.of("state","cleaned","cleaned",true,"environment_id",id);
        }finally{lock.unlock();}
    }
    private static Path ownedPath(Path directory,String name)throws Exception{
        Path relative=Path.of(name);Path path=directory.resolve(relative).normalize();
        if(relative.isAbsolute()||path.equals(directory)||!path.startsWith(directory)||name.equals("manifest.json"))throw new IOException("Invalid owned environment path");
        if(Files.exists(path)&&(Files.isSymbolicLink(path)||!path.toRealPath().startsWith(directory.toRealPath())))throw new IOException("Owned environment path moved");return path;
    }
    private static void deleteOwned(Path p)throws Exception{for(int i=0;;i++)try{Files.deleteIfExists(p);return;}catch(FileSystemException locked){if(i>=20)throw locked;Thread.sleep(100);}}
    public Map<String,Object> prepareJob(Map<String,Object> args){
        synchronized(PREPARATIONS){String source=String.valueOf(args.getOrDefault("python_path","auto"));var jobs=BackgroundJobRegistry.getInstance();var old=PREPARATIONS.get(root);
            if(old!=null){var status=jobs.getStatus(old.job());if(status.isPresent()&&!Set.of("DONE","FAILED").contains(status.get().getState().name())){if(!source.equals(old.source()))throw new IllegalStateException("Another Python preparation is running");return Map.of("job_id",old.job(),"reused",true,"next_tool","edt_operation_status");}}
            String job=jobs.startJob("python-environment-prepare",progress->JSON.toJson(prepare(args,progress)));PREPARATIONS.put(root,new Preparation(job,source));return Map.of("job_id",job,"next_tool","edt_operation_status");
        }
    }
    private static Output runProcess(List<String> command,Path cwd,Map<String,String> environment,int timeout)throws Exception{
        var builder=new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);builder.environment().clear();builder.environment().putAll(environment);Process process=builder.start();
        var pool=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"bridge-python-environment-output");t.setDaemon(true);return t;});
        var output=pool.submit(()->{try(var in=process.getInputStream()){byte[] data=in.readNBytes(65537);if(data.length>65536)throw new IOException("Subprocess output exceeds limit");return new String(data,StandardCharsets.UTF_8);}});
        try{String text=output.get(timeout,TimeUnit.SECONDS);if(!process.waitFor(5,TimeUnit.SECONDS))throw new TimeoutException();return new Output(process.exitValue(),text);}
        catch(Exception failure){
            // A dead parent can hide descendants that inherited a pipe. Best-effort stop
            // does not prove the whole tree is gone: preserve an interrupted setup.
            boolean interrupted=failure instanceof InterruptedException||Thread.currentThread().isInterrupted();
            try{
                var children=process.descendants().toList();for(var child:children)try{child.destroyForcibly();}catch(Exception pending){failure.addSuppressed(pending);}
                process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);
                for(var child:children)try{child.onExit().get(3,TimeUnit.SECONDS);}catch(Exception pending){failure.addSuppressed(pending);}
            }catch(Exception pending){if(pending instanceof InterruptedException)interrupted=true;failure.addSuppressed(pending);}
            if(interrupted)Thread.currentThread().interrupt();
            throw new PreparationUnproven("Subprocess completion/termination was not proven; preserve its original setup",failure);
        }finally{pool.shutdownNow();}
    }
}
