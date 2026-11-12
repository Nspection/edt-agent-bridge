// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
public class PowerShellRuntimeTest {
 @Test public void emptyAndRelativePathEntriesNeverResolveLocalExecutable()throws Exception{assertTrue(ManagedVanessaProcess.installedPowerShell(";.;relative;", "relative").isEmpty());}
 @Test public void knownAbsoluteSystemDirectoryStillWins()throws Exception{Path directory=Files.createTempDirectory("powershell-resolver-");try{Files.write(directory.resolve("pwsh.exe"),new byte[]{1});assertEquals(directory.resolve("pwsh.exe").toString(),ManagedVanessaProcess.installedPowerShell(directory.toString(),"relative").orElseThrow());}finally{Files.delete(directory.resolve("pwsh.exe"));Files.delete(directory);}}
 @Test public void missingShellReportsPreparationWithoutTrustOrProcess() {var result=new ManagedVanessaProcess.PowerShellUnavailable().result();assertEquals("needs_powershell",result.get("state"));assertEquals(false,result.get("process_started"));assertEquals(false,result.get("trust_changed"));assertEquals("not_dispatched",result.get("execution_state"));assertTrue(result.containsKey("next_call"));}
 @Test public void officialDescriptorHasBoundedDedicatedArchiveLimit()throws Exception{var power=VerifiedDependencyPackage.bundled("/resources/vanessa/powershell.json");var python=VerifiedDependencyPackage.bundled("/resources/test-client/python-runtime.json");assertEquals("powershell",power.id());assertEquals("github.com",power.uri().getHost());assertEquals("pwsh.exe",power.entry());assertEquals(128L*1024*1024,VerifiedDependencyPackage.archiveLimit(power));assertEquals(64L*1024*1024,VerifiedDependencyPackage.archiveLimit(python));}
}
