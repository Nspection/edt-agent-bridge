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
    private static SolidResourceComparisonNode node(String main,String other){return proxy(SolidResourceComparisonNode.class,(p,m,a)->switch(m.getName()){case "getMainQualifyingType"->main;case "getOtherQualifyingType"->other;case "getMainSymlink","getOtherSymlink"->"CommonTemplate.Sheet";default->null;});}
    private static IComparisonSession session(){return proxy(IComparisonSession.class,(p,m,a)->switch(m.getName()){case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0];default->null;});}
    @SuppressWarnings("unchecked") private static <T>T proxy(Class<T> t,java.lang.reflect.InvocationHandler h){return (T)java.lang.reflect.Proxy.newProxyInstance(t.getClassLoader(),new Class<?>[]{t},h);}
}
