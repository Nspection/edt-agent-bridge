// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import static org.junit.Assert.*;
import org.junit.*;
import java.nio.file.*;
import java.util.*;
public class OwnedHostSourcesTest {
 private Path root;
 @Before public void setup()throws Exception{root=Files.createTempDirectory("owned-host-test");Files.writeString(root.resolve("Module.bsl"),"owned code");Files.writeString(root.resolve("Object.mdo"),"owned metadata");}
 @After public void clean()throws Exception{if(!Files.exists(root))return;try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
 @Test public void partialNativeDeleteMayRemoveOnlySomeFiles()throws Exception{var snapshot=OwnedHostSources.snapshot(root);Files.delete(root.resolve("Object.mdo"));OwnedHostSources.verifyRemaining(root,snapshot);}
 @Test public void changedBslIsPreservedForReview()throws Exception{var snapshot=OwnedHostSources.snapshot(root);Files.writeString(root.resolve("Module.bsl"),"user edit");try{OwnedHostSources.verifyRemaining(root,snapshot);fail();}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("Module.bsl"));}}
 @Test public void deletesOnlyRecordedUnchangedRemainders()throws Exception{var snapshot=OwnedHostSources.snapshot(root);Files.delete(root.resolve("Object.mdo"));OwnedHostSources.deleteRemaining(root,snapshot);assertFalse(Files.exists(root));}
 @Test public void deletionRefusesNewUserFileBeforeTouchingAnySource()throws Exception{var snapshot=OwnedHostSources.snapshot(root);Files.writeString(root.resolve("User.txt"),"user data");try{OwnedHostSources.deleteRemaining(root,snapshot);fail();}catch(IllegalStateException expected){}assertTrue(Files.exists(root.resolve("Module.bsl")));assertTrue(Files.exists(root.resolve("User.txt")));}
 @Test public void unknownFileBlocksCleanup()throws Exception{var snapshot=OwnedHostSources.snapshot(root);Files.writeString(root.resolve("User.txt"),"user data");try{OwnedHostSources.verifyRemaining(root,snapshot);fail();}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("User.txt"));}}
 @Test public void stageDoesNotAdoptSiblingAddedWhileNativeOperationWaits()throws Exception{
  var before=OwnedHostSources.snapshot(root);Files.writeString(root.resolve("Form.form"),"generated");Files.writeString(root.resolve("keep.txt"),"user");
  assertThrows(IllegalStateException.class,()->OwnedHostSources.advance(root,before,Set.of("Form.form")));assertFalse(before.containsKey("keep.txt"));assertEquals("user",Files.readString(root.resolve("keep.txt")));
 }
 @Test public void stageRejectsChangedDescriptorAndRetainsOriginalInventory()throws Exception{
  var before=OwnedHostSources.snapshot(root);Files.writeString(root.resolve("Object.mdo"),"user edit");
  assertThrows(IllegalStateException.class,()->OwnedHostSources.advance(root,before,Set.of("Form.form")));
 }
 @Test public void stageMergesOnlyExactGeneratedOutputs()throws Exception{
  var before=OwnedHostSources.snapshot(root);Files.writeString(root.resolve("Form.form"),"generated");var after=OwnedHostSources.advance(root,before,Set.of("Form.form"));assertEquals(3,after.size());assertEquals(before.get("Object.mdo"),after.get("Object.mdo"));assertFalse(before.containsKey("Form.form"));OwnedHostSources.verifyRemaining(root,after);
 }
 @Test public void generatedOutputReplacementOrDisappearanceCannotReplaceProof()throws Exception{
  var before=OwnedHostSources.snapshot(root);String proof=before.get("Module.bsl");Files.writeString(root.resolve("Module.bsl"),"substitution");var after=OwnedHostSources.advance(root,before,Set.of("Module.bsl"));assertThrows(IllegalStateException.class,()->OwnedHostSources.requireProof(after,"Module.bsl",proof));Files.delete(root.resolve("Module.bsl"));var absent=OwnedHostSources.advance(root,before,Set.of("Module.bsl"));assertThrows(IllegalStateException.class,()->OwnedHostSources.requireProof(absent,"Module.bsl",proof));
 }
}
