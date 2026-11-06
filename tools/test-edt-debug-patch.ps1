param([string]$Repository=(Split-Path -Parent $PSScriptRoot), [string]$JavaHome=$env:JAVA_HOME, [string]$JunitJar=$env:JUNIT_JAR, [string]$HamcrestJar=$env:HAMCREST_JAR)
$ErrorActionPreference='Stop'
$buildDir=Join-Path $Repository '.runs\edt-debug-patch'
$metadata=Get-Content -LiteralPath (Join-Path $buildDir 'build.json') -Raw | ConvertFrom-Json
$cp=$metadata.Patch+';'+[IO.File]::ReadAllText((Join-Path $buildDir 'active-classpath.txt'))
# Standalone Java does not expand OSGi Bundle-ClassPath entries inside the core JAR.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$libDir=Join-Path $buildDir 'test-libs'
New-Item -ItemType Directory -Path $libDir -Force|Out-Null
$zip=[IO.Compression.ZipFile]::OpenRead($metadata.Patch)
try {
    foreach($entry in $zip.Entries|Where-Object {$_.FullName -match '^lib/[^/]+\.jar$'}) {
        $lib=Join-Path $libDir ([IO.Path]::GetFileName($entry.FullName))
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$lib,$true)
        $cp+=';'+$lib
    }
} finally {$zip.Dispose()}
if(!$JunitJar){$JunitJar=Join-Path $env:USERPROFILE '.p2\pool\plugins\org.junit_4.13.2.v20230809-1000.jar'}
if(!$HamcrestJar){$HamcrestJar=Join-Path $env:USERPROFILE '.m2\repository\org\hamcrest\hamcrest\2.2\hamcrest-2.2.jar'}
foreach($dependency in @($JunitJar,$HamcrestJar)){if(!(Test-Path -LiteralPath $dependency -PathType Leaf)){throw 'JUnit/Hamcrest missing: set JUNIT_JAR and HAMCREST_JAR to existing local dependency JARs'}}
$cp+=';'+$JunitJar+';'+$HamcrestJar
$testDir=Join-Path $Repository 'bundles\com.codepilot1c.core.tests\src\com\codepilot1c\core\edt\debug'
$classes=Join-Path $buildDir 'test-classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$compilerArgs=@('--release','17','-encoding','UTF-8','-classpath',('"'+$cp.Replace('\','/')+'"'),'-d',('"'+$classes.Replace('\','/')+'"'))
$compilerArgs+=Get-ChildItem -LiteralPath (Split-Path $testDir -Parent) -Recurse -Filter '*Test.java' | ForEach-Object {'"'+$_.FullName.Replace('\','/')+'"'}
$compilerArgs+='"'+(Join-Path $Repository 'bundles\com.codepilot1c.core.tests\src\com\codepilot1c\core\qa\QaRunClientParamsTest.java').Replace('\','/')+'"'
$compilerArgs+='"'+(Join-Path $Repository 'bundles\com.codepilot1c.core.tests\src\com\codepilot1c\core\mcp\host\transport\McpHostSseAuthorizationTest.java').Replace('\','/')+'"'
$compilerArgs+='"'+(Join-Path $Repository 'bundles\com.codepilot1c.core.tests\src\com\codepilot1c\core\edt\metadata\EdtNativeCreationTest.java').Replace('\','/')+'"'
$compilerArgs+='"'+(Join-Path $Repository 'bundles\com.codepilot1c.core.tests\src\com\codepilot1c\core\tools\ConnectInfobaseToolTest.java').Replace('\','/')+'"'
$argFile=Join-Path $buildDir 'test-javac.args'
[IO.File]::WriteAllLines($argFile,$compilerArgs,[Text.UTF8Encoding]::new($false))
& (Join-Path $JavaHome 'bin\javac.exe') ('@'+$argFile)
if($LASTEXITCODE -ne 0){throw 'Test compilation failed'}
$argFile=Join-Path $buildDir 'test-java.args'
[IO.File]::WriteAllLines($argFile,@('-classpath',('"'+($classes+';'+$cp).Replace('\','/')+'"'),'org.junit.runner.JUnitCore','com.codepilot1c.core.edt.development.DevelopmentRunServiceTest','com.codepilot1c.core.edt.development.RunnerScenarioParamsTest','com.codepilot1c.core.edt.development.VanessaMcpBridgeTest','com.codepilot1c.core.edt.development.RunnerProfileStoreTest','com.codepilot1c.core.edt.development.ProcessWindowCaptureTest','com.codepilot1c.core.edt.runtime.EdtInfobaseConnectionIdentityTest','com.codepilot1c.core.edt.runtime.EdtInfobaseContextTest','com.codepilot1c.core.edt.runtime.EdtInfobasePersistenceTest','com.codepilot1c.core.tools.ConnectInfobaseToolTest','com.codepilot1c.core.edt.runtime.EdtUpdateStatusResultTest','com.codepilot1c.core.edt.metadata.EdtNativeCreationTest','com.codepilot1c.core.edt.metadata.NativeContentModelsTest','com.codepilot1c.core.edt.metadata.SpreadsheetTemplateWriterTest','com.codepilot1c.core.edt.debug.EdtDebugServiceBreakpointTest','com.codepilot1c.core.edt.debug.EdtDebugServiceLaunchTest','com.codepilot1c.core.edt.editor.EdtEditorServiceTest','com.codepilot1c.core.edt.editor.EdtWindowServiceTest','com.codepilot1c.core.edt.editor.EdtEditorViewportServiceTest','com.codepilot1c.core.edt.editor.EdtUiRequestTest','com.codepilot1c.core.qa.QaRunClientParamsTest','com.codepilot1c.core.mcp.host.transport.McpHostSseAuthorizationTest'),[Text.UTF8Encoding]::new($false))
& (Join-Path $JavaHome 'bin\java.exe') ('@'+$argFile) 2>&1 | Tee-Object -FilePath (Join-Path $buildDir 'unit-tests.log')
if($LASTEXITCODE -ne 0){throw 'Patch tests failed'}
