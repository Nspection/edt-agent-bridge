// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.nio.file.*;
import java.util.*;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import com.codepilot1c.core.mcp.host.McpOperationAuthorization;
/** Agent-driven reporting. Only the user's preferences UI may enable sharing or approve text. */
public final class BugReportService {
 interface Backend {
  BugReportGitHub.Account account()throws Exception;
  BugReportGitHub.Receipt post(String account,String title,String body)throws Exception;
  Optional<BugReportGitHub.Receipt> find(String marker,String body,String account)throws Exception;
  void login()throws Exception;
 }
 private final Path root;private final BugReportOutbox outbox;private final Backend backend;private final Runnable authorize;private final String version;
 public static BugReportService workspace()throws Exception{
  long epoch=RuntimeProcessLifecycle.global().epoch();var github=new BugReportGitHub();var bundle=Platform.getBundle("com.codepilot1c.core");
  return new BugReportService(ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(".metadata/.plugins/com.codepilot1c.core/bug-reports"),new Backend(){
   public BugReportGitHub.Account account()throws Exception{return github.account();}
   public BugReportGitHub.Receipt post(String account,String title,String body)throws Exception{RuntimeProcessLifecycle.global().require(epoch);return github.post(account,title,body);}
   public Optional<BugReportGitHub.Receipt> find(String marker,String body,String account)throws Exception{return github.find(marker,body,account);}
   public void login()throws Exception{github.login();}
  },()->{RuntimeProcessLifecycle.global().require(epoch);McpOperationAuthorization.requireAllowed("edt_bug_report");},bundle==null?"unknown":bundle.getVersion().toString());
 }
 BugReportService(Path root,Backend backend,Runnable authorize,String version)throws Exception{this.root=root;this.backend=backend;this.authorize=authorize;this.version=version;outbox=new BugReportOutbox(root.resolve("outbox"));}
 @SuppressWarnings("unchecked") private Map<String,Object> config()throws Exception{
  Path path=root.resolve("policy.json");if(!Files.exists(path))return new LinkedHashMap<>(Map.of("mode","off","revision","unconfigured","account_id","","login",""));
  if(Files.isSymbolicLink(path)||Files.size(path)>8192)throw new IllegalStateException("Report policy unavailable");var config=BugReportOutbox.JSON.fromJson(Files.readString(path),LinkedHashMap.class);if(config==null||!Set.of("off","ask","auto_sanitized").contains(BugReportOutbox.text(config,"mode"))||!BugReportOutbox.text(config,"account_id").matches("[0-9]{0,20}")||!BugReportOutbox.text(config,"login").matches("[A-Za-z0-9-]{0,39}")||!BugReportOutbox.text(config,"revision").matches("[a-f0-9-]{36}"))throw new IllegalStateException("Invalid report policy; sending disabled");return config;
 }
 private void save(Map<String,Object> config)throws Exception{
  Path target=root.resolve("policy.json"),temp=root.resolve("policy.tmp");if(Files.isSymbolicLink(target)||Files.isSymbolicLink(temp))throw new IllegalStateException("Report policy changed");
  byte[] bytes=BugReportOutbox.JSON.toJson(config).getBytes(java.nio.charset.StandardCharsets.UTF_8);
  try(var channel=java.nio.channels.FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
  Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
 }
 /** Called only from the explicit Connect button. Never called by MCP/automatic reporting. */
 public Map<String,Object> connectFromUserInterface()throws Exception{backend.login();var account=backend.account();return outbox.locked(()->{var config=config();config.put("account_id",account.id());config.put("login",account.login());config.put("mode","ask");config.put("revision",UUID.randomUUID().toString());save(config);return config;});}
 /** Does not erase shared Git credentials. Changing account/policy invalidates old approvals. */
 public void policyFromUserInterface(String mode)throws Exception{
  if(!Set.of("off","ask","auto_sanitized").contains(mode))throw new IllegalArgumentException("Unsupported report policy");
  outbox.locked(()->{var config=config();if(!mode.equals("off")&&BugReportOutbox.text(config,"account_id").isBlank())throw new IllegalStateException("Connect GitHub first");if(!mode.equals(config.get("mode"))){config.put("mode",mode);config.put("revision",UUID.randomUUID().toString());save(config);}return null;});
 }
 public Map<String,Object> status()throws Exception{return outbox.locked(()->{var config=config();return Map.of("target","Nspection/edt-agent-bridge","policy",config.get("mode"),"configured_account",config.get("login"),"account_live_verified",false,"sensitive_attachments_supported",false,"retention","submitted receipts retained for deduplication; up to 200 pending reports; unknown receipts are never deleted automatically");});}
 public Map<String,Object> prepare(String component,Map<String,Object> diagnostics,boolean verified)throws Exception{var config=outbox.locked(this::config);String account=BugReportOutbox.text(config,"account_id");return outbox.prepare(BugReportEnvelope.collect(component,version,diagnostics,verified),account,BugReportOutbox.text(config,"revision"));}
 public Map<String,Object> result(String id)throws Exception{return outbox.status(id);}
 public Map<String,Object> latestPrepared()throws Exception{return outbox.locked(()->{try(var paths=Files.list(root.resolve("outbox"))){var candidates=paths.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).sorted(Comparator.comparingLong((Path p)->p.toFile().lastModified()).reversed()).limit(200).toList();for(Path path:candidates){var row=outbox.read(path.getFileName().toString().substring(0,64));if("prepared".equals(row.get("state")))return BugReportOutbox.view(row);}return Map.of("state","no_prepared_report");}});}
 public void approveFromUserInterface(String id,String hash)throws Exception{outbox.locked(()->{var row=outbox.read(id);var config=config();requireBinding(row,config,hash);if(!"ask".equals(config.get("mode"))||!"prepared".equals(row.get("state")))throw new IllegalStateException("Report cannot be approved in current policy/state");row.put("approved_sha256",hash);outbox.write(row);return null;});}
 private static void requireBinding(Map<String,Object> row,Map<String,Object> config,String hash){
  if(!Objects.equals(hash,row.get("payload_sha256"))||!Objects.equals(row.get("account_id"),config.get("account_id"))||!Objects.equals(row.get("policy_revision"),config.get("revision")))throw new IllegalStateException("Stale report preview; prepare again for the current account and policy");
 }
 private void requireSendPolicy(Map<String,Object> row,Map<String,Object> config,String hash){
  requireBinding(row,config,hash);String mode=BugReportOutbox.text(config,"mode");
  if(mode.equals("off"))throw new IllegalStateException("Reporting disabled");
  if(!mode.equals("auto_sanitized")&&!Objects.equals(hash,row.get("approved_sha256")))throw new IllegalStateException("Review this exact report in EDT preferences before submit");
 }
 public Map<String,Object> submit(String id,String hash)throws Exception{
  authorize.run();var preflight=outbox.locked(()->{var row=outbox.read(id);if("prepared".equals(row.get("state")))requireSendPolicy(row,config(),hash);return row;});
  if(!"prepared".equals(preflight.get("state")))return BugReportOutbox.view(preflight);
  // No filesystem/policy/UI lock is held during credential or network operations.
  var account=backend.account();
  var claimed=outbox.locked(()->{
   var row=outbox.read(id);if(!"prepared".equals(row.get("state")))return Map.<String,Object>of("claimed",false,"record",row);
   authorize.run();requireSendPolicy(row,config(),hash);
   if(!account.id().equals(row.get("account_id")))throw new IllegalStateException("GitHub account changed; reconnect and prepare a fresh report");
   String day=java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();Path budget=root.resolve("daily-budget.json");long sends=0;
   if(Files.exists(budget)){if(Files.isSymbolicLink(budget)||Files.size(budget)>1024)throw new IllegalStateException("Report quota unavailable");var prior=com.google.gson.JsonParser.parseString(Files.readString(budget)).getAsJsonObject();if(day.equals(prior.get("day").getAsString()))sends=prior.get("count").getAsLong();}
   if(sends<0||sends>=5)throw new IllegalStateException("Report daily quota reached; no new POST");
   // Reserve budget first: a crash may conservatively spend a slot, never permit excess POSTs.
   Path temp=root.resolve("daily-budget.tmp");if(Files.isSymbolicLink(temp))throw new IllegalStateException("Report quota changed");byte[] bytes=BugReportOutbox.JSON.toJson(Map.of("day",day,"count",sends+1)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
   try(var channel=java.nio.channels.FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}Files.move(temp,budget,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
   row.put("state","sending");row.put("dispatch_day",day);outbox.write(row);return Map.<String,Object>of("claimed",true,"record",row);
  });
  @SuppressWarnings("unchecked") var row=(Map<String,Object>)claimed.get("record");
  if(!Boolean.TRUE.equals(claimed.get("claimed")))return BugReportOutbox.view(row);
  // The durable sending claim marks an in-flight operation. Revocation blocks all later claims.
  BugReportGitHub.Receipt receipt;
  try{receipt=backend.post(account.id(),BugReportOutbox.text(row,"title"),BugReportOutbox.text(row,"body"));}
  catch(Exception failure){return outbox.locked(()->finishNetwork(row,null));}
  return outbox.locked(()->finishNetwork(row,receipt));
 }
 private Map<String,Object> finishNetwork(Map<String,Object> snapshot,BugReportGitHub.Receipt receipt)throws Exception{
  var current=outbox.read(BugReportOutbox.text(snapshot,"report_id"));
  for(String field:List.of("report_id","marker","account_id","payload_sha256"))if(!Objects.equals(current.get(field),snapshot.get(field)))throw new IllegalStateException("Report changed during delivery; acknowledgement not applied");
  if("submitted".equals(current.get("state")))return BugReportOutbox.view(current);
  if(!Set.of("sending","delivery_unknown").contains(BugReportOutbox.text(current,"state")))throw new IllegalStateException("Report delivery claim changed");
  if(receipt!=null){current.put("state","submitted");current.put("issue_number",receipt.number());current.put("issue_url",receipt.url());current.remove("reconcile_required");}
  else{current.put("state","delivery_unknown");current.put("reconcile_required",true);}
  outbox.write(current);return BugReportOutbox.view(current);
 }
 public Map<String,Object> reconcile(String id)throws Exception{
  var row=outbox.locked(()->outbox.read(id));if(!Set.of("sending","delivery_unknown").contains(row.get("state")))return BugReportOutbox.view(row);
  var account=backend.account();if(!account.id().equals(row.get("account_id")))throw new IllegalStateException("Reconnect original GitHub account to reconcile this report");
  var found=backend.find(BugReportOutbox.text(row,"marker"),BugReportOutbox.text(row,"body"),account.id());
  return outbox.locked(()->found.isPresent()?finishNetwork(row,found.get()):BugReportOutbox.view(outbox.read(id)));
 }
}
