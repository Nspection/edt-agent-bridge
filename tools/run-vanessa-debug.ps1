param(
    [ValidateSet('Start','Status','Stop','Continue','PauseClock','ResumeClock','Prepare')][string]$Action='Status',
    [string]$Workspace=$env:CODEPILOT_WORKSPACE,
    [string]$PlatformBin=$env:ONEC_PLATFORM_BIN,
    [string]$Infobase=$env:CODEPILOT_TEST_INFOBASE,
    [string]$ServerAddress,
    [string]$ServerInfobase,
    [string]$UserName='',
    [string]$ManagerInfobase,
    [string]$ManagerUserName,
    [ValidateRange(0,65535)][int]$ManagerMcpPort=0,
    [string]$DebuggerUrl,
    [string]$ParamsTemplate,
    [string]$VanessaEpf=$env:VANESSA_EPF,
    [int]$TimeoutSeconds=600,
    [switch]$VisualCheckpoint,
    [string]$RunId,
    [string]$CheckpointId,
    [string]$ScreenshotSha256,
    [string]$ExpectedVanessaSha256,
    [switch]$TrustDedicatedManager,
    [switch]$ReadAccessFromStdin
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'managed-vanessa-core.ps1')
[Console]::InputEncoding=[Text.UTF8Encoding]::new($false)
$launchSecrets=if($Action -eq 'Start' -and $ReadAccessFromStdin){ $secretJson=[Console]::In.ReadToEnd(); if($secretJson){$secretJson|ConvertFrom-Json}else{$null} }else{$null}
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$OutputEncoding=[Text.UTF8Encoding]::new($false)
# Trust preparation is explicit and limited to the dedicated TestManager infobase.
# It never installs an unconstrained wildcard or changes the target application rule.
if($Action -eq 'Prepare') {
    if(!$ManagerInfobase){throw 'ManagerInfobase is required for preparation'}
    $trustArgs=@{
        ManagerInfobase=$ManagerInfobase;Infobase=$Infobase;ServerAddress=$ServerAddress;PlatformBin=$PlatformBin
        ExpectedVanessaSha256=$ExpectedVanessaSha256;ActualHash=(Get-FileHash -LiteralPath $VanessaEpf -Algorithm SHA256).Hash
        ManagerExists=(Test-Path -LiteralPath (Join-Path $ManagerInfobase '1Cv8.1CD') -PathType Leaf)
        PlatformExists=(Test-Path -LiteralPath (Join-Path $PlatformBin '1cv8.exe') -PathType Leaf)
        UserConf=(Join-Path $env:LOCALAPPDATA '1C\1cv8\conf\conf.cfg')
        ReadConfig={param($file) if(Test-Path -LiteralPath $file){[IO.File]::ReadAllText($file)}}
        ExpandLocation={param($location) [Environment]::ExpandEnvironmentVariables($location)}
    }
    $plan=Get-MvTrustPlan @trustArgs
    $backup={
        param($entry)
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($entry.Path))|Out-Null
        if(Test-Path -LiteralPath $entry.Path){
            $file=$entry.Path+'.codepilot-'+[Guid]::NewGuid().ToString('N')+'.bak'
            [IO.File]::Copy($entry.Path,$file,$false)
            $file
        }
    }
    $replace={
        param($entry)
            $current=if(Test-Path -LiteralPath $entry.Path){[IO.File]::ReadAllText($entry.Path)}else{''}
            if($current -cne $entry.Before){throw 'Platform configuration changed during preparation; inspect before retrying'}
            $temp=$entry.Path+'.codepilot-'+[Guid]::NewGuid().ToString('N')+'.tmp'
            try {
                [IO.File]::WriteAllText($temp,$entry.After,[Text.UTF8Encoding]::new($false))
                if(Test-Path -LiteralPath $entry.Path){[IO.File]::Replace($temp,$entry.Path,[System.Management.Automation.Language.NullString]::Value)}else{[IO.File]::Move($temp,$entry.Path)}
            } finally {if(Test-Path -LiteralPath $temp){Remove-Item -LiteralPath $temp}}
    }
    Invoke-MvTrustPlan $plan ([bool]$TrustDedicatedManager) $backup $replace | ConvertTo-Json -Depth 6
    return
}
$resultsRoot=Join-Path $Workspace 'tests\qa\results'
$controller=Join-Path $Workspace 'tests\qa\managed-vanessa'
New-Item -ItemType Directory -Path $controller -Force|Out-Null
# Serialize Start/Stop/Status so two agents cannot replace each other's state.
$lock=$null
$lockDeadline=[DateTime]::UtcNow.AddSeconds(5)
while(!$lock){
    try{$lock=[IO.File]::Open((Join-Path $controller 'controller.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)}
    catch [IO.IOException]{if([DateTime]::UtcNow -ge $lockDeadline){throw 'Controller is busy; no action was performed'};Start-Sleep -Milliseconds 50}
}
$statePath=Join-Path $controller 'state.json'
function Save-State($value) {
    $temporary=$statePath+'.'+[Guid]::NewGuid().ToString('N')+'.tmp'
    try {
        [IO.File]::WriteAllText($temporary,($value|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))
        if(Test-Path -LiteralPath $statePath){[IO.File]::Replace($temporary,$statePath,[System.Management.Automation.Language.NullString]::Value)}else{[IO.File]::Move($temporary,$statePath)}
    } finally {if(Test-Path -LiteralPath $temporary){Remove-Item -LiteralPath $temporary}}
}
function Get-ActiveElapsed($value) {Get-MvElapsed $value ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())}
function Get-OwnedProcesses($state) {
    if(!$state){return}
    Select-MvOwnedProcesses $state @(Get-CimInstance Win32_Process)
}
function Get-OwnedManagerMcpReady($state) {
    if(!$state.ManagerMcpPort){return $false}
    $listeners=@(Get-NetTCPConnection -State Listen -LocalPort ([int]$state.ManagerMcpPort) -ErrorAction SilentlyContinue)
    Test-MvListener $state.ManagerMcpPort $state.ManagerPid $listeners
}
function Stop-OwnedProcesses($state) {
    $effects=@{
        Now={[datetime]::UtcNow};Delay={Start-Sleep -Milliseconds 50}
        Owned={param($record) Get-OwnedProcesses $record}
        Lookup={param($processId) Get-CimInstance Win32_Process -Filter ('ProcessId='+$processId)}
        Stop={param($processId) Stop-Process -Id $processId -ErrorAction Stop}
        RemoveParams={param($file) if(Test-Path -LiteralPath $file){Remove-Item -LiteralPath $file}}
    }
    Invoke-MvStopOwned $state $effects
}
if($ServerAddress -or $ServerInfobase){
    if($Infobase -or $ServerAddress -notmatch '^[A-Za-z0-9_.-]+(?::[0-9]{1,5})?$' -or !$ServerInfobase -or $ServerInfobase.IndexOfAny([char[]]'";\/') -ge 0 -or $ServerInfobase -match '[\r\n]'){throw 'Invalid or ambiguous server target'}
}
try {
    $state=Read-MvState {if(Test-Path -LiteralPath $statePath){[IO.File]::ReadAllText($statePath)}}
    if($Action -eq 'Stop') {
        if((Get-MvStopScope $state $RunId) -eq 'absent'){[pscustomobject]@{Status='absent';RunId=$RunId;StoppedPids=@()}|ConvertTo-Json;return}
        $stopped=@(Stop-OwnedProcesses $state)
        if($state){$state.Status='stopped';Save-State $state}
        [pscustomobject]@{Status='stopped';RunId=$(if($state){$state.RunId}else{$RunId});StoppedPids=$stopped;CheckDebuggerBeforeRestart=$true}|ConvertTo-Json
        return
    }
    if($Action -in @('PauseClock','ResumeClock','Prepare')) {
        Set-MvClock $state $RunId $Action ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
        Save-State $state
    }
    if($Action -eq 'Continue') {
        $request=@{RunId=$RunId;CheckpointId=$CheckpointId;Hash=$ScreenshotSha256;Now=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()}
        $observe={
            param($record)
            $ready=Join-Path $record.RunDir 'checkpoint.ready'
            $image=Join-Path $record.RunDir 'screenshots\checkpoint.png'
            $proof=@{Continued=(Test-Path -LiteralPath (Join-Path $record.RunDir 'checkpoint.continue'));HasEvidence=$false;Owned=$false}
            if(!$proof.Continued -and (Test-Path -LiteralPath $ready) -and (Test-Path -LiteralPath $image)){
                $proof.HasEvidence=$true
                $proof.AgeSeconds=([datetime]::UtcNow-(Get-Item -LiteralPath $ready).LastWriteTimeUtc).TotalSeconds
                $proof.Hash=(Get-FileHash -LiteralPath $image -Algorithm SHA256).Hash
                $proof.Owned=@(Get-OwnedProcesses $record).Count -gt 0
            }
            $proof
        }
        Invoke-MvContinuation $state $request $observe {param($record)
            [IO.File]::WriteAllText((Join-Path $record.RunDir 'checkpoint.continue'),'continue',[Text.UTF8Encoding]::new($false))
        }
    }
    if($Action -eq 'Start') {
        if(!$ManagerInfobase){$ManagerInfobase=$Infobase}
        if(!$PSBoundParameters.ContainsKey('ManagerUserName')){$ManagerUserName=$UserName}
        if($TimeoutSeconds -lt 30){throw 'TimeoutSeconds must be at least 30'}
        if(!$DebuggerUrl){throw 'Pass the actual EDT debug server URL'}
        $uri=[uri]$DebuggerUrl
        if(!$uri.IsAbsoluteUri -or $uri.Scheme -notin @('http','https') -or !$uri.Host){throw 'Invalid debugger URL'}
        if(!$ParamsTemplate){throw 'ParamsTemplate is required'}
        $params=Get-Content -LiteralPath $ParamsTemplate -Raw|ConvertFrom-Json
        if(!$params.КлиентыТестирования -or !$params.СписокФичДляВыполнения){throw 'Template must contain named clients and selected features'}
        foreach($file in @($VanessaEpf,(Join-Path $PlatformBin '1cv8.exe'))+@($params.СписокФичДляВыполнения)){
            if(!(Test-Path -LiteralPath $file -PathType Leaf)){throw ('Missing file: '+$file)}
        }
        $stopped=@(Stop-OwnedProcesses $state)
        $runDir=Invoke-MvAfterPortCheck $ManagerMcpPort {
            param($port)
            $probe=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$port)
            $probe.Server.ExclusiveAddressUse=$true
            try{$probe.Start()}finally{$probe.Stop()}
        } {
            Join-Path $resultsRoot ('va-managed-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8))
        }
        New-Item -ItemType Directory -Path $runDir,(Join-Path $runDir 'junit'),(Join-Path $runDir 'screenshots') -Force|Out-Null
        $featureText=if($VisualCheckpoint -and @($params.СписокФичДляВыполнения).Count -eq 1){[IO.File]::ReadAllText($params.СписокФичДляВыполнения[0])}
        $settings=@{
            Params=$params;RunDir=$runDir;PlatformBin=$PlatformBin;ManagerInfobase=$ManagerInfobase;ManagerUserName=$ManagerUserName
            Infobase=$Infobase;ServerAddress=$ServerAddress;ServerInfobase=$ServerInfobase;UserName=$UserName
            TargetPassword=$launchSecrets.target_password;VanessaEpf=$VanessaEpf;DebuggerUrl=$DebuggerUrl
            ManagerMcpPort=$ManagerMcpPort;VisualCheckpoint=[bool]$VisualCheckpoint;FeatureText=$featureText
        }
        $plan=New-MvLaunchPlan @settings
        $params=$plan.Parameters;$paramsPath=$plan.ParamsFile;$clientLog=$plan.ClientLog;$command=$plan.Arguments
        if($plan.VisualFile){[IO.File]::WriteAllText($plan.VisualFile,$plan.VisualText,[Text.UTF8Encoding]::new($true))}
        [IO.File]::WriteAllText($paramsPath,($params|ConvertTo-Json -Depth 15),[Text.UTF8Encoding]::new($false))
        $state=[pscustomobject]@{Status='starting';PausedSinceEpoch=$null;PausedSeconds=0;RunId=$(if($RunId){$RunId}else{Split-Path $runDir -Leaf});CheckpointId=[Guid]::NewGuid().ToString('N');RunDir=$runDir;Started=[DateTime]::UtcNow.ToString('o');StartedEpoch=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds();TimeoutSeconds=$TimeoutSeconds;ManagerMarker=$paramsPath;ClientMarker=$clientLog;ManagerPid=0;ManagerMcpPort=$ManagerMcpPort;StoppedPreviousPids=$stopped;Processes=@()}
        Save-State $state
        $allowedEnvironment=@('APPDATA','LOCALAPPDATA','USERPROFILE','TEMP','TMP','SYSTEMROOT','WINDIR','COMSPEC','PATH','PROGRAMFILES','PROGRAMFILES(X86)','PROGRAMW6432','COMMONPROGRAMFILES','COMMONPROGRAMFILES(X86)','COMMONPROGRAMW6432','PROCESSOR_ARCHITECTURE','NUMBER_OF_PROCESSORS','USERNAME','USERDOMAIN','COMPUTERNAME','HOMEDRIVE','HOMEPATH','SYSTEMDRIVE','ALLUSERSPROFILE','PUBLIC','PATHEXT','LANG')
        $environmentChanges=@{}
        foreach($variable in Get-ChildItem Env:){if($variable.Name.ToUpperInvariant() -notin $allowedEnvironment){$environmentChanges[$variable.Name]=$null}}
        $manager=Start-Process -FilePath (Join-Path $PlatformBin '1cv8.exe') -ArgumentList $command -Environment $environmentChanges -RedirectStandardOutput (Join-Path $runDir 'manager.stdout.log') -RedirectStandardError (Join-Path $runDir 'manager.stderr.log') -WindowStyle Hidden -PassThru
        $state.ManagerPid=$manager.Id;$state.Status='running';Save-State $state
        Confirm-MvManagerStartup $state @{
            Now={[datetime]::UtcNow};Delay={Start-Sleep -Milliseconds 200}
            Ready={param($record) Get-OwnedManagerMcpReady $record}
            Stop={param($record) Stop-OwnedProcesses $record}
            Save={param($record) Save-State $record}
        }
    } elseif(!$state) {
        [pscustomobject]@{Status='idle';OwnedPids=@()}|ConvertTo-Json
        return
    }
    $mcp=Get-MvManagerStatus $state {param($record) Get-OwnedManagerMcpReady $record}
    $managerMcpReady=$mcp.Ready;$managerMcpError=$mcp.Error
    $owned=@(Get-OwnedProcesses $state)
    $state.Processes=@($owned|ForEach-Object{[pscustomobject]@{Pid=$_.ProcessId;Created=$_.CreationDate.ToUniversalTime().ToString('o');Role=if($_.CommandLine -match '(?i)/TestManager\b'){'manager'}else{'client'}}})
    $junit=Join-Path $state.RunDir 'junit\junit.xml'
    $report=$null
    if(Test-Path -LiteralPath $junit){
        [xml]$document=Get-Content -LiteralPath $junit -Raw
        $suites=@($document.SelectNodes('//testsuite'))
        if($suites.Count){
            $report=[pscustomobject]@{Tests=($suites|ForEach-Object{[int]$_.tests}|Measure-Object -Sum).Sum;Failures=($suites|ForEach-Object{[int]$_.failures+[int]$_.errors}|Measure-Object -Sum).Sum}
            $state.Status=if($report.Failures){'failed'}else{'passed'}
        }
    }
    if(!$report -and $owned.Count -eq 0 -and $state.Status -eq 'running'){$state.Status='exited_without_report'}
    $vaStatusPath=Join-Path $state.RunDir 'status.txt'
    $vaStatus=if(Test-Path -LiteralPath $vaStatusPath){([IO.File]::ReadAllText($vaStatusPath)).Trim()}else{$null}
    if(!$report -and $owned.Count -eq 0 -and $vaStatus -in @('1','2','3')){$state.Status='failed'}
    if(Test-MvWatchdog $state $report $owned.Count ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())){
        $null=Stop-OwnedProcesses $state;$state.Status='timeout'
    }
    $step=$null
    $stepLog=Join-Path $state.RunDir 'steps.log'
    if(Test-Path -LiteralPath $stepLog){
        $stepMatches=[regex]::Matches(((Get-Content -LiteralPath $stepLog -Tail 150)-join "`n"),'\((?<line>\d+)\)\.\s*Шаг[^\r\n]*')
        if($stepMatches.Count){$last=$stepMatches[$stepMatches.Count-1];$step=[pscustomobject]@{Line=[int]$last.Groups['line'].Value;Text=$last.Value}}
    }
    $checkpoint=$null
    $phase=if($report){'completed'}elseif($step){'scenario_running'}elseif(@($state.Processes|Where-Object Role -eq 'client').Count){'client_running'}else{'loading_features'}
    if(!$report -and (Test-Path -LiteralPath (Join-Path $state.RunDir 'checkpoint.ready')) -and !(Test-Path -LiteralPath (Join-Path $state.RunDir 'checkpoint.continue'))){
        $phase='awaiting_visual_review'
        $checkpointImage=Join-Path $state.RunDir 'screenshots\checkpoint.png'
        $checkpointHash=if(Test-Path -LiteralPath $checkpointImage){(Get-FileHash -LiteralPath $checkpointImage -Algorithm SHA256).Hash}else{$null}
        $checkpoint=[pscustomobject]@{Id=$state.CheckpointId;RunId=$state.RunId;Screenshot=$checkpointImage;ScreenshotSha256=$checkpointHash;ContinueAction='Continue';WaitTimeoutSeconds=180}
    }
    if($state.Status -in @('stopped','timeout','exited_without_report','failed')){$phase=$state.Status}
    if($managerMcpError){$phase='failed'}
    $progress=[pscustomobject]@{Phase=$phase;CurrentStep=$step;Checkpoint=$checkpoint}
    $progressKey=$progress|ConvertTo-Json -Depth 5 -Compress
    if(!$state.PSObject.Properties['ProgressKey'] -or $state.ProgressKey -ne $progressKey){
        $event=[pscustomobject]@{At=[DateTime]::UtcNow.ToString('o');Status=$state.Status;Progress=$progress}
        Add-Content -LiteralPath (Join-Path $state.RunDir 'events.jsonl') -Value ($event|ConvertTo-Json -Depth 7 -Compress) -Encoding utf8
        $state|Add-Member -NotePropertyName ProgressKey -NotePropertyValue $progressKey -Force
    }
    Save-State $state
    [pscustomobject]@{Status=$(if($managerMcpError){$mcp.Status}else{$state.Status});RunId=$mcp.RunId;ManagerMcpError=$managerMcpError;ManagerMcpReady=[bool]$managerMcpReady;ClockPaused=[bool]$state.PausedSinceEpoch;ActiveElapsedSeconds=(Get-ActiveElapsed $state);Progress=$progress;RunDir=$state.RunDir;OwnedPids=@(Get-OwnedProcesses $state|ForEach-Object{$_.ProcessId});Report=$report;StoppedPreviousPids=$state.StoppedPreviousPids;CheckDebuggerBeforeRestart=(@($state.StoppedPreviousPids).Count -gt 0)}|ConvertTo-Json -Depth 8
} finally {$lock.Dispose()}
