param(
    [string]$Repository = (Split-Path -Parent $PSScriptRoot),
    [string]$EdtHome = $env:CODEPILOT_EDT_HOME,
    [switch]$Rollback,
    [switch]$Upgrade,
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
$info = Join-Path $EdtHome 'configuration\org.eclipse.equinox.simpleconfigurator\bundles.info'
$patchDir = Join-Path $EdtHome 'local-debug-patch'
$statePath = Join-Path $patchDir 'install-state.json'
$content = [IO.File]::ReadAllText($info)
$pattern = '(?m)^com\.codepilot1c\.core,[^\r\n]+'
$matchesCore = [regex]::Matches($content,$pattern)
if ($matchesCore.Count -ne 1) { throw 'Expected one core bundle entry' }
$currentLine = $matchesCore[0].Value
if ($Rollback) {
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    if ($currentLine -ne $state.PatchedLine) { throw 'Core entry changed since patch installation; inspect before rollback' }
    $newLine = $state.OriginalLine
} else {
    $build = Get-Content -LiteralPath (Join-Path $Repository '.runs\edt-debug-patch\build.json') -Raw | ConvertFrom-Json
    $parts = $currentLine.Split(',')
    if ($parts[1] -ne $build.Version) { throw 'Installed core version differs from the verified build' }
    if ((Get-FileHash -LiteralPath $build.Patch -Algorithm SHA256).Hash -ne $build.Sha256) { throw 'Patch checksum mismatch' }
    $originalLine = $currentLine
    if (Test-Path -LiteralPath $statePath) {
        if (!$Upgrade) { throw 'Existing patch found; pass -Upgrade to replace it while preserving rollback' }
        $previousState = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        if ($currentLine -ne $previousState.PatchedLine -and $currentLine -ne $previousState.OriginalLine) {
            throw 'Core entry changed since patch installation'
        }
        if ($currentLine -eq $previousState.PatchedLine -and
                (Get-FileHash -LiteralPath $previousState.Patch -Algorithm SHA256).Hash -ne $previousState.PatchSha256) {
            throw 'Installed patch checksum changed'
        }
        $originalLine = $previousState.OriginalLine
    }
    $originalUri = [Uri]($originalLine.Split(',')[2])
    if ((Get-FileHash -LiteralPath $originalUri.LocalPath -Algorithm SHA256).Hash -ne $build.OriginalSha256) { throw 'Original bundle differs from the compile target' }
    $installedJar = Join-Path $patchDir ([IO.Path]::GetFileName($build.Patch))
    $parts[2] = ([Uri]$installedJar).AbsoluteUri
    $newLine = $parts -join ','
}
$running = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq '1cedtstart.exe' -or
    ($_.Name -in @('javaw.exe','java.exe','1cedt.exe') -and $_.CommandLine -match '1cedt|com\._1c\.g5\.v8\.dt')
})
if ($CheckOnly) {
    [pscustomobject]@{Ready=($running.Count -eq 0);RunningEdtProcesses=@($running | Select-Object ProcessId,Name);CoreEntry=$currentLine;PlannedEntry=$newLine} | ConvertTo-Json -Depth 5
    exit 0
}
if ($running.Count -ne 0) { throw 'Save work and close EDT and EDT Start before installation or rollback' }
New-Item -ItemType Directory -Path $patchDir -Force | Out-Null
$backup = Join-Path $patchDir ('bundles-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.info')
Copy-Item -LiteralPath $info -Destination $backup
if (!$Rollback) {
    if (Test-Path -LiteralPath $statePath) { Copy-Item -LiteralPath $statePath -Destination ($backup+'.state.json') }
    Copy-Item -LiteralPath $build.Patch -Destination $installedJar
    $state = [ordered]@{OriginalLine=$originalLine;PatchedLine=$newLine;Backup=$backup;Patch=$installedJar;PatchSha256=$build.Sha256}
    [IO.File]::WriteAllText($statePath,($state|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
}
$newContent = $content.Substring(0,$matchesCore[0].Index) + $newLine + $content.Substring($matchesCore[0].Index+$matchesCore[0].Length)
[IO.File]::WriteAllText($info,$newContent,[Text.UTF8Encoding]::new($false))
if ($Rollback) { 'Original CodePilot restored. Restart EDT with -clean.' }
else { 'Local CodePilot debug patch installed. Restart EDT with -clean.' }
