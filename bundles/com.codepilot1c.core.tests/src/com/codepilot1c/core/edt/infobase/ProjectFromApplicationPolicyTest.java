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
}
