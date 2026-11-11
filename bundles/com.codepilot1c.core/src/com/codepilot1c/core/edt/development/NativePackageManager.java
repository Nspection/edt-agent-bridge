// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.eclipse.core.runtime.preferences.InstanceScope;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;

/** Shared lifecycle for the startup warmup, settings button and MCP. */
public final class NativePackageManager {
 private static final NativePackageManager INSTANCE=new NativePackageManager();
 private final CopyOnWriteArrayList<Consumer<Map<String,Object>>> listeners=new CopyOnWriteArrayList<>();
 private volatile String job;private volatile long generation;private volatile boolean stopped;
 private volatile Map<String,Object> last=Map.of("state","not_checked","ready",false);
 private NativePackageManager(){}
 public static NativePackageManager getInstance(){return INSTANCE;}
 public boolean automatic(){return InstanceScope.INSTANCE.getNode("com.codepilot1c.core").getBoolean("nativePackage.automatic",true);}
 public Map<String,Object> configure(boolean enabled)throws Exception {
  var preferences=InstanceScope.INSTANCE.getNode("com.codepilot1c.core");preferences.putBoolean("nativePackage.automatic",enabled);preferences.flush();var notification=new LinkedHashMap<String,Object>(last);notification.put("automatic_download",enabled);publish(generation,notification);var out=new LinkedHashMap<String,Object>();out.put("automatic_download",enabled);out.put("configuration_updated",false);out.put("trust_changed",false);if(enabled)out.putAll(prepare());return out;
 }
 public synchronized void start(){stopped=false;generation++;if(automatic())prepare();}
 public synchronized void stop(){stopped=true;generation++;listeners.clear();}
 private boolean running(){String id=job;return id!=null&&BackgroundJobRegistry.getInstance().getStatus(id).map(s->!Set.of("DONE","FAILED").contains(s.getState().name())).orElse(false);}
 private static Map<String,Object> next(String id){return WorkflowReplies.job(id);}
 public synchronized Map<String,Object> prepare(){
  if(stopped)throw new IllegalStateException("Native package lifecycle stopped");if(running())return Map.of("job_id",job,"state","preparing","ready",false,"next_call",next(job));
  long lifecycle=generation;var gate=new CountDownLatch(1);
  String id=BackgroundJobRegistry.getInstance().startJob("native-package",()->{
   gate.await();try {
    var result=NativePackageService.workspace().ensure(()->stopped||generation!=lifecycle,stage->{var p=Map.<String,Object>of("state","preparing","stage",stage,"ready",false);publish(lifecycle,p);});
    result.put("automatic_download",automatic());publish(lifecycle,result);return new com.google.gson.Gson().toJson(result);
   }catch(Exception error){var failure=Map.<String,Object>of("state","failed","ready",false,"reason",reason(error),"error_type",error.getClass().getSimpleName(),"retry_safe",true,"execution_state","not_dispatched");publish(lifecycle,failure);throw new java.io.IOException("Native package "+reason(error));}
  });
  job=id;last=Map.of("job_id",id,"state","preparing","ready",false,"next_call",next(id));gate.countDown();return new LinkedHashMap<>(last);
 }
 private static String reason(Exception error){String text=Objects.toString(error.getMessage(),"");if(text.contains("SHA256")||text.contains("manifest")||text.contains("ZIP")||text.contains("architecture"))return "integrity_check_failed";if(text.contains("capacity"))return "cache_capacity";if(text.contains("cancel"))return "cancelled";if(text.contains("origin")||text.contains("redirect"))return "download_origin_rejected";if(text.matches("Native download HTTP [0-9]{3}"))return "http_"+text.substring(text.length()-3);return "download_or_cache_unavailable";}
 private void publish(long lifecycle,Map<String,Object> value){if(stopped||generation!=lifecycle)return;last=Map.copyOf(value);for(var listener:listeners)try{listener.accept(last);}catch(RuntimeException ignored){}}
 public Map<String,Object> status()throws Exception {
  Map<String,Object> out;if(running()){out=new LinkedHashMap<>(last);out.put("job_id",job);out.put("next_call",next(job));}
  else {out=new LinkedHashMap<>(NativePackageService.workspace().status());if(!Boolean.TRUE.equals(out.get("ready"))&&last.containsKey("reason"))out.putAll(AgentBrief.select(last,"reason","error_type","retry_safe"));}
  var attempt=AgentBrief.select(last,"state","stage","cache_hit","reason","error_type");if(job!=null){attempt.put("job_id",job);attempt.put("historical_result",!running());out.put("last_attempt",attempt);}
  out.put("automatic_download",automatic());out.put("configuration_updated",false);out.put("trust_changed",false);
  if(!Boolean.TRUE.equals(out.get("ready"))&&!running())out.put("next_call",Map.of("tool","edt.run","arguments",Map.of("op","edt_runtime_host","args",Map.of("action","prepare_package"))));
  return out;
 }
 /** UI reads only this in-memory view; verification/network stay on workers. */
 public Map<String,Object> peek(){return last;}
 public AutoCloseable listen(Consumer<Map<String,Object>> listener){listeners.add(listener);listener.accept(last);return ()->listeners.remove(listener);}
 public Map<String,Object> ensure()throws Exception {return NativePackageService.workspace().ensure(()->stopped,stage->{});}
 public Map<String,Object> resolve(Map<String,Object> args)throws Exception {
  if(args.containsKey("native_path")!=args.containsKey("expected_native_sha256"))throw new IllegalArgumentException("Provide both native_path and expected_native_sha256, or omit both for the bundled package");
  if(args.containsKey("native_path"))return args;
  var packageResult=ensure();if(!Boolean.TRUE.equals(packageResult.get("ready")))throw new IllegalStateException("Native package not verified");var result=new LinkedHashMap<>(args);result.put("native_path",packageResult.get("native_path"));result.put("expected_native_sha256",packageResult.get("expected_native_sha256"));return result;
 }
}
