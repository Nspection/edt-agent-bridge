param(
    [string]$Repository = (Split-Path -Parent $PSScriptRoot),
    [string]$EdtHome = $env:CODEPILOT_EDT_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Qualifier = '20261105development3'
)
$ErrorActionPreference = 'Stop'
$buildDir = Join-Path $Repository '.runs\edt-debug-patch'
$classesDir = Join-Path $buildDir ('classes-'+$Qualifier)
New-Item -ItemType Directory -Path $classesDir -Force | Out-Null
$info = Join-Path $EdtHome 'configuration\org.eclipse.equinox.simpleconfigurator\bundles.info'
$jarFiles = @()
foreach ($line in Get-Content -LiteralPath $info) {
    if ($line.StartsWith('#')) { continue }
    $fields = $line.Split(',')
    if ($fields.Length -lt 3 -or !$fields[2].StartsWith('file:')) { continue }
    $uri = [Uri]$fields[2]
    $file = $uri.LocalPath
    if ((Test-Path -LiteralPath $file) -and ($file.EndsWith('.jar') -or (Test-Path -LiteralPath (Join-Path $file 'META-INF\MANIFEST.MF')))) { $jarFiles += $file }
    if ($fields[0] -eq 'com.codepilot1c.core') { $coreJar = $file; $coreVersion = $fields[1] }
}
if (!$coreJar -or $coreVersion -ne '1.3.9.20261019-0402') {
    throw "This patch build is verified only against core 1.3.9.20261019-0402; found $coreVersion"
}
$statePath = Join-Path $EdtHome 'local-debug-patch\install-state.json'
if (Test-Path -LiteralPath $statePath) {
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    $originalUri = [Uri]($state.OriginalLine.Split(',')[2])
    $jarFiles = @($jarFiles | Where-Object { $_ -ne $coreJar })
    $coreJar = $originalUri.LocalPath
    $jarFiles = @($coreJar) + $jarFiles
}
$classpath = $jarFiles -join ';'
$sourceRoot = Join-Path $Repository 'bundles\com.codepilot1c.core\src\com\codepilot1c\core'
$sources = @(
    (Join-Path $sourceRoot 'edt\runtime\EdtRuntimeService.java'),
    (Join-Path $sourceRoot 'edt\runtime\EdtInfobaseConnectService.java'),
    (Join-Path $sourceRoot 'tools\workspace\EdtUpdateInfobaseTool.java'),
    (Join-Path $sourceRoot 'tools\metadata\EdtDeletePreviewTool.java'),
    (Join-Path $sourceRoot 'tools\metadata\EdtCreationDefaultsTool.java'),
    (Join-Path $sourceRoot 'edt\debug\EdtDebugService.java'),
    (Join-Path $sourceRoot 'tools\workspace\EdtLaunchAppTool.java'),
    (Join-Path $sourceRoot 'tools\qa\QaRunTool.java'),
    (Join-Path $sourceRoot 'mcp\host\McpHostServer.java'),
    (Join-Path $sourceRoot 'mcp\host\transport\McpHostHttpTransport.java'),
    (Join-Path $sourceRoot 'mcp\host\transport\McpHostOriginPolicy.java'),
    (Join-Path $sourceRoot 'mcp\host\transport\McpHttpClientDisconnects.java')
)
$sources += Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'edt\editor') -Filter '*.java' | ForEach-Object FullName
$sources += Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'tools\editor') -Filter '*.java' | ForEach-Object FullName
$sources += Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'edt\metadata') -Filter '*.java' | ForEach-Object FullName
$emptySources=Join-Path $buildDir 'empty-sourcepath'
New-Item -ItemType Directory -Path $emptySources -Force|Out-Null
$sources += Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'tools\development') -Filter '*.java' | ForEach-Object FullName
$sources += Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'edt\development') -Filter '*.java' | ForEach-Object FullName
$argsFile = Join-Path $buildDir 'javac.args' 
$compilerArgs = @('-sourcepath',('"'+$emptySources.Replace('\','/')+'"'),'--release','17','-encoding','UTF-8','-classpath',('"'+$classpath.Replace('\','/')+'"'),'-d',('"'+$classesDir.Replace('\','/')+'"'))
$compilerArgs += $sources | ForEach-Object { '"'+$_.Replace('\','/')+'"' }
[IO.File]::WriteAllLines($argsFile,$compilerArgs,[Text.UTF8Encoding]::new($false))
& (Join-Path $JavaHome 'bin\javac.exe') ('@'+$argsFile)
if ($LASTEXITCODE -ne 0) { throw 'Patch compilation failed' }
$patchJar = Join-Path $buildDir "com.codepilot1c.core_debugfix_$Qualifier.jar"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$sourceZip = [IO.Compression.ZipFile]::OpenRead($coreJar)
try {
    if ($sourceZip.Entries | Where-Object { $_.FullName -match '^META-INF/[^/]+\.(SF|RSA|DSA|EC)$' }) {
        throw 'Refusing to rewrite a signed host bundle; use a proper rebuilt p2 artifact instead'
    }
    if (Test-Path -LiteralPath $patchJar) { Remove-Item -LiteralPath $patchJar }
    $targetZip = [IO.Compression.ZipFile]::Open($patchJar,[IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entry in $sourceZip.Entries) {
            if ($entry.FullName -eq 'plugin.xml') { continue }
            if ($entry.FullName -eq 'META-INF/MANIFEST.MF') {
                $reader=[IO.StreamReader]::new($entry.Open(),[Text.Encoding]::UTF8)
                try {$manifest=$reader.ReadToEnd()} finally {$reader.Dispose()}
                $importPattern='(?m)^Import-Package: [^\r\n]*(?:\r?\n [^\r\n]*)*'
                $importMatch=[regex]::Match($manifest,$importPattern)
                if(!$importMatch.Success){throw 'Original core manifest has no Import-Package'}
                $imports=$importMatch.Value.Replace("`r`n ",'').Replace("`n ",'')
                $addedImports=@('com._1c.g5.v8.dt.moxel.sheet','org.eclipse.emf.ecore.xmi','org.eclipse.emf.ecore.xmi.impl','com._1c.g5.v8.dt.platform.services.core.infobases.sync','com._1c.g5.v8.dt.core.model','com._1c.g5.v8.dt.md.refactoring.core','com._1c.g5.v8.dt.refactoring.core')
                foreach($import in $addedImports) {
                    if($imports -notmatch ('(?<![A-Za-z0-9_.])'+[regex]::Escape($import)+'(?=[;,]|$)')){$imports+=','+$import}
                }
                $folded=[Collections.Generic.List[string]]::new()
                while([Text.Encoding]::UTF8.GetByteCount($imports) -gt 70) {
                    $length=[Math]::Min(70,$imports.Length)
                    while([Text.Encoding]::UTF8.GetByteCount($imports.Substring(0,$length)) -gt 70){$length--}
                    $folded.Add($imports.Substring(0,$length));$imports=' '+$imports.Substring($length)
                }
                $folded.Add($imports)
                $manifest=$manifest.Substring(0,$importMatch.Index)+($folded -join "`r`n")+$manifest.Substring($importMatch.Index+$importMatch.Length)
                $manifestEntry=$targetZip.CreateEntry('META-INF/MANIFEST.MF')
                $writer=[IO.StreamWriter]::new($manifestEntry.Open(),[Text.UTF8Encoding]::new($false))
                try {$writer.Write($manifest)} finally {$writer.Dispose()}
                continue
            }
            if (Test-Path -LiteralPath (Join-Path $classesDir $entry.FullName) -PathType Leaf) { continue }
            $copy = $targetZip.CreateEntry($entry.FullName)
            $copy.LastWriteTime = $entry.LastWriteTime
            $inputStream = $entry.Open(); $outputStream = $copy.Open()
            try { $inputStream.CopyTo($outputStream) } finally { $inputStream.Dispose(); $outputStream.Dispose() }
        }
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($targetZip,(Join-Path $Repository 'bundles\com.codepilot1c.core\plugin.xml'),'plugin.xml') | Out-Null
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($targetZip,(Join-Path $Repository 'tools\run-vanessa-debug.ps1'),'scripts/run-vanessa-debug.ps1') | Out-Null
        foreach ($file in Get-ChildItem -LiteralPath $classesDir -Recurse -File) {
            $relative = $file.FullName.Substring($classesDir.Length + 1).Replace('\','/')
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($targetZip,$file.FullName,$relative) | Out-Null
        }
    } finally { $targetZip.Dispose() }
} finally { $sourceZip.Dispose() }
[IO.File]::WriteAllText((Join-Path $buildDir 'active-classpath.txt'),$classpath,[Text.UTF8Encoding]::new($false))
$metadata = [ordered]@{Patch=$patchJar;OriginalCore=$coreJar;Version=$coreVersion;Classes=$classesDir;Sha256=(Get-FileHash -LiteralPath $patchJar -Algorithm SHA256).Hash;OriginalSha256=(Get-FileHash -LiteralPath $coreJar -Algorithm SHA256).Hash}
[IO.File]::WriteAllText((Join-Path $buildDir 'build.json'),($metadata | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
$metadata | ConvertTo-Json -Compress
