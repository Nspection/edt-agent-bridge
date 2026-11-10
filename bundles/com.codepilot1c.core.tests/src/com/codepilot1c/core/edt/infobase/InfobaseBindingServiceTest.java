// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.util.*;
import java.nio.file.*;
public class InfobaseBindingServiceTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 static final String UUID="11111111-1111-4111-8111-111111111111";
 Fake gateway;InfobaseBindingService service;Path root;
 class Fake implements InfobaseBindingService.Gateway {
  int binds;boolean fail,lie;Map<String,Object> state=new LinkedHashMap<>(Map.of("project","Project","project_path","D:/existing/project","context",List.of(List.of(true,"branch85")),"base_uuid",UUID,"base_name","Existing outside home","target_identity_sha256","target","owners",List.of(),"primary_uuid","","associated",false));
  public Map<String,Object> describe(String p,String id){if(!UUID.equals(id))throw new IllegalArgumentException("REGISTERED_BASE_NOT_FOUND");return new LinkedHashMap<>(state);}
  public List<Map<String,Object>> list(String f,int o,int l){return List.of(Map.of("base_uuid",UUID,"name","Existing"));}
  public Map<String,Object> bind(String p,String id,Map<String,Object> expected,boolean primary){binds++;state.put("associated",true);state.put("owners",List.of("Project"));if(primary)state.put("primary_uuid",UUID);if(fail)throw new IllegalStateException("simulated partial SDK failure");if(lie)state.put("context",List.of(List.of(true,"another-branch")));return new LinkedHashMap<>(state);}
 }
 @Before public void setup()throws Exception{gateway=new Fake();root=temp.newFolder().toPath().resolve("receipts");service=new InfobaseBindingService(gateway,root);}
 Map<String,Object> args(){return new LinkedHashMap<>(Map.of("project","Project","base_uuid",UUID));}
 Map<String,Object> planned()throws Exception{var a=args();a.put("expected_preview_sha256",service.preview(a).get("preview_sha256"));return a;}
 @Test public void previewDoesNotBindStartOrUpdate()throws Exception{var p=service.preview(args());assertEquals(0,gateway.binds);assertEquals(false,p.get("configuration_updated"));assertEquals(false,p.get("database_started"));assertEquals(true,p.get("credentials_preserved"));}
 @Test public void bindingOutsideHomeUsesRegisteredUuidAndPreservesData()throws Exception{var r=service.apply(planned());assertEquals(true,r.get("binding_verified"));assertEquals(false,r.get("configuration_updated"));assertEquals(false,r.get("database_started"));assertEquals(1,gateway.binds);}
 @Test public void sameReceiptReturnsDurableResultWithoutRebinding()throws Exception{var a=planned();var first=service.apply(a);var restarted=new InfobaseBindingService(gateway,root);assertEquals(first,restarted.apply(a));assertEquals(1,gateway.binds);}
 @Test public void staleBranchContextNeverBinds()throws Exception{var a=planned();gateway.state.put("context",List.of(List.of(true,"branch83")));try{service.apply(a);fail();}catch(IllegalStateException expected){}assertEquals(0,gateway.binds);}
 @Test public void staleUuidTargetNeverBinds()throws Exception{var a=planned();gateway.state.put("target_identity_sha256","other-target");try{service.apply(a);fail();}catch(IllegalStateException expected){}assertEquals(0,gateway.binds);}
 @Test public void anotherProjectOwnerIsPreserved()throws Exception{gateway.state.put("owners",List.of("Other"));try{service.preview(args());fail();}catch(IllegalStateException expected){}assertEquals(0,gateway.binds);}
 @Test public void differentPrimaryRequiresExplicitReplacement()throws Exception{gateway.state.put("primary_uuid","other");try{service.preview(args());fail();}catch(IllegalStateException expected){}var a=args();a.put("replace_primary",true);a.put("expected_preview_sha256",service.preview(a).get("preview_sha256"));assertEquals(true,service.apply(a).get("binding_verified"));}
 @Test public void nonPrimaryAssociationPreservesExistingPrimary()throws Exception{gateway.state.put("primary_uuid","other");var a=args();a.put("set_primary",false);a.put("expected_preview_sha256",service.preview(a).get("preview_sha256"));assertEquals(true,service.apply(a).get("binding_verified"));assertEquals("other",gateway.state.get("primary_uuid"));}
 @Test public void unknownPartialBindingNeverReplays()throws Exception{var a=planned();gateway.fail=true;assertEquals(true,service.apply(a).get("outcome_unknown"));gateway.fail=false;assertEquals(true,service.apply(a).get("outcome_unknown"));assertEquals(1,gateway.binds);}
 @Test public void unverifiedSdkResultCannotClaimSuccess()throws Exception{var a=planned();gateway.lie=true;assertEquals(true,service.apply(a).get("outcome_unknown"));assertEquals(1,gateway.binds);}
 @Test public void arbitraryPathAndUnregisteredUuidCannotBecomeBinding()throws Exception{var a=args();a.put("base_uuid","22222222-2222-4222-8222-222222222222");try{service.preview(a);fail();}catch(IllegalArgumentException expected){}a.put("base_uuid","D:/some/database");try{service.preview(a);fail();}catch(IllegalArgumentException expected){}assertEquals(0,gateway.binds);}
 @Test public void changedArgumentsCannotReuseAnotherReceipt()throws Exception{var a=planned();service.apply(a);a.put("set_primary",false);try{service.apply(a);fail();}catch(IllegalStateException expected){}assertEquals(1,gateway.binds);}
}
