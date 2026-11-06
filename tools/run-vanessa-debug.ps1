param(
    [ValidateSet('Start','Status','Stop','Continue','PauseClock','ResumeClock','Prepare')][string]$Action='Status',
    [string]$Workspace=$env:CODEPILOT_WORKSPACE,
    [string]$PlatformBin=$env:ONEC_PLATFORM_BIN,
    [string]$Infobase=$env:CODEPILOT_TEST_INFOBASE,
    [string]$UserName='',
    [string]$ManagerInfobase,
    [string]$ManagerUserName,
    [string]$DebuggerUrl,
    [string]$ParamsTemplate,
    [string]$VanessaEpf=$env:VANESSA_EPF,
    [int]$TimeoutSeconds=600,
    [switch]$VisualCheckpoint,
    [string]$RunId,
    [string]$CheckpointId,
    [string]$ScreenshotSha256,
    [string]$ExpectedVanessaSha256,
    [switch]$TrustDedicatedManager
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$OutputEncoding=[Text.UTF8Encoding]::new($false)
# Trust preparation is explicit and limited to the dedicated TestManager infobase.
# It never installs an unconstrained wildcard or changes the target application rule.
if($Action -eq 'Prepare') {
    if(!$ManagerInfobase){throw 'ManagerInfobase is required for preparation'}
    $managerPath=[IO.Path]::GetFullPath($ManagerInfobase).TrimEnd('\','/')
    $targetPath=[IO.Path]::GetFullPath($Infobase).TrimEnd('\','/')
    if($managerPath -ieq $targetPath){throw 'Trust preparation requires a separate manager infobase'}
    if(!(Test-Path -LiteralPath (Join-Path $managerPath '1Cv8.1CD') -PathType Leaf)){throw 'Dedicated manager infobase does not exist'}
    if($ExpectedVanessaSha256 -notmatch '^[a-fA-F0-9]{64}$'){throw 'ExpectedVanessaSha256 is required'}
    $actualHash=(Get-FileHash -LiteralPath $VanessaEpf -Algorithm SHA256).Hash
    if($actualHash -ine $ExpectedVanessaSha256){throw 'Vanessa package changed; verify its source before preparing trust'}
    # POSIX BRE metacharacters only. Reject configuration line/delimiter injection.
    if($managerPath.IndexOfAny([char[]]'";') -ge 0 -or $managerPath.Contains("`r") -or $managerPath.Contains("`n")){throw 'Unsupported manager path'}
    $escaped=[regex]::Replace($managerPath,'[\\.\[\]*^$]',{param($m) '\'+$m.Value})
    $rule='[Ff]ile="'+$escaped+'[\\/]*".*'
    $platformExecutable=Join-Path $PlatformBin '1cv8.exe'
    if(!(Test-Path -LiteralPath $platformExecutable -PathType Leaf)){throw 'Platform executable does not exist'}
    $conf=Join-Path ([IO.Path]::GetFullPath($PlatformBin)) 'conf\conf.cfg'
    # The version-local file can redirect platform settings to a shared directory.
    $chain=@();$seen=@{}
    for($depth=0;$depth -lt 4;$depth++) {
        if($seen.ContainsKey($conf)){throw 'Cyclic platform ConfLocation configuration'}
        $seen[$conf]=$true;$chain+=,$conf
        if(!(Test-Path -LiteralPath $conf)){break}
        $source=[IO.File]::ReadAllText($conf)
        $redirect=[regex]::Match($source,'(?m)^\s*ConfLocation\s*=([^\r\n]+)')
        if(!$redirect.Success){break}
        $location=[Environment]::ExpandEnvironmentVariables($redirect.Groups[1].Value.Trim().Trim('"'))
        if(![IO.Path]::IsPathRooted($location)){throw 'ConfLocation must be an absolute directory'}
        $next=Join-Path ([IO.Path]::GetFullPath($location)) 'conf.cfg'
        if($next -ieq $conf){break}
        $conf=$next
        if($depth -eq 3){throw 'Too many platform ConfLocation redirects'}
    }
    $userConf=Join-Path $env:LOCALAPPDATA '1C\1cv8\conf\conf.cfg'
    $targets=@($conf)
    foreach($candidate in (@($chain)+@($userConf))) {
        if($targets -contains $candidate -or !(Test-Path -LiteralPath $candidate)){continue}
        if([IO.File]::ReadAllText($candidate) -match '(?m)^\s*DisableUnsafeActionProtection\s*='){$targets+=,$candidate}
    }
    $updates=@();$backups=@()
    $priorRules=@(('[Ff]ile="'+$escaped+'"'),('[Ff]ile="'+$escaped+'[\\/]*"'),$rule)
    foreach($target in $targets) {
        $content=if(Test-Path -LiteralPath $target){[IO.File]::ReadAllText($target)}else{''}
        $matches=@([regex]::Matches($content,'(?m)^\s*DisableUnsafeActionProtection\s*=([^\r\n]*)'))
        if($matches.Count -gt 1){throw 'Multiple protection settings exist in '+$target+'; resolve ambiguity first'}
        $rules=@(if($matches.Count){$matches[0].Groups[1].Value.Split(';')|Where-Object {$_ -and $_ -cnotin $priorRules}})
        $rules+=,$rule
        $replacement='DisableUnsafeActionProtection='+($rules -join ';')
        if($matches.Count){$match=$matches[0];$updated=$content.Substring(0,$match.Index)+$replacement+$content.Substring($match.Index+$match.Length)}
        else{$updated=$content.TrimEnd()+"`r`n"+$replacement+"`r`n"}
        if($updated -cne $content){$updates+=,[pscustomobject]@{Path=$target;Before=$content;After=$updated}}
    }
    if($TrustDedicatedManager -and $updates.Count) {
        # Back up every destination before changing any of them; a permission error leaves all untouched.
        foreach($entry in $updates) {
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($entry.Path))|Out-Null
            if(Test-Path -LiteralPath $entry.Path){$backup=$entry.Path+'.codepilot-'+[Guid]::NewGuid().ToString('N')+'.bak';[IO.File]::Copy($entry.Path,$backup,$false);$backups+=,$backup}
        }
        foreach($entry in $updates) {
            $current=if(Test-Path -LiteralPath $entry.Path){[IO.File]::ReadAllText($entry.Path)}else{''}
            if($current -cne $entry.Before){throw 'Platform configuration changed during preparation; inspect before retrying'}
            $temp=$entry.Path+'.codepilot-'+[Guid]::NewGuid().ToString('N')+'.tmp'
            try {
                [IO.File]::WriteAllText($temp,$entry.After,[Text.UTF8Encoding]::new($false))
                if(Test-Path -LiteralPath $entry.Path){[IO.File]::Replace($temp,$entry.Path,[System.Management.Automation.Language.NullString]::Value)}else{[IO.File]::Move($temp,$entry.Path)}
            } finally {if(Test-Path -LiteralPath $temp){Remove-Item -LiteralPath $temp}}
        }
    }
    $already=$updates.Count -eq 0
    [pscustomobject]@{Status=$(if($already -or $TrustDedicatedManager){'prepared'}else{'preview'});ManagerInfobase=$managerPath;VanessaSha256=$actualHash;ConfigurationFile=$conf;ConfigurationFiles=$targets;Rule=$rule;Backup=$(if($backups.Count){$backups[0]}else{$null});Backups=$backups;RestartRequired=(!$already -and [bool]$TrustDedicatedManager);Scope='All external processing inside this dedicated manager base only; target base unchanged'}|ConvertTo-Json -Depth 6
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
    [IO.File]::WriteAllText($statePath,($value|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))
}
function Get-ActiveElapsed($value) {
    $now=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $paused=if($value.PausedSeconds){[long]$value.PausedSeconds}else{0}
    if($value.PausedSinceEpoch){$paused+=$now-[long]$value.PausedSinceEpoch}
    $now-[long]$value.StartedEpoch-$paused
}
function Get-OwnedProcesses($state) {
    if (!$state) { return @() }
    $floor=if($null -ne $state.StartedEpoch){[DateTimeOffset]::FromUnixTimeSeconds([long]$state.StartedEpoch).LocalDateTime.AddSeconds(-1)}else{([datetime]$state.Started).ToLocalTime().AddSeconds(-1)}
    $markers=@($state.ManagerMarker,$state.ClientMarker)
    @(Get-CimInstance Win32_Process | Where-Object {
        if ($_.Name -notin @('1cv8.exe','1cv8c.exe') -or !$_.CommandLine -or $_.CreationDate -lt $floor) { return $false }
        $command=$_.CommandLine
        ($command -match '(?i)/TestManager\b' -and $command.IndexOf($markers[0],[StringComparison]::OrdinalIgnoreCase) -ge 0) -or
        ($command -match '(?i)/TestClient\b' -and $command.IndexOf($markers[1],[StringComparison]::OrdinalIgnoreCase) -ge 0)
    })
}
function Stop-OwnedProcesses($state) {
    $owned=@(Get-OwnedProcesses $state)
    foreach ($entry in ($owned|Sort-Object @{Expression={if($_.CommandLine -match '(?i)/TestClient\b'){0}else{1}}})) {
        # Re-read identity immediately before termination; never trust a recycled PID.
        $current=Get-CimInstance Win32_Process -Filter ('ProcessId='+$entry.ProcessId)
        if($current -and $current.CreationDate -eq $entry.CreationDate -and $current.CommandLine -eq $entry.CommandLine) {
            Stop-Process -Id $entry.ProcessId -ErrorAction Stop
        }
    }
    $remaining=@(Get-OwnedProcesses $state)
    if($remaining.Count) { throw 'Some processes owned by the preceding run are still active' }
    @($owned|ForEach-Object {$_.ProcessId})
}
function Quote-Argument([string]$value) {
    if($value.Contains('"') -or $value.Contains("`r") -or $value.Contains("`n")) { throw 'Unsupported quote or newline in launch argument' }
    '"'+$value+'"'
}
try {
    $state=if(Test-Path -LiteralPath $statePath){Get-Content -LiteralPath $statePath -Raw|ConvertFrom-Json}else{$null}
    if($Action -eq 'Stop') {
        $stopped=@(Stop-OwnedProcesses $state)
        if($state){$state.Status='stopped';Save-State $state}
        [pscustomobject]@{Status='stopped';StoppedPids=$stopped;CheckDebuggerBeforeRestart=$true}|ConvertTo-Json
        return
    }
    if($Action -in @('PauseClock','ResumeClock','Prepare')) {
        if(!$state -or !$RunId -or $state.RunId -cne $RunId -or $state.Status -ne 'running'){throw 'Run clock request is stale or the run is not running'}
        $now=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
        if($Action -eq 'PauseClock' -and !$state.PausedSinceEpoch){$state|Add-Member -NotePropertyName PausedSinceEpoch -NotePropertyValue $now -Force}
        if($Action -eq 'ResumeClock' -and $state.PausedSinceEpoch){
            $total=[long]$state.PausedSeconds+$now-[long]$state.PausedSinceEpoch
            $state|Add-Member -NotePropertyName PausedSeconds -NotePropertyValue $total -Force
            $state.PausedSinceEpoch=$null
        }
        Save-State $state
    }
    if($Action -eq 'Continue') {
        if(!$state){throw 'No active run'}
        if(!$RunId -or !$CheckpointId -or !$ScreenshotSha256){throw 'Continue requires RunId, CheckpointId and ScreenshotSha256 from Status'}
        if($state.RunId -cne $RunId -or $state.CheckpointId -cne $CheckpointId){throw 'Stale run or checkpoint; inspect the current Status and screenshot'}
        if($state.Status -ne 'running'){throw 'Run is not awaiting continuation'}
        if((Get-ActiveElapsed $state) -ge $state.TimeoutSeconds){throw 'Run has expired'}
        if(Test-Path -LiteralPath (Join-Path $state.RunDir 'checkpoint.continue')){throw 'Checkpoint has already been continued'}
        $ready=Join-Path $state.RunDir 'checkpoint.ready'
        $screenshot=Join-Path $state.RunDir 'screenshots\checkpoint.png'
        if(!(Test-Path -LiteralPath $ready) -or !(Test-Path -LiteralPath $screenshot)){throw 'No visual checkpoint ready'}
        if(([DateTime]::UtcNow-(Get-Item -LiteralPath $ready).LastWriteTimeUtc).TotalSeconds -ge 180){throw 'Visual checkpoint has expired'}
        if((Get-FileHash -LiteralPath $screenshot -Algorithm SHA256).Hash -ine $ScreenshotSha256){throw 'Screenshot changed; inspect the current image'}
        if(@(Get-OwnedProcesses $state).Count -eq 0){throw 'No active processes for this run'}
        [IO.File]::WriteAllText((Join-Path $state.RunDir 'checkpoint.continue'),'continue',[Text.UTF8Encoding]::new($false))
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
        $runDir=Join-Path $resultsRoot ('va-managed-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8))
        New-Item -ItemType Directory -Path $runDir,(Join-Path $runDir 'junit'),(Join-Path $runDir 'screenshots') -Force|Out-Null
        if($VisualCheckpoint){
            if(@($params.СписокФичДляВыполнения).Count -ne 1){throw 'VisualCheckpoint currently requires one selected feature'}
            $featureText=[IO.File]::ReadAllText($params.СписокФичДляВыполнения[0])
            if([regex]::Matches($featureText,'(?m)^\s*(Сценарий|Scenario):').Count -ne 1){throw 'VisualCheckpoint requires one ordinary scenario'}
            $checkpointLines=@(
                '  И я сохраняю скриншот "'+(Join-Path $runDir 'screenshots\checkpoint.png')+'"'
                '  И я создаю файл "'+(Join-Path $runDir 'checkpoint.ready')+'"'
                '    | "scenario-final-ui" |'
                '  И я жду существования файла "'+(Join-Path $runDir 'checkpoint.continue')+'" в течение 180 секунд'
            )
            $featureCopy=Join-Path $runDir 'visual-checkpoint.feature'
            [IO.File]::WriteAllText($featureCopy,($featureText.TrimEnd()+[Environment]::NewLine+($checkpointLines -join [Environment]::NewLine)+[Environment]::NewLine),[Text.UTF8Encoding]::new($true))
            $params.СписокФичДляВыполнения=@($featureCopy)
            $params.КаталогФич=$runDir
            $params.КаталогОтносительноКоторогоНадоСтроитьИерархию=$runDir
        }
        $paramsPath=Join-Path $runDir 'va-params.json'
        $clientLog=Join-Path $runDir 'testclient.log'
        foreach($client in $params.КлиентыТестирования){
            # The temporary /Out path is also an ownership marker, distinct for each run.
            $client.ДопПараметры='/i85 /N '+(Quote-Argument $UserName)+' /Out '+(Quote-Argument $clientLog)
            $client.ПутьКИнфобазе='File="'+$Infobase+'";'
        }
        $overrides=@{
            'ВыполнитьСценарии'=$true
            'ДелатьОтчетВФорматеjUnit'=$true
            'ДобавлятьКИмениСценарияУсловияВыгрузки'=$false
            'ОтчетJUnit'=@{КаталогВыгрузкиJUnit=(Join-Path $runDir 'junit');ЗаписыватьСценарииВJunitЧерезTestSuite=$false}
            'ЗавершитьРаботуСистемы'=$false
            'ЗакрытьTestClientПослеЗапускаСценариев'=$false
            'ЗапускатьТестКлиентВРежимеОтладки'=$true
            'КлючиОтладки'='-http -attach'
            'АдресОтладчика'=$DebuggerUrl
            'МаксимальноеВремяВыполненияДействия'=0
            'ВыгружатьСтатусВыполненияСценариевВФайл'=$true
            'ДелатьЛогВыполненияСценариевВТекстовыйФайл'=$true
            'ВыводитьВЛогВыполнениеШагов'=$true
            'РазмерБуфераЛогаВыполненияСценариев'=1
            'ИмяФайлаЛогВыполненияСценариев'=(Join-Path $runDir 'steps.log')
            'СпособСнятияСкриншотовВнешнейКомпонентой'=1
            'КаталогOutputjUnit'=(Join-Path $runDir 'junit')
            'КаталогВыгрузкиJUnit'=(Join-Path $runDir 'junit')
            'КаталогOutputСкриншоты'=(Join-Path $runDir 'screenshots')
            'ВыводитьСообщенияВФайл'=(Join-Path $runDir 'steps.log')
            'ПутьКФайлуДляВыгрузкиСтатусаВыполненияСценариев'=(Join-Path $runDir 'status.txt')
        }
        foreach($name in $overrides.Keys){$params|Add-Member -NotePropertyName $name -NotePropertyValue $overrides[$name] -Force}
        [IO.File]::WriteAllText($paramsPath,($params|ConvertTo-Json -Depth 15),[Text.UTF8Encoding]::new($false))
        $state=[pscustomobject]@{Status='starting';PausedSinceEpoch=$null;PausedSeconds=0;RunId=$(if($RunId){$RunId}else{Split-Path $runDir -Leaf});CheckpointId=[Guid]::NewGuid().ToString('N');RunDir=$runDir;Started=[DateTime]::UtcNow.ToString('o');StartedEpoch=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds();TimeoutSeconds=$TimeoutSeconds;ManagerMarker=$paramsPath;ClientMarker=$clientLog;ManagerPid=0;StoppedPreviousPids=$stopped;Processes=@()}
        Save-State $state
        $command='ENTERPRISE /i85 /F '+(Quote-Argument $ManagerInfobase)+' /N '+(Quote-Argument $ManagerUserName)+' /TestManager /DisableStartupDialogs /DisableStartupMessages /Execute '+(Quote-Argument $VanessaEpf)+' /C '+(Quote-Argument ('StartFeaturePlayer;QuietInstallVanessaExt;DisableFirstRunHelper;ShowMainForm;VAParams='+$paramsPath))+' /Out '+(Quote-Argument (Join-Path $runDir 'va.log'))
        $allowedEnvironment=@('APPDATA','LOCALAPPDATA','USERPROFILE','TEMP','TMP','SYSTEMROOT','WINDIR','COMSPEC','PATH','PROGRAMFILES','PROGRAMFILES(X86)','PROGRAMW6432','COMMONPROGRAMFILES','COMMONPROGRAMFILES(X86)','COMMONPROGRAMW6432','PROCESSOR_ARCHITECTURE','NUMBER_OF_PROCESSORS','USERNAME','USERDOMAIN','COMPUTERNAME','HOMEDRIVE','HOMEPATH','SYSTEMDRIVE','ALLUSERSPROFILE','PUBLIC','PATHEXT','LANG')
        $environmentChanges=@{}
        foreach($variable in Get-ChildItem Env:){if($variable.Name.ToUpperInvariant() -notin $allowedEnvironment){$environmentChanges[$variable.Name]=$null}}
        $manager=Start-Process -FilePath (Join-Path $PlatformBin '1cv8.exe') -ArgumentList $command -Environment $environmentChanges -RedirectStandardOutput (Join-Path $runDir 'manager.stdout.log') -RedirectStandardError (Join-Path $runDir 'manager.stderr.log') -WindowStyle Hidden -PassThru
        $state.ManagerPid=$manager.Id;$state.Status='running';Save-State $state
    } elseif(!$state) {
        [pscustomobject]@{Status='idle';OwnedPids=@()}|ConvertTo-Json
        return
    }
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
    if(!$report -and $owned.Count -and $null -ne $state.StartedEpoch -and (Get-ActiveElapsed $state) -gt $state.TimeoutSeconds){
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
    $progress=[pscustomobject]@{Phase=$phase;CurrentStep=$step;Checkpoint=$checkpoint}
    $progressKey=$progress|ConvertTo-Json -Depth 5 -Compress
    if(!$state.PSObject.Properties['ProgressKey'] -or $state.ProgressKey -ne $progressKey){
        $event=[pscustomobject]@{At=[DateTime]::UtcNow.ToString('o');Status=$state.Status;Progress=$progress}
        Add-Content -LiteralPath (Join-Path $state.RunDir 'events.jsonl') -Value ($event|ConvertTo-Json -Depth 7 -Compress) -Encoding utf8
        $state|Add-Member -NotePropertyName ProgressKey -NotePropertyValue $progressKey -Force
    }
    Save-State $state
    [pscustomobject]@{Status=$state.Status;RunId=$state.RunId;ClockPaused=[bool]$state.PausedSinceEpoch;ActiveElapsedSeconds=(Get-ActiveElapsed $state);Progress=$progress;RunDir=$state.RunDir;OwnedPids=@(Get-OwnedProcesses $state|ForEach-Object{$_.ProcessId});Report=$report;StoppedPreviousPids=$state.StoppedPreviousPids;CheckDebuggerBeforeRestart=(@($state.StoppedPreviousPids).Count -gt 0)}|ConvertTo-Json -Depth 8
} finally {$lock.Dispose()}
