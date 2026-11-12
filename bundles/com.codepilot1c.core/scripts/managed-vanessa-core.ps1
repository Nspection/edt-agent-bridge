# Managed Vanessa decisions shared by the shipped CLI and its deterministic contract suite.
# A dot-source loads definitions only. OS operations are supplied by the CLI adapters.


function Get-MvTrustPlan {
    param($ManagerInfobase,$Infobase,$ServerAddress,$ExpectedVanessaSha256,$ActualHash,
          [bool]$ManagerExists,[bool]$PlatformExists,$PlatformBin,$UserConf,
          [scriptblock]$ReadConfig,[scriptblock]$ExpandLocation)
    if(!$ManagerInfobase){throw 'ManagerInfobase is required for preparation'}
    $managerPath=[IO.Path]::GetFullPath($ManagerInfobase).TrimEnd('\','/')
    $targetPath=if($ServerAddress){$null}else{[IO.Path]::GetFullPath($Infobase).TrimEnd('\','/')}
    if($targetPath -and $managerPath -ieq $targetPath){throw 'Trust preparation requires a separate manager infobase'}
    if(!$ManagerExists){throw 'Dedicated manager infobase does not exist'}
    if($ExpectedVanessaSha256 -notmatch '^[a-fA-F0-9]{64}$'){throw 'ExpectedVanessaSha256 is required'}
    if($actualHash -ine $ExpectedVanessaSha256){throw 'Vanessa package changed; verify its source before preparing trust'}
    # POSIX BRE metacharacters only. Reject configuration line/delimiter injection.
    if($managerPath.IndexOfAny([char[]]'";') -ge 0 -or $managerPath.Contains("`r") -or $managerPath.Contains("`n")){throw 'Unsupported manager path'}
    $escaped=[regex]::Replace($managerPath,'[\\.\[\]*^$]',{param($m) '\'+$m.Value})
    $rule='[Ff]ile="'+$escaped+'[\\/]*".*'
    if(!$PlatformExists){throw 'Platform executable does not exist'}
    $conf=Join-Path ([IO.Path]::GetFullPath($PlatformBin)) 'conf\conf.cfg'
    # The version-local file can redirect platform settings to a shared directory.
    $chain=@();$seen=@{}
    for($depth=0;$depth -lt 4;$depth++) {
        if($seen.ContainsKey($conf)){throw 'Cyclic platform ConfLocation configuration'}
        $seen[$conf]=$true;$chain+=,$conf
        $source=& $ReadConfig $conf
        if($null -eq $source){break}
        $redirect=[regex]::Match($source,'(?m)^\s*ConfLocation\s*=([^\r\n]+)')
        if(!$redirect.Success){break}
        $location=& $ExpandLocation ($redirect.Groups[1].Value.Trim().Trim('"'))
        if(![IO.Path]::IsPathRooted($location)){throw 'ConfLocation must be an absolute directory'}
        $next=Join-Path ([IO.Path]::GetFullPath($location)) 'conf.cfg'
        if($next -ieq $conf){break}
        $conf=$next
        if($depth -eq 3){throw 'Too many platform ConfLocation redirects'}
    }

    $targets=@($conf)
    foreach($candidate in (@($chain)+@($userConf))) {
        if($targets -contains $candidate){continue}
        if((& $ReadConfig $candidate) -match '(?m)^\s*DisableUnsafeActionProtection\s*='){$targets+=,$candidate}
    }
    $updates=@()
    $priorRules=@(('[Ff]ile="'+$escaped+'"'),('[Ff]ile="'+$escaped+'[\\/]*"'),$rule)
    foreach($target in $targets) {
        $content=[string](& $ReadConfig $target)
        $matches=@([regex]::Matches($content,'(?m)^\s*DisableUnsafeActionProtection\s*=([^\r\n]*)'))
        if($matches.Count -gt 1){throw 'Multiple protection settings exist in '+$target+'; resolve ambiguity first'}
        $rules=@(if($matches.Count){$matches[0].Groups[1].Value.Split(';')|Where-Object {$_ -and $_ -cnotin $priorRules}})
        $rules+=,$rule
        $replacement='DisableUnsafeActionProtection='+($rules -join ';')
        if($matches.Count){$match=$matches[0];$updated=$content.Substring(0,$match.Index)+$replacement+$content.Substring($match.Index+$match.Length)}
        else{$updated=$content.TrimEnd()+"`r`n"+$replacement+"`r`n"}
        if($updated -cne $content){$updates+=,[pscustomobject]@{Path=$target;Before=$content;After=$updated}}
    }
    [pscustomobject]@{Manager=$managerPath;Hash=$ActualHash;Config=$conf;Targets=$targets;Rule=$rule;Updates=$updates}
}
function Invoke-MvTrustPlan($Plan,[bool]$Apply,[scriptblock]$Backup,[scriptblock]$Replace) {
    $backups=@()
    if($Apply){
        foreach($entry in $Plan.Updates){
            $path=& $Backup $entry
            if($path){$backups+=,$path}
        }
        foreach($entry in $Plan.Updates){& $Replace $entry}
    }
    $changed=$Plan.Updates.Count -gt 0
    [pscustomobject]@{Status=$(if(!$changed -or $Apply){'prepared'}else{'preview'});ManagerInfobase=$Plan.Manager;
        VanessaSha256=$Plan.Hash;ConfigurationFile=$Plan.Config;ConfigurationFiles=$Plan.Targets;Rule=$Plan.Rule;
        Backup=$(if($backups.Count){$backups[0]}else{$null});Backups=$backups;RestartRequired=($changed -and $Apply);
        Scope='All external processing inside this dedicated manager base only; target base unchanged'}
}

function Get-MvElapsed($value,[long]$now) {
    $paused=if($value.PausedSeconds){[long]$value.PausedSeconds}else{0}
    if($value.PausedSinceEpoch){$paused+=$now-[long]$value.PausedSinceEpoch}
    $now-[long]$value.StartedEpoch-$paused
}


function Set-MvClock($state,$RunId,$Action,[long]$now) {
        if(!$state -or !$RunId -or $state.RunId -cne $RunId -or $state.Status -ne 'running'){throw 'Run clock request is stale or the run is not running'}
        if($Action -eq 'PauseClock' -and !$state.PausedSinceEpoch){$state|Add-Member -NotePropertyName PausedSinceEpoch -NotePropertyValue $now -Force}
        if($Action -eq 'ResumeClock' -and $state.PausedSinceEpoch){
            $total=[long]$state.PausedSeconds+$now-[long]$state.PausedSinceEpoch
            $state|Add-Member -NotePropertyName PausedSeconds -NotePropertyValue $total -Force
            $state.PausedSinceEpoch=$null
        }

}

function Select-MvOwnedProcesses($state,$Processes) {
    if (!$state) { return @() }
    if([string]::IsNullOrWhiteSpace($state.ManagerMarker) -or [string]::IsNullOrWhiteSpace($state.ClientMarker)){
        throw 'Cannot verify process ownership without both markers'
    }
    $floor=if($null -ne $state.StartedEpoch){[DateTimeOffset]::FromUnixTimeSeconds([long]$state.StartedEpoch).LocalDateTime.AddSeconds(-1)}else{([datetime]$state.Started).ToLocalTime().AddSeconds(-1)}
    @($Processes | Where-Object {
        if ($_.Name -notin @('1cv8.exe','1cv8c.exe') -or !$_.CommandLine -or $_.CreationDate -lt $floor) { return $false }
        Test-MvProcessMarker $_.CommandLine $state.ManagerMarker $state.ClientMarker
    })
}

# The controller emits separately quoted whole arguments and rejects embedded quotes.
# Refuse ambiguous command lines. A marker in an unrelated option or a longer path is insufficient.
function Test-MvProcessMarker([string]$line,[string]$manager,[string]$client) {
    $line=$line.Trim()
    $tokens=[regex]::Matches($line,'\G\s*(?:"(?<arg>[^"]*)"|(?<arg>[^\s"]+))(?=\s|$)')
    if(!$tokens.Count -or $tokens[-1].Index+$tokens[-1].Length -ne $line.Length){return $false}
    $argv=@($tokens|ForEach-Object {$_.Groups['arg'].Value})
    $managerRole=$argv -icontains '/TestManager'
    $clientRole=$argv -icontains '/TestClient'
    for($i=0;$i+1 -lt $argv.Count;$i++){
        $value=$argv[$i+1]
        if($managerRole -and $argv[$i] -ieq '/C' -and
           ($value -ieq ('VAParams='+$manager) -or $value.EndsWith((';VAParams='+$manager),[StringComparison]::OrdinalIgnoreCase))){return $true}
        if($clientRole -and $argv[$i] -ieq '/Out' -and $value -ieq $client){return $true}
    }
    return $false
}


function Test-MvListener($port,$managerPid,$listeners) {
    if(!$port -or !@($listeners).Count){return $false}
    if(@($listeners|Where-Object {$_.OwningProcess -ne $managerPid}).Count){
        throw 'Manager MCP port belongs to another process; no foreign endpoint will be accepted'
    }
    return $true
}
function Get-MvStopScope($state,$requestedRun) {
    if(!$state -and $requestedRun){return 'absent'}
    if($requestedRun -and $state.RunId -cne $requestedRun){throw 'Stop request is stale; no foreign run was stopped'}
    return 'stop'
}
function Invoke-MvStopOwned($state,$effects) {
    $stopped=[Collections.Generic.HashSet[int]]::new()
    $deadline=(& $effects.Now).AddSeconds(5)
    do {
        $owned=@(& $effects.Owned $state)
        if(!$owned.Count){break}
        foreach($entry in ($owned|Sort-Object @{Expression={if($_.CommandLine -match '(?i)/TestManager\b'){0}else{1}}})){
            $current=& $effects.Lookup $entry.ProcessId
            if($current -and $current.ProcessId -eq $entry.ProcessId -and $current.CreationDate -eq $entry.CreationDate -and $current.CommandLine -ceq $entry.CommandLine){
                & $effects.Stop $entry.ProcessId
                $null=$stopped.Add([int]$entry.ProcessId)
            }
        }
        & $effects.Delay
    }while((& $effects.Now) -lt $deadline)
    if(@(& $effects.Owned $state).Count){throw 'Some processes owned by the preceding run are still active'}
    if($state -and $state.ManagerMarker){
        $file=[IO.Path]::GetFullPath($state.ManagerMarker)
        $expected=[IO.Path]::GetFullPath((Join-Path $state.RunDir 'va-params.json'))
        if($file -ieq $expected){& $effects.RemoveParams $file}
    }
    @($stopped)
}

function Quote-Argument([string]$value) {
    if($value.Contains('"') -or $value.Contains("`r") -or $value.Contains("`n")) { throw 'Unsupported quote or newline in launch argument' }
    '"'+$value+'"'
}


function Invoke-MvContinuation($state,$request,$observe,[scriptblock]$Continue) {
    if(!$state){throw 'No active run'}
    if(!$request.RunId -or !$request.CheckpointId -or !$request.Hash){throw 'Continue requires RunId, CheckpointId and ScreenshotSha256 from Status'}
    if($state.RunId -cne $request.RunId -or $state.CheckpointId -cne $request.CheckpointId){throw 'Stale run or checkpoint; inspect the current Status and screenshot'}
    if($state.Status -ne 'running'){throw 'Run is not awaiting continuation'}
    if((Get-MvElapsed $state $request.Now) -ge $state.TimeoutSeconds){throw 'Run has expired'}
    $proof=& $observe $state
    if($proof.Continued){throw 'Checkpoint has already been continued'}
    if(!$proof.HasEvidence){throw 'No visual checkpoint ready'}
    if($proof.AgeSeconds -ge 180){throw 'Visual checkpoint has expired'}
    if($proof.Hash -ine $request.Hash){throw 'Screenshot changed; inspect the current image'}
    if(!$proof.Owned){throw 'No active processes for this run'}
    & $Continue $state
}

function New-MvLaunchPlan {
    param($Params,$RunDir,$PlatformBin,$ManagerInfobase,$ManagerUserName,$Infobase,$ServerAddress,$ServerInfobase,
          $UserName,$TargetPassword,$VanessaEpf,$DebuggerUrl,$ManagerMcpPort,[bool]$VisualCheckpoint,$FeatureText)
    $interfaceOption=if(([IO.DirectoryInfo]$PlatformBin).Parent.Name -match '^8\.[5-9]\.'){ '/i85 ' }else{ '' }
    $featureCopy=$null
    $visualText=$null
        if($VisualCheckpoint){
            if(@($params.СписокФичДляВыполнения).Count -ne 1){throw 'VisualCheckpoint currently requires one selected feature'}
            if([regex]::Matches($featureText,'(?m)^\s*(Сценарий|Scenario):').Count -ne 1){throw 'VisualCheckpoint requires one ordinary scenario'}
            $checkpointLines=@(
                '  И я сохраняю скриншот "'+(Join-Path $runDir 'screenshots\checkpoint.png')+'"'
                '  И я создаю файл "'+(Join-Path $runDir 'checkpoint.ready')+'"'
                '    | "scenario-final-ui" |'
                '  И я жду существования файла "'+(Join-Path $runDir 'checkpoint.continue')+'" в течение 180 секунд'
            )
            $featureCopy=Join-Path $runDir 'visual-checkpoint.feature'
            $visualText=$featureText.TrimEnd()+[Environment]::NewLine+($checkpointLines -join [Environment]::NewLine)+[Environment]::NewLine
            $params.СписокФичДляВыполнения=@($featureCopy)
            $params|Add-Member -NotePropertyName 'КаталогФич' -NotePropertyValue $runDir -Force
            $params|Add-Member -NotePropertyName 'КаталогОтносительноКоторогоНадоСтроитьИерархию' -NotePropertyValue $runDir -Force
        }
        $paramsPath=Join-Path $runDir 'va-params.json'
        $clientLog=Join-Path $runDir 'testclient.log'
        foreach($client in $params.КлиентыТестирования){
            # The temporary /Out path is also an ownership marker, distinct for each run.
            $client.ДопПараметры=$interfaceOption+'/N '+(Quote-Argument $UserName)+' /Out '+(Quote-Argument $clientLog)
            if($TargetPassword){$client.ДопПараметры+=' /P '+(Quote-Argument $TargetPassword)}
            $client.ПутьКИнфобазе=if($ServerAddress){'Srvr="'+$ServerAddress+'";Ref="'+$ServerInfobase+'";'}else{'File="'+$Infobase+'";'}
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
            'ИспользоватьКомпонентуVanessaExt'=$true
            'ИспользоватьВнешнююКомпонентуДляСкриншотов'=$true
            'СпособСнятияСкриншотовВнешнейКомпонентой'=1
            'КаталогOutputjUnit'=(Join-Path $runDir 'junit')
            'КаталогВыгрузкиJUnit'=(Join-Path $runDir 'junit')
            'КаталогOutputСкриншоты'=(Join-Path $runDir 'screenshots')
            'ВыводитьСообщенияВФайл'=(Join-Path $runDir 'steps.log')
            'ПутьКФайлуДляВыгрузкиСтатусаВыполненияСценариев'=(Join-Path $runDir 'status.txt')
        }
        foreach($name in $overrides.Keys){$params|Add-Member -NotePropertyName $name -NotePropertyValue $overrides[$name] -Force}
        $startupFlags='StartFeaturePlayer;QuietInstallVanessaExt;DisableFirstRunHelper;ShowMainForm;'
        if($ManagerMcpPort){$startupFlags='runMcp;mcpPort='+$ManagerMcpPort+';'+$startupFlags}
        $command='ENTERPRISE '+$interfaceOption+'/F '+(Quote-Argument $ManagerInfobase)+' /N '+(Quote-Argument $ManagerUserName)+' /TestManager /DisableStartupDialogs /DisableStartupMessages /Execute '+(Quote-Argument $VanessaEpf)+' /C '+(Quote-Argument ($startupFlags+'VAParams='+$paramsPath))+' /Out '+(Quote-Argument (Join-Path $runDir 'va.log'))
    [pscustomobject]@{Arguments=$command;Parameters=$params;ParamsFile=$paramsPath;ClientLog=$clientLog;
        VisualFile=$featureCopy;VisualText=$visualText}
}
function Invoke-MvAfterPortCheck($port,[scriptblock]$Check,[scriptblock]$OnAvailable) {
    if($port){& $Check $port}
    & $OnAvailable
}
function Confirm-MvManagerStartup($state,$effects) {
    if(!$state.ManagerMcpPort){return}
    try {
        $deadline=(& $effects.Now).AddSeconds(15)
        while(!(& $effects.Ready $state)){
            if((& $effects.Now) -ge $deadline){throw 'Owned manager MCP did not become ready; inspect startup before retrying'}
            & $effects.Delay
        }
    }catch {
        $failure=$_
        $null=& $effects.Stop $state
        $state.Status='stopped'
        & $effects.Save $state
        throw $failure
    }
}
function Get-MvManagerStatus($state,[scriptblock]$Read) {
    $ready=$false
    $errorText=$null
    if($state.Status -eq 'running' -and $state.ManagerMcpPort){
        try{$ready=& $Read $state}catch{$errorText='Manager MCP port is owned by another process'}
    }
    [pscustomobject]@{Status=$(if($errorText){'failed'}else{$state.Status});RunId=$state.RunId;Ready=[bool]$ready;Error=$errorText}
}

function Read-MvState([scriptblock]$ReadText) {
    $text=& $ReadText
    if($null -eq $text){return $null}
    if([string]::IsNullOrWhiteSpace($text)){throw 'Empty controller state'}
    $record=ConvertFrom-Json -InputObject $text -ErrorAction Stop -NoEnumerate
    if(!($record -is [pscustomobject]) -or $record -is [array] -or [string]::IsNullOrWhiteSpace($record.Status)){
        throw 'Invalid controller state'
    }
    $record
}
function Test-MvWatchdog($state,$report,[int]$ownedCount,[long]$epoch) {
    return (!$report -and $ownedCount -gt 0 -and $null -ne $state.StartedEpoch -and (Get-MvElapsed $state $epoch) -gt $state.TimeoutSeconds)
}
