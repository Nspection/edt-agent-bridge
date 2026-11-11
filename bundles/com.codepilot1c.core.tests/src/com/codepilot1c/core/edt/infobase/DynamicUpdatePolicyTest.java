// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;
import java.util.*;import org.junit.*;import org.junit.rules.TemporaryFolder;import static org.junit.Assert.*;
public class DynamicUpdatePolicyTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 static class Fake implements DynamicUpdatePolicy.Backend {
  boolean unbound;String base="11111111-1111-1111-1111-111111111111",path="C:/own/project",branch="refs/heads/one",target="a".repeat(64);
  public Map<String,Object> binding(String project){if(unbound)throw new IllegalStateException("Unbound");return Map.of("project",project,"project_path",path,"context",List.of(List.of(true,branch)),"base_uuid",base,"primary_uuid",base,"target_identity_sha256",target);}
 }
 Fake fake;DynamicUpdatePolicy store;
 @Before public void setup()throws Exception{fake=new Fake();store=new DynamicUpdatePolicy(temp.newFolder().toPath(),fake);}
 Map<String,Object> apply(String policy)throws Exception{var preview=store.preview("P",policy);return store.apply("P",policy,(String)preview.get("plan_sha256"));}
 @Test public void defaultIsAskAndGrantSurvivesNewStoreInstance()throws Exception{assertEquals("ask",store.status("P").get("policy"));var r=apply("allow_dynamic");assertFalse(store.permission("P",fake.base,fake.target).isEmpty());assertEquals(r,new DynamicUpdatePolicy(temp.getRoot().toPath().resolve(storeRootName()),fake).status("P"));}
 String storeRootName(){return temp.getRoot().list()[0];}
 @Test public void revokeStopsFutureAnswersAndInvalidatesOldPreview()throws Exception{var old=store.preview("P","allow_dynamic");apply("allow_dynamic");assertThrows(IllegalStateException.class,()->store.apply("P","allow_dynamic",(String)old.get("plan_sha256")));apply("ask");assertTrue(store.permission("P",fake.base,fake.target).isEmpty());}
 @Test public void branchDriftDisablesGrantAndStalePlan()throws Exception{var p=store.preview("P","allow_dynamic");apply("allow_dynamic");fake.branch="refs/heads/two";assertEquals("ask",store.status("P").get("policy"));assertThrows(IllegalStateException.class,()->store.apply("P","allow_dynamic",(String)p.get("plan_sha256")));}
 @Test public void projectPathBaseTargetAndProjectNameAreAllScoped()throws Exception{apply("allow_dynamic");String path=fake.path;fake.path="C:/other/project";assertEquals("ask",store.status("P").get("policy"));fake.path=path;assertTrue(store.permission("P","22222222-2222-2222-2222-222222222222",fake.target).isEmpty());assertTrue(store.permission("P",fake.base,"b".repeat(64)).isEmpty());assertEquals("ask",store.status("Other").get("policy"));}
 @Test public void invalidAndUnboundScopeCannotGrant()throws Exception{assertThrows(IllegalArgumentException.class,()->store.preview("P","allow_all"));fake.base="";assertThrows(IllegalStateException.class,()->store.preview("P","allow_dynamic"));}
 @Test public void forgedRevisionOrScopeCannotSilentlyAuthorize()throws Exception{apply("allow_dynamic");java.nio.file.Path file;try(var files=java.nio.file.Files.list(temp.getRoot().toPath().resolve(storeRootName()))){file=files.findFirst().orElseThrow();}java.nio.file.Files.writeString(file,"{}");assertThrows(IllegalStateException.class,()->store.permission("P",fake.base,fake.target));}
 @Test public void revokeSavedScopeAfterUnbindWithoutTouchingDatabase()throws Exception{String id=(String)apply("allow_dynamic").get("policy_id");fake.unbound=true;var preview=store.revokePreview(id);assertEquals("ask",store.revoke(id,(String)preview.get("plan_sha256")).get("policy"));assertThrows(IllegalStateException.class,()->store.revoke(id,(String)preview.get("plan_sha256")));fake.unbound=false;assertEquals("ask",store.status("P").get("policy"));}

 @Test public void newBranchPermissionCannotAuthorizeAnOldPendingOperation()throws Exception{var original=fake.binding("P");String oldHash=com.codepilot1c.core.edt.development.TaskSessionService.fingerprint(original);apply("allow_dynamic");assertFalse(store.permission("P",fake.base,fake.target,oldHash).isEmpty());fake.branch="refs/heads/two";apply("allow_dynamic");assertTrue(store.permission("P",fake.base,fake.target,oldHash).isEmpty());assertFalse(store.permission("P",fake.base,fake.target,com.codepilot1c.core.edt.development.TaskSessionService.fingerprint(fake.binding("P"))).isEmpty());}

}
