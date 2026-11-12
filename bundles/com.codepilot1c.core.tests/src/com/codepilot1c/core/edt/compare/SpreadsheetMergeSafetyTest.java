// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.compare;
import static org.junit.Assert.*;
import org.junit.Test;
import com._1c.g5.v8.dt.compare.model.*;
import com._1c.g5.v8.dt.compare.core.IComparisonSession;
public class SpreadsheetMergeSafetyTest {
    @Test public void rejectsDoctypeAndExternalEntities(){
        assertThrows(IllegalArgumentException.class,()->SpreadsheetMergeCodec.checkedBytes("<!DOCTYPE SpreadsheetDocument [<!ENTITY x SYSTEM 'file:///C:/secret'>]><SpreadsheetDocument>&x;</SpreadsheetDocument>"));
        assertThrows(IllegalArgumentException.class,()->SpreadsheetMergeCodec.checkedBytes("<Other/>"));
        assertThrows(IllegalArgumentException.class,()->SpreadsheetMergeCodec.checkedBytes(null));
    }
    @Test public void acceptsOnlyNativeSpreadsheetNamespace(){assertTrue(SpreadsheetMergeCodec.checkedBytes("<document xmlns=\"http://v8.1c.ru/8.2/data/spreadsheet\"/>").length>0);assertThrows(IllegalArgumentException.class,()->SpreadsheetMergeCodec.checkedBytes("<document/>"));}
    @Test public void rejectsOversizedUtf8BeforeNativeParsing(){assertThrows(IllegalArgumentException.class,()->SpreadsheetMergeCodec.checkedBytes("<SpreadsheetDocument>"+"Ж".repeat(1100000)+"</SpreadsheetDocument>"));}
    @Test public void unrelatedResourceTypesAndMissingSidesAreNeverIntercepted(){
        assertFalse(SpreadsheetMergeBridge.supports(null));
        assertFalse(SpreadsheetMergeBridge.supports(node("FileAwareTextDocument","FileAwareTextDocument")));
        assertFalse(SpreadsheetMergeBridge.supports(node("SpreadsheetDocument",null)));
        assertTrue(SpreadsheetMergeBridge.supports(node("SpreadsheetDocument","SpreadsheetDocument")));
    }
    @Test public void dirtyFlagsAreScopedToSessionAndRemainUntilEveryDraftIsClean(){
        var a=session();var b=session();SpreadsheetMergeBridge.dirty(a,1,true);SpreadsheetMergeBridge.dirty(a,2,true);
        assertTrue(SpreadsheetMergeBridge.dirty(a));assertFalse(SpreadsheetMergeBridge.dirty(b));
        SpreadsheetMergeBridge.dirty(a,1,false);assertTrue(SpreadsheetMergeBridge.dirty(a));
        SpreadsheetMergeBridge.dirty(a,2,false);assertFalse(SpreadsheetMergeBridge.dirty(a));
    }
    @Test public void virtualRowsNeverReachBmOrSessionStatus()throws Exception {
        var s=proxy(IComparisonSession.class,(p,m,a)->{throw new AssertionError("Virtual row reached session: "+m.getName());});
        assertFalse(SpreadsheetMergeSupport.supports(s,-1));
        assertFalse(SpreadsheetMergeSupport.supports(null,42));
    }
    @Test public void transientComparisonStateDoesNotBecomeNegativeSupportCache()throws Exception {
        var status=new java.util.concurrent.atomic.AtomicReference<>(com._1c.g5.v8.dt.compare.core.ComparisonProcessStatus.COMPARISON_PROCESS_INITIALIZATION_STARTED);
        var s=proxy(IComparisonSession.class,(p,m,a)->switch(m.getName()){
            case "getStatus"->status.get();default->throw new AssertionError("Unready probe entered BM: "+m.getName());
        });
        assertFalse(SpreadsheetMergeSupport.probeReady(s,7));assertNull(SpreadsheetMergeSupport.supports(s,7));
        status.set(com._1c.g5.v8.dt.compare.core.ComparisonProcessStatus.COMPARISON_PROCESS_FINISHED);
        assertTrue(SpreadsheetMergeSupport.probeReady(s,7));
        status.set(com._1c.g5.v8.dt.compare.core.ComparisonProcessStatus.MERGE_PROCESS_STARTED);
        assertFalse(SpreadsheetMergeSupport.probeReady(s,7));
    }
    @Test public void hugeUnrelatedNativeTreeIsNeverTraversedOrIndexed(){
        var calls=new java.util.ArrayList<Long>();
        var s=lookupSession(java.util.Map.of(),calls);
        var root=proxy(ComparisonNode.class,(p,m,a)->{
            if(m.getName().equals("getChildren"))throw new AssertionError("Would traverse 100001 unrelated native rows");
            return null;
        });
        var problems=new java.util.ArrayList<com._1c.g5.v8.dt.compare.merge.MergeValidationProblem>();
        SpreadsheetMergeBridge.validateChanged(s,null,root,problems);
        assertTrue(calls.isEmpty());assertTrue(problems.isEmpty());
    }
    @Test public void onlyRegisteredSpreadsheetCandidatesAreReadAndUntrustedCustomIsBlocked(){
        var calls=new java.util.ArrayList<Long>();var custom=CompareFactory.eINSTANCE.createTextNodeCustomMergeSettings();
        custom.setCustomMergeResult("untrusted restored result");
        var settings=proxy(MergeSettings.class,(p,m,a)->switch(m.getName()){
            case "isMustBeMerged"->true;case "getMergeRule"->MergeRule.CUSTOM_MERGE;case "getCustomSettings"->custom;default->null;
        });
        var n=candidate(7,settings);var s=lookupSession(java.util.Map.of(7L,n),calls);
        SpreadsheetMergeBridge.track(s,n);
        var problems=new java.util.ArrayList<com._1c.g5.v8.dt.compare.merge.MergeValidationProblem>();
        SpreadsheetMergeBridge.validateChanged(s,null,n,problems);
        assertEquals(java.util.List.of(7L),calls);assertEquals(1,problems.size());
    }
    @Test public void nativeNonCustomSelectionRemainsNative(){
        var calls=new java.util.ArrayList<Long>();
        var settings=proxy(MergeSettings.class,(p,m,a)->switch(m.getName()){
            case "isMustBeMerged"->true;case "getMergeRule"->MergeRule.GET_FROM_OTHER;default->throw new AssertionError("Native result was intercepted");
        });
        var n=candidate(9,settings);var s=lookupSession(java.util.Map.of(9L,n),calls);SpreadsheetMergeBridge.track(s,n);
        var problems=new java.util.ArrayList<com._1c.g5.v8.dt.compare.merge.MergeValidationProblem>();
        SpreadsheetMergeBridge.validateChanged(s,null,n,problems);assertTrue(problems.isEmpty());assertEquals(java.util.List.of(9L),calls);
    }
    @Test public void restoreCallbackUsesItsSessionWithoutTrustingResult()throws Throwable {
        var settings=proxy(MergeSettings.class,(p,m,a)->switch(m.getName()){
            case "isMustBeMerged"->true;case "getMergeRule"->MergeRule.CUSTOM_MERGE;case "getCustomSettings"->CompareFactory.eINSTANCE.createTextNodeCustomMergeSettings();default->null;
        });
        var n=candidate(17,settings);var calls=new java.util.ArrayList<Long>();var s=lookupSession(java.util.Map.of(17L,n),calls);
        var otherCalls=new java.util.ArrayList<Long>();var other=lookupSession(java.util.Map.of(),otherCalls);
        var type=com._1c.g5.v8.dt.compare.core.IComparisonParticipant.class;
        var original=proxy(type,(p,m,a)->{throw new AssertionError("Native restore should not handle bridge payload");});
        var restore=type.getMethod("restoreCustomSettings",ComparisonNode.class,com._1c.g5.v8.dt.compare.core.SerializableMergeSettings.class,IComparisonSession.class);
        var dispatch=SpreadsheetMergeBridge.class.getDeclaredMethod("dispatch",type,java.lang.reflect.Method.class,Object[].class);dispatch.setAccessible(true);
        dispatch.invoke(null,original,restore,new Object[]{n,null,s});
        var problems=new java.util.ArrayList<com._1c.g5.v8.dt.compare.merge.MergeValidationProblem>();
        SpreadsheetMergeBridge.validateChanged(s,null,n,problems);assertEquals(1,problems.size());assertEquals(java.util.List.of(17L),calls);
        problems.clear();SpreadsheetMergeBridge.validateChanged(other,null,n,problems);assertTrue(otherCalls.isEmpty());assertTrue(problems.isEmpty());
    }
    @Test public void staleRegisteredBmIdBecomesValidationProblemInsteadOfMergeCrash(){
        var n=candidate(23,null);
        var s=proxy(IComparisonSession.class,(p,m,a)->switch(m.getName()){
            case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0];case "getNode"->throw new IllegalStateException("retired BM node");default->throw new AssertionError(m.getName());
        });
        SpreadsheetMergeBridge.track(s,n);var problems=new java.util.ArrayList<com._1c.g5.v8.dt.compare.merge.MergeValidationProblem>();
        SpreadsheetMergeBridge.validateChanged(s,null,n,problems);assertEquals(1,problems.size());
    }
    private static IComparisonSession lookupSession(java.util.Map<Long,? extends ComparisonNode> nodes,java.util.List<Long> calls){
        return proxy(IComparisonSession.class,(p,m,a)->switch(m.getName()){
            case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0];
            case "getNode"->{long id=(Long)a[a.length-1];calls.add(id);yield nodes.get(id);}
            default->throw new AssertionError("Unexpected native tree access: "+m.getName());
        });
    }
    private static SolidResourceComparisonNode candidate(long id,MergeSettings settings){
        return proxy(SolidResourceComparisonNode.class,(p,m,a)->switch(m.getName()){
            case "bmGetId"->id;case "getMainQualifyingType","getOtherQualifyingType"->"SpreadsheetDocument";
            case "getMainSymlink","getOtherSymlink"->"CommonTemplate.Sheet";case "getMergeSettings"->settings;
            default->throw new AssertionError("Unexpected node access: "+m.getName());
        });
    }
    private static SolidResourceComparisonNode node(String main,String other){return proxy(SolidResourceComparisonNode.class,(p,m,a)->switch(m.getName()){case "getMainQualifyingType"->main;case "getOtherQualifyingType"->other;case "getMainSymlink","getOtherSymlink"->"CommonTemplate.Sheet";default->null;});}
    private static IComparisonSession session(){return proxy(IComparisonSession.class,(p,m,a)->switch(m.getName()){case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0];default->null;});}
    @SuppressWarnings("unchecked") private static <T>T proxy(Class<T> t,java.lang.reflect.InvocationHandler h){return (T)java.lang.reflect.Proxy.newProxyInstance(t.getClassLoader(),new Class<?>[]{t},h);}
}
