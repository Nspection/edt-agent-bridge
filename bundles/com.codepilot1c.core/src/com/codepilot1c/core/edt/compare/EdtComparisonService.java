// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.compare;

import java.nio.file.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.emf.ecore.*;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.ui.*;
import org.osgi.framework.Bundle;
import com.google.gson.GsonBuilder;
import com._1c.g5.v8.bm.core.*;
import com._1c.g5.v8.bm.integration.*;
import com._1c.g5.v8.dt.compare.core.*;
import com._1c.g5.v8.dt.compare.datasource.*;
import com._1c.g5.v8.dt.compare.matching.MatchingStrategy;
import com._1c.g5.v8.dt.compare.model.*;
import com._1c.g5.v8.dt.compare.ui.editor.*;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.IPartialModelNode;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.jface.window.ToolTip;
import org.eclipse.jface.layout.AbstractColumnLayout;
import com._1c.g5.v8.dt.bsl.compare.*;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;

/** Native comparison sessions; source projects are only changed by apply(). */
public final class EdtComparisonService {
    private static final EdtComparisonService INSTANCE=new EdtComparisonService();
    private static final com.google.gson.Gson JSON=new GsonBuilder().serializeNulls().create();
    private final Map<String,Entry> entries=new ConcurrentHashMap<>();
    private IComparisonManager observedManager;
    private final IComparisonManager.ICompareMergeStatusListener statusListener=new IComparisonManager.ICompareMergeStatusListener() {
        @Override public void statusChanged(ComparisonProcessHandle handle,ComparisonProcessStatus status) {
            IComparisonSession current=observedManager==null?null:observedManager.getComparisonSession(handle);
            for(Entry e:entries.values())if(e.handle.equals(handle) && e.binding.matches(current)) {
                e.lifecycle.accept(status);
                if(terminal(status))e.binding.retire();
            }
        }
        @Override public void statusChanged(CompareMergeProcessBatch batch,CompareMergeProcessBatchStatus status) {
            if(status==CompareMergeProcessBatchStatus.MERGE_STARTED)for(Entry e:entries.values()) {
                if(batch.containsHandle(e.handle) && e.binding.matches(observedManager.getComparisonSession(e.handle)))e.mergeBatch=batch;
            }
            if(status==CompareMergeProcessBatchStatus.MERGE_FINISHED)for(Entry e:entries.values())if(e.mergeBatch==batch) {
                e.lifecycle.applyRequested=false;
                if(batch.getFailureCause()!=null)e.lifecycle.failure=batch.getFailureCause().toString();
            }
        }
    };
    private static boolean terminal(ComparisonProcessStatus status) {
        return status==ComparisonProcessStatus.MERGE_PROCESS_FINISHED || status==ComparisonProcessStatus.MERGE_PROCESS_DISCARDED
                || status==ComparisonProcessStatus.COMPARISON_MERGE_PROCESS_CANCELLED;
    }
    /** A handle identifies projects/scope, not a particular run. Never rebind a completed run. */
    static final class SessionBinding {
        private final ComparisonProcessHandle requestedHandle;
        private volatile IComparisonSession session;
        private volatile boolean retired;
        SessionBinding(ComparisonProcessHandle handle,IComparisonSession existing){requestedHandle=handle;session=existing;}
        synchronized boolean matches(IComparisonSession current) {
            if(retired || current==null)return false;
            if(session==null && current.getHandle()==requestedHandle)session=current;
            return session==current;
        }
        void retire(){retired=true;}
        IComparisonSession get(){return session;}
    }
    // EDT discards a successful native session immediately after its FINISHED event.
    // Keep the event, otherwise polling getStatus(handle) incorrectly reports null.
    static final class Lifecycle {
        volatile ComparisonProcessStatus lastStatus;
        volatile boolean applied,applyRequested;
        volatile String failure;
        void accept(ComparisonProcessStatus status) {
            if(status==null)return;
            lastStatus=status;
            if(status==ComparisonProcessStatus.MERGE_PROCESS_FINISHED){applied=true;applyRequested=false;}
            if(status==ComparisonProcessStatus.COMPARISON_MERGE_PROCESS_CANCELLED || status==ComparisonProcessStatus.MERGE_PROCESS_DISCARDED)applyRequested=false;
        }
        String effectiveStatus(ComparisonProcessStatus live) {
            return applied?ComparisonProcessStatus.MERGE_PROCESS_FINISHED.name():String.valueOf(live==null?lastStatus:live);
        }
    }
    private synchronized void observeManager() {
        if(observedManager==null){observedManager=manager();observedManager.addStatusListener(statusListener);}
    }
    public synchronized void shutdown() {
        if(observedManager!=null){observedManager.removeStatusListener(statusListener);observedManager=null;}
        entries.clear();
    }
    private static final int MAX_NODES=100000, MAX_TEXT=1024*1024;
    private static class Entry {
        String id=UUID.randomUUID().toString();
        ComparisonProcessHandle handle; boolean owned, initialized;
        final SessionBinding binding;
        volatile CompareMergeProcessBatch mergeBatch;
        final Lifecycle lifecycle=new Lifecycle();
        IEditorPart editor; Map<String,String> reviewNotes=new ConcurrentHashMap<>();
        Map<String,String> sources=new TreeMap<>();
        Entry(ComparisonProcessHandle h,boolean own){this(h,own,null);}
        Entry(ComparisonProcessHandle h,boolean own,IComparisonSession session){handle=h;owned=own;initialized=!own;binding=new SessionBinding(h,session);}
    }
    public static EdtComparisonService getInstance(){return INSTANCE;}
    public Object execute(String tool,Map<String,Object> args) throws Exception {
        observeManager();
        return switch(tool) {
            case "compare" -> compare(args);
            case "plan" -> plan(args);
            case "result" -> result(args);
            case "apply" -> apply(args);
            default -> throw new IllegalArgumentException("Unknown comparison operation");
        };
    }
    private Object compare(Map<String,Object> a) throws Exception {
        String action=(String)a.getOrDefault("action","sessions");
        if(action.equals("start"))return start(a);
        if(action.equals("sessions"))return sessions();
        Entry e=entry(a);
        synchronized(e) {
            if(action.equals("close")) {
                if(!e.owned)throw new IllegalArgumentException("Borrowed UI comparison is never closed by MCP");
                IComparisonSession current=manager().getComparisonSession(e.handle);
                // A stale id may be forgotten, but must not touch another run or its editor.
                if(e.binding.matches(current)) {
                    assertNoDirtyEditors(e);
                    if(e.editor!=null)PlatformUI.getWorkbench().getDisplay().syncExec(()->{
                        var page=e.editor.getSite().getPage();
                        if(page!=null && page.getReference(e.editor)!=null)page.closeEditor(e.editor,false);
                    });
                    if(e.binding.matches(manager().getComparisonSession(e.handle)))manager().cancel(e.handle);
                    e.binding.retire();
                } else if(e.binding.get()==null && !terminal(e.lifecycle.lastStatus)) {
                    throw new IllegalStateException("Comparison is still initializing; wait for status before closing");
                }
                entries.remove(e.id);return Map.of("session_id",e.id,"closed",true);
            }
            if(action.equals("status"))return status(e);
            IComparisonSession session=ready(e);
            if(action.equals("open_ui") || action.equals("handoff"))return openUi(e,session,a);
            if(action.equals("details"))return task(session,false,(tx,ctx)->details(session,node(session,ctx,a),ctx));
            if(action.equals("tree")) {
                int offset=number(a,"offset",0),limit=number(a,"limit",100);
                if(offset<0 || limit<1 || limit>500)throw new IllegalArgumentException("Invalid page (limit 1..500)");
                String filter=(String)a.getOrDefault("name_contains","");
                List<Map<String,Object>> rows=task(session,false,(tx,ctx)->rows(session,ctx));
                rows=rows.stream().filter(r->!Boolean.TRUE.equals(a.getOrDefault("changes_only",true)) || Boolean.TRUE.equals(r.get("changed"))).filter(r->r.toString().toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))).toList();
                return Map.of("session_id",e.id,"total",rows.size(),"offset",offset,"nodes",rows.stream().skip(offset).limit(limit).toList(),"has_more",offset+limit<rows.size());
            }
            throw new IllegalArgumentException("Unknown compare action");
        }
    }
    private Object start(Map<String,Object> a) throws Exception {
        IV8Project main=project(required(a,"main_project")),other=project(required(a,"other_project"));
        String ancestorName=(String)a.get("ancestor_project");
        if(main.equals(other))throw new IllegalArgumentException("Choose distinct projects");
        IV8Project ancestor=ancestorName==null?null:project(ancestorName);
        if(ancestor!=null && (ancestor.equals(main)||ancestor.equals(other)))throw new IllegalArgumentException("Ancestor must be a distinct project");
        var mainSource=new V8ProjectComparisonDataSourceDescriptor(main);var otherSource=new V8ProjectComparisonDataSourceDescriptor(other);
        @SuppressWarnings("unchecked") List<String> scope=(List<String>)a.getOrDefault("scope",List.of());
        var handle=new ComparisonProcessHandle(mainSource,otherSource,ancestor==null?null:new V8ProjectComparisonDataSourceDescriptor(ancestor),new ComparisonScope(scope,scope,scope));
        if(manager().getComparisonSession(handle)!=null)throw new IllegalStateException("A native session for this pair/scope already exists; attach through sessions instead");
        var settings=ComparisonProcessSettings.builder(MatchingStrategy.valueOf((String)a.getOrDefault("matching","UUID_THEN_NAME")))
                .parseBslModuleStructure(!Boolean.FALSE.equals(a.get("parse_bsl_structure")))
                .mainSideObjectsDeletionAllowed(Boolean.TRUE.equals(a.get("allow_deletions")))
                .avoidExternalMergeToolSupport(true).mergeObjectsContent(true).mergeReadOnlyObjects(false).build();
        Entry entry=new Entry(handle,true);entry.sources=sources(handle);assertNoDirtyEditors(entry);
        entries.put(entry.id,entry);
        try{manager().startComparison(new CompareMergeProcessBatch(new CompareMergeProcessDescriptor(handle,settings)));}
        catch(Exception error){entries.remove(entry.id);throw error;}
        return status(entry);
    }
    private List<Map<String,Object>> sessions() throws Exception {
        var handles=new LinkedHashSet<ComparisonProcessHandle>();
        for(IProject p:ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if(!p.isAccessible())continue;
            IV8Project v8=new EdtMetadataGateway().getV8ProjectManager().getProject(p);
            if(v8!=null)handles.addAll(manager().getHandles(v8));
        }
        for(var h:handles) {
            IComparisonSession current=manager().getComparisonSession(h);
            if(current==null)continue;
            if(entries.values().stream().noneMatch(e->e.handle.equals(h) && e.binding.matches(current))) {
                Entry entry=new Entry(h,false,current);entry.sources=sources(h);entries.put(entry.id,entry);
            }
        }
        List<Map<String,Object>> result=new ArrayList<>();
        for(Entry e:entries.values())synchronized(e){result.add(status(e));}return result;
    }
    private Map<String,Object> status(Entry e) throws Exception {
        IComparisonSession current=manager().getComparisonSession(e.handle);
        IComparisonSession session=e.binding.matches(current)?current:null;
        var result=new LinkedHashMap<String,Object>();result.put("session_id",e.id);result.put("owned_by_mcp",e.owned);
        result.put("main_project",e.handle.getMainDescriptor().getProjectName());result.put("other_project",e.handle.getOtherDescriptor().getProjectName());
        result.put("ancestor_project",e.handle.isThreeWay()?e.handle.getCommonAncestorDescriptor().getProjectName():null);
        var nativeStatus=session==null?null:session.getStatus();
        if(nativeStatus==ComparisonProcessStatus.MERGE_PROCESS_FINISHED)e.lifecycle.accept(nativeStatus);
        result.put("status",e.lifecycle.effectiveStatus(nativeStatus));result.put("applied",e.lifecycle.applied);
        result.put("native_status",String.valueOf(nativeStatus));result.put("native_session_present",session!=null);
        result.put("session_replaced",current!=null && e.binding.get()!=null && current!=e.binding.get());
        result.put("apply_pending",e.lifecycle.applyRequested);result.put("failure",e.lifecycle.failure);
        if(session!=null && session.getStatus()==ComparisonProcessStatus.COMPARISON_PROCESS_FINISHED && !e.initialized)ready(e);
        result.put("selection_initialized",e.initialized);return result;
    }
    private IComparisonSession ready(Entry e) throws Exception {
        IComparisonSession session=manager().getComparisonSession(e.handle);
        if(!e.binding.matches(session) || session.getStatus()!=ComparisonProcessStatus.COMPARISON_PROCESS_FINISHED || e.lifecycle.applied || e.lifecycle.applyRequested)throw new IllegalStateException("Native comparison is not editable/ready; inspect status");
        if(!e.initialized) {
            session.setMustBeMerged(session.getRootNode().bmGetId(),false,true);e.initialized=true;
        }
        return session;
    }
    private Object plan(Map<String,Object> a) throws Exception {
        Entry e=entry(a);synchronized(e) {
            IComparisonSession session=ready(e);
            if(a.containsKey("rule")) {
                assertNoDirtyEditors(e);
                task(session,true,(tx,ctx)->{
                    ComparisonNode node=node(session,ctx,a);Map<String,Object> current=details(session,node,ctx);
                    requireHash(required(a,"expected_node_sha256"),(String)current.get("node_sha256"));
                    String rule=required(a,"rule");MergeRule nativeRule=rule(rule);
                    MergeSettings settings=node.getMergeSettings();
                    if(settings==null || !settings.isCanBeMerged() || !settings.getAvailableMergeRules().contains(nativeRule))throw new IllegalArgumentException("EDT does not permit this merge rule for the selected node");
                    if(!session.setMergeRule(node.bmGetId(),nativeRule,ctx))throw new IllegalStateException("EDT declined merge rule");
                    return null;
                });
                // Native session owns selection propagation; keep-left is explicit exclusion.
                session.setMustBeMerged(Long.parseLong(required(a,"node_id")),!required(a,"rule").equals("keep_main"),Boolean.TRUE.equals(a.get("recursive")));
            }
            return plan(e,session);
        }
    }
    private Map<String,Object> plan(Entry e,IComparisonSession session) throws Exception {
        List<Map<String,Object>> rows=task(session,false,(tx,ctx)->rows(session,ctx));
        var result=new LinkedHashMap<String,Object>();result.put("session_id",e.id);result.put("main_project",e.handle.getMainDescriptor().getProjectName());
        result.put("other_project",e.handle.getOtherDescriptor().getProjectName());result.put("selected",rows.stream().filter(r->Boolean.TRUE.equals(r.get("selected"))).toList());
        result.put("potential_native_problems",task(session,false,(tx,ctx)->{
            List<String> problems=new ArrayList<>();
            for(Long id:session.getPotentialMergeProblemsSourceNodes())for(var problem:session.getPotentialMergeProblemsDescriptions(id,ctx))problems.add(String.valueOf(problem));
            return problems;
        }));
        result.put("review_notes",new TreeMap<>(e.reviewNotes));
        result.put("source_guard",e.sources);result.put("tree_settings_sha256",hash(JSON.toJson(rows)));result.put("plan_sha256",hash(JSON.toJson(result)));
        result.put("note","This is a native merge plan, not proof of business correctness. Apply performs EDT validation; inspect diagnostics and compare again afterwards.");return result;
    }
    private Object result(Map<String,Object> a) throws Exception {
        Entry e=entry(a);synchronized(e) {
            IComparisonSession session=ready(e);String action=(String)a.getOrDefault("action","read");
            if(action.equals("read"))return task(session,false,(tx,ctx)->details(session,node(session,ctx,a),ctx));
            if(!Set.of("write","patch").contains(action))throw new IllegalArgumentException("Unknown result action");
            assertNoDirtyEditors(e);
            return task(session,true,(tx,ctx)->{
                ComparisonNode node=node(session,ctx,a);
                if(!(node instanceof BslModuleComparisonNode || node instanceof BslModuleSectionComparisonNode))throw new IllegalArgumentException("Custom text is supported only for native BSL module/method nodes");
                Map<String,Object> before=details(session,node,ctx);requireHash(required(a,"expected_node_sha256"),(String)before.get("node_sha256"));
                String content=action.equals("patch")?patch((String)before.get("result"),required(a,"old_text"),text(a,"new_text")):text(a,"content");
                validateText(content);
                if(!node.getMergeSettings().getAvailableMergeRules().contains(MergeRule.CUSTOM_MERGE))throw new IllegalArgumentException("EDT does not support custom merge for this node");
                TextNodeCustomMergeSettings custom=CompareFactory.eINSTANCE.createTextNodeCustomMergeSettings();custom.setCustomMergeResult(content);
                session.setCustomMergeSettings(node.bmGetId(),custom,ctx);
                if(!session.setMergeRule(node.bmGetId(),MergeRule.CUSTOM_MERGE,ctx))throw new IllegalStateException("EDT declined custom merge");
                return details(session,node,ctx);
            });
        }
    }
    private Object apply(Map<String,Object> a) throws Exception {
        Entry e=entry(a);synchronized(e) {
            IComparisonSession session=ready(e);assertNoDirtyEditors(e);
            var preview=plan(e,session);requireHash(required(a,"expected_plan_sha256"),(String)preview.get("plan_sha256"));
            if(!sources(e.handle).equals(e.sources))throw new IllegalStateException("SOURCE_CHANGED: projects changed since comparison/attachment; start a fresh comparison");
            if(((List<?>)preview.get("selected")).isEmpty())throw new IllegalArgumentException("No changes selected");
            task(session,false,(tx,ctx)->{
                for(var row:rows(session,ctx)) {
                    if(!Boolean.TRUE.equals(row.get("selected")))continue;
                    var node=session.getNode(ctx,Long.parseLong((String)row.get("node_id")));
                    if(requiresCustomValidation(node.getMergeSettings())) {
                        var text=(TextNodeCustomMergeSettings)node.getMergeSettings().getCustomSettings();
                        validateText(text.getCustomMergeResult());
                        if(!syntaxErrors(text.getCustomMergeResult()).isEmpty())throw new IllegalStateException("Custom BSL result has syntax errors; fix before applying");
                    }
                }
                return null;
            });
            e.lifecycle.applyRequested=true;
            IStatus status;
            e.mergeBatch=new CompareMergeProcessBatch(new CompareMergeProcessDescriptor(e.handle,session.getSettings()));
            try {status=manager().startMerge(e.mergeBatch,new NullProgressMonitor());}
            catch(RuntimeException error){e.lifecycle.applyRequested=false;throw error;}
            if(!status.isOK())e.lifecycle.applyRequested=false;
            var result=status(e);result.put("accepted",status.isOK());result.put("severity",status.getSeverity());
            result.put("message",status.getMessage());result.put("native_problems",Arrays.stream(status.getChildren()).map(IStatus::getMessage).toList());
            result.put("verification_required",List.of("poll edt_compare status until applied or failure; startMerge is asynchronous","get_diagnostics","edt_compare: start a fresh comparison","runtime checks when required"));return result;
        }
    }
    private List<Map<String,Object>> rows(IComparisonSession session,ComparisonContext ctx) {
        List<Map<String,Object>> rows=new ArrayList<>();ArrayDeque<ComparisonNode> queue=new ArrayDeque<>();queue.add(session.getRootNode());Set<Long> seen=new HashSet<>();
        while(!queue.isEmpty()) {
            ComparisonNode n=session.getNode(ctx,queue.remove().bmGetId());if(!seen.add(n.bmGetId()))continue;
            if(seen.size()>MAX_NODES)throw new IllegalStateException("Comparison tree exceeds safe page traversal limit");
            rows.add(row(session,n,ctx));queue.addAll(n.getChildren());
        }
        return rows;
    }
    private Map<String,Object> row(IComparisonSession session,ComparisonNode n,ComparisonContext ctx) {
        Map<String,Object> r=new LinkedHashMap<>();r.put("node_id",Long.toString(n.bmGetId()));r.put("kind",n.eClass().getName());
        r.put("path",ctx.describeNode(n,false));r.put("parent_id",n.getParent()==null?null:Long.toString(n.getParent().bmGetId()));
        var f=n.getComparisonFlags();r.put("changed",f!=null && f.hasDiffsMainOther());r.put("double_change",f!=null && f.hasDoubleChanges());
        var ms=n.getMergeSettings();r.put("selected",ms!=null && ms.isMustBeMerged());r.put("merge_rule",ms==null?null:String.valueOf(ms.getMergeRule()));
        r.put("can_merge",ms!=null && ms.isCanBeMerged());r.put("available_rules",ms==null?List.of():ms.getAvailableMergeRules().stream().map(Enum::name).toList());
        if(n instanceof SymlinkComparisonNode link){r.put("main_fqn",link.getMainSymlink());r.put("other_fqn",link.getOtherSymlink());}
        if(n instanceof BslModuleSectionComparisonNode method){r.put("main_name",method.getMainName());r.put("other_name",method.getOtherName());r.put("section_type",String.valueOf(method.getSectionType()));}
        if(ms!=null) {
            r.put("merge_settings_sha256",settingsFingerprint(ms));
            r.put("order_side",ms.getOrderRule()==null?null:String.valueOf(ms.getOrderRule().getOrderSide()));
            r.put("order_set_by_user",ms.getOrderRule()!=null && ms.getOrderRule().isOrderSetByUser());
        }
        if(ms!=null && ms.getCustomSettings() instanceof TextNodeCustomMergeSettings text){r.put("custom_sha256",hash(text.getCustomMergeResult()));}
        return r;
    }
    private Map<String,Object> details(IComparisonSession session,ComparisonNode n,ComparisonContext ctx) {
        Map<String,Object> out=row(session,n,ctx);Map<String,Object> sides=new LinkedHashMap<>();
        for(ComparisonSide side:ComparisonSide.values()) {
            if(side==ComparisonSide.COMMON_ANCESTOR && !session.isThreeWay())continue;
            Object value;
            if(n instanceof BslModuleComparisonNode module) {
                var info=BslCompareUtils.readBslModuleContentInfo(module,session,side,false,converter());
                if(info!=null && info.isBinary())throw new IllegalArgumentException("Binary BSL modules are not exposed as text");
                value=info==null?null:info.getContent();
            } else if(n instanceof BslModuleSectionComparisonNode method) {
                var docs=new ComparedObjects<IDocument>(null,null,null);
                for(ComparisonSide part:ComparisonSide.values()) {
                    if(part==ComparisonSide.COMMON_ANCESTOR && !session.isThreeWay())continue;
                    String module=BslCompareUtils.readBslModuleContentBySectionNode(method,session,part,converter());
                    docs.setComparedObject(part,module==null?null:new Document(module));
                }
                try {value=BslCompareUtils.getSectionContent(method,docs,side);}
                catch(org.eclipse.jface.text.BadLocationException error){throw new IllegalStateException("Native method regions no longer match the module; compare again",error);}
            } else {
                var compared=session.getComparedObjects(n,ctx);value=compared==null?null:encode(compared.getComparedObject(side));
            }
            if(value instanceof String text && text.length()>MAX_TEXT)throw new IllegalStateException("Node text too large; narrow comparison scope");
            sides.put(side.name(),value);
        }
        out.put("sides",sides);String result=null;String state="not_materialized";
        if(n.getMergeSettings()!=null) {
            var ms=n.getMergeSettings();
            if(ms.getMergeRule()==MergeRule.CUSTOM_MERGE && ms.getCustomSettings() instanceof TextNodeCustomMergeSettings text){result=text.getCustomMergeResult();state="native_custom_settings";}
            else if(ms.getMergeRule()==MergeRule.GET_FROM_OTHER){if(sides.get("OTHER") instanceof String t)result=t;state="other";}
            else if(ms.getMergeRule()==MergeRule.DO_NOT_MERGE){if(sides.get("MAIN") instanceof String t)result=t;state="main";}
        }
        out.put("result",result);out.put("result_source",state);out.put("live_editor_buffer",false);
        if(result!=null && (n instanceof BslModuleComparisonNode || n instanceof BslModuleSectionComparisonNode))out.put("syntax_errors",syntaxErrors(result));
        out.put("node_sha256",hash(JSON.toJson(out)));return out;
    }
    private static List<Map<String,Object>> syntaxErrors(String text) {
        var parsed=BslCompareUtils.parseBslModuleContent(new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        if(parsed==null)throw new IllegalStateException("EDT BSL parser did not return a result");
        List<Map<String,Object>> errors=new ArrayList<>();
        for(var node:parsed.getSyntaxErrors()) {
            errors.add(Map.of("offset",node.getOffset(),"length",node.getLength(),"message",node.getSyntaxErrorMessage().getMessage()));
            if(errors.size()>=100)break;
        }
        return errors;
    }
    private Map<String,Object> openUi(Entry e,IComparisonSession session,Map<String,Object> a) throws Exception {
        String selected=(String)a.get("node_id"),reason=(String)a.get("reason");
        if(selected!=null)task(session,false,(tx,ctx)->node(session,ctx,a));
        if(reason!=null && reason.length()>2000)throw new IllegalArgumentException("Review reason too long");
        if(selected!=null && reason!=null){if(reason.isBlank())e.reviewNotes.remove(selected);else e.reviewNotes.put(selected,reason);}
        Map<String,Object> result=new LinkedHashMap<>();
        // Window restoration is independent of changing the active perspective.
        com.codepilot1c.core.edt.editor.EdtWindowService.getInstance().execute(Map.of("action","restore"));
        Exception[] failure={null};
        PlatformUI.getWorkbench().getDisplay().syncExec(()->{
            try {
                var window=PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if(window==null)window=PlatformUI.getWorkbench().getWorkbenchWindows()[0];
                IEditorPart active=window.getActivePage().getActiveEditor();
                IEditorPart existing=active!=null && active.getEditorInput() instanceof IDtComparisonEditorInput && editorView(active,session)!=null?active:null;
                search: for(var candidateWindow:PlatformUI.getWorkbench().getWorkbenchWindows())for(var page:candidateWindow.getPages()) {
                    if(existing!=null)break search;
                    IEditorPart found=findReusableEditor(Arrays.asList(page.getEditorReferences()),part->editorView(part,session)!=null);
                    if(found!=null){existing=found;window=candidateWindow;break search;}
                }
                // A newly created editor can still be building its native tree. Keep the
                // editor already bound by this entry rather than opening duplicates on retry.
                if(existing==null && e.editor!=null && e.editor.getSite().getPage()!=null
                        && e.editor.getSite().getPage().getReference(e.editor)!=null)existing=e.editor;
                result.put("editor_reused",existing!=null);
                if(existing!=null) {
                    e.editor=existing;e.editor.getSite().getPage().activate(e.editor);
                } else {
                    var factory=nativeService("com._1c.g5.v8.dt.compare.ui","com._1c.g5.v8.dt.internal.compare.ui.CompareUiPlugin",IDtComparisonEditorInputFactory.class);
                    var input=factory.create(List.of(new CompareMergeProcessDescriptor(e.handle,session.getSettings())),false,e.handle.getMainDescriptor().getProjectName(),e.handle.getOtherDescriptor().getProjectName());
                    input.setRunInitialComparison(false);
                    e.editor=window.getActivePage().openEditor(input,"com._1c.g5.v8.dt.compare.ui.editor",true);
                }
                if(e.editor.getEditorInput() instanceof IDtComparisonEditorInput input)result.put("editor_input_uuid",input.getUuid().toString());
                DtComparisonView activeView=editorView(e.editor,session);
                result.put("review_column_installed",activeView!=null && installReviewColumn(activeView,e));
                boolean highlighted=selected!=null && activeView!=null && highlight(activeView,session,Long.parseLong(selected));
                if(reason!=null)e.editor.getEditorSite().getActionBars().getStatusLineManager().setMessage("AI review: "+reason);
                result.put("editor_title",e.editor.getTitle());result.put("highlighted",highlighted);
                result.put("process_id",ProcessHandle.current().pid());
            }catch(Exception error){failure[0]=error;}
        });
        if(failure[0]!=null)throw failure[0];
        result.put("session_id",e.id);result.put("workflow_state","awaiting_user_review");result.put("source_projects_changed",false);
        result.put("review_notes",new LinkedHashMap<>(e.reviewNotes));result.put("plan",plan(e,session));
        result.put("next","User may edit and press Merge in EDT, or ask the agent to continue. Re-read the native plan/result and obtain fresh hashes after user edits. No automatic apply is scheduled.");return result;
    }
    static IEditorPart findReusableEditor(List<IEditorReference> references,java.util.function.Predicate<IEditorPart> matches) {
        for(IEditorReference reference:references) {
            IEditorPart part=reference.getEditor(false); // Never restore/re-run a dormant editor as a side effect.
            if(part!=null && part.getEditorInput() instanceof IDtComparisonEditorInput && matches.test(part))return part;
        }
        return null;
    }
    private static DtComparisonView editorView(IEditorPart editor,IComparisonSession session) {
        MPart part=editor.getSite().getService(MPart.class);
        return part!=null && part.getWidget() instanceof Control control?findView(control,session):null;
    }
    private static DtComparisonView findView(Control control,IComparisonSession session) {
        if(control.isDisposed())return null;
        if(control instanceof DtComparisonView view && hasSessionNode(view.getTreeControl().getTreeViewer(),session))return view;
        if(control instanceof Composite composite)for(Control child:composite.getChildren()) {
            DtComparisonView found=findView(child,session);if(found!=null)return found;
        }
        return null;
    }
    static String settingsFingerprint(MergeSettings settings){return hash(JSON.toJson(snapshotSetting(settings,0)));}
    private static Object snapshotSetting(Object value,int depth) {
        if(depth>64)throw new IllegalStateException("Native merge settings nesting exceeds limit");
        if(value==null || value instanceof String || value instanceof Number || value instanceof Boolean)return value;
        if(value instanceof org.eclipse.emf.common.util.Enumerator enumerator)return enumerator.getLiteral();
        if(value instanceof Collection<?> values)return values.stream().map(item->snapshotSetting(item,depth+1)).toList();
        if(value instanceof EObject object) {
            if(object.eIsProxy())return Map.of("proxy_uri",((InternalEObject)object).eProxyURI().toString());
            Map<String,Object> fields=new TreeMap<>();
            for(EStructuralFeature feature:object.eClass().getEAllStructuralFeatures()) {
                if(feature.isDerived() || feature.isTransient() || feature.isVolatile())continue;
                Object item=object.eGet(feature,false);
                if(feature instanceof EReference reference && !reference.isContainment()) {
                    if(item instanceof Collection<?> values)item=values.stream().map(EdtComparisonService::settingReference).toList();
                    else item=settingReference(item);
                    fields.put(feature.getName(),item);
                } else fields.put(feature.getName(),snapshotSetting(item,depth+1));
            }
            return Map.of("type",object.eClass().getEPackage().getNsURI()+"#"+object.eClass().getName(),"fields",fields);
        }
        return value.toString();
    }
    private static Object settingReference(Object value) {
        if(value instanceof EObject object) {
            if(object.eIsProxy())return ((InternalEObject)object).eProxyURI().toString();
            return object instanceof IBmObject bm?Map.of("type",object.eClass().getName(),"bm_id",Long.toString(bm.bmGetId())):org.eclipse.emf.ecore.util.EcoreUtil.getURI(object).toString();
        }
        return value==null?null:value.toString();
    }
    private boolean installReviewColumn(Control control,Entry e) {
        boolean installed=false;
        if(control instanceof DtComparisonView view) {
            TreeViewer viewer=view.getTreeControl().getTreeViewer();
            if(hasSessionNode(viewer,e.binding.get())) {
                String key="edt.agent.bridge.review.column";
                Object old=viewer.getTree().getData(key);
                if(old instanceof TreeViewerColumn column && !column.getColumn().isDisposed()) {
                    column.setLabelProvider(reviewLabels(e));
                } else {
                    if(!(viewer.getTree().getParent().getLayout() instanceof AbstractColumnLayout layout))return false;
                    TreeViewerColumn column=new TreeViewerColumn(viewer,SWT.CENTER);
                    layout.setColumnData(column.getColumn(),new ColumnPixelData(85,true,true));
                    column.getColumn().setText("Вопрос ИИ");column.getColumn().setWidth(85);
                    column.getColumn().setToolTipText("Вопросы агента: наведите на значок для пояснения");
                    column.getColumn().setMoveable(true);column.setLabelProvider(reviewLabels(e));
                    viewer.getTree().setData(key,column);
                    int[] order=viewer.getTree().getColumnOrder();
                    if(order.length>1){int last=order[order.length-1];System.arraycopy(order,1,order,2,order.length-2);order[1]=last;viewer.getTree().setColumnOrder(order);}
                    ColumnViewerToolTipSupport.enableFor(viewer,ToolTip.NO_RECREATE);
                }
                viewer.refresh();installed=true;
            }
        }
        if(control instanceof Composite composite)for(Control child:composite.getChildren())installed=installReviewColumn(child,e)||installed;
        return installed;
    }
    private static boolean hasSessionNode(TreeViewer viewer,IComparisonSession session) {
        for(var item:viewer.getTree().getItems())if(item.getData() instanceof IPartialModelNode node && node.getComparisonSession()==session)return true;
        return false;
    }
    private ColumnLabelProvider reviewLabels(Entry e) {
        return new ColumnLabelProvider() {
            private String note(Object element) {
                if(element instanceof IPartialModelNode node && node.getComparisonSession()==e.binding.get())return e.reviewNotes.get(Long.toString(node.getNodeId()));
                return null;
            }
            @Override public String getText(Object element){return "";}
            @Override public Image getImage(Object element){return note(element)==null?null:PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_OBJS_WARN_TSK);}
            @Override public String getToolTipText(Object element){return note(element);}
        };
    }
    private static boolean highlight(Control control,IComparisonSession session,long id) {
        if(control instanceof DtComparisonView view) {
            TreeViewer viewer=view.getTreeControl().getTreeViewer();
            if(viewer.getContentProvider() instanceof ITreeContentProvider provider) {
                var queue=new ArrayDeque<Object>();Object[] roots=provider.getElements(viewer.getInput());
                if(roots!=null)Collections.addAll(queue,roots);
                if(queue.isEmpty())for(var treeItem:viewer.getTree().getItems())if(treeItem.getData()!=null)queue.add(treeItem.getData());
                int count=0;
                while(!queue.isEmpty() && count++<MAX_NODES) {
                    Object item=queue.remove();
                    if(item instanceof IPartialModelNode partial && partial.getNodeId()==id && partial.getComparisonSession()==session) {
                        viewer.setSelection(new StructuredSelection(item),true);viewer.reveal(item);return true;
                    }
                    Object[] children=provider.getChildren(item);if(children!=null)Collections.addAll(queue,children);
                }
            }
        }
        if(control instanceof Composite composite)for(Control child:composite.getChildren())if(highlight(child,session,id))return true;
        return false;
    }
    private Object encode(Object value) {
        if(value==null || value instanceof String || value instanceof Number || value instanceof Boolean)return value;
        if(value instanceof EObject object) {
            var result=new LinkedHashMap<String,Object>();result.put("class",object.eClass().getName());
            if(object.eIsProxy()){result.put("proxy_uri",((InternalEObject)object).eProxyURI().toString());return result;}
            for(EAttribute f:object.eClass().getEAllAttributes())if(!f.isDerived()&&!f.isTransient())result.put(f.getName(),String.valueOf(object.eGet(f,false)));
            return result;
        }
        return String.valueOf(value);
    }
    private ComparisonNode node(IComparisonSession session,ComparisonContext ctx,Map<String,Object> a) {
        ComparisonNode n=session.getNode(ctx,Long.parseLong(required(a,"node_id")));if(n==null)throw new IllegalArgumentException("Node no longer exists");return n;
    }
    @SuppressWarnings("unchecked") private <T>T task(IComparisonSession session,boolean write,BiFunction<IBmTransaction,ComparisonContext,T> work) throws Exception {
        var job=new AbstractBmTask<T>("EDT Agent Bridge compare/merge") {
            @Override public T execute(IBmTransaction tx,IProgressMonitor monitor) {
                var ctx=new ComparisonContext(session);
                try {
                    ctx.setComparisonTransaction(tx);
                    ctx.setDataSourceContext(new ComparisonDataSourceTransactionalContext(session,null));
                    return work.apply(tx,ctx);
                }
                finally {
                    // The native task owns commit/rollback. Context.close() otherwise commits
                    // even when work failed, which would partially apply rejected changes.
                    ctx.setComparisonTransaction(null);ctx.close();
                }
            }
        };
        if(!write)return session.runComparisonTreeReadonlyTask(job);
        try{return (T)manager().getClass().getMethod("runComparisonTreeBmModelTask",IComparisonSession.class,IBmTask.class).invoke(manager(),session,job);}
        catch(java.lang.reflect.InvocationTargetException e){if(e.getCause() instanceof Exception error)throw error;throw e;}
    }
    private Entry entry(Map<String,Object> a){Entry e=entries.get(required(a,"session_id"));if(e==null)throw new IllegalArgumentException("Unknown/expired session_id; use sessions or start");return e;}
    private IV8Project project(String name) {
        IProject p=ResourcesPlugin.getWorkspace().getRoot().getProject(name);if(!p.isAccessible())throw new IllegalArgumentException("Project is not open: "+name);
        IV8Project v8=new EdtMetadataGateway().getV8ProjectManager().getProject(p);if(v8==null)throw new IllegalArgumentException("Not a ready EDT project: "+name);return v8;
    }
    private Map<String,String> sources(ComparisonProcessHandle handle) throws Exception {
        Map<String,String> hashes=new TreeMap<>();
        for(var side:ComparisonSide.values()) {
            if(side==ComparisonSide.COMMON_ANCESTOR && !handle.isThreeWay())continue;
            String name=handle.getDescriptor(side).getProjectName();IProject project=ResourcesPlugin.getWorkspace().getRoot().getProject(name);
            if(!project.isAccessible() || project.getLocation()==null)throw new IllegalArgumentException("Only local open workspace projects are supported for guarded merging");
            Path root=project.getLocation().toFile().toPath().toRealPath();MessageDigest md=MessageDigest.getInstance("SHA-256");
            try(var stream=Files.walk(root.resolve("src"))) {
                for(Path path:stream.filter(Files::isRegularFile).sorted().toList()) {
                    if(!path.toRealPath().startsWith(root))throw new IllegalArgumentException("Linked source outside project is unsupported");
                    md.update(root.relativize(path).toString().replace('\\','/').getBytes(StandardCharsets.UTF_8));
                    try(var input=Files.newInputStream(path)){byte[] b=new byte[65536];int read;while((read=input.read(b))!=-1)md.update(b,0,read);}
                }
            }
            hashes.put(name,HexFormat.of().formatHex(md.digest()));
        }
        return hashes;
    }
    private void assertNoDirtyEditors(Entry e) throws Exception {
        if(!PlatformUI.isWorkbenchRunning())return;String[] problem={null};
        PlatformUI.getWorkbench().getDisplay().syncExec(()->{
            for(var shell:PlatformUI.getWorkbench().getDisplay().getShells()) {
                if(shell.isVisible() && (shell.getStyle() & (org.eclipse.swt.SWT.APPLICATION_MODAL|org.eclipse.swt.SWT.PRIMARY_MODAL|org.eclipse.swt.SWT.SYSTEM_MODAL))!=0)problem[0]="modal editor: "+shell.getText();
            }
            for(IWorkbenchWindow w:PlatformUI.getWorkbench().getWorkbenchWindows())for(IWorkbenchPage p:w.getPages())for(IEditorReference r:p.getEditorReferences()) {
                var part=r.getEditor(false);if(part==null || !part.isDirty())continue;
                var file=part.getEditorInput().getAdapter(IFile.class);
                if(file==null || e.sources.containsKey(file.getProject().getName()))problem[0]=part.getTitle();
            }
        });
        if(problem[0]!=null)throw new IllegalStateException("Unsaved editor blocks merge changes: "+problem[0]);
    }
    private static IComparisonManager manager(){return nativeService("com._1c.g5.v8.dt.compare","com._1c.g5.v8.dt.internal.compare.ComparePlugin",IComparisonManager.class);}
    private static IQualifiedNameFilePathConverter converter(){return nativeService("com._1c.g5.v8.dt.compare","com._1c.g5.v8.dt.internal.compare.ComparePlugin",IQualifiedNameFilePathConverter.class);}
    private static <T>T nativeService(String id,String pluginName,Class<T> type) {
        try {
            Bundle b=Platform.getBundle(id);if(b==null)throw new IllegalStateException("EDT compare bundle missing");if(b.getState()!=Bundle.ACTIVE)b.start(Bundle.START_TRANSIENT);
            Class<?> p=b.loadClass(pluginName);Object plugin=p.getMethod("getDefault").invoke(null);Object injector=p.getMethod("getInjector").invoke(plugin);
            return type.cast(b.loadClass("com.google.inject.Injector").getMethod("getInstance",Class.class).invoke(injector,type));
        }catch(Exception e){throw new IllegalStateException("Native EDT comparison service unavailable: "+type.getSimpleName(),e);}
    }
    static boolean requiresCustomValidation(MergeSettings settings) {
        return settings!=null && settings.getMergeRule()==MergeRule.CUSTOM_MERGE
                && settings.getCustomSettings() instanceof TextNodeCustomMergeSettings;
    }
    static MergeRule rule(String rule){return switch(rule){case "keep_main"->MergeRule.DO_NOT_MERGE;case "take_other"->MergeRule.GET_FROM_OTHER;case "prefer_main"->MergeRule.MERGE_PRIORITIZING_MAIN;case "prefer_other"->MergeRule.MERGE_PRIORITIZING_OTHER;default->throw new IllegalArgumentException("Unknown rule");};}
    static String patch(String content,String old,String replacement) {
        if(content==null || old.isEmpty())throw new IllegalArgumentException("Read a materialized result first; old_text must not be empty");
        int at=content.indexOf(old);if(at<0 || content.indexOf(old,at+old.length())>=0)throw new IllegalArgumentException("Patch must match exactly once");
        return content.substring(0,at)+replacement+content.substring(at+old.length());
    }
    static void validateText(String text) {
        if(text==null)throw new IllegalArgumentException("Native custom result is missing");
        if(text.length()>MAX_TEXT || text.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid/oversized text");
        for(String line:text.split("\\R"))if(line.startsWith("<<<<<<<") || line.startsWith("=======") || line.startsWith(">>>>>>>"))throw new IllegalArgumentException("Unresolved merge markers");
    }
    static void requireHash(String expected,String actual){if(!Objects.equals(expected,actual))throw new IllegalStateException("STALE_STATE: read the current node/plan again");}
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((value==null?"<null>":value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static int number(Map<String,Object>a,String key,int fallback){return ((Number)a.getOrDefault(key,fallback)).intValue();}
    private static String text(Map<String,Object>a,String key){if(!(a.get(key) instanceof String value))throw new IllegalArgumentException("Missing "+key);return value;}
    private static String required(Map<String,Object>a,String key){if(!(a.get(key) instanceof String value)||value.isBlank())throw new IllegalArgumentException("Missing "+key);return value;}
}
