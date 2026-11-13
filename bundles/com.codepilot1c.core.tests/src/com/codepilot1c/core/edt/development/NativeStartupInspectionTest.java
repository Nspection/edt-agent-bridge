// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
public class NativeStartupInspectionTest {
 @Test public void alreadyConnectedHostIsObservedWithoutInspectionOrInput()throws Exception{var calls=new AtomicInteger();var value=NativeStartupInspection.inspect("owned",()->Map.of("state","running","execution_allowed",true),()->{calls.incrementAndGet();return Map.of();});assertEquals("running",value.get("state"));assertEquals(false,value.get("startup_input_dispatched"));assertEquals(0,calls.get());}
 @Test public void helloBetweenStatusAndInspectionReturnsFreshSameHostState()throws Exception{var reads=new AtomicInteger();var value=NativeStartupInspection.inspect("owned",()->Map.of("host_id","owned","state",reads.getAndIncrement()==0?"starting":"running"),()->{throw new NativeStartupInspection.PhaseEnded();});assertEquals(2,reads.get());assertEquals("running",value.get("state"));assertEquals("owned",value.get("host_id"));assertEquals(false,value.get("startup_inspection_performed"));}
 @Test public void realStartupQuestionPassesThroughWithoutAnswering()throws Exception{var question=Map.<String,Object>of("state","awaiting_platform_consent","question_hash","hash");assertSame(question,NativeStartupInspection.inspect("owned",()->Map.of("state","starting"),()->question));}
 @Test public void processConsentAndBaseFailuresAreNotSwallowed(){var error=new IllegalStateException("base changed");assertSame(error,assertThrows(IllegalStateException.class,()->NativeStartupInspection.inspect("owned",()->Map.of("state","starting"),()->{throw error;})));}
 @Test public void changedPhaseWithoutReadyChannelAsksOnlyForStatus()throws Exception{var value=NativeStartupInspection.inspect("owned",()->Map.of("state","starting"),()->{throw new NativeStartupInspection.PhaseEnded();});assertEquals("phase_changed",value.get("startup_inspection"));String next=AgentResponses.JSON.toJson(value.get("next_call"));assertTrue(next.contains("status"));assertFalse(next.contains("startup_inspect"));assertFalse(next.contains("startup_answer"));}
}
