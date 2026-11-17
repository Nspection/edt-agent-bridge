// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
public class ProcessSnapshotScopeTest {
 @Test public void unknownClientFilterNeverEnumeratesAllProcesses(){var calls=new AtomicInteger();assertThrows(IllegalArgumentException.class,()->EdtProcessSnapshotTool.observe(Map.of("action","list","client_id","owned-client"),(a,p,s,w,i,e)->{calls.incrementAndGet();return Map.of();}));assertEquals(0,calls.get());}
 @Test public void invalidPidNeverFallsBackToGlobalInventory(){for(Object pid:List.of("12",0,1.5)){var calls=new AtomicInteger();assertThrows(IllegalArgumentException.class,()->EdtProcessSnapshotTool.observe(Map.of("action","list","process_id",pid),(a,p,s,w,i,e)->{calls.incrementAndGet();return Map.of();}));assertEquals(0,calls.get());}}
 @Test public void explicitPidReachesObserverUnchanged()throws Exception{EdtProcessSnapshotTool.observe(Map.of("action","list","process_id",42),(a,p,s,w,i,e)->{assertEquals("list",a);assertEquals(Long.valueOf(42),p);return Map.of();});}
}
