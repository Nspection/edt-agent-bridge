package com.codepilot1c.core.edt.runtime;
import org.junit.Test;
import static org.junit.Assert.*;
import org.eclipse.core.runtime.*;
public class EdtUpdateStatusResultTest {
    @Test public void recognizesModernSdkOkStatus(){assertTrue(EdtRuntimeService.interpretUpdateResult(Status.OK_STATUS));}
    @Test public void preservesErrorDetails(){var error=assertThrows(IllegalStateException.class,()->EdtRuntimeService.interpretUpdateResult(new Status(IStatus.ERROR,"fixture","configuration rejected")));assertTrue(error.getMessage().contains("configuration rejected"));}
    @Test public void cancellationIsNotSuccess(){assertThrows(OperationCanceledException.class,()->EdtRuntimeService.interpretUpdateResult(Status.CANCEL_STATUS));}
    @Test public void preservesLegacyBooleanContract(){assertTrue(EdtRuntimeService.interpretUpdateResult(Boolean.TRUE));assertFalse(EdtRuntimeService.interpretUpdateResult(Boolean.FALSE));}
    @Test public void unknownResultIsExplicitlyRejected(){assertThrows(IllegalStateException.class,()->EdtRuntimeService.interpretUpdateResult(null));assertThrows(IllegalStateException.class,()->EdtRuntimeService.interpretUpdateResult("ok"));}
}
