// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import org.junit.*;import org.junit.rules.TemporaryFolder;import static org.junit.Assert.*;import java.util.*;import java.nio.file.*;import java.util.concurrent.*;
public class RuntimeConsentPolicyTest {
 @Rule public TemporaryFolder tmp=new TemporaryFolder();
 Map<String,Object> scope=Map.of("project","own","base_uuid","11111111-1111-4111-8111-111111111111","context",List.of("main"),"artifact",Map.of("sha256","a".repeat(64)));
 RuntimeConsentPolicy policy;Path root;
 @Before public void init()throws Exception {root=tmp.newFolder().toPath();policy=new RuntimeConsentPolicy(root);}
 void grant(String choice)throws Exception {var p=policy.preview(scope,choice);policy.apply(scope,choice,(String)p.get("plan_sha256"));}
 @Test public void defaultAskNeverGrants(){try{assertTrue(policy.claim(scope).isEmpty());}catch(Exception e){throw new AssertionError(e);}}
 @Test public void onceAllowsOneLaunchAndContinuedUseOnly()throws Exception {grant("once");var lease=policy.claim(scope);assertFalse(lease.isEmpty());assertTrue(policy.claim(scope).isEmpty());policy.requireUse(scope,lease);}
 @Test public void rememberAllowsIndependentLaunchesButRequiresSameScope()throws Exception {grant("remember");var one=policy.claim(scope);assertFalse(policy.claim(scope).isEmpty());policy.requireUse(scope,one);var changed=new LinkedHashMap<>(scope);changed.put("context",List.of("other"));assertTrue(policy.claim(changed).isEmpty());assertThrows(IllegalStateException.class,()->policy.requireUse(changed,one));}
 @Test public void artifactOrBaseDriftDoesNotInheritConsent()throws Exception {grant("remember");var lease=policy.claim(scope);for(String key:List.of("base_uuid","artifact")){var changed=new LinkedHashMap<>(scope);changed.put(key,"different");assertTrue(policy.claim(changed).isEmpty());assertThrows(IllegalStateException.class,()->policy.requireUse(changed,lease));}}
 @Test public void revokeInvalidatesExistingLeaseAndFutureLaunch()throws Exception {grant("remember");var lease=policy.claim(scope);String id=(String)lease.get("consent_id");var plan=policy.revokePreview(id);policy.revoke(id,(String)plan.get("plan_sha256"));assertTrue(policy.claim(scope).isEmpty());assertThrows(IllegalStateException.class,()->policy.requireUse(scope,lease));}
 @Test public void stalePreviewCannotOverrideUserDecision()throws Exception {var preview=policy.preview(scope,"remember");grant("deny");assertThrows(IllegalStateException.class,()->policy.apply(scope,"remember",(String)preview.get("plan_sha256")));assertTrue(policy.claim(scope).isEmpty());}
 @Test public void deniedAndConsumedOnceAreNotReplayPermission()throws Exception {grant("deny");assertTrue(policy.claim(scope).isEmpty());grant("once");policy.claim(scope);var reopened=new RuntimeConsentPolicy(root);assertTrue(reopened.claim(scope).isEmpty());}
 @Test public void corruptedRecordFailsClosed()throws Exception {grant("remember");String id=(String)policy.status(scope).get("consent_id");Files.writeString(root.resolve(id+".json"),"{}");assertThrows(IllegalStateException.class,()->policy.claim(scope));}
 @Test public void onceClaimIsAtomicAcrossInstances()throws Exception {grant("once");var other=new RuntimeConsentPolicy(root);var pool=Executors.newFixedThreadPool(2);try{var one=pool.submit(()->policy.claim(scope));var two=pool.submit(()->other.claim(scope));assertNotEquals(one.get().isEmpty(),two.get().isEmpty());}finally{pool.shutdownNow();}}
 @Test public void consentDoesNotPromisePlatformTrust()throws Exception {var preview=policy.preview(scope,"once");assertEquals(false,preview.get("platform_trust_changed"));assertEquals("ask",policy.status(scope).get("policy"));}
 @Test public void missingGrantProvesOnlyLaunchNotDispatched()throws Exception{var e=assertThrows(RuntimeConsentPolicy.Required.class,()->RuntimeConsentPolicy.claimOrRequire(policy,"edt_test_client",Map.of("project","own"),scope));var r=e.result();assertEquals("launch_not_dispatched",r.get("execution_state"));assertEquals(false,r.get("outcome_unknown"));assertEquals(false,r.get("process_started"));assertFalse(r.containsKey("resources_created"));assertTrue(r.containsKey("decision"));}
 @Test public void manyExactScopesDoNotExhaustPermanentConsentCapacity()throws Exception {
  for(int i=0;i<260;i++){var many=new HashMap<>(scope);many.put("artifact",Map.of("sha256",String.format("%064x",i)));var plan=policy.preview(many,"remember");policy.apply(many,"remember",(String)plan.get("plan_sha256"));assertFalse(policy.claim(many).isEmpty());}
 }

}
