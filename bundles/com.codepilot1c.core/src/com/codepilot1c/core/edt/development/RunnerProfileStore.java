package com.codepilot1c.core.edt.development;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.eclipse.core.resources.ResourcesPlugin;
import com.google.gson.*;
/** Workspace-local runner configuration. No passwords, arbitrary command lines or process ids. */
public final class RunnerProfileStore {
    private static final Gson GSON=new Gson();
    private static final Set<String> FIELDS=Set.of("platform_bin","infobase_path","manager_infobase_path","vanessa_epf","params_template","user_name","manager_user_name","configuration_name","timeout_s","visual_checkpoint","expected_vanessa_sha256");
    private static final List<String> REQUIRED=List.of("platform_bin","infobase_path","manager_infobase_path","vanessa_epf","params_template","expected_vanessa_sha256");
    private final Path storage;
    public RunnerProfileStore(Path storage) throws Exception {this.storage=storage;Files.createDirectories(storage);}
    public static RunnerProfileStore workspace() throws Exception {return new RunnerProfileStore(ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/runner-profiles"));}
    private static String id(String value){String id=value==null?"default":value;if(!id.matches("[A-Za-z0-9_-]{1,64}")){throw new IllegalArgumentException("Invalid profile_id");}return id;}
    private Path file(String project,String profileId) throws Exception {if(project==null || project.isBlank()){throw new IllegalArgumentException("project is required");}String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(project.getBytes(StandardCharsets.UTF_8)));return storage.resolve(hash+"-"+id(profileId)+".json");}
    public static void ensureSeparateBases(Path manager,Path target) throws Exception {
        Path a=manager.toAbsolutePath().normalize(),b=target.toAbsolutePath().normalize();
        Path first=a.resolve("1Cv8.1CD"),second=b.resolve("1Cv8.1CD");
        if(a.equals(b) || Files.exists(first) && Files.exists(second) && Files.isSameFile(first,second)){throw new IllegalArgumentException("Use a dedicated manager base, separate from the target infobase");}
    }
    public synchronized Map<String,Object> put(String project,String profileId,Map<String,Object> profile) throws Exception {
        Set<String> unknown=new TreeSet<>(profile.keySet());unknown.removeAll(FIELDS);if(!unknown.isEmpty()){throw new IllegalArgumentException("Unsupported profile fields (credentials/command lines are not stored): "+unknown);}
        for(String field:REQUIRED){if(!(profile.get(field) instanceof String value) || value.isBlank()){throw new IllegalArgumentException("Missing profile field: "+field);}}
        String expected=(String)profile.get("expected_vanessa_sha256");if(!expected.matches("[A-Fa-f0-9]{64}")){throw new IllegalArgumentException("Expected Vanessa SHA256 must have 64 hexadecimal characters");}
        for(String field:List.of("platform_bin","infobase_path","manager_infobase_path","vanessa_epf","params_template")){if(!Path.of((String)profile.get(field)).isAbsolute()){throw new IllegalArgumentException("Use an absolute path for "+field);}}
        ensureSeparateBases(Path.of((String)profile.get("manager_infobase_path")),Path.of((String)profile.get("infobase_path")));
        Path file=file(project,profileId);Map<String,Object> document=new LinkedHashMap<>();document.put("project",project);document.put("profile_id",id(profileId));document.put("updated_at",Instant.now().toString());document.put("profile",new TreeMap<>(profile));
        Path temporary=Files.createTempFile(storage,"profile-",".json");Files.writeString(temporary,GSON.toJson(document),StandardCharsets.UTF_8);try{Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}return get(project,profileId);
    }
    @SuppressWarnings("unchecked") public synchronized Map<String,Object> get(String project,String profileId) throws Exception {
        Path file=file(project,profileId);if(!Files.isRegularFile(file)){return Map.of("configured",false,"state","needs_setup","project",project,"profile_id",id(profileId),"next_tool","edt_runner_profile","required_fields",REQUIRED,"note","Provision a dedicated TestManager once, save its verified paths and call edt_runner_prepare. Do not repeatedly open clients or ask users to approve each library.");}
        Map<String,Object> document=GSON.fromJson(Files.readString(file,StandardCharsets.UTF_8),Map.class);if(!project.equals(document.get("project")) || !id(profileId).equals(document.get("profile_id"))){throw new IllegalStateException("Profile identity does not match the request");}document.put("configured",true);document.put("state","configured_not_live_checked");document.put("next_tool","edt_runner_prepare");return document;
    }
    @SuppressWarnings("unchecked") public Map<String,Object> resolve(Map<String,Object> arguments) throws Exception {
        Map<String,Object> result=new LinkedHashMap<>(arguments);boolean needsProfile=arguments.containsKey("profile_id") || REQUIRED.stream().anyMatch(key->!arguments.containsKey(key));if(!needsProfile){return selectedFeatures(result);}
        String project=(String)arguments.get("project");Map<String,Object> document=get(project,(String)arguments.get("profile_id"));if(!Boolean.TRUE.equals(document.get("configured"))){throw new IllegalStateException("needs_setup: runner profile is missing; call edt_runner_profile(action=get) before starting clients");}
        result=new LinkedHashMap<>((Map<String,Object>)document.get("profile"));result.putAll(arguments);return selectedFeatures(result);
    }
    private Map<String,Object> selectedFeatures(Map<String,Object> arguments) throws Exception {
        if(arguments.containsKey("feature_paths")){
            Path original=Path.of((String)arguments.get("params_template"));Path selected=RunnerScenarioParams.selected(storage,original,arguments.get("feature_paths"),!Boolean.FALSE.equals(arguments.get("visual_checkpoint")));arguments.put("params_template",selected.toString());
        }return arguments;
    }
}
