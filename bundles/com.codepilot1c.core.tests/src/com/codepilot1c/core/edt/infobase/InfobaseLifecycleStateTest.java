// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
public class InfobaseLifecycleStateTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 @Test public void caseAndSlashAliasesShareOnePendingMarker() throws Exception {
  Assume.assumeTrue(java.io.File.separatorChar=='\\');
  Path state=temp.getRoot().toPath().resolve("state"),db=temp.getRoot().toPath().resolve("MixedCaseDb");
  var original=InfobaseReferences.newFileInfobaseReference(db.toString());
  var alias=InfobaseReferences.newFileInfobaseReference(db.toString().toUpperCase(Locale.ROOT).replace('\\','/'));
  InfobaseLifecycleState.requireFull(state,original);
  assertTrue(InfobaseLifecycleState.requiresFull(state,alias));
  assertEquals(InfobaseLifecycleState.marker(state,original),InfobaseLifecycleState.marker(state,alias));
  InfobaseLifecycleState.fullCompleted(state,alias);assertFalse(InfobaseLifecycleState.requiresFull(state,original));
 }
 @Test public void legacyMarkerIsHonoredAndRemovedOnlyAfterFullCompletion() throws Exception {
  Path state=temp.newFolder("state").toPath();var base=InfobaseReferences.newFileInfobaseReference(temp.getRoot()+"/db");
  Path legacy=state.resolve(InfobasePaths.hash(base.getConnectionString().asConnectionString())+".full-required");
  Files.writeString(legacy,"legacy");assertTrue(InfobaseLifecycleState.requiresFull(state,base));
  InfobaseLifecycleState.requireFull(state,base);assertTrue(Files.exists(legacy));
  InfobaseLifecycleState.fullCompleted(state,base);assertFalse(Files.exists(legacy));assertFalse(InfobaseLifecycleState.requiresFull(state,base));
 }
 @Test public void workflowUsesTheSameIdentityAsMarkers() {
  var a=InfobaseReferences.newFileInfobaseReference(temp.getRoot()+"/db/../database");
  var b=InfobaseReferences.newFileInfobaseReference(temp.getRoot()+"/database");
  assertEquals(InfobaseWorkflow.resourceKeys("Test",a),InfobaseWorkflow.resourceKeys("Test",b));
 }
 @Test public void absentComparisonCleanupIsIdempotentWithoutAttachingToAnotherSession() throws Exception {
  assertFalse(com.codepilot1c.core.edt.compare.EdtComparisonService.getInstance().closeOwnedSessionIfPresent(UUID.randomUUID().toString()));
 }
}
