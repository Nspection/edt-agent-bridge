// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;import java.util.concurrent.atomic.*;
public class UiPollLifecycleTest {
 @Test public void queuedOldCallbackCannotReviveAcrossRepeatedRestarts(){var poll=new UiPollLifecycle();var scheduled=new ArrayList<Runnable>();var cancel=new ArrayList<Runnable>();var work=new AtomicInteger();for(int i=0;i<1000;i++){poll.start((ms,r)->scheduled.add(r),cancel::add,work::incrementAndGet);Runnable old=scheduled.get(scheduled.size()-1);poll.stop();poll.start((ms,r)->scheduled.add(r),cancel::add,work::incrementAndGet);int before=scheduled.size();old.run();assertEquals(before,scheduled.size());poll.stop();}assertEquals(0,work.get());assertEquals(2000,cancel.size());}
 @Test public void duplicateStartDoesNotAddAnotherTimerAndStopDuringWorkCannotReschedule(){var poll=new UiPollLifecycle();var queue=new ArrayList<Runnable>();var work=new AtomicInteger();Runnable task=()->{work.incrementAndGet();poll.stop();};poll.start((ms,r)->queue.add(r),r->{},task);poll.start((ms,r)->queue.add(r),r->{},task);assertEquals(1,queue.size());queue.get(0).run();assertEquals(1,work.get());assertEquals(1,queue.size());}
}
