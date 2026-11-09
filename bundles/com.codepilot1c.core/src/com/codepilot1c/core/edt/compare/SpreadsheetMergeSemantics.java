// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.compare;
import java.util.*;
import org.eclipse.emf.common.util.URI;
import com._1c.g5.v8.dt.compare.core.*;
import com._1c.g5.v8.dt.compare.model.*;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;

/** Decisions are scoped to the exact native session and never silently applied to a new comparison. */
public final class SpreadsheetMergeSemantics {
    private static final Map<IComparisonSession,Map<Long,Map<String,String>>> STATES=Collections.synchronizedMap(new WeakHashMap<>());
    private SpreadsheetMergeSemantics(){}
    private static Map<String,String> choices(IComparisonSession s,long id){synchronized(STATES){return new TreeMap<>(STATES.getOrDefault(s,Map.of()).getOrDefault(id,Map.of()));}}
    static Map<String,String> checkpointChoices(IComparisonSession session,long id){return choices(session,id);}
    public static String revision(IComparisonSession s,long id){synchronized(STATES){return STATES.containsKey(s)&&STATES.get(s).containsKey(id)?EdtComparisonService.hash(choices(s,id).toString()):null;}}
    public static void activate(IComparisonSession s,long id){synchronized(STATES){STATES.computeIfAbsent(s,k->new HashMap<>()).computeIfAbsent(id,k->new TreeMap<>());}}
    public static void clear(){STATES.clear();}
    static void replaceChoices(IComparisonSession s,long id,Map<String,String> choices){synchronized(STATES){STATES.computeIfAbsent(s,k->new HashMap<>()).put(id,new TreeMap<>(choices));}}
    /** Explicit reset of this node only; the source documents and other nodes are untouched. */
    public static Map<String,Object> reset(IComparisonSession s,long id,String expectedHash,boolean discardUnsaved)throws Exception {
        if(!ComparisonUtils.isComparisonModelEditable(s.getStatus()))throw new IllegalStateException("Comparison is no longer editable");
        var before=details(s,id);EdtComparisonService.requireHash(expectedHash,(String)before.get("node_sha256"));
        @SuppressWarnings("unchecked") var sides=(Map<String,String>)before.get("sides");
        String main=sides.get("MAIN");if(main==null)throw new IllegalStateException("Current spreadsheet is unavailable");
        if(!discardUnsaved&&SpreadsheetMergeBridge.dirty(s))throw new IllegalStateException("Save or discard the spreadsheet draft before resetting its plan");
        // Never discard a dirty UI draft until the guarded native write has succeeded.
        SpreadsheetMergeSupport.commit(s,id,main,expectedHash,Map.of());
        var closed=SpreadsheetMergeSupport.close(s,id,discardUnsaved);
        var out=new LinkedHashMap<>(details(s,id));out.put("reset",true);out.put("reset_base_side","MAIN");out.put("source_projects_changed",false);
        if(Boolean.TRUE.equals(closed.get("closed")))out.put("editor",SpreadsheetMergeSupport.open(s,id));
        return out;
    }

    public static SpreadsheetSemanticMerge.Analysis analyze(IComparisonSession s,Map<String,Object> details,Map<String,String> choices,boolean automatic,String currentXml,Set<String> applyIds){
        @SuppressWarnings("unchecked") var sides=(Map<String,String>)details.get("sides");
        var project=s.getDataSource(ComparisonSide.MAIN).getDtProject();
        java.util.function.Function<String,SpreadsheetDocument> load=xml->xml==null?null:(SpreadsheetDocument)SpreadsheetMergeCodec.load(xml,project,URI.createURI("memory:/semantic.mxlx")).getContents().get(0);
        String current=currentXml!=null?currentXml:(String)details.get("result");
        return SpreadsheetSemanticMerge.analyze(load.apply(sides.get("COMMON_ANCESTOR")),load.apply(sides.get("MAIN")),load.apply(sides.get("OTHER")),load.apply(current),choices,automatic,applyIds);
    }
    public static Map<String,Object> details(IComparisonSession s,long id)throws Exception {
        return EdtComparisonService.getInstance().task(s,false,(tx,ctx)->SpreadsheetMergeSupport.details(s,(SolidResourceComparisonNode)s.getNode(ctx,id)));
    }
    public static SpreadsheetSemanticMerge.Analysis preview(IComparisonSession s,long id,String currentXml)throws Exception{return analyze(s,details(s,id),choices(s,id),false,currentXml,Set.of());}
    public static void validate(IComparisonSession s,SolidResourceComparisonNode node){
        if(revision(s,node.bmGetId())==null)return;
        var analysis=analyze(s,SpreadsheetMergeSupport.details(s,node),choices(s,node.bmGetId()),false,null,Set.of());
        if(analysis.unresolved()>0)throw new IllegalStateException("Unresolved spreadsheet conflicts: "+analysis.unresolved());
    }
    public static Map<String,Object> mergeMode(IComparisonSession s,long id,Map<String,Object> args)throws Exception {
        if(!Boolean.TRUE.equals(args.get("replace_result")))throw new IllegalArgumentException("merge_mode requires replace_result=true: existing manual edits and decisions will be replaced");
        if(SpreadsheetMergeBridge.dirty(s))throw new IllegalStateException("Save or discard the spreadsheet draft before changing its mode");
        var before=details(s,id);String expected=(String)args.get("expected_node_sha256");EdtComparisonService.requireHash(expected,(String)before.get("node_sha256"));
        @SuppressWarnings("unchecked") var sides=(Map<String,String>)before.get("sides");
        var project=s.getDataSource(ComparisonSide.MAIN).getDtProject();
        java.util.function.Function<String,SpreadsheetDocument> load=xml->xml==null?null:(SpreadsheetDocument)SpreadsheetMergeCodec.load(xml,project,URI.createURI("memory:/mode.mxlx")).getContents().get(0);
        String mode=(String)args.get("mode");
        var merged=SpreadsheetSemanticMerge.applyMode(load.apply(sides.get("COMMON_ANCESTOR")),load.apply(sides.get("MAIN")),load.apply(sides.get("OTHER")),mode);
        String xml=Set.of("MAIN","OTHER").contains(mode)?sides.get(mode):SpreadsheetMergeCodec.saveDocument(merged.analysis().result(),project);
        SpreadsheetMergeSupport.commit(s,id,xml,expected,merged.decisions());
        var out=new LinkedHashMap<>(execute(s,id,Map.of("action","semantic_diff")));
        out.put("mode",mode);out.put("saved_to_plan",true);out.put("replaced_result",true);
        out.put("structural_conflict",merged.analysis().differences().stream().anyMatch(d->d.path().equals("/structure")&&!d.resolved()));
        return out;
    }
    public static Map<String,Object> execute(IComparisonSession s,long id,Map<String,Object> args)throws Exception {
        String action=(String)args.getOrDefault("action","semantic_diff");var before=details(s,id);var selected=choices(s,id);
        boolean write=!action.equals("semantic_diff");
        int offset=((Number)args.getOrDefault("offset",0)).intValue(),limit=((Number)args.getOrDefault("limit",100)).intValue();
        if(offset<0||limit<1||limit>500)throw new IllegalArgumentException("Invalid semantic diff range");
        if(!write&&args.containsKey("resolutions"))throw new IllegalArgumentException("Use resolve_conflicts to set decisions");
        if(write){
            if(SpreadsheetMergeBridge.dirty(s))throw new IllegalStateException("Save or discard the spreadsheet draft before changing its plan");
            EdtComparisonService.requireHash((String)args.get("expected_node_sha256"),(String)before.get("node_sha256"));
        }
        if(args.get("resolutions") instanceof Map<?,?> incoming)for(var entry:incoming.entrySet()){
            String key=String.valueOf(entry.getKey()),value=String.valueOf(entry.getValue());
            if(!Set.of("MAIN","OTHER","COMMON_ANCESTOR","RESULT").contains(value))throw new IllegalArgumentException("Unknown resolution");selected.put(key,value);
        }
        var applyIds=new HashSet<String>();if(args.get("resolutions") instanceof Map<?,?> incoming)incoming.keySet().forEach(k->applyIds.add(String.valueOf(k)));
        var analysis=analyze(s,before,selected,action.equals("auto_merge"),null,applyIds);
        var valid=new HashSet<String>();analysis.differences().forEach(d->valid.add(d.id()));
        if(args.get("resolutions") instanceof Map<?,?> incoming)for(var key:incoming.keySet())if(!valid.contains(String.valueOf(key)))throw new IllegalArgumentException("Unknown or stale difference id");
        if(write){
            String xml;try{xml=SpreadsheetMergeCodec.saveDocument(analysis.result(),s.getDataSource(ComparisonSide.MAIN).getDtProject());}catch(RuntimeException error){com.codepilot1c.core.internal.VibeCorePlugin.logError(error);throw error;}
            SpreadsheetMergeSupport.commit(s,id,xml,(String)before.get("node_sha256"),selected);
            before=details(s,id);analysis=analyze(s,before,selected,false,null,Set.of());
        }
        var out=new LinkedHashMap<String,Object>();out.put("node_sha256",before.get("node_sha256"));out.put("three_way",analysis.threeWay());out.put("total",analysis.differences().size());out.put("unresolved_conflicts",analysis.unresolved());
        out.put("differences",analysis.differences().stream().skip(offset).limit(limit).toList());out.put("has_more",offset+limit<analysis.differences().size());out.put("saved_to_plan",write);out.put("source_projects_changed",false);return out;
    }
}
