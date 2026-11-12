package com.codepilot1c.core.search;
import org.junit.Test; import static org.junit.Assert.*;
public class SearchRoutingTest {
 @Test public void omittedAndBlankKeepLegacy(){assertEquals("text",SearchRouting.select(null,"a b").selected());assertEquals("text",SearchRouting.select(" ","a b").selected());}
 @Test public void explicitTextKeepsPhrase(){assertEquals("text",SearchRouting.select("text","проверка склада").selected());}
 @Test public void explicitRegexNeverRewritten(){assertEquals("regex",SearchRouting.select("REGEX","a.*b|c").selected());}
 @Test public void explicitBm25KeepsSingleTerm(){assertEquals("bm25",SearchRouting.select("bm25","склад").selected());}
 @Test public void autoIdentifiers(){for(String q:new String[]{"ПриОткрытии","_orders42","CommonModule.Export","Склад_Отгрузки"})assertEquals(q,"text",SearchRouting.select("auto",q).selected());}
 @Test public void autoPhrases(){for(String q:new String[]{"доступность склада","проверка-остатков","order dispatch","a.*b"})assertEquals(q,"bm25",SearchRouting.select("auto",q).selected());}
 @Test public void invalidAutoRejected(){for(String q:new String[]{""," ","a".repeat(1001)}){try{SearchRouting.select("auto",q);fail();}catch(IllegalArgumentException ok){}}}
 @Test public void legacyLongQueryUntouched(){assertEquals("text",SearchRouting.select("text","a".repeat(1001)).selected());}
 @Test(expected=IllegalArgumentException.class) public void unknownRejected(){SearchRouting.select("other","a");}
}
