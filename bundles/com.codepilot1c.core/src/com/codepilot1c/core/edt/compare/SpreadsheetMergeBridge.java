// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.compare;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com._1c.g5.v8.dt.compare.core.*;
import com._1c.g5.v8.dt.compare.model.*;
import com._1c.g5.v8.dt.compare.merge.*;
import org.eclipse.core.runtime.*;

/** Guarded adapter for EDT 28: same participant indexes preserve native EClass caches.
 * All unrelated behavior is delegated. Actual writes are native postponed operations.
 */
public final class SpreadsheetMergeBridge {
    private static final Map<IComparisonSession,Map<Long,Saved>> SAVED=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<IComparisonSession,Set<Long>> DIRTY=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<IComparisonSession,Set<Long>> CANDIDATES=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<IComparisonParticipant,IComparisonParticipant> WRAPPED=new IdentityHashMap<>();
    private static List<IComparisonParticipant> participants;
    private static String failure;
    record Saved(String resultHash,Map<String,String> sides) {}
    private SpreadsheetMergeBridge() {}
    public static synchronized void install() {
        if(participants!=null)return;
        try {
            var bundle=Platform.getBundle("com._1c.g5.v8.dt.compare");
            if(bundle==null || bundle.getVersion().getMajor()!=28)throw new IllegalStateException("Supported compare SDK major is 28");
            var manager=EdtComparisonService.manager();
            if(manager.hasActiveComparison())throw new IllegalStateException("Wait for active comparison to finish");
            if(!manager.getClass().getName().equals("com._1c.g5.v8.dt.internal.compare.ComparisonManager"))throw new IllegalStateException("Unknown comparison manager");
            var field=manager.getClass().getDeclaredField("participants");field.setAccessible(true);
            @SuppressWarnings("unchecked") var list=(List<IComparisonParticipant>)field.get(manager);
            if(list.stream().noneMatch(p->p.getClass().getName().contains(".SolidResourceComparisonParticipant")))throw new IllegalStateException("Native resource participant missing");
            var replacements=new ArrayList<IComparisonParticipant>();
            for(var original:list) {
                var wrapper=(IComparisonParticipant)Proxy.newProxyInstance(original.getClass().getClassLoader(),participantInterfaces(original),(proxy,method,args)->dispatch(original,method,args));
                replacements.add(wrapper);WRAPPED.put(wrapper,original);
            }
            for(int i=0;i<list.size();i++)list.set(i,replacements.get(i));
            participants=list;failure=null;
        }catch(ReflectiveOperationException|RuntimeException e){WRAPPED.clear();failure=e.toString();throw new IllegalStateException("Spreadsheet merge integration unavailable: "+failure,e);}
    }
    public static synchronized void shutdown() {
        if(participants!=null)for(int i=0;i<participants.size();i++){var original=WRAPPED.get(participants.get(i));if(original!=null)participants.set(i,original);}
        participants=null;WRAPPED.clear();SAVED.clear();DIRTY.clear();CANDIDATES.clear();SpreadsheetMergeSemantics.clear();
    }
    public static boolean supports(ComparisonNode node) {
        if(!(node instanceof SolidResourceComparisonNode n))return false;
        return "SpreadsheetDocument".equals(n.getMainQualifyingType()) && "SpreadsheetDocument".equals(n.getOtherQualifyingType())
            && n.getMainSymlink()!=null && n.getOtherSymlink()!=null;
    }
    public static void dirty(IComparisonSession session,long id,boolean value) {
        synchronized(DIRTY){var ids=DIRTY.computeIfAbsent(session,s->new HashSet<>());if(value)ids.add(id);else ids.remove(id);if(ids.isEmpty())DIRTY.remove(session);}
    }
    public static boolean dirty(IComparisonSession session){synchronized(DIRTY){return DIRTY.containsKey(session);}}
    public static Map<String,String> fingerprints(IComparisonSession session,SolidResourceComparisonNode node) {
        var result=new TreeMap<String,String>();
        for(var side:ComparisonSide.values())if(side!=ComparisonSide.COMMON_ANCESTOR || session.isThreeWay()) {
            result.put(side.name(),EdtComparisonService.hash(read(session,node,side)));
            if(node.getSymlink(side)==null)continue;
            var dt=session.getDataSource(side).getDtProject();var project=dt==null?null:dt.getWorkspaceProject();
            if(project==null || project.getLocation()==null)throw new IllegalStateException("Guarded spreadsheet merge requires local workspace projects");
            var relative=ComparisonUtils.getFilePathBySymlink(node.getSymlink(side),node.getQualifyingType(side),EdtComparisonService.converter());
            try {
                var root=project.getLocation().toFile().toPath().toRealPath();var file=root.resolve(relative).normalize();
                if(!java.nio.file.Files.isRegularFile(file))file=root.resolve("src").resolve(relative).normalize();
                file=file.toRealPath();if(!file.startsWith(root))throw new IllegalStateException("Linked spreadsheet outside project is unsupported");
                try(var stream=java.nio.file.Files.newInputStream(file)){
                    byte[] bytes=stream.readNBytes(SpreadsheetMergeCodec.MAX_BYTES+1);if(bytes.length>SpreadsheetMergeCodec.MAX_BYTES)throw new IllegalArgumentException("Spreadsheet exceeds 2 MiB");
                    result.put(side.name()+"_live",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
                }
            }catch(java.io.IOException|java.security.NoSuchAlgorithmException e){throw new IllegalStateException("Cannot verify live spreadsheet source",e);}
        }
        return result;
    }
    public static String read(IComparisonSession session,SolidResourceComparisonNode node,ComparisonSide side) {
        String link=node.getSymlink(side);String type=node.getQualifyingType(side);
        if(link==null || !"SpreadsheetDocument".equals(type))return null;
        var path=ComparisonUtils.getFilePathBySymlink(link,type,EdtComparisonService.converter());
        var source=session.getDataSource(side);if(!source.fileExists(path))return null;
        try(var in=source.getFileStream(path)) {
            byte[] bytes=in.readNBytes(SpreadsheetMergeCodec.MAX_BYTES+1);
            if(bytes.length>SpreadsheetMergeCodec.MAX_BYTES)throw new IllegalArgumentException("Spreadsheet exceeds 2 MiB");
            return new String(bytes,StandardCharsets.UTF_8);
        }catch(IOException e){throw new IllegalStateException("Cannot read compared spreadsheet",e);}
    }
    public static void remember(IComparisonSession session,SolidResourceComparisonNode node,String xml) {
        synchronized(SAVED){SAVED.computeIfAbsent(session,s->new HashMap<>()).put(node.bmGetId(),new Saved(EdtComparisonService.hash(xml),fingerprints(session,node)));}
    }
    private static void validate(IComparisonSession session,SolidResourceComparisonNode node) {
        if(dirty(session))throw new IllegalStateException("Unsaved spreadsheet result: save or discard the editor before merging");
        SpreadsheetMergeSemantics.validate(session,node);
        var custom=node.getMergeSettings().getCustomSettings();
        if(!(custom instanceof TextNodeCustomMergeSettings text))throw new IllegalStateException("Spreadsheet result missing");
        String xml=text.getCustomMergeResult();
        Saved saved;synchronized(SAVED){saved=SAVED.getOrDefault(session,Map.of()).get(node.bmGetId());}
        if(saved==null || !saved.resultHash().equals(EdtComparisonService.hash(xml)) || !saved.sides().equals(fingerprints(session,node)))
            throw new IllegalStateException("Spreadsheet result/source changed: reopen and save the result again");
        SpreadsheetMergeCodec.load(xml,session.getDataSource(ComparisonSide.MAIN).getDtProject(),org.eclipse.emf.common.util.URI.createURI("memory:/validate.mxlx"));
    }
    static void track(IComparisonSession session,ComparisonNode node) {
        synchronized(CANDIDATES){CANDIDATES.computeIfAbsent(session,s->new HashSet<>()).add(node.bmGetId());}
    }
    /** Address only registered spreadsheet nodes. Native Team merges can contain millions of unrelated rows. */
    static void validateChanged(IComparisonSession session,ComparisonContext context,ComparisonNode root,List<MergeValidationProblem> problems) {
        var ids=new LinkedHashSet<Long>();
        synchronized(SAVED){ids.addAll(SAVED.getOrDefault(session,Map.of()).keySet());}
        synchronized(CANDIDATES){ids.addAll(CANDIDATES.getOrDefault(session,Set.of()));}
        for(long id:ids) {
            if(id<0)continue;
            ComparisonNode next;
            try{next=session.getNode(context,id);}
            catch(RuntimeException e){
                // A vanished guarded result cannot silently become trusted.
                boolean guarded;
                synchronized(SAVED){guarded=SAVED.getOrDefault(session,Map.of()).containsKey(id);}
                synchronized(CANDIDATES){guarded|=CANDIDATES.getOrDefault(session,Set.of()).contains(id);}
                if(guarded)problems.add(new MergeValidationProblem(root,"Spreadsheet result is unavailable: reopen the comparison"));
                continue;
            }
            if(supports(next) && next.getMergeSettings().isMustBeMerged() && next.getMergeSettings().getMergeRule()==MergeRule.CUSTOM_MERGE)
                try{validate(session,(SolidResourceComparisonNode)next);}catch(RuntimeException e){problems.add(new MergeValidationProblem(next,e.getMessage()));}
        }
    }
    private static Class<?>[] participantInterfaces(IComparisonParticipant original) {
        var types=new LinkedHashSet<Class<?>>();types.add(IComparisonParticipant.class);
        for(Class<?> type=original.getClass();type!=null;type=type.getSuperclass())for(Class<?> i:type.getInterfaces())
            if(java.lang.reflect.Modifier.isPublic(i.getModifiers()))types.add(i);
        return types.toArray(Class<?>[]::new);
    }
    public static synchronized boolean installed(){return participants!=null;}
    private static Object dispatch(IComparisonParticipant original,Method method,Object[] args)throws Throwable {
        String name=method.getName();
        if(name.equals("supportsCustomMerging") && supports((ComparisonNode)args[0])) {
            track((IComparisonSession)args[1],(ComparisonNode)args[0]);return true;
        }
        if(name.equals("validateMerge")) {
            var session=((IMergeSession)args[0]).getComparisonSession();var node=(ComparisonNode)args[2];
            @SuppressWarnings("unchecked") var problems=(List<MergeValidationProblem>)args[3];
            if(dirty(session)){problems.add(new MergeValidationProblem(node,"Save or discard the unsaved spreadsheet result"));return null;}
            if(original.getClass().getName().contains(".SolidResourceComparisonParticipant")) {
                validateChanged(session,(ComparisonContext)args[1],node,problems);
            }
        }
        if(name.equals("merge") && supports((ComparisonNode)args[0])) {
            var node=(SolidResourceComparisonNode)args[0];var context=(MergeContext)args[1];
            if(node.getMergeSettings().getMergeRule()==MergeRule.CUSTOM_MERGE) {
                validate(context.mergeSession.getComparisonSession(),node);
                byte[] bytes=SpreadsheetMergeCodec.checkedBytes(((TextNodeCustomMergeSettings)node.getMergeSettings().getCustomSettings()).getCustomMergeResult());
                MergeUtils.addPostponedMergeOperation(context,node,node.getMainQualifyingType(),(id,converter)->s->new ByteArrayInputStream(bytes),EdtComparisonService.converter());
                return Status.OK_STATUS;
            }
        }
        if((name.equals("saveCustomSettings") || name.equals("restoreCustomSettings")) && supports((ComparisonNode)args[0])) {
            if(name.equals("saveCustomSettings"))MergeUtils.saveCustomSettings((ComparisonNode)args[0],(SerializableMergeSettings)args[1],"bridgeSpreadsheet");
            else {
                MergeUtils.restoreCustomSettings((ComparisonNode)args[0],(SerializableMergeSettings)args[1],"bridgeSpreadsheet");
                track((IComparisonSession)args[2],(ComparisonNode)args[0]);
            }
            return null;
        }
        try{return method.invoke(original,args);}catch(InvocationTargetException e){throw e.getCause();}
    }
}
