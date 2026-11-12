// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
public class StartupVisibilityLeaseTest {
 @Rule public TemporaryFolder tmp=new TemporaryFolder();
 Map<String,Object> context(){return Map.of("pid",ProcessHandle.current().pid(),"started",ProcessHandle.current().info().startInstant().orElseThrow().toString());}
 Path lease()throws Exception{Path file=tmp.getRoot().toPath().resolve("lease.json");var data=new LinkedHashMap<>(context());data.putAll(Map.of("window_id","0x123","generation","abc","property","owned","was_topmost",false,"owners",List.of()));Files.writeString(file,AgentResponses.JSON.toJson(data));return file;}
 static class Fake implements StartupVisibilityLease.Desktop {int identity=1,restores;boolean restored=true;public int identity(Map<String,Object> lease){return identity;}public boolean restore(Map<String,Object> lease){restores++;return restored;}}
 @Test public void helperTerminationStillRestoresPersistedWindowLease()throws Exception{var fake=new Fake();Path file=lease();assertTrue(StartupVisibilityLease.restore(file,context(),fake));assertEquals(1,fake.restores);assertFalse(Files.exists(file));}
 @Test public void changedWindowGenerationIsPreservedForCleanup()throws Exception{var fake=new Fake();fake.identity=2;Path file=lease();assertFalse(StartupVisibilityLease.restore(file,context(),fake));assertEquals(0,fake.restores);assertTrue(Files.exists(file));}
 @Test public void failedRestoreRetainsLeaseAndCanBeRetriedWithoutInput()throws Exception{var fake=new Fake();fake.restored=false;Path file=lease();assertFalse(StartupVisibilityLease.restore(file,context(),fake));assertTrue(Files.exists(file));fake.restored=true;assertTrue(StartupVisibilityLease.restore(file,context(),fake));assertEquals(2,fake.restores);}
 @Test public void foreignProcessLeaseNeverTouchesWindow()throws Exception{var fake=new Fake();Path file=lease();assertThrows(IllegalStateException.class,()->StartupVisibilityLease.restore(file,Map.of("pid",0,"started",context().get("started")),fake));assertEquals(0,fake.restores);assertTrue(Files.exists(file));}
 @Test public void alreadyTopmostWindowIsNeverDemoted()throws Exception{var fake=new Fake();Path file=lease();var saved=TaskSessionService.object(AgentResponses.JSON.fromJson(Files.readString(file),Map.class));saved.put("was_topmost",true);Files.writeString(file,AgentResponses.JSON.toJson(saved));assertTrue(StartupVisibilityLease.restore(file,context(),fake));assertEquals(0,fake.restores);}
}
