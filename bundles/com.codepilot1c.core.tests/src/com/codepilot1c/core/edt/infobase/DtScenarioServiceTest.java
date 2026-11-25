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
}
