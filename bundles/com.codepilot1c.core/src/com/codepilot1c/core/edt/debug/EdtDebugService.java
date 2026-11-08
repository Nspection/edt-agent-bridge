/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.debug;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com.codepilot1c.core.edt.ast.BreakpointInfo;
import com.codepilot1c.core.edt.ast.DebugStatusRequest;
import com.codepilot1c.core.edt.ast.DebugStatusResult;
import com.codepilot1c.core.edt.ast.DebugVariableInfo;
import com.codepilot1c.core.edt.ast.EdtAstErrorCode;
import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.ast.EdtServiceGateway;
import com.codepilot1c.core.edt.ast.EvaluateExpressionRequest;
import com.codepilot1c.core.edt.ast.EvaluateExpressionResult;
import com.codepilot1c.core.edt.ast.GetVariablesRequest;
import com.codepilot1c.core.edt.ast.GetVariablesResult;
import com.codepilot1c.core.edt.ast.ListBreakpointsRequest;
import com.codepilot1c.core.edt.ast.ListBreakpointsResult;
import com.codepilot1c.core.edt.ast.RemoveBreakpointRequest;
import com.codepilot1c.core.edt.ast.RemoveBreakpointResult;
import com.codepilot1c.core.edt.ast.ResumeRequest;
import com.codepilot1c.core.edt.ast.ResumeResult;
import com.codepilot1c.core.edt.ast.SetBreakpointRequest;
import com.codepilot1c.core.edt.ast.SetBreakpointResult;
import com.codepilot1c.core.edt.ast.StepRequest;
import com.codepilot1c.core.edt.ast.StepResult;
import com.codepilot1c.core.edt.ast.WaitForBreakRequest;
import com.codepilot1c.core.edt.ast.WaitForBreakResult;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Debug facade for EDT/1C debug operations.
 *
 * <p>The service intentionally talks to Eclipse Debug Framework and EDT debug
 * services through reflection. This keeps the OSS core resilient across EDT
 * versions while the concrete {@code com._1c.g5.v8.dt.debug.*} service binding
 * is filled in incrementally.</p>
 */
public class EdtDebugService {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtDebugService.class);
    private static final String DEBUG_CORE_BUNDLE = "org.eclipse.debug.core"; //$NON-NLS-1$
    private static final String DEBUG_PLUGIN_CLASS = "org.eclipse.debug.core.DebugPlugin"; //$NON-NLS-1$
    private static final String ATTR_PROJECT_NAME = "com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME"; //$NON-NLS-1$

    private static volatile EdtDebugService instance;

    private final EdtServiceGateway gateway;
    private final java.util.Map<String,Object> ownedLaunches = new java.util.LinkedHashMap<>();

    public EdtDebugService(EdtServiceGateway gateway) {
        this.gateway = gateway;
    }

    public static EdtDebugService getInstance() {
        EdtDebugService local = instance;
        if (local == null) {
            synchronized (EdtDebugService.class) {
                local = instance;
                if (local == null) {
                    local = new EdtDebugService(new EdtServiceGateway());
                    instance = local;
                }
            }
        }
        return local;
    }

    public SetBreakpointResult setBreakpoint(SetBreakpointRequest request) {
        request.validate();
        IProject project = requireProject(request.projectName());
        String filePath = normalizeSourcePath(request.filePath());
        org.eclipse.core.resources.IFile file = project.getFile("src/" + filePath);
        if (!file.exists()) {
            throw invalid("BSL source file not found: " + filePath);
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(file.getContents(), java.nio.charset.StandardCharsets.UTF_8))) {
            long lines = reader.lines().count();
            if (request.line() > lines) {
                throw invalid("Breakpoint line exceeds source length: " + request.line());
            }
        } catch (org.eclipse.core.runtime.CoreException | java.io.IOException e) {
            throw internal("Cannot read breakpoint source", e);
        }
        Object breakpoint = findBreakpoint(request.projectName(), filePath, request.line());
        boolean created = breakpoint == null;
        if (created) {
            breakpoint = invoke(breakpointFactory(), "createLineBreakpoint", file, request.line());
        }
        try {
            invoke(breakpoint, "setCondition", request.condition() == null ? "" : request.condition());
            boolean enabled = request.enabled() == null || request.enabled();
            invoke(breakpoint, "setEnabled", enabled);
            if (created) {
                invoke(breakpointManager(), "addBreakpoint", breakpoint);
            }
            Object marker = invoke(breakpoint, "getMarker");
            return new SetBreakpointResult(request.projectName(), filePath, request.line(),
                    stringValue(invoke(marker, "getId")), created, enabled,
                    created ? "created" : "updated", "BSL breakpoint registered with EDT");
        } catch (RuntimeException e) {
            if (created) {
                invokeOptional(breakpoint, "delete");
            }
            throw internal("Failed to configure BSL breakpoint", e);
        }
    }

    public RemoveBreakpointResult removeBreakpoint(RemoveBreakpointRequest request) {
        request.validate();
        requireProject(request.projectName());
        Object breakpoint = findBreakpoint(request.projectName(), request.breakpointId(), request.filePath(), request.line());
        if (breakpoint == null) {
            return new RemoveBreakpointResult(request.projectName(), request.breakpointId(), false,
                    "not_found", "Breakpoint was not found"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        try {
            invoke(breakpoint, "delete"); //$NON-NLS-1$
            return new RemoveBreakpointResult(request.projectName(), request.breakpointId(), true,
                    "removed", "Breakpoint removed"); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (RuntimeException e) {
            throw internal("Failed to remove breakpoint", e); //$NON-NLS-1$
        }
    }

    public ListBreakpointsResult listBreakpoints(ListBreakpointsRequest request) {
        request.validate();
        if (request.projectName() != null) {
            requireProject(request.projectName());
        }
        return new ListBreakpointsResult(request.projectName(), listBreakpointInfos(request.projectName()));
    }

    public WaitForBreakResult waitForBreak(WaitForBreakRequest request) {
        request.validate();
        requireProject(request.projectName());
        long started = System.currentTimeMillis();
        long deadline = started + request.timeoutMs();
        while (System.currentTimeMillis() <= deadline) {
            DebugThreadSelection selection = findThread(request.projectName(), null, true);
            if (selection != null) {
                return new WaitForBreakResult(request.projectName(), true, selection.threadId(),
                        "suspended", System.currentTimeMillis() - started, false); //$NON-NLS-1$
            }
            sleepQuietly(200L);
        }
        return new WaitForBreakResult(request.projectName(), false, null,
                "timeout", System.currentTimeMillis() - started, true); //$NON-NLS-1$
    }

    public GetVariablesResult getVariables(GetVariablesRequest request) {
        request.validate();
        requireProject(request.projectName());
        DebugFrameSelection frame = findFrame(request.projectName(), request.threadId(), request.frameId());
        if (frame == null) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "No suspended stack frame found for project " + request.projectName(), true); //$NON-NLS-1$
        }
        List<DebugVariableInfo> variables = new ArrayList<>();
        for (Object variable : readVariableSnapshot(frame.frame(),frame.frame(),false).variables()) { //$NON-NLS-1$
            variables.add(toVariableInfo(variable));
        }
        return new GetVariablesResult(request.projectName(), frame.threadId(), frame.frameId(), variables);
    }

    public StepResult step(StepRequest request) {
        request.validate();
        requireProject(request.projectName());
        DebugThreadSelection selection = findThread(request.projectName(), request.threadId(), true);
        if (selection == null) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "No suspended debug thread found for project " + request.projectName(), true); //$NON-NLS-1$
        }
        String kind = request.normalizedKind();
        String capability = switch (kind) {
            case "into" -> "canStepInto"; //$NON-NLS-1$ //$NON-NLS-2$
            case "over" -> "canStepOver"; //$NON-NLS-1$ //$NON-NLS-2$
            case "out" -> "canStepReturn"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> "canStepOver"; //$NON-NLS-1$
        };
        String operation = switch (kind) {
            case "into" -> "stepInto"; //$NON-NLS-1$ //$NON-NLS-2$
            case "over" -> "stepOver"; //$NON-NLS-1$ //$NON-NLS-2$
            case "out" -> "stepReturn"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> "stepOver"; //$NON-NLS-1$
        };
        if (!booleanValue(invokeOptional(selection.thread(), capability), true)) {
            return new StepResult(request.projectName(), selection.threadId(), kind,
                    "not_available", "Selected thread cannot perform " + kind); //$NON-NLS-1$ //$NON-NLS-2$
        }
        invoke(selection.thread(), operation);
        return new StepResult(request.projectName(), selection.threadId(), kind,
                "started", "Step command sent"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public ResumeResult resume(ResumeRequest request) {
        request.validate();
        requireProject(request.projectName());
        DebugThreadSelection selection = findThread(request.projectName(), request.threadId(), false);
        if (selection == null) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "No debug thread found for project " + request.projectName(), true); //$NON-NLS-1$
        }
        Object resumable = selection.thread();
        if (!booleanValue(invokeOptional(resumable, "canResume"), true)) { //$NON-NLS-1$
            resumable = selection.target();
        }
        if (!booleanValue(invokeOptional(resumable, "canResume"), true)) { //$NON-NLS-1$
            return new ResumeResult(request.projectName(), selection.threadId(),
                    "not_available", "Debug target cannot resume"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        invoke(resumable, "resume"); //$NON-NLS-1$
        return new ResumeResult(request.projectName(), selection.threadId(),
                "resumed", "Resume command sent"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public java.util.Map<String,Object> inspectDebug(String projectName,String selectedThread,String selectedFrame,boolean includeVariables,java.util.List<String> variablePath,int offset,int limit) {
        requireProject(projectName);
        if(offset<0 || limit<1 || limit>200){throw invalid("offset>=0 and limit between 1 and 200 are required");}
        java.util.List<java.util.Map<String,Object>> launches=new java.util.ArrayList<>();
        int suspendedThreads=0;
        java.util.Set<Object> seenSuspendedThreads=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for(Object launch:getLaunches()) {
            if(!matchesProject(launch,projectName)){continue;}
            java.util.Map<String,Object> launchInfo=new java.util.LinkedHashMap<>();launchInfo.put("launch_id",debugObjectId(launch));
            launchInfo.put("mode",stringValue(invokeOptional(launch,"getLaunchMode")));
            launchInfo.put("terminated",booleanValue(invokeOptional(launch,"isTerminated"),false));
            java.util.List<java.util.Map<String,Object>> targets=new java.util.ArrayList<>();
            for(Object target:toArray(invokeOptional(launch,"getDebugTargets"))) {
                java.util.Map<String,Object> targetInfo=new java.util.LinkedHashMap<>();targetInfo.put("target_id",debugObjectId(target));targetInfo.put("name",stringValue(invokeOptional(target,"getName")));
                java.util.List<java.util.Map<String,Object>> threads=new java.util.ArrayList<>();
                for(Object thread:toArray(invokeOptional(target,"getThreads"))) {
                    java.util.Map<String,Object> threadInfo=new java.util.LinkedHashMap<>();String id=debugObjectId(thread);threadInfo.put("thread_id",id);threadInfo.put("name",stringValue(invokeOptional(thread,"getName")));
                    boolean suspended=booleanValue(invokeOptional(thread,"isSuspended"),false);threadInfo.put("suspended",suspended);
                    if(suspended && seenSuspendedThreads.add(thread)){suspendedThreads++;}
                    if(suspended && (selectedThread==null || selectedThread.equals(id))) {
                        java.util.List<java.util.Map<String,Object>> frames=new java.util.ArrayList<>();
                        for(Object frame:toArray(invokeOptional(thread,"getStackFrames"))) {
                            if(frames.size()>=100){break;}
                            java.util.Map<String,Object> row=new java.util.LinkedHashMap<>();row.put("frame_id",debugObjectId(frame));row.put("name",stringValue(invokeOptional(frame,"getName")));row.put("line",invokeOptional(frame,"getLineNumber"));row.put("source",stringValue(invokeOptional(frame,"getSource")));frames.add(row);
                        }
                        threadInfo.put("frames",frames);
                        java.util.List<String> stopped=new java.util.ArrayList<>();for(Object bp:toArray(invokeOptional(thread,"getBreakpoints"))){Object marker=invokeOptional(bp,"getMarker");stopped.add(stringValue(invokeOptional(marker,"getId")));}threadInfo.put("breakpoint_ids",stopped);
                    }
                    threads.add(threadInfo);
                }
                targetInfo.put("threads",threads);targets.add(targetInfo);
            }
            launchInfo.put("targets",targets);launches.add(launchInfo);
        }
        java.util.Map<String,Object> out=new java.util.LinkedHashMap<>();out.put("project",projectName);out.put("launches",launches);out.put("suspended_threads",suspendedThreads);
        if(includeVariables) {
            if(selectedThread==null && suspendedThreads!=1){throw invalid("Select thread_id when there is not exactly one suspended thread");}
            DebugFrameSelection frame=findFrame(projectName,selectedThread,selectedFrame);
            if(frame==null){throw invalid("Suspended frame not found");}
            VariableSnapshot snapshot=readVariableSnapshot(frame.frame(),frame.frame(),false);
            Object[] variables=snapshot.variables();
            for(String name:variablePath) {
                Object match=null;
                for(Object variable:variables){if(name.equals(stringValue(invokeOptional(variable,"getName")))){if(match!=null){throw invalid("Ambiguous variable path: "+name);}match=variable;}}
                if(match==null){throw invalid("Variable path not found: "+name);}
                Object value=invoke(match,"getValue");snapshot=readVariableSnapshot(value,frame.frame(),true);variables=snapshot.variables();
            }
            java.util.List<DebugVariableInfo> rows=new java.util.ArrayList<>();for(int i=offset;i<variables.length && rows.size()<limit;i++){rows.add(toVariableInfo(variables[i]));}
            out.put("variables_state",snapshot.pending()?"pending":"ready");
            out.put("thread_id",frame.threadId());out.put("frame_id",frame.frameId());out.put("variable_path",variablePath);out.put("variables",rows);out.put("variable_count",variables.length);out.put("has_more",offset+rows.size()<variables.length);
        }
        return out;
    }

    record VariableSnapshot(Object[] variables,boolean pending) {}
    static VariableSnapshot readVariableSnapshot(Object owner,Object frame,boolean children) {
        Object[] values=toArray(invoke(owner,"getVariables"));
        if(!pendingVariables(owner,values)){
            Object chain=children?(booleanValue(invokeOptional(owner,"hasVariables"),false) && !booleanValue(invokeOptional(owner,"isEvaluated"),true)?invokeOptional(owner,"reevaluate"):null):invokeOptional(owner,"reevaluateVariables");
            if(chain!=null && intValue(invokeOptional(chain,"size"),0)>0){
                Object engine=invoke(invoke(frame,"getDebugTarget"),"getEvaluationEngine");
                Object target=invoke(invoke(frame,"getThread"),"getRuntimeDebugTarget");invoke(engine,"evaluateExpressions",chain,target);
            }
        }
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        boolean pending;
        do{values=toArray(invoke(owner,"getVariables"));pending=pendingVariables(owner,values);if(!pending){return new VariableSnapshot(values,false);}sleepQuietly(50);}while(System.nanoTime()<deadline);
        return new VariableSnapshot(values,true);
    }
    private static boolean pendingVariables(Object owner,Object[] variables) {
        if(booleanValue(invokeOptional(owner,"isPending"),false)){return true;}
        for(Object variable:variables){if(variable!=null && "BslPlaceholderVariable".equals(variable.getClass().getSimpleName())){return true;}Object value=invokeOptional(variable,"getValue");if(value==null || "BslPlaceholderValue".equals(value.getClass().getSimpleName()) || booleanValue(invokeOptional(value,"isPending"),false)){return true;}}
        return false;
    }

    public synchronized java.util.Map<String,Object> exceptionBreakpoint(String action,String breakpointId,String message,Boolean catchAll,boolean enabled) {
        if("remove".equals(action)) {
            for(Object bp:getBreakpoints()) {
                if(!(invokeOptional(bp,"isCatchAllExceptions") instanceof Boolean)){continue;}
                String id=stringValue(invoke(invoke(bp,"getMarker"),"getId"));
                if(id.equals(breakpointId)){invoke(bp,"delete");return java.util.Map.of("scope","workspace","breakpoint_id",id,"removed",true);}
            }
            return java.util.Map.of("scope","workspace","removed",false);
        }
        if(!"set".equals(action)){throw invalid("action must be set or remove");}
        boolean all=catchAll==null?(message==null || message.isBlank()):catchAll;
        if(!all && (message==null || message.isBlank())){throw invalid("message is required when catch_all=false");}
        String filter=all?"":message;String fingerprint=all+"|"+filter;
        for(Object existing:getBreakpoints()){
            if(!(invokeOptional(existing,"isCatchAllExceptions") instanceof Boolean)){continue;}
            Object marker=invoke(existing,"getMarker");
            if(fingerprint.equals(invokeOptional(marker,"getAttribute","com.codepilot1c.exceptionFingerprint",""))){invoke(existing,"setEnabled",enabled);return java.util.Map.of("scope","workspace","breakpoint_id",stringValue(invoke(marker,"getId")),"enabled",enabled,"catch_all",all,"message",filter,"reused",true);}
        }
        Object bp=filter.isBlank()?invoke(breakpointFactory(),"createExceptionBreakpoint"):invoke(breakpointFactory(),"createExceptionBreakpoint",filter);
        try {
            invoke(invoke(bp,"getMarker"),"setAttribute","com.codepilot1c.exceptionFingerprint",fingerprint);invoke(bp,"setCatchAllExceptions",all);invoke(bp,"setEnabled",enabled);invoke(breakpointManager(),"addBreakpoint",bp);
            return java.util.Map.of("scope","workspace","breakpoint_id",stringValue(invoke(invoke(bp,"getMarker"),"getId")),"enabled",enabled,"catch_all",all,"message",message==null?"":message);
        } catch(RuntimeException error){invokeOptional(bp,"delete");throw error;}
    }

    public java.util.Map<String,Object> runToLine(String projectName,String threadId,String path,int line) {
        org.eclipse.core.resources.IProject project=requireProject(projectName);
        String normalized=normalizeSourcePath(path);org.eclipse.core.resources.IFile file=project.getFile("src/"+normalized);
        if(!file.exists() || line<1){throw invalid("Existing source and positive line are required");}
        try(var input=file.getContents()) {String source=new String(input.readAllBytes(),java.nio.charset.Charset.forName(file.getCharset()));if(line>source.lines().count()){throw invalid("Requested line is outside the source file");}}
        catch(java.io.IOException | org.eclipse.core.runtime.CoreException error){throw invalid("Cannot read requested source: "+error.getMessage());}
        DebugThreadSelection thread=findThread(projectName,threadId,true);
        if(thread==null || !booleanValue(invokeOptional(thread.thread(),"canResume"),false)){throw invalid("Selected thread cannot resume");}
        Object bp=invoke(breakpointFactory(),"createRunToLineBreakpoint",file,line);
        try {
            invoke(bp,"setEnabled",true);invoke(breakpointManager(),"addBreakpoint",bp);invoke(thread.thread(),"resume");
            return java.util.Map.of("project",projectName,"thread_id",thread.threadId(),"breakpoint_id",stringValue(invoke(invoke(bp,"getMarker"),"getId")),"status","run_to_line_sent","file_path",normalized,"line",line);
        } catch(RuntimeException error){invokeOptional(bp,"delete");throw error;}
    }

    public EvaluateExpressionResult evaluateExpression(EvaluateExpressionRequest request) {
        request.validate();
        requireProject(request.projectName());
        DebugFrameSelection frame = findFrame(request.projectName(), request.threadId(), request.frameId());
        if (frame == null) {
            throw invalid("No suspended stack frame found for project " + request.projectName());
        }
        Object target = invoke(frame.frame(), "getDebugTarget");
        Object engine = invoke(target, "getEvaluationEngine");
        try {
            Bundle bundle = requireBslDebugBundle();
            Class<?> pathType = bundle.loadClass("com._1c.g5.v8.dt.debug.core.model.values.BslValuePath");
            Object path = pathType.getConstructor(String.class).newInstance(request.expression());
            Class<?> requestType = bundle.loadClass("com._1c.g5.v8.dt.debug.core.model.evaluation.EvaluationRequest");
            Class<?> listenerType = bundle.loadClass("com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationListener");
            java.util.concurrent.CompletableFuture<Object> completed = new java.util.concurrent.CompletableFuture<>();
            Object listener = java.lang.reflect.Proxy.newProxyInstance(listenerType.getClassLoader(),
                    new Class<?>[] {listenerType}, (proxy, method, args) -> {
                        if ("evaluationComplete".equals(method.getName())) {
                            completed.complete(args[0]);
                        } else if ("toString".equals(method.getName())) {
                            return "CodePilotEvaluationListener";
                        } else if ("hashCode".equals(method.getName())) {
                            return System.identityHashCode(proxy);
                        } else if ("equals".equals(method.getName())) {
                            return proxy == args[0];
                        }
                        return null;
                    });
            invoke(engine, "evaluateExpression", buildEvaluationRequest(requestType, path, frame.frame(), listener));
            Object result = completed.get(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!booleanValue(invoke(result, "isSuccess"), false)) {
                return new EvaluateExpressionResult(request.projectName(), request.expression(), false,
                        null, null, "evaluation_error", stringValue(invoke(result, "getErrorMessage")));
            }
            Object data = invoke(result, "getResult");
            Object value = invoke(data, "getResultValueInfo");
            Object presentation = invokeOptional(value, "getPres");
            String text = presentation instanceof byte[] bytes
                    ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8) : stringValue(presentation);
            return new EvaluateExpressionResult(request.projectName(), request.expression(), true,
                    text, stringValue(invokeOptional(value, "getTypeName")), "evaluated", "Expression evaluated");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw internal("Expression evaluation interrupted", e);
        } catch (java.util.concurrent.TimeoutException e) {
            throw serviceUnavailable("Expression evaluation timed out after 15 seconds", e);
        } catch (ReflectiveOperationException | java.util.concurrent.ExecutionException e) {
            throw internal("Expression evaluation failed", e);
        }
    }

    static Object buildEvaluationRequest(Class<?> requestType, Object path, Object frame, Object listener) {
        Object builder = invokeStatic(requestType, "builder", path);
        invoke(builder, "setStackFrame", frame);
        invoke(builder, "setExpressionUuid", java.util.UUID.randomUUID());
        invoke(builder, "setInterfaces", java.util.List.of());
        invoke(builder, "setEvaluationListener", listener);
        invoke(builder, "setMaxTestSize", 8192);
        return invoke(builder, "build");
    }

    public DebugStatusResult debugStatus(DebugStatusRequest request) {
        request.validate();
        if (request.projectName() != null) {
            requireProject(request.projectName());
        }
        Object[] launches = getLaunches();
        int launchCount = 0;
        int targetCount = 0;
        boolean suspended = false;
        String activeThreadId = null;
        for (Object launch : launches) {
            if (!matchesProject(launch, request.projectName())) {
                continue;
            }
            launchCount++;
            for (Object target : toArray(invokeOptional(launch, "getDebugTargets"))) { //$NON-NLS-1$
                targetCount++;
                for (Object thread : toArray(invokeOptional(target, "getThreads"))) { //$NON-NLS-1$
                    if (booleanValue(invokeOptional(thread, "isSuspended"), false)) { //$NON-NLS-1$
                        suspended = true;
                        activeThreadId = debugObjectId(thread);
                        break;
                    }
                }
            }
        }
        int breakpointCount = listBreakpointInfos(request.projectName()).size();
        String state = suspended ? "suspended" : (targetCount > 0 ? "running" : "inactive"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return new DebugStatusResult(request.projectName(), state, suspended, launchCount, targetCount,
                breakpointCount, activeThreadId, "Debug status collected"); //$NON-NLS-1$
    }

    private List<BreakpointInfo> listBreakpointInfos(String projectName) {
        List<BreakpointInfo> result = new ArrayList<>();
        for (Object breakpoint : getBreakpoints()) {
            BreakpointInfo info = toBreakpointInfo(breakpoint);
            if (info != null && (projectName == null || projectName.equals(info.projectName()))) {
                result.add(info);
            }
        }
        return result;
    }

    private Object findBreakpoint(String projectName, String breakpointId, String filePath, Integer line) {
        for (Object breakpoint : getBreakpoints()) {
            BreakpointInfo info = toBreakpointInfo(breakpoint);
            if (info == null || !projectName.equals(info.projectName())) {
                continue;
            }
            if (breakpointId != null && breakpointId.equals(info.id())) {
                return breakpoint;
            }
            if (filePath != null && line != null && normalizeSourcePath(filePath).equals(info.filePath()) && line.intValue() == info.line()) {
                return breakpoint;
            }
        }
        return null;
    }

    private BreakpointInfo toBreakpointInfo(Object breakpoint) {
        try {
            Object marker = invokeOptional(breakpoint, "getMarker"); //$NON-NLS-1$
            if (marker == null) {
                return null;
            }
            Object resource = invokeOptional(marker, "getResource"); //$NON-NLS-1$
            Object project = resource == null ? null : invokeOptional(resource, "getProject"); //$NON-NLS-1$
            String projectName = project == null ? null : stringValue(invokeOptional(project, "getName")); //$NON-NLS-1$
            String filePath = resource == null ? null : stringValue(invokeOptional(resource, "getProjectRelativePath")); //$NON-NLS-1$
            if (filePath != null && filePath.startsWith("src/")) { filePath = filePath.substring(4); }
            int line = intValue(invokeOptional(marker, "getAttribute", "lineNumber", Integer.valueOf(-1)), -1); //$NON-NLS-1$ //$NON-NLS-2$
            String id = stringValue(invokeOptional(marker, "getId")); //$NON-NLS-1$
            boolean enabled = booleanValue(invokeOptional(breakpoint, "isEnabled"), true); //$NON-NLS-1$
            String condition = stringValue(invokeOptional(breakpoint, "getCondition")); //$NON-NLS-1$
            String model = stringValue(invokeOptional(breakpoint, "getModelIdentifier")); //$NON-NLS-1$
            return new BreakpointInfo(id, projectName, filePath, line, enabled, condition, model);
        } catch (RuntimeException e) {
            LOG.debug("Skipping unreadable breakpoint: %s", e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private DebugThreadSelection findThread(String projectName, String threadId, boolean requireSuspended) {
        for (Object launch : getLaunches()) {
            if (!matchesProject(launch, projectName)) {
                continue;
            }
            for (Object target : toArray(invokeOptional(launch, "getDebugTargets"))) { //$NON-NLS-1$
                for (Object thread : toArray(invokeOptional(target, "getThreads"))) { //$NON-NLS-1$
                    String candidateId = debugObjectId(thread);
                    String candidateName = stringValue(invokeOptional(thread, "getName")); //$NON-NLS-1$
                    boolean idMatches = threadId == null || threadId.equals(candidateId) || threadId.equals(candidateName);
                    boolean stateMatches = !requireSuspended
                            || booleanValue(invokeOptional(thread, "isSuspended"), false); //$NON-NLS-1$
                    if (idMatches && stateMatches) {
                        return new DebugThreadSelection(launch, target, thread, candidateId);
                    }
                }
            }
        }
        return null;
    }

    private DebugFrameSelection findFrame(String projectName, String threadId, String frameId) {
        DebugThreadSelection thread = findThread(projectName, threadId, true);
        if (thread == null) {
            return null;
        }
        for (Object frame : toArray(invoke(thread.thread(), "getStackFrames"))) { //$NON-NLS-1$
            String candidateId = debugObjectId(frame);
            String candidateName = stringValue(invokeOptional(frame, "getName")); //$NON-NLS-1$
            if (frameId == null || frameId.equals(candidateId) || frameId.equals(candidateName)) {
                return new DebugFrameSelection(thread.threadId(), candidateId, frame);
            }
        }
        return null;
    }

    private DebugVariableInfo toVariableInfo(Object variable) {
        String name = stringValue(invokeOptional(variable, "getName")); //$NON-NLS-1$
        String type = stringValue(invokeOptional(variable, "getReferenceTypeName")); //$NON-NLS-1$
        Object value = invokeOptional(variable, "getValue"); //$NON-NLS-1$
        String valueText = value == null ? null : stringValue(invokeOptional(value, "getValueString")); //$NON-NLS-1$
        if (type == null && value != null) {
            type = stringValue(invokeOptional(value, "getReferenceTypeName")); //$NON-NLS-1$
        }
        boolean hasChildren = value != null && booleanValue(invokeOptional(value, "hasVariables"), false); //$NON-NLS-1$
        return new DebugVariableInfo(name, type, valueText, hasChildren);
    }

    protected Object breakpointManager() {
        return invoke(getDebugPlugin(), "getBreakpointManager");
    }

    private Object[] getBreakpoints() {
        return toArray(invoke(breakpointManager(), "getBreakpoints"));
    }

    private Object[] getLaunches() {
        Object plugin = getDebugPlugin();
        Object manager = invoke(plugin, "getLaunchManager"); //$NON-NLS-1$
        return toArray(invoke(manager, "getLaunches")); //$NON-NLS-1$
    }

    protected Object getDebugPlugin() {
        try {
            Bundle bundle = Platform.getBundle(DEBUG_CORE_BUNDLE);
            if (bundle == null) {
                throw serviceUnavailable("Eclipse debug core bundle is unavailable"); //$NON-NLS-1$
            }
            Class<?> debugPluginClass = bundle.loadClass(DEBUG_PLUGIN_CLASS);
            Object plugin = invokeStatic(debugPluginClass, "getDefault"); //$NON-NLS-1$
            if (plugin == null) {
                throw serviceUnavailable("Eclipse debug plugin is not initialized"); //$NON-NLS-1$
            }
            return plugin;
        } catch (ClassNotFoundException e) {
            throw serviceUnavailable("Eclipse debug plugin class is unavailable", e); //$NON-NLS-1$
        }
    }

    private boolean matchesProject(Object launch, String projectName) {
        if (projectName == null) {
            return true;
        }
        Object configuration = invokeOptional(launch, "getLaunchConfiguration"); //$NON-NLS-1$
        if (configuration != null) {
            String configuredProject = stringValue(invokeOptional(configuration, "getAttribute", ATTR_PROJECT_NAME, "")); //$NON-NLS-1$ //$NON-NLS-2$
            if (projectName.equals(configuredProject)) {
                return true;
            }
        }
        String launchName = stringValue(invokeOptional(launch, "getLaunchMode")); //$NON-NLS-1$
        return launchName != null && launchName.contains(projectName);
    }

    protected IProject requireProject(String projectName) {
        IProject project = gateway.resolveProject(projectName);
        if (project == null || !project.exists()) {
            throw new EdtAstException(EdtAstErrorCode.PROJECT_NOT_FOUND,
                    "EDT project not found: " + projectName, true); //$NON-NLS-1$
        }
        return project;
    }


    static String normalizeSourcePath(String path) {
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            throw invalid("Expected a project-relative BSL source path");
        }
        for (String segment : normalized.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw invalid("Invalid source path segment");
            }
        }
        if (normalized.startsWith("src/")) { normalized = normalized.substring(4); }
        if (!normalized.endsWith(".bsl")) { throw invalid("Expected a BSL module"); }
        return normalized;
    }

    private Object findBreakpoint(String projectName, String filePath, int line) {
        return findBreakpoint(projectName, null, filePath, Integer.valueOf(line));
    }

    protected Object breakpointFactory() {
        try {
            Bundle bundle = requireBslDebugBundle();
            Class<?> factoryType = bundle.loadClass(
                    "com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslBreakpointFactory");
            Object plugin = invokeStatic(bundle.loadClass(
                    "com._1c.g5.v8.dt.internal.debug.core.DebugCorePlugin"), "getDefault");
            Method accessor = plugin.getClass().getDeclaredMethod("getInjector");
            accessor.setAccessible(true);
            Object injector = accessor.invoke(plugin);
            return invoke(injector, "getInstance", factoryType);
        } catch (ReflectiveOperationException e) {
            throw serviceUnavailable("EDT BSL breakpoint factory is unavailable", e);
        }
    }

    private static Bundle requireBslDebugBundle() {
        Bundle bundle = Platform.getBundle("com._1c.g5.v8.dt.debug.core");
        if (bundle == null) { throw serviceUnavailable("EDT BSL debug bundle is unavailable"); }
        try {
            if (bundle.getState() != Bundle.ACTIVE) { bundle.start(Bundle.START_TRANSIENT); }
        } catch (org.osgi.framework.BundleException e) {
            throw serviceUnavailable("Cannot activate EDT BSL debug bundle", e);
        }
        return bundle;
    }

    /** SDK launch attributes cross-checked with DitriXNew/EDT-MCP CreateLaunchConfigTool (AGPL-3.0-or-later). */
    public synchronized java.util.Map<String,Object> prepareDebugConfiguration(String projectName, String requestedName, boolean dryRun) {
        IProject project=requireProject(projectName);
        String name=requestedName==null || requestedName.isBlank()?"CodePilot "+projectName+" (debug)":requestedName;
        Object manager=invoke(getDebugPlugin(),"getLaunchManager");
        for(Object existing:toArray(invoke(manager,"getLaunchConfigurations"))){
            if(!name.equals(stringValue(invoke(existing,"getName")))){continue;}
            if(!projectName.equals(stringValue(invokeOptional(existing,"getAttribute",ATTR_PROJECT_NAME,""))) || !"com._1c.g5.v8.dt.launching.core.RuntimeClient".equals(stringValue(invoke(invoke(existing,"getType"),"getIdentifier")))){throw invalid("Launch configuration name is owned by another project/type: "+name);}
            String applicationId=defaultApplicationId(project);String key="com._1c.g5.v8.dt.debug.core.ATTR_APPLICATION_ID";
            boolean changed=!applicationId.equals(stringValue(invokeOptional(existing,"getAttribute",key,"")));
            if(changed && !dryRun){Object working=invoke(existing,"getWorkingCopy");invoke(working,"setAttribute",key,applicationId);invoke(working,"doSave");}
            return java.util.Map.of("project",projectName,"configuration",name,"application_id",applicationId,"created",false,"binding_changed",changed,"dry_run",dryRun);
        }
        String applicationId=defaultApplicationId(project);
        if(dryRun){return java.util.Map.of("project",projectName,"configuration",name,"application_id",applicationId,"created",false,"dry_run",true);}
        Object type=invoke(manager,"getLaunchConfigurationType","com._1c.g5.v8.dt.launching.core.RuntimeClient");
        Object copy=invoke(type,"newInstance",null,name);
        java.util.Map<String,Object> attributes=new java.util.LinkedHashMap<>();
        attributes.put(ATTR_PROJECT_NAME,projectName);
        attributes.put("com._1c.g5.v8.dt.debug.core.ATTR_APPLICATION_ID",applicationId);
        attributes.put("process_factory_id","com._1c.g5.v8.dt.debug.core.RuntimeProcessFactory");
        attributes.put("com._1c.g5.v8.dt.launching.core.ATTR_CLIENT_AUTO_SELECT",false);
        attributes.put("com._1c.g5.v8.dt.launching.core.ATTR_CLIENT_TYPE","com._1c.g5.v8.dt.platform.services.core.componentTypes.ThinClient");
        attributes.put("com._1c.g5.v8.dt.debug.core.ATTR_RUNTIME_INSTALLATION_USE_AUTO",true);
        attributes.put("com._1c.g5.v8.dt.debug.core.ATTR_USE_LOCAL_DEBUG_SERVER","AUTO");
        String prefix="com._1c.g5.v8.dt.launching.core.";
        attributes.put(prefix+"ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS",true);
        attributes.put(prefix+"ATTR_CALL_DELAY",145);attributes.put(prefix+"ATTR_DATA_SENDING_DELAY",45);attributes.put(prefix+"ATTR_DATA_RECEIVING_DELAY",15);
        attributes.put(prefix+"ATTR_DO_NOT_DISPLAY_WARNINGS",true);attributes.put(prefix+"ATTR_SHOW_PERFORMANCE",true);attributes.put(prefix+"ATTR_SHOW_ALL_FUNCTIONS",true);
        for(var entry:attributes.entrySet()){invoke(copy,"setAttribute",entry.getKey(),entry.getValue());}
        invoke(copy,"setMappedResources",(Object)new org.eclipse.core.resources.IResource[]{project});
        invoke(copy,"doSave");
        return java.util.Map.of("project",projectName,"configuration",name,"application_id",applicationId,"created",true,"dry_run",false);
    }

    protected String defaultApplicationId(IProject project) {
        var context=org.osgi.framework.FrameworkUtil.getBundle(EdtDebugService.class).getBundleContext();
        var reference=context.getServiceReference(com.e1c.g5.dt.applications.IApplicationManager.class);
        if(reference==null){throw serviceUnavailable("EDT application manager is unavailable");}
        String applicationId;
        try{var applications=context.getService(reference);if(applications==null){throw serviceUnavailable("EDT application manager stopped");}applicationId=applications.getDefaultApplication(project).orElseThrow(()->invalid("Project has no default EDT application")).getId();}
        catch(com.e1c.g5.dt.applications.ApplicationException e){throw serviceUnavailable("Cannot resolve EDT application: "+e.getMessage());}
        finally{context.ungetService(reference);}
        return applicationId;
    }

    private final java.util.Set<Object> closedOwnedLaunches=java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final java.util.Map<String,java.util.Set<Object>> ownedDebugTargets=new java.util.HashMap<>();
    public synchronized java.util.Map<String, Object> launchDebugSession(String projectName, String configurationName,
            boolean dryRun) {
        IProject selectedProject=requireProject(projectName);
        Object manager = invoke(getDebugPlugin(), "getLaunchManager");
        List<Object> candidates = new ArrayList<>();
        for (Object config : toArray(invoke(manager, "getLaunchConfigurations"))) {
            if (!projectName.equals(stringValue(invokeOptional(config, "getAttribute", ATTR_PROJECT_NAME, "")))) {
                continue;
            }
            String name = stringValue(invoke(config, "getName"));
            String type = stringValue(invoke(invoke(config, "getType"), "getIdentifier"));
            if (configurationName != null ? configurationName.equals(name)
                    : "com._1c.g5.v8.dt.launching.core.RuntimeClient".equals(type)) {
                candidates.add(config);
            }
        }
        if (candidates.size() != 1) {
            throw invalid("Select exactly one saved EDT debug configuration for " + projectName
                    + "; matching configurations: " + candidates.size());
        }
        Object config = candidates.get(0);
        String type = stringValue(invoke(invoke(config, "getType"), "getIdentifier"));
        String applicationId=stringValue(invokeOptional(config,"getAttribute","com._1c.g5.v8.dt.debug.core.ATTR_APPLICATION_ID",""));
        if(applicationId!=null && !applicationId.isBlank() && !applicationId.equals(defaultApplicationId(selectedProject))){throw invalid("Saved debug configuration is bound to another application; run edt_debug_configure for the current project/base before launching");}
        if ("com._1c.g5.v8.dt.debug.core.RemoteRuntime".equals(type)) {
            String url = stringValue(invokeOptional(config, "getAttribute",
                    "com._1c.g5.v8.dt.debug.core.ATTR_DEBUG_SERVER_URL", ""));
            try {
                java.net.URI uri = java.net.URI.create(url == null ? "" : url);
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null) { throw new IllegalArgumentException(); }
            } catch (IllegalArgumentException e) {
                throw invalid("RemoteRuntime requires a valid debug server URL; select a RuntimeClient "
                        + "configuration to start a local debug session");
            }
        }
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("project_name", projectName);
        result.put("configuration", invoke(config, "getName"));
        result.put("mode", "debug");
        result.put("dry_run", dryRun);
        if (dryRun) { result.put("status", "dry_run"); return result; }
        Object launch = null;
        for (Object existing : getLaunches()) {
            Object saved = invokeOptional(existing, "getLaunchConfiguration");
            if (!closedOwnedLaunches.contains(existing) && saved != null && (config.equals(saved) || sameDebugApplication(saved,config,projectName,toArray(invokeOptional(existing,"getDebugTargets"))))
                    && "debug".equals(stringValue(invokeOptional(existing, "getLaunchMode")))
                    && !booleanValue(invokeOptional(existing, "isTerminated"), true)
                    && java.util.Arrays.stream(toArray(invokeOptional(existing,"getDebugTargets"))).anyMatch(t->!booleanValue(invokeOptional(t,"isTerminated"),false) && !booleanValue(invokeOptional(t,"isDisconnected"),false))) {
                launch = existing;
                break;
            }
        }
        String sessionId=null;
        java.util.Set<Object> previousTargets=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());for(Object current:getLaunches()){previousTargets.addAll(java.util.Arrays.asList(toArray(invokeOptional(current,"getDebugTargets"))));}
        if (launch == null) {
            ownedLaunches.entrySet().removeIf(entry->booleanValue(invokeOptional(entry.getValue(),"isTerminated"),false));
            if(ownedLaunches.size()>=32){throw serviceUnavailable("Too many active CodePilot debug launches");}
            launch = invoke(config, "launch", "debug", null, Boolean.FALSE);
            sessionId=java.util.UUID.randomUUID().toString();ownedLaunches.put(sessionId,launch);
        }
        if(sessionId!=null){result.put("owned_debug_session_id",sessionId);java.util.Set<Object> targets=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());for(Object target:toArray(invokeOptional(launch,"getDebugTargets"))){if(!previousTargets.contains(target)){targets.add(target);}}ownedDebugTargets.put(sessionId,targets);}
        List<String> urls = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 10000;
        do {
            for (Object target : toArray(invokeOptional(launch, "getDebugTargets"))) {
                if (booleanValue(invokeOptional(target, "isTerminated"), false) || booleanValue(invokeOptional(target,"isDisconnected"),false)) { continue; }
                String url = stringValue(invokeOptional(target, "getDebugServerUrl"));
                if (url != null && !url.isBlank() && !urls.contains(url)) { urls.add(url); }
            }
            if (urls.isEmpty()) { sleepQuietly(200); }
        } while (urls.isEmpty() && System.currentTimeMillis() < deadline);
        if(sessionId!=null){var targets=ownedDebugTargets.get(sessionId);for(Object target:toArray(invokeOptional(launch,"getDebugTargets"))){if(!previousTargets.contains(target)){targets.add(target);}}}
        if (urls.isEmpty()) {
            if(sessionId!=null){closeOwnedDebugSession(projectName,sessionId);}
            throw serviceUnavailable("EDT launch did not expose an active debug target with a server URL; "
                    + "check EDT launch errors and pending dialogs");
        }
        result.put("status", "debug_started");
        result.put("debug_server_urls", urls);
        return result;
    }

    private static boolean sameDebugApplication(Object existing,Object requested,String project,Object[] targets){
        if(!project.equals(stringValue(invokeOptional(existing,"getAttribute",ATTR_PROJECT_NAME,"")))){return false;}
        String key="com._1c.g5.v8.dt.debug.core.ATTR_APPLICATION_ID";
        String expected=stringValue(invokeOptional(requested,"getAttribute",key,""));if(expected==null || expected.isBlank()){return false;}
        if(expected.equals(stringValue(invokeOptional(existing,"getAttribute",key,"")))){return true;}
        for(Object target:targets){Object app=invokeOptional(target,"getApplication");if(app instanceof java.util.Optional<?> optional && optional.isPresent() && expected.equals(stringValue(invokeOptional(optional.get(),"getId")))){return true;}}
        return false;
    }

    public synchronized void closeOwnedDebugSession(String projectName,String sessionId) {
        Object launch=ownedLaunches.get(sessionId);if(launch==null){return;}
        Object config=invokeOptional(launch,"getLaunchConfiguration");
        if(config==null || !projectName.equals(stringValue(invokeOptional(config,"getAttribute",ATTR_PROJECT_NAME,"")))){throw invalid("Debug launch ownership mismatch");}
        if(!booleanValue(invokeOptional(launch,"isTerminated"),false)){invoke(launch,"terminate");}
        // DtLaunch intentionally preserves its shared debug target. Terminate only targets
        // created by this exact owned launch, never a target that existed before it.
        for(Object target:ownedDebugTargets.getOrDefault(sessionId,java.util.Set.of())){
            if(!booleanValue(invokeOptional(target,"isTerminated"),false)){invoke(target,"terminate");}
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while(!booleanValue(invokeOptional(target,"isTerminated"),true) && System.nanoTime()<deadline){sleepQuietly(50);}
            if(!booleanValue(invokeOptional(target,"isTerminated"),true)){throw serviceUnavailable("Owned EDT debug target did not terminate; inspect session before retrying");}
        }
        closedOwnedLaunches.add(launch);ownedDebugTargets.remove(sessionId);ownedLaunches.remove(sessionId);
    }

    private static EdtAstException invalid(String message) {
        return new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT, message, true);
    }

    private static Object invokeStatic(Class<?> type, String methodName, Object... args) {
        return invokeMethod(null, type, methodName, args, false);
    }

    private static Object invoke(Object target, String methodName, Object... args) {
        if (target == null) {
            throw new IllegalArgumentException("Cannot call " + methodName + " on null"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return invokeMethod(target, target.getClass(), methodName, args, false);
    }

    private static Object invokeOptional(Object target, String methodName, Object... args) {
        if (target == null) {
            return null;
        }
        return invokeMethod(target, target.getClass(), methodName, args, true);
    }

    private static Object invokeMethod(Object target, Class<?> type, String methodName, Object[] args, boolean optional) {
        Method method = findMethod(type, methodName, args);
        if (method == null) {
            if (optional) {
                return null;
            }
            throw new IllegalArgumentException("Method not found: " + type.getName() + "." + methodName); //$NON-NLS-1$ //$NON-NLS-2$
        }
        try {
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (IllegalAccessException e) {
            if (optional) {
                return null;
            }
            throw new IllegalStateException("Cannot access method: " + methodName, e); //$NON-NLS-1$
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (optional) {
                return null;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Method failed: " + methodName, cause); //$NON-NLS-1$
        }
    }

    private static Method findMethod(Class<?> type, String methodName, Object[] args) {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != args.length) {
                continue;
            }
            if (parametersMatch(method.getParameterTypes(), args)) {
                return method;
            }
        }
        return null;
    }

    private static boolean parametersMatch(Class<?>[] parameterTypes, Object[] args) {
        for (int i = 0; i < parameterTypes.length; i++) {
            if (args[i] == null) {
                continue;
            }
            Class<?> expected = wrap(parameterTypes[i]);
            if (!expected.isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }

    private static Object[] toArray(Object value) {
        if (value == null) {
            return new Object[0];
        }
        if (value instanceof Object[] array) {
            return array;
        }
        if (!value.getClass().isArray()) {
            return new Object[0];
        }
        int length = Array.getLength(value);
        Object[] array = new Object[length];
        for (int i = 0; i < length; i++) {
            array[i] = Array.get(value, i);
        }
        return array;
    }

    private static String debugObjectId(Object value) {
        if (value == null) {
            return null;
        }
        return value.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(value)); //$NON-NLS-1$
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intValue(Object value, int defaultValue) {
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private static boolean booleanValue(Object value, boolean defaultValue) {
        return value instanceof Boolean bool ? bool.booleanValue() : defaultValue;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdtAstException(EdtAstErrorCode.INTERNAL_ERROR,
                    "Interrupted while waiting for debug break", true, e); //$NON-NLS-1$
        }
    }

    private static EdtAstException unsupported(String message) {
        return new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE, message, true);
    }

    private static EdtAstException serviceUnavailable(String message) {
        return new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE, message, true);
    }

    private static EdtAstException serviceUnavailable(String message, Throwable cause) {
        return new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE, message, true, cause);
    }

    private static EdtAstException internal(String message, Throwable cause) {
        return new EdtAstException(EdtAstErrorCode.INTERNAL_ERROR, message, false, cause);
    }

    private record DebugThreadSelection(Object launch, Object target, Object thread, String threadId) {
    }

    private record DebugFrameSelection(String threadId, String frameId, Object frame) {
    }
}
