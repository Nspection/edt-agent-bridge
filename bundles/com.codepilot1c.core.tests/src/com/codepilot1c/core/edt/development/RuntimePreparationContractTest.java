// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;
public class RuntimePreparationContractTest {
 @Test public void timeoutIsWaitOnlyAndErrorNamesTheField(){var error=assertThrows(RuntimePreparationContract.RejectedField.class,()->RuntimePreparationContract.validateFields("prepare",Map.of("timeout_ms",1000)));assertEquals("timeout_ms",error.diagnostic().get("rejected_field").getAsString());RuntimePreparationContract.validateFields("wait",Map.of("preparation_id","prep-id","timeout_ms",1000));RuntimePreparationContract.validateFields("prepare",Map.of("required_actions",List.of("open"),"readiness_timeout_s",90));}
 @Test public void everySchemaBranchUsesTheSameAllowedFieldsAsDispatch(){var root=com.google.gson.JsonParser.parseString(new com.codepilot1c.core.tools.development.EdtRuntimePrepareTool().getParameterSchema()).getAsJsonObject();var branches=root.getAsJsonArray("oneOf");assertEquals(7,branches.size());for(var item:branches){var branch=item.getAsJsonObject();var properties=branch.getAsJsonObject("properties");String action=properties.getAsJsonObject("action").getAsJsonArray("enum").get(0).getAsString();var expected=new HashSet<>(RuntimePreparationContract.fields(action));expected.add("action");assertEquals(expected,properties.keySet());assertFalse(branch.get("additionalProperties").getAsBoolean());assertEquals(action.equals("wait"),properties.has("timeout_ms"));}}
 @Test public void wrongActionFieldsDoNotAcceptOrEchoValues(){String secret="PRIVATE_VALUE";var error=assertThrows(RuntimePreparationContract.RejectedField.class,()->RuntimePreparationContract.validateFields("status",Map.of("project",secret)));assertFalse(error.getMessage().contains(secret));assertFalse(error.diagnostic().toString().contains(secret));assertThrows(IllegalArgumentException.class,()->RuntimePreparationContract.validateFields("arbitrary",Map.of()));}
}
