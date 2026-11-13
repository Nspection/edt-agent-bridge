// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.development;
import static org.junit.Assert.*;
import java.util.Map;
import org.junit.Test;
import com.codepilot1c.core.tools.ToolParameters;
public class EdtRuntimePrepareToolContractTest {
 @Test public void wrongFieldIsReportedBeforeWorkspaceOrRuntimeIsOpened()throws Exception{var result=new EdtRuntimePrepareTool().doExecute(new ToolParameters(Map.of("action","prepare","timeout_ms",1000))).get();assertFalse(result.isSuccess());assertTrue(result.getErrorMessage().contains("rejected_field=timeout_ms"));assertEquals("timeout_ms",result.getStructuredData().get("rejected_field").getAsString());}
}
