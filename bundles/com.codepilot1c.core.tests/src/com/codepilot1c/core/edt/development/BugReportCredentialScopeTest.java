// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import static org.junit.Assert.*;
import org.junit.Test;
public class BugReportCredentialScopeTest {
 @org.junit.Rule public org.junit.rules.TemporaryFolder temporary=new org.junit.rules.TemporaryFolder();
 @Test public void eachWorkspaceHasAnIsolatedStableCredentialNamespace()throws Exception{var one=temporary.newFolder().toPath();var two=temporary.newFolder().toPath();String scope=BugReportGitHub.namespaceFor(one);assertEquals(scope,BugReportGitHub.namespaceFor(one.resolve(".")));assertNotEquals(scope,BugReportGitHub.namespaceFor(two));assertFalse(scope.contains(one.toString()));assertTrue(scope.matches("edt-agent-bridge-[a-f0-9]{24}"));}
 @Test public void loginHintCannotInjectCredentialProtocolFields(){assertEquals("Nspection",BugReportGitHub.loginHint("Nspection"));assertEquals("",BugReportGitHub.loginHint(""));assertThrows(IllegalArgumentException.class,()->BugReportGitHub.loginHint("user\npassword=secret"));}
}
