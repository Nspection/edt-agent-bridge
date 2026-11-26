// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;

import static org.junit.Assert.*;
import java.util.Map;
import org.junit.Test;

public class DtScenarioServiceTest {
    @Test public void licenseContinueRequiresAProvenDifferentForm() {
        assertFalse(DtScenarioService.verifiedLicenseStep("continue",Map.of("success",true)));
        assertFalse(DtScenarioService.verifiedLicenseStep("continue",Map.of(
                "success",true,"window",Map.of("form_name",
                "Обработка.ЛегальностьПолученияОбновлений.Форма.ПроверкаЛегальностиПолученияОбновления"))));
        assertTrue(DtScenarioService.verifiedLicenseStep("continue",Map.of(
                "success",true,"window",Map.of("form_name","ОбщаяФорма.ПустойРабочийСтол"))));
    }
    @Test public void sendingStatisticsIsNotInferredToBeOff() {
        assertFalse(DtScenarioService.verifiedLicenseStep("privacy",Map.of("success",true)));
        assertFalse(DtScenarioService.verifiedLicenseStep("privacy",Map.of("success",true,
                "control",Map.of("state_verified",true,"value_after",true))));
        assertTrue(DtScenarioService.verifiedLicenseStep("privacy",Map.of("success",true,
                "control",Map.of("state_verified",true,"value_after",false))));
    }
    @Test public void activeTraceRequiresNamedPasswordlessAccess() {
        var args=new com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionArguments();
        args.setAccess(com._1c.g5.v8.dt.platform.services.model.InfobaseAccess.INFOBASE);
        assertThrows(IllegalStateException.class,()->NativeInfobaseGateway.requirePasswordlessAccess(args));
        args.setUsername("TestUser");
        args.setPassword("");
        NativeInfobaseGateway.requirePasswordlessAccess(args);
        args.setPassword("synthetic-nonempty");
        assertThrows(IllegalStateException.class,()->NativeInfobaseGateway.requirePasswordlessAccess(args));
    }

    @Test public void fullCycleAllowsRestoreButPreservesRequestedBackupAndExternalUseConsent() {
        var incremental=Map.<String,Object>of("request",Map.of("update_mode","incremental"));
        var replacement=Map.<String,Object>of("request",Map.of("dt_path","first.dt"),
                "dt_sha256","first", "backup_before_restore",false,"restored_base_choice","copy");
        assertTrue(DtScenarioService.withinFullCycle(incremental,replacement));
        var backedUp=Map.<String,Object>of("request",Map.of("dt_path","first.dt"),
                "dt_sha256","first","backup_before_restore",true);
        assertFalse(DtScenarioService.withinFullCycle(backedUp,replacement));
        var otherDump=Map.<String,Object>of("request",Map.of("dt_path","second.dt"),
                "dt_sha256","second","backup_before_restore",true);
        assertFalse(DtScenarioService.withinFullCycle(backedUp,otherDump));
        var moved=Map.<String,Object>of("request",Map.of(),"restored_base_choice","moved");
        assertFalse(DtScenarioService.withinFullCycle(incremental,moved));
        var legal=Map.<String,Object>of("request",Map.of("accept_legal_updates",true));
        assertFalse(DtScenarioService.withinFullCycle(incremental,legal));
    }

    @Test public void traceStateCanChangeButApprovedOperationCannot() {
        var active=Map.<String,Object>of("trace","ACTIVE","trace_passwordless_restore",true,
                "steps",java.util.List.of("restore_dt"),"binding_sha256","base-one","dt_sha256","dump-one");
        var inactive=Map.<String,Object>of("trace","INACTIVE","steps",java.util.List.of("restore_dt","full"),
                "binding_sha256","base-one","dt_sha256","dump-one");
        var wrongBase=Map.<String,Object>of("trace","INACTIVE","binding_sha256","base-two","dt_sha256","dump-one");
        var wrongDump=Map.<String,Object>of("trace","INACTIVE","binding_sha256","base-one","dt_sha256","dump-two");
        String approved=DtScenarioService.operationFingerprint(active);
        assertEquals(approved,DtScenarioService.operationFingerprint(inactive));
        assertNotEquals(approved,DtScenarioService.operationFingerprint(wrongBase));
        assertNotEquals(approved,DtScenarioService.operationFingerprint(wrongDump));
    }
}
