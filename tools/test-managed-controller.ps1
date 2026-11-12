# Independent controller contracts. All scenario observations and effects below are in memory.
# The final CLI smoke has a newly created, empty controller workspace.
param([string]$ControllerDirectory=$PSScriptRoot)
$ErrorActionPreference='Stop'
. (Join-Path $ControllerDirectory 'managed-vanessa-core.ps1')

$completed=[Collections.Generic.List[string]]::new()
function Check($name,[scriptblock]$scenario) {
    try{& $scenario}catch{throw ('Contract '+$name+': '+$_.Exception.Message)}
    $completed.Add($name)
}
function Expect($condition,$message) {if(!$condition){throw $message}}
function Reject([scriptblock]$action,$message) {
    $reason=$null
    try{& $action|Out-Null}catch{$reason=$_.Exception.Message}
    Expect ($null -ne $reason -and $reason.Contains($message)) "Expected rejection containing '$message'; observed '$reason'"
}

function New-CheckpointCase {
    @{
        State=[pscustomobject]@{RunId='dispatch-41';CheckpointId='view-7';Status='running';StartedEpoch=100;TimeoutSeconds=60}
        Request=@{RunId='dispatch-41';CheckpointId='view-7';Hash='image-A';Now=120}
        Proof=@{Continued=$false;HasEvidence=$true;AgeSeconds=12;Hash='image-A';Owned=$true}
        Events=[Collections.Generic.List[string]]::new()
    }
}
function Continue-Case($case) {
    Invoke-MvContinuation $case.State $case.Request {
        $case.Events.Add('observe')
        $case.Proof
    } {
        $case.Events.Add('continue')
        $case.Proof.Continued=$true
    }
}
$rejections=@(
    @{Id='01';Edit={param($c) $c.Request.Hash=''};Message='requires';Inspect=$false}
    @{Id='02';Edit={param($c) $c.Request.RunId='earlier'};Message='Stale';Inspect=$false}
    @{Id='03';Edit={param($c) $c.Request.CheckpointId='earlier'};Message='Stale';Inspect=$false}
    @{Id='04';Edit={param($c) $c.Proof.Hash='changed'};Message='Screenshot changed';Inspect=$true}
    @{Id='05';Edit={param($c) $c.State.Status='stopped'};Message='not awaiting';Inspect=$false}
    @{Id='06';Edit={param($c) $c.Request.Now=160};Message='Run has expired';Inspect=$false}
    @{Id='07';Edit={param($c) $c.Proof.AgeSeconds=180};Message='checkpoint has expired';Inspect=$true}
)
foreach($variant in $rejections){
    Check $variant.Id {
        $case=New-CheckpointCase
        & $variant.Edit $case
        Reject {Continue-Case $case} $variant.Message
        Expect (!$case.Events.Contains('continue')) 'Rejected checkpoint released gate'
        Expect ($case.Events.Contains('observe') -eq $variant.Inspect) 'Unexpected observation boundary'
    }
}
Check '08' {
    $c=New-CheckpointCase;Continue-Case $c
    Expect (($c.Events -join ',') -ceq 'observe,continue') 'Valid checkpoint did not release gate exactly once'
}
Check '09' {
    $c=New-CheckpointCase;Continue-Case $c
    Reject {Continue-Case $c} 'already been continued'
    Expect (@($c.Events|Where-Object {$_ -ceq 'continue'}).Count -eq 1) 'Duplicate gate effect'
}
Check '10' {Reject {Get-MvStopScope ([pscustomobject]@{RunId='current'}) 'obsolete'} 'stale'}
Check '11' {
    $seen=[Collections.Generic.List[string]]::new()
    try{$state=Read-MvState {'{'};$seen.Add('decoded')}catch{}
    Expect (!$seen.Count) 'Malformed controller state reached decision code'
}
Check '12' {
    $state=Read-MvState {$null}
    Expect ((Get-MvStopScope $state 'prior-run') -ceq 'absent') 'Missing run is not safely absent'
}
function New-StopCase {
    $now=[datetime]::new(2026,1,1,12,0,0,[DateTimeKind]::Utc)
    $dir='C:\ContractData\OneRun'
    $s=[pscustomobject]@{RunId='one';RunDir=$dir;StartedEpoch=[DateTimeOffset]::new($now).ToUnixTimeSeconds();
        ManagerMarker=(Join-Path $dir 'va-params.json');ClientMarker=(Join-Path $dir 'testclient.log')}
    $rows=@{}
    $rows[31]=[pscustomobject]@{ProcessId=31;Name='1cv8.exe';CommandLine=('/TestManager /C "StartFeaturePlayer;VAParams='+$s.ManagerMarker+'"');CreationDate=$now.ToLocalTime()}
    $rows[44]=[pscustomobject]@{ProcessId=44;Name='1cv8.exe';CommandLine='/TestManager different-run';CreationDate=$now.ToLocalTime()}
    @{State=$s;Rows=$rows;Now=$now;Effects=[Collections.Generic.List[string]]::new()}
}
function Stop-Case($world) {
    $io=@{
        Now={$world.Now}
        Delay={$world.Now=$world.Now.AddMilliseconds(50)}
        Owned={param($s) Select-MvOwnedProcesses $s @($world.Rows.Values)}
        Lookup={param($pidValue) $world.Rows[[int]$pidValue]}
        Stop={param($pidValue)
            $world.Effects.Add("stop-$pidValue")
            $world.Rows.Remove([int]$pidValue)
            if($pidValue -eq 31){
                $world.Rows[32]=[pscustomobject]@{ProcessId=32;Name='1cv8c.exe';CommandLine=('/TestClient /Out "'+$world.State.ClientMarker+'"');CreationDate=$world.Now.ToLocalTime()}
            }
        }
        RemoveParams={param($file) $world.Effects.Add("remove-$file")}
    }
    Invoke-MvStopOwned $world.State $io
}
Check '13' {
    $w=New-StopCase;$ids=@(Stop-Case $w)
    Expect ($ids.Count -eq 2 -and $ids -contains 31 -and $ids -contains 32 -and $w.Rows.ContainsKey(44)) 'Incomplete or foreign teardown'
    Expect ($w.Effects[0] -ceq 'stop-31' -and $w.Effects[1] -ceq 'stop-32') 'Late client did not follow manager'
}

function New-TrustCase {
    $f=@{Manager='C:\ContractData\Manager.A';Base='C:\ContractData\Target';Bin='C:\ContractData\Platform';
        Hash=('e'*64);User='C:\ContractData\User\conf.cfg';Files=@{};Events=[Collections.Generic.List[string]]::new()}
    $f.First=Join-Path $f.Bin 'conf\conf.cfg'
    $f.Shared='C:\ContractData\Shared\conf.cfg'
    $f.Files[$f.First]='ConfLocation=C:\ContractData\Shared'
    $f.Files[$f.Shared]='SystemLanguage=System'
    $f
}
function Plan-Trust($fixture) {
    $a=@{ManagerInfobase=$fixture.Manager;Infobase=$fixture.Base;ServerAddress=$null;ExpectedVanessaSha256=$fixture.Hash;
        ActualHash=('e'*64);ManagerExists=$true;PlatformExists=$true;PlatformBin=$fixture.Bin;UserConf=$fixture.User;
        ReadConfig={param($path) $fixture.Files[$path]};ExpandLocation={param($path) $path}}
    Get-MvTrustPlan @a
}
function Apply-Trust($fixture,$plan,$approved) {
    Invoke-MvTrustPlan $plan $approved {
        param($e)
        $fixture.Events.Add('backup')
        if($fixture.Files.ContainsKey($e.Path)){'memory-backup'}
    } {
        param($e)
        if([string]$fixture.Files[$e.Path] -cne $e.Before){throw 'Stale configuration contents'}
        $fixture.Events.Add('replace')
        $fixture.Files[$e.Path]=$e.After
    }
}
Check '14' {
    $f=New-TrustCase;$p=Plan-Trust $f;$result=Apply-Trust $f $p $false
    Expect ($result.Status -ceq 'preview' -and $result.ConfigurationFile -ieq $f.Shared -and !$f.Events.Count -and $f.Files[$f.Shared] -ceq 'SystemLanguage=System') 'Trust preview has effects or wrong redirect'
}
Check '15' {
    $f=New-TrustCase;$p=Plan-Trust $f;$old=$p.Rule.Substring(0,$p.Rule.Length-2)
    $f.Files[$f.First]+=[Environment]::NewLine+'DisableUnsafeActionProtection=keep;'+$old
    $f.Files[$f.User]='DisableUnsafeActionProtection='+$old
    $p=Plan-Trust $f;$r=Apply-Trust $f $p $true
    Expect ($r.Status -ceq 'prepared' -and $r.RestartRequired -and $r.Backups.Count -eq 3 -and $r.ConfigurationFiles.Count -eq 3) 'Preparation receipt missing layers or backups'
    Expect (($f.Events -join ',') -ceq 'backup,backup,backup,replace,replace,replace') 'Replacements preceded backups'
    $regex='^'+$r.Rule+'$'
    Expect (('File="'+$f.Manager+'";') -cmatch $regex -and ('File="'+$f.Manager+'\";') -cmatch $regex -and ('File="'+$f.Base+'";') -cnotmatch $regex) 'Manager rule too broad or too narrow'
    Expect ($f.Files[$f.First].Contains('keep;') -and $f.Files[$f.Shared].Contains('SystemLanguage=System')) 'Unrelated configuration lost'
    foreach($path in $r.ConfigurationFiles){
        $rules=([regex]::Match($f.Files[$path],'(?m)^\s*DisableUnsafeActionProtection\s*=([^\r\n]*)')).Groups[1].Value.Split(';')
        Expect (@($rules|Where-Object {$_ -ceq $r.Rule}).Count -eq 1 -and $rules -cnotcontains $old) 'Manager rule not unique'
    }
}
Check '16' {
    $f=New-TrustCase;$null=Apply-Trust $f (Plan-Trust $f) $true
    $f.Events.Clear();$r=Apply-Trust $f (Plan-Trust $f) $true
    Expect (!$r.RestartRequired -and !$f.Events.Count) 'Idempotent trust preparation rewrote configuration'
}
Check '17' {$f=New-TrustCase;$f.Hash='d'*64;Reject {Plan-Trust $f} 'package changed'}
Check '18' {$f=New-TrustCase;$f.Base=$f.Manager;Reject {Plan-Trust $f} 'separate manager'}
function Paused-State {
    [pscustomobject]@{Status='running';RunId='one';StartedEpoch=100;PausedSinceEpoch=600;PausedSeconds=0;TimeoutSeconds=600}
}
Check '19' {
    $s=Paused-State
    Expect ((Get-MvElapsed $s 900) -eq 500 -and !(Test-MvWatchdog $s $null 1 900)) 'Pause counted toward watchdog'
}
Check '20' {$s=Paused-State;Set-MvClock $s 'one' 'PauseClock' 900;Expect ($s.PausedSinceEpoch -eq 600) 'Second pause reset timestamp'}
Check '21' {$s=Paused-State;Set-MvClock $s 'one' 'ResumeClock' 900;Expect (!$s.PausedSinceEpoch -and $s.PausedSeconds -eq 300 -and (Get-MvElapsed $s 900) -eq 500) 'Resume lost elapsed pause'}
Check '22' {$s=Paused-State;Reject {Set-MvClock $s 'other' 'PauseClock' 900} 'stale'}
function Launch-Case {
    param($port=0,[switch]$Server,[switch]$NewPlatform,[switch]$Visual)
    $params=[pscustomobject]@{
        КлиентыТестирования=@([pscustomobject]@{ДопПараметры='';ПутьКИнфобазе=''})
        СписокФичДляВыполнения=@('C:\ContractData\example.feature');ВыполнитьСценарии=$false
    }
    $a=@{Params=$params;RunDir='C:\ContractData\Run';PlatformBin=$(if($NewPlatform){'C:\ContractData\8.5.1.1522\bin'}else{'C:\ContractData\bin'})
        ManagerInfobase='C:\ContractData\Manager';ManagerUserName='M';Infobase='C:\ContractData\Target'
        ServerAddress=$(if($Server){'host:1541'});ServerInfobase=$(if($Server){'Warehouse'});UserName='U'
        VanessaEpf='C:\ContractData\package.epf';DebuggerUrl='http://localhost:1550';ManagerMcpPort=$port
        VisualCheckpoint=$Visual.IsPresent;FeatureText="Функционал: Форма"+[Environment]::NewLine+'Сценарий: Просмотр'}
    New-MvLaunchPlan @a
}
Check '23' {
    $p=(Launch-Case).Parameters
    Expect ($p.ВыполнитьСценарии -and $p.ИспользоватьКомпонентуVanessaExt -and $p.ИспользоватьВнешнююКомпонентуДляСкриншотов -and $p.ДелатьОтчетВФорматеjUnit -and $p.ОтчетJUnit.КаталогВыгрузкиJUnit) 'Managed scenario overrides are incomplete'
}
Check '24' {$a=(Launch-Case).Arguments;Expect ($a.Contains('DisableFirstRunHelper') -and !$a.Contains('/i85')) 'Unexpected default interface/startup arguments'}
Check '25' {Expect (!(Launch-Case).Arguments.Contains('runMcp')) 'Manager MCP enabled by default'}
Check '26' {$a=(Launch-Case -port 19600).Arguments;Expect ($a.Contains('runMcp;mcpPort=19600;StartFeaturePlayer;') -and $a.Contains('VAParams=')) 'MCP port/feature parameters missing'}
Check '27' {
    $effects=[Collections.Generic.List[string]]::new()
    Reject {Invoke-MvAfterPortCheck 19600 {throw 'occupied'} {$effects.Add('prepare-launch')}} 'occupied'
    Expect (!$effects.Count) 'Occupied port reached launch preparation'
}
function Foreign-Port($record) {Test-MvListener $record.ManagerMcpPort $record.ManagerPid @([pscustomobject]@{OwningProcess=32})}
Check '28' {
    $s=[pscustomobject]@{Status='running';RunId='one';ManagerMcpPort=19600;ManagerPid=31}
    $log=[Collections.Generic.List[string]]::new()
    $effects=@{Now={[datetime]::UtcNow};Ready={param($r) Foreign-Port $r};Stop={$log.Add('cleanup')};Save={param($r) $log.Add($r.Status)}}
    Reject {Confirm-MvManagerStartup $s $effects} 'another process'
    Expect (($log -join ',') -ceq 'cleanup,stopped' -and $s.Status -ceq 'stopped') 'Foreign listener did not tear down the owned session'
}
Check '29' {
    $s=[pscustomobject]@{Status='running';RunId='one';ManagerMcpPort=19600;ManagerPid=31}
    $m=Get-MvManagerStatus $s {param($r) Foreign-Port $r}
    Expect ($m.RunId -ceq 'one' -and $m.Status -ceq 'failed' -and $m.Error -and !$m.Ready) 'Foreign endpoint status lost guarded identity'
}
Check '30' {
    $client=(Launch-Case -Server).Parameters.КлиентыТестирования[0]
    Expect ($client.ПутьКИнфобазе -ceq 'Srvr="host:1541";Ref="Warehouse";' -and !$client.ДопПараметры.Contains('/i85')) 'Server connection formatting changed'
}
Check '31' {
    $w=New-StopCase;$w.Rows.Clear();$null=Stop-Case $w
    Expect (($w.Effects -join ',') -ceq ('remove-'+$w.State.ManagerMarker)) 'Private parameters not released at stop'
    $w.State.ManagerMarker='C:\Elsewhere\va-params.json';$w.Effects.Clear();$null=Stop-Case $w
    Expect (!$w.Effects.Count) 'Non-owned parameter path released'
}
Check '32' {Expect ((Launch-Case -Server -NewPlatform).Arguments.Contains('/i85')) '8.5 interface argument missing'}
Check '33' {
    $p=Launch-Case -Visual
    Expect ($p.Parameters.КаталогФич -ceq 'C:\ContractData\Run' -and $p.Parameters.КаталогОтносительноКоторогоНадоСтроитьИерархию -ceq $p.Parameters.КаталогФич -and $p.Parameters.СписокФичДляВыполнения[0] -ceq $p.VisualFile -and $p.VisualText.Contains('checkpoint.continue')) 'Minimal visual template has wrong generated feature'
}
if($completed.Count -ne 33 -or ($completed -join ',') -ne ((1..33|ForEach-Object {$_.ToString('00')}) -join ',')){throw 'Incomplete controller contract inventory'}

# Fault injection changes observed data only. Production decisions and effects remain the subject.
Reject {Read-MvState {''}} 'Empty controller state'
foreach($invalid in @('null','[]','23','{}')){Reject {Read-MvState {$invalid}} 'Invalid controller state'}
$markers=(New-StopCase).State
foreach($command in @(('/TestManager /C "StartFeaturePlayer;VAParams='+$markers.ManagerMarker+'.old"'),
                       ('/TestClient /Out "'+$markers.ClientMarker+'.old"'),
                       ('/TestClient /Comment "'+$markers.ClientMarker+'"'))){
    Expect (!(Test-MvProcessMarker $command $markers.ManagerMarker $markers.ClientMarker)) 'Similar foreign marker accepted'
}
foreach($field in @('Owned','HasEvidence')){
    $c=New-CheckpointCase;$c.Proof[$field]=$false
    $reason=if($field -eq 'Owned'){'No active processes'}else{'No visual checkpoint'}
    Reject {Continue-Case $c} $reason
    Expect (!$c.Events.Contains('continue')) 'Incomplete evidence released a gate'
}
$w=New-StopCase
$stale=$w.Rows[31];$replacement=$stale.PSObject.Copy();$replacement.CreationDate=$replacement.CreationDate.AddSeconds(1)
$effects=@{Now={$w.Now};Delay={$w.Now=$w.Now.AddSeconds(6)};Owned={,$stale};Lookup={$replacement};
    Stop={$w.Effects.Add('unexpected-stop')};RemoveParams={$w.Effects.Add('unexpected-remove')}}
Reject {Invoke-MvStopOwned $w.State $effects} 'still active'
Expect (!$w.Effects.Count) 'A reused PID was stopped or its parameters released'
$w.State.ManagerMarker='';Reject {Select-MvOwnedProcesses $w.State @($stale)} 'both markers'
$s=Paused-State;$s.PausedSinceEpoch=$null
Expect (Test-MvWatchdog $s $null 1 900) 'An active unpaused run did not expire'
$f=New-TrustCase;$p=Plan-Trust $f
Reject {Invoke-MvTrustPlan $p $true {throw 'backup failure'} {$f.Events.Add('unexpected-replace')}} 'backup failure'
Expect (!$f.Events.Count) 'Backup failure allowed a replacement'
Expect (Test-MvListener 19600 31 @([pscustomobject]@{OwningProcess=31})) 'Correct manager listener rejected'
Expect (!(Test-MvListener 19600 31 @())) 'Missing listener accepted'

# Integration smoke is limited to three read/reject/absent paths in an empty directory.
$workspace=[IO.Directory]::CreateTempSubdirectory('edt-managed-contract-')
$marker=[IO.Path]::Combine($workspace.FullName,'contract-owner')
$identity=[guid]::NewGuid().ToString('N')
[IO.File]::WriteAllText($marker,$identity)
try{
    $cli=Join-Path $ControllerDirectory 'run-vanessa-debug.ps1'
    $result=& $cli -Workspace $workspace.FullName -Action Status|ConvertFrom-Json
    Expect ($result.Status -ceq 'idle') 'Empty workspace did not report idle'
    Reject {& $cli -Workspace $workspace.FullName -Action Continue -RunId previous} 'No active run'
    $result=& $cli -Workspace $workspace.FullName -Action Stop -RunId previous|ConvertFrom-Json
    Expect ($result.Status -ceq 'absent' -and !$result.StoppedPids.Count) 'Missing run did not report absent'
}finally{
    if($workspace.LinkTarget -or [IO.File]::ReadAllText($marker) -cne $identity){throw 'Changed contract workspace; preserve for inspection'}
    $testsDir=Join-Path $workspace.FullName 'tests';$qaDir=Join-Path $testsDir 'qa';$control=Join-Path $qaDir 'managed-vanessa'
    foreach($dir in @($testsDir,$qaDir,$control)){if([IO.Directory]::Exists($dir) -and ([IO.DirectoryInfo]$dir).LinkTarget){throw 'Linked fixture directory; preserve it'}}
    $lockPath=Join-Path $control 'controller.lock';if([IO.File]::Exists($lockPath)){[IO.File]::Delete($lockPath)}
    foreach($dir in @($control,$qaDir,$testsDir)){if([IO.Directory]::Exists($dir)){[IO.Directory]::Delete($dir,$false)}}
    [IO.File]::Delete($marker);$workspace.Delete()
}
Write-Output ('PASS: '+$completed.Count+' managed controller contracts and 3 empty-workspace CLI checks')
