// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;import java.util.concurrent.atomic.AtomicLong;
public class StartupTimelineTest {
 @Test public void phasesAndSlowestStageAreMeasuredWithoutSensitiveDetails(){var clock=new AtomicLong();var t=new StartupTimeline(clock::get);clock.set(2_000_000_000L);t.next("connecting_protocol");clock.set(14_000_000_000L);t.finish("ready");var s=t.snapshot();assertEquals(14000L,s.get("elapsed_ms"));assertEquals(true,s.get("slow_start"));assertEquals("connecting_protocol",s.get("slowest_stage"));assertEquals(false,s.get("running"));assertFalse(s.containsKey("command_line"));}
 @Test public void liveStatusAdvancesOnlyAnActivePhase(){var t=new StartupTimeline(()->0);var s=t.snapshot();long now=((Number)s.get("observed_at_ms")).longValue()+12000;var live=StartupTimeline.live(s,now);assertEquals(12000L,live.get("elapsed_ms"));assertEquals(true,live.get("slow_start"));assertEquals(0L,s.get("elapsed_ms"));t.finish("failed");var frozen=t.snapshot();assertEquals(frozen,StartupTimeline.live(frozen,now));}
 @Test public void backwardsClockCannotProduceNegativeDuration(){var clock=new AtomicLong(10);var t=new StartupTimeline(clock::get);clock.set(0);assertEquals(0L,t.snapshot().get("elapsed_ms"));}
}
