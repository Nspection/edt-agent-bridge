// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.compare;

import java.util.*;
import org.eclipse.emf.common.util.URI;
import org.eclipse.ui.*;
import com._1c.g5.v8.dt.compare.core.*;
import com._1c.g5.v8.dt.compare.model.*;
import com._1c.g5.v8.dt.moxel.*;
import com.codepilot1c.core.edt.metadata.*;

/** Shared native plan for ordinary UI sessions and MCP sessions. */
public final class SpreadsheetMergeSupport {
    public static final String EDITOR_ID="com.codepilot1c.ui.spreadsheetMerge";
    private static final com.google.gson.Gson JSON=new com.google.gson.GsonBuilder().serializeNulls().create();
    private SpreadsheetMergeSupport() {}
    public static Map<String,Object> details(IComparisonSession s,SolidResourceComparisonNode n) {
        if(!SpreadsheetMergeBridge.supports(n))throw new IllegalArgumentException("Only matching spreadsheet templates on both sides are supported");
        var sides=new LinkedHashMap<String,String>();var cells=new LinkedHashMap<String,Object>();
        for(var side:ComparisonSide.values())if(side!=ComparisonSide.COMMON_ANCESTOR || s.isThreeWay()) {
            String xml=SpreadsheetMergeBridge.read(s,n,side);sides.put(side.name(),xml);
            if(xml!=null){var resource=SpreadsheetMergeCodec.load(xml,s.getDataSource(side).getDtProject(),URI.createURI("memory:/side.mxlx"));cells.put(side.name(),NativeContentModels.sheet((SpreadsheetDocument)resource.getContents().get(0),1,100,1,50,200));}
        }
        var ms=n.getMergeSettings();String result=null;String source="not_materialized";
        if(ms.getMergeRule()==MergeRule.CUSTOM_MERGE && ms.getCustomSettings() instanceof TextNodeCustomMergeSettings text){result=text.getCustomMergeResult();source="native_custom_settings";}
        else if(ms.getMergeRule()==MergeRule.GET_FROM_OTHER){result=sides.get("OTHER");source="other";}
        else if(ms.getMergeRule()==MergeRule.DO_NOT_MERGE){result=sides.get("MAIN");source="main";}
        var out=new LinkedHashMap<String,Object>();out.put("node_id",Long.toString(n.bmGetId()));out.put("format","native_spreadsheet");out.put("source_guard",SpreadsheetMergeBridge.fingerprints(s,n));out.put("sides",sides);out.put("spreadsheet_sides",cells);out.put("result",result);out.put("result_source",source);out.put("merge_rule",ms.getMergeRule().name());out.put("selected",ms.isMustBeMerged());
        if(result!=null){var r=SpreadsheetMergeCodec.load(result,s.getDataSource(ComparisonSide.MAIN).getDtProject(),URI.createURI("memory:/result.mxlx"));out.put("spreadsheet_result",NativeContentModels.sheet((SpreadsheetDocument)r.getContents().get(0),1,100,1,50,200));}
        out.put("semantic_revision",SpreadsheetMergeSemantics.revision(s,n.bmGetId()));out.put("node_sha256",EdtComparisonService.hash(JSON.toJson(out)));out.put("draft_dirty",SpreadsheetMergeBridge.dirty(s));out.put("live_editor_buffer",false);return out;
    }
    public static Map<String,Object> commit(IComparisonSession s,long id,String xml,String expectedHash) throws Exception {return commit(s,id,xml,expectedHash,null);}
    static Map<String,Object> commit(IComparisonSession s,long id,String xml,String expectedHash,Map<String,String> decisions) throws Exception {
        SpreadsheetMergeBridge.install();
        if(!ComparisonUtils.isComparisonModelEditable(s.getStatus()))throw new IllegalStateException("Comparison is no longer editable");
        SpreadsheetMergeCodec.load(xml,s.getDataSource(ComparisonSide.MAIN).getDtProject(),URI.createURI("memory:/result.mxlx"));
        var result=EdtComparisonService.getInstance().task(s,true,(tx,ctx)->{
            var n=(SolidResourceComparisonNode)s.getNode(ctx,id);var before=details(s,n);
            EdtComparisonService.requireHash(expectedHash,(String)before.get("node_sha256"));
            if(!n.getMergeSettings().getAvailableMergeRules().contains(MergeRule.CUSTOM_MERGE))throw new IllegalStateException("Reopen comparison after enabling spreadsheet integration");
            var custom=CompareFactory.eINSTANCE.createTextNodeCustomMergeSettings();custom.setCustomMergeResult(xml);
            s.setCustomMergeSettings(id,custom,ctx);if(!s.setMergeRule(id,MergeRule.CUSTOM_MERGE,ctx))throw new IllegalStateException("EDT declined custom spreadsheet result");
            if(decisions==null)SpreadsheetMergeSemantics.activate(s,id);else SpreadsheetMergeSemantics.replaceChoices(s,id,decisions);SpreadsheetMergeBridge.remember(s,n,xml);return details(s,n);
        });
        s.setMustBeMerged(id,true,false);
        return EdtComparisonService.getInstance().task(s,false,(tx,ctx)->details(s,(SolidResourceComparisonNode)s.getNode(ctx,id)));
    }
    public static Map<String,Object> patch(IComparisonSession s,long id,Map<String,Object> a) throws Exception {
        if(SpreadsheetMergeBridge.dirty(s))throw new IllegalStateException("Unsaved spreadsheet editor blocks MCP writes");
        var before=EdtComparisonService.getInstance().task(s,false,(tx,ctx)->details(s,(SolidResourceComparisonNode)s.getNode(ctx,id)));
        String expected=(String)a.get("expected_node_sha256");EdtComparisonService.requireHash(expected,(String)before.get("node_sha256"));
        String xml=(String)before.get("result");
        @SuppressWarnings("unchecked") var sides=(Map<String,String>)before.get("sides");
        if(a.containsKey("base_side")){String side=(String)a.get("base_side");if(!Set.of("MAIN","OTHER","COMMON_ANCESTOR").contains(side))throw new IllegalArgumentException("Unknown base_side");xml=sides.get(side);}
        if(xml==null)throw new IllegalArgumentException("Choose base_side for an unmaterialized result");
        var resource=SpreadsheetMergeCodec.load(xml,s.getDataSource(ComparisonSide.MAIN).getDtProject(),URI.createURI("memory:/patch.mxlx"));
        if(a.containsKey("sheet_patch")){
            @SuppressWarnings("unchecked") var patch=(Map<String,Object>)a.get("sheet_patch");
            var r=SpreadsheetTemplateWriter.apply((SpreadsheetDocument)resource.getContents().get(0),NativeContentModels.sheetPatch(patch));
            if(r.hasError())throw new IllegalArgumentException(r.error);
        }
        return commit(s,id,SpreadsheetMergeCodec.save(resource),expected);
    }
    public static boolean probeReady(IComparisonSession s,long id) {
        return s!=null && id>=0 && ComparisonUtils.isComparisonModelEditable(s.getStatus());
    }
    /** Null is transient model unavailability and must never become a negative UI cache entry. */
    public static Boolean supports(IComparisonSession s,long id)throws Exception {
        // Native partial-model root/group rows use -1 and have no BM object.
        if(s==null || id<0)return false;
        if(!probeReady(s,id))return null;
        return EdtComparisonService.getInstance().task(s,false,(tx,ctx)->
            probeReady(s,id) ? SpreadsheetMergeBridge.supports(s.getNode(ctx,id)) : null);
    }
    public static Map<String,Object> close(IComparisonSession session,long id) throws Exception {return close(session,id,false);}
    static Map<String,Object> close(IComparisonSession session,long id,boolean discard) throws Exception {
        if(session==null)return Map.of("closed",false);
        var key=new SpreadsheetMergeInput(session,id,Map.of(),null,"");boolean[] closed={false};Exception[] error={null};
        PlatformUI.getWorkbench().getDisplay().syncExec(()->{
            for(var window:PlatformUI.getWorkbench().getWorkbenchWindows())for(var page:window.getPages()) {
                var part=page.findEditor(key);if(part==null)continue;
                if(part.isDirty()&&!discard){error[0]=new IllegalStateException("Unsaved spreadsheet draft: save or discard it in EDT before closing");return;}
                closed[0]=page.closeEditor(part,false);
            }
        });
        if(error[0]!=null)throw error[0];return Map.of("closed",closed[0],"source_projects_changed",false);
    }
    public static Object visual(IComparisonSession s,long id,Map<String,Object> args)throws Exception {
        if(!"ui_state".equals(args.get("action")))open(s,id);Object[] answer={null};Exception[] error={null};
        PlatformUI.getWorkbench().getDisplay().syncExec(()->{try{
            var key=new SpreadsheetMergeInput(s,id,Map.of(),null,"");var part=PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().findEditor(key);String action=(String)args.get("action");
            if(part==null&&action.equals("ui_state")){answer[0]=Map.of("opened",false);return;}
            if(action.equals("save_ui")){part.getClass().getMethod("savePlanForAgent").invoke(part);if(part.isDirty())throw new IllegalStateException("Spreadsheet editor remains unsaved");}
            if(action.equals("reveal_difference"))part.getClass().getMethod("revealDifference",String.class).invoke(part,args.get("difference_id"));
            if(action.equals("set_highlighting"))part.getClass().getMethod("setHighlighting",boolean.class).invoke(part,Boolean.TRUE.equals(args.get("enabled")));
            answer[0]=part.getClass().getMethod("semanticUiState").invoke(part);
        }catch(java.lang.reflect.InvocationTargetException wrapped){error[0]=wrapped.getCause() instanceof Exception cause?cause:wrapped;}catch(Exception e){error[0]=e;}});if(error[0]!=null)throw error[0];return answer[0];
    }
    public static Map<String,Object> open(IComparisonSession s,long id) throws Exception {
        SpreadsheetMergeBridge.install();
        var input=EdtComparisonService.getInstance().task(s,false,(tx,ctx)->{
            var d=details(s,(SolidResourceComparisonNode)s.getNode(ctx,id));
            @SuppressWarnings("unchecked") var sides=(Map<String,String>)d.get("sides");
            String initial=(String)d.get("result");if(initial==null)initial=sides.get("MAIN");
            return new SpreadsheetMergeInput(s,id,sides,initial,(String)d.get("node_sha256"));
        });
        var display=PlatformUI.getWorkbench().getDisplay();Exception[] error={null};String[] editorId={null};
        display.syncExec(()->{try{var w=PlatformUI.getWorkbench().getActiveWorkbenchWindow();if(w==null)throw new IllegalStateException("No workbench window");var existing=w.getActivePage().findEditor(input);
            if(existing!=null && existing.getEditorInput() instanceof SpreadsheetMergeInput old && !old.expectedHash.equals(input.expectedHash)){
                if(existing.isDirty())throw new IllegalStateException("Unsaved spreadsheet editor blocks refresh");
                w.getActivePage().closeEditor(existing,false);
            }
            var editor=w.getActivePage().openEditor(input,EDITOR_ID,true);if(!editor.getClass().getName().equals("com.codepilot1c.ui.bridge.SpreadsheetMergeEditor")){w.getActivePage().closeEditor(editor,false);throw new IllegalStateException("EDT opened an error editor; inspect workspace error log");}editorId[0]=com.codepilot1c.core.edt.editor.EdtEditorService.getInstance().register(editor);}catch(Exception e){error[0]=e;}});
        if(error[0]!=null)throw error[0];
        return Map.of("opened",true,"editor",EDITOR_ID,"editor_id",editorId[0]);
    }
}
