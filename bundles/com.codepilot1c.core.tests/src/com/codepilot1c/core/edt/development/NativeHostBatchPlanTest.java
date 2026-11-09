// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;import com.google.gson.JsonParser;
public class NativeHostBatchPlanTest {
 @Test public void explicitStepsArePreservedWithTypedDefaults(){var value=JsonParser.parseString(NativeHostBatchPlan.payload(Map.of("steps",List.of(Map.of("code","result=1;","screenshot",true,"caption","Stage 1"))))).getAsJsonObject().getAsJsonArray("steps").get(0).getAsJsonObject();assertEquals("client",value.get("context").getAsString());assertTrue(value.get("screenshot").getAsBoolean());assertEquals("Stage 1",value.get("caption").getAsString());}
 @Test public void malformedOrUnboundedBatchesFailBeforeSubmission(){for(Object steps:List.of(List.of(),Collections.nCopies(17,Map.of("code","result=1;")),List.of(Map.of("code","x","context","background")),List.of(Map.of("code","x","screenshot","true")),List.of(Map.of("code","x","unknown",true)))){try{NativeHostBatchPlan.payload(Map.of("steps",steps));fail();}catch(IllegalArgumentException expected){}}}
 @Test public void oldComponentCannotExecuteScreenshotBatch(){var input=Map.<String,Object>of("steps",List.of(Map.of("code","x","screenshot",true)));try{NativeHostBatchPlan.requireCapabilities(input,Map.of());fail();}catch(IllegalStateException expected){}NativeHostBatchPlan.requireCapabilities(input,Map.of("native_features",List.of("screenshot_png")));NativeHostBatchPlan.requireCapabilities(Map.of("steps",List.of(Map.of("code","x"))),Map.of());}
}
