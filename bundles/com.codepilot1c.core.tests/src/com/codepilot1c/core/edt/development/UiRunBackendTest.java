// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;import java.nio.file.*;
public class UiRunBackendTest {
 @Test public void absentPreparationReceiptCannotProveCleanup(){assertThrows(IllegalStateException.class,()->UiRunBackend.requireReceipt(Path.of("missing-original-preparation-"+UUID.randomUUID()+".json")));}
 @Test public void bookkeepingFailureNeverDiscardsAReceivedExecution(){var full=Map.<String,Object>of("success",true,"results",List.of(Map.of("values",Map.of("Name","saved"))));var r=UiRunBackend.collectedResult(full,()->{throw new IllegalStateException("binding changed");});assertEquals(full.get("results"),r.get("results"));assertEquals(false,r.get("task_collection_verified"));assertEquals("IllegalStateException",r.get("task_collection_error_type"));assertFalse(full.containsKey("task_collection_verified"));}
 @Test public void reusableClientRequiresExactIdentityKindAndLiveChannel(){var a=Map.<String,Object>of("project","P","base_uuid","base");var c=new LinkedHashMap<String,Object>(Map.of("project","P","base_uuid","base","client_kind","thin","connected",true,"ui_ready",true,"live_client_binding_verified",true));UiRunBackend.verifyReusableClient(a,c);for(String field:List.of("project","base_uuid","client_kind","connected","ui_ready","live_client_binding_verified")){var changed=new LinkedHashMap<>(c);changed.put(field,"changed");assertThrows(IllegalStateException.class,()->UiRunBackend.verifyReusableClient(a,changed));}}
}
