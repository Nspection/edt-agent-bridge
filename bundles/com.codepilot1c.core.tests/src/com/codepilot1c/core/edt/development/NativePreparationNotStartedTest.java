// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import static org.junit.Assert.*;
import org.junit.Test;
import com.codepilot1c.core.edt.metadata.*;
public class NativePreparationNotStartedTest {
 @Test public void onlyPreReservationReadinessFailureProvesNotStarted(){
  var error=assertThrows(NativeHostService.PreparationNotStarted.class,()->NativeHostService.readinessBeforeReservation("Fixture",()->{throw new MetadataOperationException(MetadataOperationCode.PROJECT_NOT_READY,"READY/AFTER_BUILD",true);}));
  var r=error.result();assertEquals("not_started",r.get("execution_state"));assertEquals(true,r.get("retry_safe"));assertEquals(false,r.get("outcome_unknown"));assertEquals(false,r.get("host_allocated"));assertEquals(false,r.get("external_project_created"));assertEquals(false,r.get("process_started"));assertEquals(false,r.get("database_updated"));
 }
 @Test public void nonrecoverableReadinessStillCannotRecommendRetry(){
  var error=assertThrows(NativeHostService.PreparationNotStarted.class,()->NativeHostService.readinessBeforeReservation("Fixture",()->{throw new MetadataOperationException(MetadataOperationCode.PROJECT_NOT_READY,"Not EDT",false);}));assertEquals(false,error.result().get("retry_safe"));
 }
 @Test public void genericOrPostAllocationFailureIsNeverPromotedToSafe(){
  var error=new IllegalStateException("derived-data failed after write");assertSame(error,assertThrows(IllegalStateException.class,()->NativeHostService.readinessBeforeReservation("Fixture",()->{throw error;})));
 }
 @Test public void successfulReadinessDoesNotReserveOrLaunchAnything(){var called=new java.util.concurrent.atomic.AtomicBoolean();NativeHostService.readinessBeforeReservation("Fixture",()->called.set(true));assertTrue(called.get());}
}
