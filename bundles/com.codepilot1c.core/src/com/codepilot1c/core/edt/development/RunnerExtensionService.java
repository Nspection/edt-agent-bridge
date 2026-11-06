package com.codepilot1c.core.edt.development;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import java.util.*;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.osgi.framework.Bundle;
import com.google.gson.Gson;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionArguments;
import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
/**
 * Dedicated-manager-only Designer operations. SDK acquire/use pattern adapted from
 * DitriXNew/EDT-MCP DesignerExtensionBackend and PlatformServicesSupport (AGPL-3.0-or-later,
 * Copyright 2026 DitriX). Runtime extension properties are changed through EDT's own session.
 */
public final class RunnerExtensionService {
    private static final String PACKAGE="com._1c.g5.v8.dt.internal.platform.services.core.runtimes.execution.";
    private static final Gson GSON=new Gson();
    private static final Map<String,InfobaseReference> REFERENCES=new java.util.concurrent.ConcurrentHashMap<>();
    public Map<String,Object> prepare(String project,String profileId,boolean apply,String expected) throws Exception {
        try{return prepareInternal(project,profileId,apply,expected);}catch(Exception error){StringBuilder description=new StringBuilder();for(Throwable cause=error;cause!=null;cause=cause.getCause()){if(description.length()>0){description.append(" -> ");}description.append(cause.getClass().getSimpleName()).append(": ").append(String.valueOf(cause.getMessage()));}throw new IllegalStateException(com.codepilot1c.core.logging.LogSanitizer.redactSecrets(description.toString()),error);}
    }
    @SuppressWarnings("unchecked") private Map<String,Object> prepareInternal(String project,String profileId,boolean apply,String expected) throws Exception {
        var document=RunnerProfileStore.workspace().get(project,profileId);
        if(!Boolean.TRUE.equals(document.get("configured"))){return document;}
        Map<String,Object> profile=(Map<String,Object>)document.get("profile");
        Path manager=Path.of((String)profile.get("manager_infobase_path")).toAbsolutePath().normalize();
        if(!RunnerProfileStore.serverTarget(profile))RunnerProfileStore.ensureSeparateBases(manager,Path.of((String)profile.get("infobase_path")));
        String user=(String)profile.getOrDefault("manager_user_name","");
        if(!user.isBlank()){throw new IllegalArgumentException("needs_setup: native manager preparation currently requires the dedicated base's OS-auth profile; no login dialog will be opened");}
        String managerKey=manager.toRealPath().toString().toLowerCase(Locale.ROOT);
        InfobaseReference reference=REFERENCES.computeIfAbsent(managerKey,key->{InfobaseReference created=InfobaseReferences.newFileInfobaseReference(manager.toString());created.setUuid(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)));return created;});
        Path bin=Path.of((String)profile.get("platform_bin")).toAbsolutePath().normalize();
        String version=bin.getParent().getFileName().toString();if(!version.matches("[0-9]+[.][0-9]+[.][0-9]+[.][0-9]+")){version=null;}
        var info=new EdtRuntimeService().resolveThickClientInfo(reference,version);
        if(info==null || !info.component().getFile().toPath().getParent().toAbsolutePath().normalize().equals(bin)){throw new IllegalArgumentException("EDT runtime does not match the profile platform_bin");}
        RuntimeExecutionArguments arguments=new RuntimeExecutionArguments();arguments.setMonitor(new NullProgressMonitor());arguments.setAccess(InfobaseAccess.OS);
        Bundle bundle=Platform.getBundle("com._1c.g5.v8.dt.platform.services.core");if(bundle==null){throw new IllegalStateException("EDT platform services unavailable");}
        Class<?> pluginType=bundle.loadClass("com._1c.g5.v8.dt.internal.platform.services.core.PlatformServicesCore");
        Method getDefault=pluginType.getDeclaredMethod("getDefault");getDefault.setAccessible(true);Object plugin=getDefault.invoke(null);if(plugin==null){bundle.start(Bundle.START_TRANSIENT);plugin=getDefault.invoke(null);}
        Method getInjector=pluginType.getDeclaredMethod("getInjector");getInjector.setAccessible(true);Object injector=getInjector.invoke(plugin);
        Class<?> poolType=bundle.loadClass(PACKAGE+"DesignerSessionPool");Object pool=bundle.loadClass("com.google.inject.Injector").getMethod("getInstance",Class.class).invoke(injector,poolType);
        Class<?> connectionType=bundle.loadClass(PACKAGE+"DesignerAgentConnection"),actionType=bundle.loadClass(PACKAGE+"DesignerAgentConnection$DesignerAgentConnectionAction");
        Object connection=poolType.getMethod("acquire",RuntimeInstallation.class,InfobaseReference.class,RuntimeExecutionArguments.class).invoke(pool,info.installation(),reference,arguments);
        Object action=Proxy.newProxyInstance(actionType.getClassLoader(),new Class<?>[]{actionType},(proxy,method,args)->{
            if(method.getName().equals("run")){Object session=connectionType.getMethod("getSession").invoke(connection);return operation(session,manager,apply,expected);}
            if(method.getName().equals("toString")){return "CodePilot dedicated manager extension preparation";}
            if(method.getName().equals("hashCode")){return System.identityHashCode(proxy);}
            if(method.getName().equals("equals")){return proxy==args[0];}
            throw new UnsupportedOperationException(method.getName());
        });
        try{return (Map<String,Object>)connectionType.getMethod("use",actionType).invoke(connection,action);}
        catch(InvocationTargetException error){Throwable cause=error.getCause();if(cause instanceof Exception e){throw e;}throw error;}
    }
    private Map<String,Object> operation(Object session,Path manager,boolean apply,String expected) throws Exception {
        Map<String,Object> state=read(session);String fingerprint=hash(GSON.toJson(new TreeMap<>(state)));
        boolean prepared=Boolean.TRUE.equals(state.get("installed")) && Boolean.TRUE.equals(state.get("active")) && Boolean.FALSE.equals(state.get("safe_mode")) && Boolean.FALSE.equals(state.get("unsafe_action_protection"));
        Map<String,Object> result=new LinkedHashMap<>();result.put("manager_infobase",manager.toString());result.put("extension",state);result.put("state_sha256",fingerprint);result.put("prepared",prepared);result.put("strategy","edt-designer-session");
        if(!Boolean.TRUE.equals(state.get("installed"))){result.put("state","needs_setup");result.put("note","Install the verified client_mcp extension into this dedicated manager base first");return result;}
        if(!prepared && apply){if(!fingerprint.equalsIgnoreCase(String.valueOf(expected))){throw new IllegalArgumentException("Extension state changed or expected_state_sha256 is missing; request a fresh preview");}
            Object set=call(call(call(session,"extensions"),"properties"),"set");set=call(set,"extension","client_mcp");set=call(set,"safeMode",false);set=call(set,"unsafeActionProtection",false);set=call(set,"active",true);call(set,"exec");
            Map<String,Object> actual=read(session);if(!Boolean.FALSE.equals(actual.get("safe_mode")) || !Boolean.FALSE.equals(actual.get("unsafe_action_protection")) || !Boolean.TRUE.equals(actual.get("active"))){throw new IllegalStateException("Designer extension postcondition failed; inspect state before retrying");}
            result.put("extension",actual);result.put("prepared",true);result.put("restart_required",true);
        }
        result.put("state",Boolean.TRUE.equals(result.get("prepared"))?"prepared":"needs_setup");result.put("scope","Only client_mcp in this dedicated manager base; target infobase untouched");return result;
    }
    private Map<String,Object> read(Object session) throws Exception {
        Object query=call(call(call(session,"extensions"),"properties"),"get");Object rows=call(call(query,"allExtensions"),"exec");
        if(!(rows instanceof Collection<?>)){throw new IllegalStateException("Designer did not return extension inventory");}
        for(Object row:(Collection<?>)rows){if(!"client_mcp".equals(String.valueOf(call(row,"getName")))){continue;}
            return Map.of("installed",true,"name","client_mcp","active",call(row,"isActive"),"safe_mode",call(row,"isSafeMode"),"unsafe_action_protection",call(row,"isUnsafeActionProtected"),"version",String.valueOf(call(row,"getVersion")),"hash",String.valueOf(call(row,"getHashSum")));}
        return Map.of("installed",false,"name","client_mcp");
    }
    private static String hash(String text) throws Exception {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    private static Object call(Object target,String name,Object... args) throws Exception {
        for(Method method:target.getClass().getMethods()){if(method.getName().equals(name) && method.getParameterCount()==args.length){try{method.setAccessible(true);return method.invoke(target,args);}catch(IllegalArgumentException mismatch){/* Select another overload. */}catch(InvocationTargetException e){if(e.getCause() instanceof Exception cause){throw cause;}throw e;}}}
        throw new NoSuchMethodException(target.getClass().getName()+"."+name);
    }
}
