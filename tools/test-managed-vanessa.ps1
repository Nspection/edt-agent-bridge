param([string]$Repository=(Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference='Stop'
$script=Join-Path $Repository 'tools\run-vanessa-debug.ps1'
$root=Join-Path $Repository ('.runs\vanessa-contract-'+[Guid]::NewGuid().ToString('N'))
$control=Join-Path $root 'tests\qa\managed-vanessa'
$run=Join-Path $root 'run-fixture'
New-Item -ItemType Directory -Force -Path $control,(Join-Path $run 'screenshots')|Out-Null
$ready=Join-Path $run 'checkpoint.ready'
$image=Join-Path $run 'screenshots\checkpoint.png'
$gate=Join-Path $run 'checkpoint.continue'
[IO.File]::WriteAllText($ready,'ready')
[IO.File]::WriteAllText($image,'test-image')
$hash=(Get-FileHash -LiteralPath $image -Algorithm SHA256).Hash
$state=[pscustomobject]@{RunId='run-B';CheckpointId='checkpoint-B';Status='running';RunDir=$run;StartedEpoch=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds();TimeoutSeconds=600;ManagerMarker='fixture-manager';ClientMarker='fixture-client';Processes=@()}
function Save-Fixture {$state|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $control 'state.json') -Encoding UTF8}
# No real process calls: the controller only sees this synthetic owned manager.
function Get-CimInstance { [pscustomobject]@{Name='1cv8.exe';CommandLine='/TestManager fixture-manager';CreationDate=(Get-Date);ProcessId=2147483647} }
$common=@{Action='Continue';Workspace=$root;RunId='run-B';CheckpointId='checkpoint-B';ScreenshotSha256=$hash}
$count=0
function Assert-Rejected($arguments) {
    $rejected=$false
    try {& $script @arguments|Out-Null} catch {$rejected=$true}
    if(!$rejected -or (Test-Path -LiteralPath $gate)){throw 'Invalid continuation was accepted'}
    $script:count++
}
Save-Fixture
Assert-Rejected @{Action='Continue';Workspace=$root}
$a=$common.Clone();$a.RunId='run-A';Assert-Rejected $a
$a=$common.Clone();$a.CheckpointId='checkpoint-A';Assert-Rejected $a
$a=$common.Clone();$a.ScreenshotSha256='wrong';Assert-Rejected $a
$state.Status='stopped';Save-Fixture;Assert-Rejected $common
$state.Status='running';$state.StartedEpoch-=700;Save-Fixture;Assert-Rejected $common
$state.StartedEpoch=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds();Save-Fixture
(Get-Item -LiteralPath $ready).LastWriteTimeUtc=[DateTime]::UtcNow.AddSeconds(-190);Assert-Rejected $common
(Get-Item -LiteralPath $ready).LastWriteTimeUtc=[DateTime]::UtcNow
$result=& $script @common|ConvertFrom-Json
if(!(Test-Path -LiteralPath $gate)){throw 'Valid current checkpoint did not continue'}
$count++
$duplicateRejected=$false
try {& $script @common|Out-Null} catch {$duplicateRejected=$true}
if(!$duplicateRejected){throw 'Duplicate continuation was accepted'}
$count++
'PASS: '+$count+' managed Vanessa continuation contracts; fixtures: '+$root

# Preparation contracts run in an isolated LOCALAPPDATA, never touching user 1C settings.
$previousLocalAppData=$env:LOCALAPPDATA
try {
    $env:LOCALAPPDATA=Join-Path $root 'profile'
    $manager=Join-Path $root 'manager-base';New-Item -ItemType Directory -Path $manager|Out-Null
    [IO.File]::WriteAllText((Join-Path $manager '1Cv8.1CD'),'fixture')
    $epf=Join-Path $root 'verified.epf';[IO.File]::WriteAllText($epf,'verified-package')
    $platform=Join-Path $root 'platform';New-Item -ItemType Directory -Path $platform|Out-Null
    [IO.File]::WriteAllText((Join-Path $platform '1cv8.exe'),'fixture')
    $shared=Join-Path $root 'shared-conf';New-Item -ItemType Directory -Path $shared,(Join-Path $platform 'conf')|Out-Null
    [IO.File]::WriteAllText((Join-Path $platform 'conf\conf.cfg'),('ConfLocation='+$shared))
    [IO.File]::WriteAllText((Join-Path $shared 'conf.cfg'),'SystemLanguage=System')
    $prepare=@{Action='Prepare';PlatformBin=$platform;Infobase=(Join-Path $root 'target-base');ManagerInfobase=$manager;VanessaEpf=$epf;ExpectedVanessaSha256=(Get-FileHash $epf -Algorithm SHA256).Hash}
    $preview=& $script @prepare|ConvertFrom-Json
    if($preview.Status -ne 'preview' -or $preview.ConfigurationFile -ne (Join-Path $shared 'conf.cfg') -or [IO.File]::ReadAllText($preview.ConfigurationFile) -cne 'SystemLanguage=System'){throw 'Preparation preview mutated configuration'}
    $count++
    $oldRule=$preview.Rule.Substring(0,$preview.Rule.Length-2)
    [IO.File]::AppendAllText((Join-Path $platform 'conf\conf.cfg'),("`r`nDisableUnsafeActionProtection=other_base;"+$oldRule))
    $userConf=Join-Path $env:LOCALAPPDATA '1C\1cv8\conf\conf.cfg'
    New-Item -ItemType Directory -Path (Split-Path $userConf -Parent) -Force|Out-Null
    [IO.File]::WriteAllText($userConf,('DisableUnsafeActionProtection='+$oldRule))
    $prepare.TrustDedicatedManager=$true
    $result=& $script @prepare|ConvertFrom-Json
    $configText=[IO.File]::ReadAllText($result.ConfigurationFile)
    if($result.Status -ne 'prepared' -or !$result.RestartRequired -or !$result.Backup -or !$configText.Contains('SystemLanguage=System') -or $result.Rule -eq '.*'){throw 'Preparation did not produce a scoped rule'}
    if(!(('File="'+$manager+'";') -cmatch ('^'+$result.Rule+'$')) -or !(('File="'+$manager+'\";') -cmatch ('^'+$result.Rule+'$')) -or (('File="'+$prepare.Infobase+'";') -cmatch ('^'+$result.Rule+'$'))){throw 'Trust rule does not match exactly the manager base'}
    if($result.ConfigurationFiles.Count -ne 3 -or !(Get-Content (Join-Path $platform 'conf\conf.cfg') -Raw).Contains('other_base;')){throw 'Preparation lost unrelated rules or missed a configuration layer'}
    foreach($file in $result.ConfigurationFiles){$rules=([regex]::Match([IO.File]::ReadAllText($file),'(?m)^\s*DisableUnsafeActionProtection\s*=([^\r\n]*)')).Groups[1].Value.Split(';');if(@($rules|Where-Object {$_ -eq $oldRule}).Count -or @($rules|Where-Object {$_ -eq $result.Rule}).Count -ne 1){throw 'Obsolete or duplicate manager rule remains'}}
    $count++
    $replayed=& $script @prepare|ConvertFrom-Json
    if($replayed.RestartRequired -or [IO.File]::ReadAllText($result.ConfigurationFile) -cne $configText){throw 'Preparation is not idempotent'}
    $count++
    $invalid=$prepare.Clone();$invalid.ExpectedVanessaSha256=('0'*64)
    $rejected=$false;try{& $script @invalid|Out-Null}catch{$rejected=$true};if(!$rejected){throw 'Changed package was accepted'}
    $count++
    $invalid=$prepare.Clone();$invalid.Infobase=$manager
    $rejected=$false;try{& $script @invalid|Out-Null}catch{$rejected=$true};if(!$rejected){throw 'Target database was accepted as trusted manager'}
    $count++
} finally {$env:LOCALAPPDATA=$previousLocalAppData}
'PASS: '+$count+' managed Vanessa contracts including isolated trust preparation'

# Paused debugger time must not expire the controller watchdog.
$state.Status='running';$state.StartedEpoch=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()-700
$state|Add-Member -NotePropertyName PausedSinceEpoch -NotePropertyValue ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()-200) -Force
$state|Add-Member -NotePropertyName PausedSeconds -NotePropertyValue 0 -Force
Save-Fixture
$paused=& $script -Action Status -Workspace $root|ConvertFrom-Json
if($paused.Status -ne 'running' -or !$paused.ClockPaused -or $paused.ActiveElapsedSeconds -gt 505){throw 'Debugger pause consumed the watchdog budget'}
$count++
$before=(Get-Content (Join-Path $control 'state.json') -Raw|ConvertFrom-Json).PausedSinceEpoch
$null=& $script -Action PauseClock -Workspace $root -RunId 'run-B'
$after=(Get-Content (Join-Path $control 'state.json') -Raw|ConvertFrom-Json).PausedSinceEpoch
if($before -ne $after){throw 'Repeated pause reset the clock'}
$count++
$resumed=& $script -Action ResumeClock -Workspace $root -RunId 'run-B'|ConvertFrom-Json
if($resumed.ClockPaused -or $resumed.ActiveElapsedSeconds -gt 505){throw 'Resume lost the paused duration'}
$count++
$rejected=$false;try{& $script -Action PauseClock -Workspace $root -RunId 'run-A'|Out-Null}catch{$rejected=$true};if(!$rejected){throw 'Stale run paused the current watchdog'}
$count++
'PASS: '+$count+' managed Vanessa contracts including pause-aware watchdog'

# A managed Start must actively execute features, overriding an interactive template.
# Process launch is replaced by a local fake; no executable is run.
function Start-Process {
    param($FilePath,$ArgumentList,$WindowStyle,$Environment,$RedirectStandardOutput,$RedirectStandardError,[switch]$PassThru)
    $global:codePilotContractLaunchArguments=$ArgumentList
    [pscustomobject]@{Id=2147483646}
}
$startRoot=Join-Path $root 'start-contract'
New-Item -ItemType Directory -Path $startRoot -Force|Out-Null
$feature=Join-Path $startRoot 'fixture.feature'
[IO.File]::WriteAllText($feature,"# language: ru`r`nФункционал: Контракт`r`nСценарий: Запуск`r`n  И Пауза 1`r`n")
$template=Join-Path $startRoot 'template.json'
@{КлиентыТестирования=@(@{Имя='Fixture';ДопПараметры='';ПутьКИнфобазе=''});СписокФичДляВыполнения=@($feature);ВыполнитьСценарии=$false}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $template -Encoding utf8
$started=& $script -Action Start -Workspace $startRoot -PlatformBin $platform -Infobase (Join-Path $root 'target-base') -ManagerInfobase $manager -ManagerUserName '' -VanessaEpf $epf -ParamsTemplate $template -DebuggerUrl 'http://localhost:1550' -UserName ''|ConvertFrom-Json
$actual=Get-Content (Join-Path $started.RunDir 'va-params.json') -Raw|ConvertFrom-Json
if($actual.ВыполнитьСценарии -ne $true -or $actual.ДелатьОтчетВФорматеjUnit -ne $true -or !$actual.ОтчетJUnit.КаталогВыгрузкиJUnit){throw 'Managed Start omitted automatic execution or the required JUnit report'}
$count++
if(!$global:codePilotContractLaunchArguments.Contains('DisableFirstRunHelper') -or !$global:codePilotContractLaunchArguments.Contains('/i85')){throw 'Managed Start omitted its noninteractive 8.5 launch flags'}
$count++
'PASS: '+$count+' managed Vanessa contracts including automatic feature execution'

Remove-Variable codePilotContractLaunchArguments -Scope Global
