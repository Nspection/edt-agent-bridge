// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.google.gson.*;
/** Local, exact-scope and revocable launch/use authorization. Never changes platform trust. */
public final class RuntimeConsentPolicy {
 private static final Object LOCK=new Object();
 private static final Gson JSON=new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
 private final Path root;
 public RuntimeConsentPolicy(Path root)throws Exception{this.root=root.toAbsolutePath().normalize();Files.createDirectories(this.root);checkRoot();}
 public static RuntimeConsentPolicy workspace()throws Exception{return new RuntimeConsentPolicy(org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/runtime-consent"));}
 public static void assertToolAllowed(String tool){com.codepilot1c.core.mcp.host.McpOperationAuthorization.requireAllowed(tool);}
 public static Map<String,Object> scope(String project,String capability,Map<String,Object> artifact)throws Exception{
  var runtime=new com.codepilot1c.core.edt.runtime.EdtRuntimeService();var base=runtime.resolveDefaultInfobase(project);if(base==null)throw new IllegalStateException("Bound base required");
  var binding=new com.codepilot1c.core.edt.infobase.RegisteredInfobaseGateway().describeScope(project,base.getUuid().toString());var scope=new LinkedHashMap<String,Object>();
  for(String k:List.of("project","project_path","context","base_uuid","target_identity_sha256","primary_uuid")){var value=binding.get(k);if(value==null)throw new IllegalStateException("Incomplete consent scope");scope.put(k,value);}
  if(!scope.get("base_uuid").equals(scope.get("primary_uuid")))throw new IllegalStateException("Primary base changed");scope.put("capability",capability);scope.put("artifact",artifact);return scope;
 }
 private void checkRoot()throws Exception{if(Files.isSymbolicLink(root)||!root.toRealPath().equals(root))throw new IllegalStateException("Consent storage moved");}
 private Path file(String id)throws Exception{checkRoot();if(!id.matches("consent-[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid consent id");var p=root.resolve(id+".json");if(Files.isSymbolicLink(p))throw new IllegalStateException("Linked consent file");return p;}
 private static String id(Map<String,Object> scope){return "consent-"+TaskSessionService.fingerprint(scope);}
 private Map<String,Object> readScope(Map<String,Object> scope)throws Exception{String id=id(scope);if(!Files.exists(file(id),LinkOption.NOFOLLOW_LINKS))return new LinkedHashMap<>(Map.of("consent_id",id,"scope",scope,"policy","ask","revision","none","used",false));return read(id);}
 @SuppressWarnings("unchecked") private Map<String,Object> read(String id)throws Exception{Path p=file(id);if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)>65536)throw new IllegalStateException("Invalid consent record");Map<String,Object> r=JSON.fromJson(Files.readString(p,StandardCharsets.UTF_8),LinkedHashMap.class);if(!(r.get("scope") instanceof Map<?,?>)||!id.equals(r.get("consent_id"))||!id.equals("consent-"+TaskSessionService.fingerprint(r.get("scope")))||!Set.of("ask","once","remember","deny").contains(r.get("policy"))||!(r.get("revision") instanceof String)||!(r.get("used") instanceof Boolean))throw new IllegalStateException("Invalid consent identity");return r;}
 private void save(Map<String,Object> r)throws Exception{Path p=file((String)r.get("consent_id"));Path tmp=Files.createTempFile(root,"consent-",".tmp");try{Files.writeString(tmp,JSON.toJson(r),StandardCharsets.UTF_8);Files.move(tmp,p,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}}
 public String policyFile(Map<String,Object> lease)throws Exception{return file(Objects.toString(lease.get("consent_id"))).toString();}
 public Map<String,Object> status(Map<String,Object> scope)throws Exception{synchronized(LOCK){return readScope(scope);}}
 public Map<String,Object> preview(Map<String,Object> scope,String policy)throws Exception{synchronized(LOCK){if(!Set.of("once","remember","deny").contains(policy))throw new IllegalArgumentException("policy must be once/remember/deny");var out=new LinkedHashMap<String,Object>();out.put("before",readScope(scope));out.put("policy",policy);out.put("platform_trust_changed",false);out.put("scope",scope);out.put("plan_sha256",TaskSessionService.fingerprint(out));return out;}}
 public Map<String,Object> apply(Map<String,Object> scope,String policy,String expected)throws Exception{synchronized(LOCK){var plan=preview(scope,policy);if(!Objects.equals(expected,plan.get("plan_sha256")))throw new IllegalStateException("STALE_RUNTIME_CONSENT_PREVIEW");var r=new LinkedHashMap<String,Object>(Map.of("consent_id",id(scope),"scope",scope,"policy",policy,"revision",UUID.randomUUID().toString(),"used",false));save(r);return r;}}
 public Map<String,Object> claim(Map<String,Object> scope)throws Exception{synchronized(LOCK){var r=readScope(scope);if(!"remember".equals(r.get("policy"))&&!("once".equals(r.get("policy"))&&!Boolean.TRUE.equals(r.get("used"))))return Map.of();r.put("used",true);save(r);return Map.of("consent_id",r.get("consent_id"),"revision",r.get("revision"),"scope_sha256",TaskSessionService.fingerprint(scope));}}
 public void requireUse(Map<String,Object> scope,Map<String,Object> lease)throws Exception{synchronized(LOCK){var r=readScope(scope);if(!Objects.equals(lease.get("consent_id"),r.get("consent_id"))||!Objects.equals(lease.get("revision"),r.get("revision"))||!Objects.equals(lease.get("scope_sha256"),TaskSessionService.fingerprint(scope))||!Set.of("once","remember").contains(r.get("policy")))throw new IllegalStateException("Runtime consent expired/revoked or scope changed; observation and cleanup remain available");}}
 public Map<String,Object> revokePreview(String id)throws Exception{synchronized(LOCK){var out=new LinkedHashMap<String,Object>(Map.of("before",read(id),"consent_id",id,"policy","ask"));out.put("plan_sha256",TaskSessionService.fingerprint(out));return out;}}
 public Map<String,Object> revoke(String id,String expected)throws Exception{synchronized(LOCK){var plan=revokePreview(id);if(!Objects.equals(expected,plan.get("plan_sha256")))throw new IllegalStateException("STALE_RUNTIME_CONSENT_PREVIEW");var r=new LinkedHashMap<>(TaskSessionService.object(plan.get("before")));r.put("policy","ask");r.put("revision",UUID.randomUUID().toString());save(r);return r;}}
 public static final class Required extends IllegalStateException{private final Map<String,Object> result;public Required(Map<String,Object> result){super("Explicit runtime consent required");this.result=result;}public Map<String,Object> result(){return result;}}
 public static Map<String,Object> call(String tool,Map<String,Object> args){return Map.of("tool","edt.run","arguments",Map.of("op",tool,"args",args));}
 public static Map<String,Object> operation(String tool,Map<String,Object> selector,Map<String,Object> scope,Map<String,Object> args)throws Exception{
  var p=workspace();String action=Objects.toString(args.get("action"));String policy=Objects.toString(args.getOrDefault("policy","once"));
  var allowed=new HashSet<>(selector.keySet());allowed.addAll(Set.of("action","policy","expected_plan_sha256","consent_id","preparation_id"));if(!allowed.containsAll(args.keySet()))throw new IllegalArgumentException("Unsupported runtime consent field");
  return switch(action){case "consent_status"->p.status(scope);case "consent_preview"->p.preview(scope,policy);case "consent_apply"->{var out=new LinkedHashMap<>(p.apply(scope,policy,RuntimeExecutionPlan.required(args,"expected_plan_sha256")));if(!policy.equals("deny")){var next=new LinkedHashMap<>(selector);next.put("action","start");if(args.get("preparation_id") instanceof String prep&&prep.matches("prep-[a-f0-9]{64}"))out.put("next_call",call("prepare",Map.of("action","resume","preparation_id",prep)));else out.put("next_call",call(tool,next));}yield out;}case "consent_revoke_preview"->p.revokePreview(RuntimeExecutionPlan.required(args,"consent_id"));case "consent_revoke"->p.revoke(RuntimeExecutionPlan.required(args,"consent_id"),RuntimeExecutionPlan.required(args,"expected_plan_sha256"));default->throw new IllegalArgumentException("Unknown consent operation");};
 }
 public static Map<String,Object> claimOrRequire(String tool,Map<String,Object> selector,Map<String,Object> scope)throws Exception{
  return claimOrRequire(workspace(),tool,selector,scope);
 }
 static Map<String,Object> claimOrRequire(RuntimeConsentPolicy p,String tool,Map<String,Object> selector,Map<String,Object> scope)throws Exception{
  var lease=p.claim(scope);if(!lease.isEmpty())return lease;
  var options=new ArrayList<Map<String,Object>>();for(String policy:List.of("once","remember","deny")){var plan=p.preview(scope,policy);var args=new LinkedHashMap<>(selector);args.put("action","consent_apply");args.put("policy",policy);args.put("expected_plan_sha256",plan.get("plan_sha256"));options.add(Map.of("policy",policy,"next_call",call(tool,args)));}
  throw new Required(Map.of("state","awaiting_consent","ready",false,"process_started",false,"execution_state","launch_not_dispatched","outcome_unknown",false,"scope",scope,"decision",Map.of("kind","runtime_launch_and_use","requires_explicit_user_authorization",true,"options",options),"platform_trust_changed",false));
 }
}
