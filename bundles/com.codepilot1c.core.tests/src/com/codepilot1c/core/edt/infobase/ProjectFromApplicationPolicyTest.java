// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;
import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;
public class ProjectFromApplicationPolicyTest {
 @Test public void newUnboundBaseCanBeAttached(){ProjectFromApplicationService.validateOwnerPolicy(true,List.of(),null);}
 @Test public void readonlyImportPreservesExistingOwner(){ProjectFromApplicationService.validateOwnerPolicy(false,List.of("Original"),null);}
 @Test public void exactExplicitOwnerCanBeTransferred(){ProjectFromApplicationService.validateOwnerPolicy(true,List.of("Original"),"Original");}
 @Test public void existingBindingIsNeverMovedImplicitly(){try{ProjectFromApplicationService.validateOwnerPolicy(true,List.of("Original"),null);fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("APPLICATION_ALREADY_BOUND"));}}
 @Test public void staleOrMultipleOwnersRejectTransfer(){for(var owners:List.of(List.of("Other"),List.of("Original","Other"),List.<String>of()))try{ProjectFromApplicationService.validateOwnerPolicy(true,owners,"Original");fail();}catch(IllegalArgumentException expected){}}
 @Test public void transferCannotBeCombinedWithNoBinding(){try{ProjectFromApplicationService.validateOwnerPolicy(false,List.of("Original"),"Original");fail();}catch(IllegalArgumentException expected){}}
 @Test public void linkageFailureRestoresPreviousPrimaryAndPreservesOriginalError() throws Exception {
  var gateway=new RollbackGateway();var service=new ProjectFromApplicationService(gateway);
  try{service.bindImportedApplication("New",null,"8.5.1.1522",null,null,"Old");fail();}
  catch(NoClassDefFoundError expected){assertSame(gateway.failure,expected);}
  assertEquals(List.of("detach","select","remove","restore"),gateway.calls);assertTrue(gateway.restored);
 }
 @Test public void rollbackStillRestoresOwnerIfPartialAssociationRemovalFails() throws Exception {
  var gateway=new RollbackGateway();gateway.failRemoval=true;var service=new ProjectFromApplicationService(gateway);
  try{service.bindImportedApplication("New",null,"8.5.1.1522",null,null,"Old");fail();}
  catch(NoClassDefFoundError expected){assertSame(gateway.failure,expected);assertEquals(1,expected.getSuppressed().length);}
  assertTrue(gateway.restored);
 }
 @Test public void staleOwnerNeverGetsDetached() throws Exception {
  var gateway=new RollbackGateway();var service=new ProjectFromApplicationService(gateway);
  try{service.bindImportedApplication("New",null,"8.5.1.1522",null,null,"WrongOwner");fail();}catch(IllegalStateException expected){}
  assertTrue(gateway.calls.isEmpty());
 }
 @Test public void rollbackFailureIsVisibleWithoutLeakingCredentials() {
  var error=new NoClassDefFoundError("SdkFailure");error.addSuppressed(new IllegalStateException("rollback failed private-password"));
  String safe=InfobaseProvisionService.safeError(error,java.util.Map.of("password","private-password"));
  assertTrue(safe.contains("recovery errors"));assertTrue(safe.contains("rollback failed"));assertFalse(safe.contains("private-password"));
 }
 private static class RollbackGateway extends NativeInfobaseGateway {
        @Override public void restoredAccess(com._1c.g5.v8.dt.platform.services.model.InfobaseReference ref,String login,String password) {}
  final java.util.List<String> calls=new java.util.ArrayList<>();
  final NoClassDefFoundError failure=new NoClassDefFoundError("SdkMissingDuringTransfer");
  boolean restored,failRemoval;
  @Override public List<String> ownerProjects(com._1c.g5.v8.dt.platform.services.model.InfobaseReference ref){return List.of("Old");}
  @Override public boolean detachApplication(String source,com._1c.g5.v8.dt.platform.services.model.InfobaseReference ref){assertEquals("Old",source);calls.add("detach");return true;}
  @Override public void selectRuntimeForApplication(String target,com._1c.g5.v8.dt.platform.services.model.InfobaseReference ref,String version){calls.add("select");throw failure;}
  @Override public void removeOwnAssociation(String target,com._1c.g5.v8.dt.platform.services.model.InfobaseReference ref){calls.add("remove");if(failRemoval)throw new IllegalStateException("cleanup failed");}
  @Override public void restoreApplication(String source,com._1c.g5.v8.dt.platform.services.model.InfobaseReference ref,boolean primary){assertEquals("Old",source);assertTrue(primary);calls.add("restore");restored=true;}
 }
}
