# SPDX-License-Identifier: AGPL-3.0-only
# Private typed startup adapter. No caller coordinates, keyboard, or arbitrary controls.
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding=[System.Text.UTF8Encoding]::new($false)
$script:inputAttempted=$false
$script:stage="provider_preflight"
try {
 Add-Type -AssemblyName UIAutomationClient
 Add-Type -AssemblyName UIAutomationTypes
 Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class StartupInput {
 [StructLayout(LayoutKind.Sequential)] public struct Mouse {public int x,y;public uint data,flags,time;public UIntPtr extra;}
 [StructLayout(LayoutKind.Sequential)] public struct Input {public uint type;public Mouse mouse;}
 [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr window);
 [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
 [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr window);
 [DllImport("user32.dll",CharSet=CharSet.Unicode,EntryPoint="GetPropW")] public static extern IntPtr GetProp(IntPtr window,string name);
 [DllImport("user32.dll",CharSet=CharSet.Unicode,EntryPoint="SetPropW")] public static extern bool SetProp(IntPtr window,string name,IntPtr value);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr window,out uint pid);
 [DllImport("user32.dll")] public static extern int GetSystemMetrics(int index);
 [DllImport("user32.dll")] public static extern uint SendInput(uint count,Input[] inputs,int size);
 public static uint Click(double px,double py) {
  int left=GetSystemMetrics(76),top=GetSystemMetrics(77),width=GetSystemMetrics(78),height=GetSystemMetrics(79);
  if(width<2||height<2||px<left||py<top||px>=left+width||py>=top+height)throw new InvalidOperationException("bounds");
  var input=new Input[3];input[0].mouse.x=(int)Math.Round((px-left)*65535/(width-1));input[0].mouse.y=(int)Math.Round((py-top)*65535/(height-1));input[0].mouse.flags=0xC001;
  input[1].mouse.flags=2;input[2].mouse.flags=4;
  uint count=SendInput(3,input,Marshal.SizeOf(typeof(Input)));
  if(count==2){var release=new Input[1];release[0].mouse.flags=4;SendInput(1,release,Marshal.SizeOf(typeof(Input)));}
  return count;
 }
}
"@
 [void][StartupInput]::SetProcessDPIAware()
 $requestData=[Console]::In.ReadToEnd() | ConvertFrom-Json
 function HashText([string]$text) {return ([BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($text)))).Replace('-','').ToLowerInvariant()}
 function CheckOwner {
  $policy=[IO.File]::ReadAllText($requestData.policy_path) | ConvertFrom-Json
  if ($policy.revision -ne $requestData.policy_revision -or $policy.policy -notin @('once','remember')) {throw 'consent_revoked'}
  $process=Get-Process -Id $requestData.pid
  if ($process.StartTime.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ') -ne ([DateTimeOffset]::Parse($requestData.started).UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ss.fffZ'))) {throw 'identity_changed'}
  if ($process.Path -ne $requestData.executable) {throw 'executable_changed'}
  if ($requestData.capability -eq 'native_external') {
   if ((HashBytes $requestData.artifact_path) -ne $requestData.artifact_sha256 -or (HashBytes $requestData.native_path) -ne $requestData.native_sha256) {throw 'artifact_changed'}
  }
 }
 function HashBytes([string]$path) {return ([BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash([IO.File]::ReadAllBytes($path)))).Replace('-','').ToLowerInvariant()}
 function ReadQuestion([string]$windowId) {
  CheckOwner
  $handle=[IntPtr]([Convert]::ToInt64($windowId.Replace('0x',''),16))
  $windowOwner=[uint32]0
  if (-not [StartupInput]::IsWindow($handle)) {return $null}
  [void][StartupInput]::GetWindowThreadProcessId($handle,[ref]$windowOwner)
  if ($windowOwner -ne $requestData.pid) {throw 'window_owner_changed'}
  $root=[System.Windows.Automation.AutomationElement]::FromHandle($handle)
  if ($root.Current.ProcessId -ne $requestData.pid -or $root.Current.IsOffscreen -or $root.Current.ClassName -notlike 'V8Window*') {return $null}
  $walker=[System.Windows.Automation.TreeWalker]::ControlViewWalker
  $queue=[Collections.Queue]::new();$queue.Enqueue([pscustomobject]@{node=$root;depth=0});$rows=@();$buttons=@();$texts=@()
  while ($queue.Count -gt 0) {
   if ($rows.Count -ge 128) {return $null}
   $entry=$queue.Dequeue();$node=$entry.node;$v=$node.Current
   if ($v.ProcessId -ne $requestData.pid -or $v.IsPassword) {return $null}
   if ($v.Name.Length -gt 8192) {return $null}
   $rows+= [ordered]@{id=@($node.GetRuntimeId());type=$v.ControlType.ProgrammaticName;name=$v.Name;enabled=$v.IsEnabled;offscreen=$v.IsOffscreen}
   if ($v.ControlType -eq [System.Windows.Automation.ControlType]::Text -and -not $v.IsOffscreen -and $v.Name) {$texts+= $v.Name}
   if ($v.ControlType -eq [System.Windows.Automation.ControlType]::Button -and -not $v.IsOffscreen) {$buttons+= $node}
   if ($entry.depth -ge 8) {if ($null -ne $walker.GetFirstChild($node)) {return $null};continue}
   $child=$walker.GetFirstChild($node);$siblings=0
   while ($null -ne $child) {if (++$siblings -gt 128 -or $queue.Count -ge 128) {return $null};$queue.Enqueue([pscustomobject]@{node=$child;depth=($entry.depth+1)});$child=$walker.GetNextSibling($child)}
  }
  if ($texts.Count -ne 1 -or $buttons.Count -ne 2) {return $null}
  $labels=@($buttons | ForEach-Object {$_.Current.Name.Trim()});if (-not ($labels -contains 'Да') -or -not ($labels -contains 'Нет') -or @($buttons | Where-Object {-not $_.Current.IsEnabled}).Count -gt 0) {return $null}
  $text=$texts[0];$kind=$null
  if ($requestData.capability -eq 'native_external') {
   if (-not $text.Contains('"'+$requestData.artifact_path+'"') -or -not $text.Contains('"'+$requestData.module_caption+'"')) {return $null}
   if ($text.StartsWith("Предупреждение безопасности`n`nОткрывается ") -and $text.EndsWith('Разрешить открывать данный файл?')) {$kind='external_epf_trust'}
   elseif ($text.StartsWith("Предупреждение безопасности`n`nМодуль ") -and $text.Contains('выполняет подключение исполнимого бинарного файла "e1cib/tempstorage/') -and $text.EndsWith('Разрешить подключать исполнимые бинарные файлы для данного модуля?')) {$kind='native_component_for_module'}
  } elseif ($requestData.capability -eq 'testclient' -and $text.StartsWith('Предупреждение безопасности')) {$kind='unverified_testing_security'}
  if ($null -eq $kind) {return $null}
  # UIA runtime IDs are apartment-local; keep them only for in-helper freshness.
  # A broker-owned HWND property survives helper exit but not DestroyWindow/HWND reuse.
  $property='EDT.AgentBridge.StartupConsent.'+[IO.Path]::GetFileNameWithoutExtension($requestData.policy_path)
  $marker=[StartupInput]::GetProp($handle,$property)
  if ($marker -eq [IntPtr]::Zero) {
   $random=[byte[]]::new(8);[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($random);$nonce=([BitConverter]::ToInt64($random,0) -band [long]::MaxValue);if ($nonce -eq 0) {$nonce=1}
   if (-not [StartupInput]::SetProp($handle,$property,[IntPtr]$nonce)) {throw 'window_generation_unavailable'}
   $marker=[StartupInput]::GetProp($handle,$property);if ($marker -eq [IntPtr]::Zero) {throw 'window_generation_unavailable'}
  }
  $semantic=@($rows | ForEach-Object {[ordered]@{type=$_.type;name=$_.name;enabled=$_.enabled;offscreen=$_.offscreen}}) | ConvertTo-Json -Depth 6 -Compress
  $tree=$rows | ConvertTo-Json -Depth 6 -Compress
  $generation=$marker.ToInt64().ToString('x')
  $hash=HashText ($requestData.pid.ToString()+'|'+$requestData.started+'|'+$windowId+'|'+$generation+'|'+$kind+'|'+$semantic)
  return @{window_id=$windowId;handle=$handle;kind=$kind;text=$text;hash=$hash;identity_hash=(HashText $tree);generation=$generation;property=$property;buttons=$buttons}

 }
 function Inspect {
  $found=@();foreach ($windowId in $requestData.windows) {try {$q=ReadQuestion $windowId;if ($null -ne $q) {$found+= $q}} catch {throw}}
  if ($found.Count -ne 1) {return $null};return $found[0]
 }
 $script:stage="read_first"
 $first=Inspect
 if ($null -eq $first) {@{success=$false;state='manual_required';action_dispatched=$false} | ConvertTo-Json -Compress;exit 0}
 $script:stage="read_second"
 $second=ReadQuestion $first.window_id
 if ($null -eq $second -or $first.hash -ne $second.hash -or $first.identity_hash -ne $second.identity_hash) {throw 'question_changed'}
 if ($second.kind -eq 'unverified_testing_security') {@{success=$false;state='manual_required';platform_question_present=$true;reason='testing_security_template_not_live_verified';action_dispatched=$false} | ConvertTo-Json -Compress;exit 0}
 if ($requestData.operation -eq 'inspect') {
  $publicText=$second.text -replace 'e1cib/tempstorage/[^"\s]+','[temporary-storage-address-redacted]'
  @{success=$true;question_kind=$second.kind;question_sha256=$second.hash;text=$publicText;window_id=$second.window_id;buttons=@('allow','deny');provider='guarded_uia_point_input';foreground_input_required=$true} | ConvertTo-Json -Depth 5 -Compress;exit 0
 }
 $script:stage='validate_question'
 if ($requestData.operation -ne 'answer' -or $second.hash -ne $requestData.expected_question_sha256 -or $requestData.answer -notin @('allow','deny')) {throw 'stale_or_invalid_answer'}
 $name=if ($requestData.answer -eq 'allow') {'Да'} else {'Нет'}
 $selected=@($second.buttons | Where-Object {$_.Current.Name.Trim() -ceq $name});if ($selected.Count -ne 1) {throw 'ambiguous_button'}
 $script:stage='activate_own_dialog'
 $selected[0].SetFocus()
 [void][StartupInput]::SetForegroundWindow($second.handle)
 if ([StartupInput]::GetForegroundWindow() -ne $second.handle) {throw 'foreground_unavailable'}
 $point=$selected[0].GetClickablePoint();$hit=[System.Windows.Automation.AutomationElement]::FromPoint($point)
 if (-not [System.Windows.Automation.Automation]::Compare($selected[0],$hit)) {throw 'point_target_changed'}
 $script:stage='refresh_before_input'
 $third=ReadQuestion $second.window_id
 if ($null -eq $third -or $third.hash -ne $second.hash -or $third.identity_hash -ne $second.identity_hash) {throw 'question_changed'}
 CheckOwner
 $script:stage='final_hit_test'
 if ([StartupInput]::GetProp($second.handle,$second.property).ToInt64().ToString('x') -ne $second.generation) {throw 'window_generation_changed'}
 $pointNow=$third.buttons | Where-Object {$_.Current.Name.Trim() -ceq $name}
 $pointFinal=$pointNow.GetClickablePoint()
 if ($pointFinal.X -ne $point.X -or $pointFinal.Y -ne $point.Y) {throw 'point_moved'}
 $finalHit=[System.Windows.Automation.AutomationElement]::FromPoint($pointFinal)
 if (-not [System.Windows.Automation.Automation]::Compare($pointNow,$finalHit)) {throw 'point_target_changed'}
 if ([StartupInput]::GetForegroundWindow() -ne $second.handle) {throw 'foreground_changed'}
 $script:stage='input_dispatch'
 $script:inputAttempted=$true;$count=[StartupInput]::Click($point.X,$point.Y)
 $script:stage='observe_departure'
 $departed=$false
 for ($i=0;$i -lt 30;$i++) {try {$current=ReadQuestion $second.window_id;if ($null -eq $current -or $current.text -cne $second.text) {$departed=$true;break}} catch {break};[Threading.Thread]::Sleep(100)}
 @{success=($count -eq 3 -and $departed);action_dispatched=$true;inserted_events=$count;release_only_after_partial=($count -eq 2);dialog_departed=$departed;ready=$false;outcome_unknown=($count -ne 3 -or -not $departed);question_kind=$second.kind;question_sha256=$second.hash} | ConvertTo-Json -Compress
} catch {
 $reason=$_.Exception.GetType().Name
 if ($_.Exception.Message -in @('identity_changed','executable_changed','artifact_changed','consent_revoked','window_owner_changed','question_changed','stale_or_invalid_answer','ambiguous_button','foreground_unavailable','point_target_changed','point_moved','foreground_changed','window_generation_unavailable','window_generation_changed')) {$reason=$_.Exception.Message}
 @{success=$false;stage=$script:stage;state=if ($script:inputAttempted) {'outcome_unknown'} else {'rejected'};action_dispatched=$script:inputAttempted;outcome_unknown=$script:inputAttempted;reason=$reason} | ConvertTo-Json -Compress
}
