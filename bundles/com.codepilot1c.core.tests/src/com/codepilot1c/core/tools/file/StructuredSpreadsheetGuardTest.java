// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.file;
import static org.junit.Assert.*;
import org.junit.Test;
public class StructuredSpreadsheetGuardTest {
    @Test public void blocksXmlAndBinarySpreadsheetsIncludingUppercase() {
        for (String suffix : new String[]{"mxl", "mxlx", "MXLX"}) {
            String path = "Project/src/CommonTemplates/Report/Template." + suffix;
            assertTrue(EditFileTool.isStructuredEdtArtifactPath(path));
            assertFalse(WorkspaceCopyTransformSupport.validateWorkspacePath(path, true).ok());
        }
    }
}
