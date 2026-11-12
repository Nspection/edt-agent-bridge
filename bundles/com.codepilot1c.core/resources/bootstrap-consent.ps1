# SPDX-License-Identifier: AGPL-3.0-only
# Private typed startup adapter. No caller coordinates, keyboard, or arbitrary controls.
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding=[System.Text.UTF8Encoding]::new($false)
$script:inputAttempted=$false
$script:visibilityLease=$null
$script:temporarilyRaised=$false
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
 [DllImport("user32.dll",EntryPoint="SendMessageTimeoutW",SetLastError=true)] public static extern IntPtr SendMessageTimeout(IntPtr window,uint message,UIntPtr wParam,IntPtr lParam,uint flags,uint timeout,out UIntPtr result);
 [StructLayout(LayoutKind.Sequential)] public struct Rect {public int left,top,right,bottom;}
 [StructLayout(LayoutKind.Sequential)] public struct GuiThread {public uint size,flags;public IntPtr active,focus,capture,menuOwner,moveSize,caret;public Rect caretRect;}
 [StructLayout(LayoutKind.Sequential)] public struct Point {public int x,y;public Point(int x,int y){this.x=x;this.y=y;}}
 [DllImport("user32.dll")] public static extern bool GetGUIThreadInfo(uint thread,ref GuiThread info);
 [DllImport("user32.dll")] public static extern short GetAsyncKeyState(int key);
 [DllImport("user32.dll")] public static extern IntPtr WindowFromPoint(Point point);
 [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr window,IntPtr after,int x,int y,int width,int height,uint flags);
 public static bool SafeInputState(uint flags,IntPtr capture,bool keysDown){return capture==IntPtr.Zero&&(flags&0x1e)==0&&!keysDown;}
 public static bool OutsideActions(double x,double y,double[] rectangles){
  if(Double.IsNaN(x)||Double.IsInfinity(x)||Double.IsNaN(y)||Double.IsInfinity(y)||rectangles.Length%4!=0)return false;
  for(int i=0;i<rectangles.Length;i+=4){for(int n=0;n<4;n++)if(Double.IsNaN(rectangles[i+n])||Double.IsInfinity(rectangles[i+n]))return false;
   if(rectangles[i+2]<=0||rectangles[i+3]<=0)return false;
   if(x>=rectangles[i]&&x<=rectangles[i]+rectangles[i+2]&&y>=rectangles[i+1]&&y<=rectangles[i+1]+rectangles[i+3])return false;
  }
  return true;
 }
 public static bool InputIdle(){
  var info=new GuiThread();info.size=(uint)Marshal.SizeOf(typeof(GuiThread));
  if(!GetGUIThreadInfo(0,ref info))return false;
  bool down=false;foreach(int key in new int[]{1,2,4,5,6,16,17,18,91,92})if((GetAsyncKeyState(key)&0x8000)!=0)down=true;
  return SafeInputState(info.flags,info.capture,down);
 }
 [DllImport("user32.dll",EntryPoint="GetWindowLongPtrW")] private static extern IntPtr GetWindowLongPtr(IntPtr window,int index);
 [DllImport("user32.dll")] private static extern IntPtr GetWindow(IntPtr window,uint command);
 private delegate bool Visitor(IntPtr window,IntPtr data);
 [DllImport("user32.dll")] private static extern bool EnumWindows(Visitor visitor,IntPtr data);
 public static string[] VisibilityOwners(IntPtr window,uint pid,bool wasTopmost){
  var owners=new System.Collections.Generic.List<string>();var seen=new System.Collections.Generic.HashSet<IntPtr>();seen.Add(window);
  for(IntPtr owner=GetWindow(window,4);owner!=IntPtr.Zero;owner=GetWindow(owner,4)){
   uint actual;GetWindowThreadProcessId(owner,out actual);
   if(owners.Count>=8||!seen.Add(owner)||actual!=pid||(!wasTopmost&&Topmost(owner)))throw new InvalidOperationException("visibility_owner_changed");
   owners.Add("0x"+owner.ToInt64().ToString("x"));
  }
  var windows=new System.Collections.Generic.List<IntPtr>();EnumWindows(delegate(IntPtr candidate,IntPtr ignored){windows.Add(candidate);return true;},IntPtr.Zero);
  var family=new System.Collections.Generic.HashSet<IntPtr>();family.Add(window);bool added=true;
  while(added){added=false;foreach(IntPtr candidate in windows){if(!family.Contains(candidate)&&family.Contains(GetWindow(candidate,4))){
   uint actual;GetWindowThreadProcessId(candidate,out actual);if(actual!=pid||(!wasTopmost&&Topmost(candidate))||family.Count>=64)throw new InvalidOperationException("visibility_owned_popup_present");family.Add(candidate);added=true;
  }}}
  return owners.ToArray();
 }
 public static bool Topmost(IntPtr window){return (GetWindowLongPtr(window,-20).ToInt64()&8)!=0;}
 public static bool RaiseOwnWindow(IntPtr window){return SetWindowPos(window,new IntPtr(-1),0,0,0,0,0x4013);}
 public static bool Recipient(IntPtr window,double x,double y){return InputIdle()&&WindowFromPoint(new Point((int)x,(int)y))==window;}
 public static bool ActivateOwnWindow(IntPtr window) {
  // Cross-queue activation is asynchronous. Synchronize only with this owned window;
  // never attach input queues or synthesize keys into another foreground app.
  SetForegroundWindow(window);
  UIntPtr result;
  if(SendMessageTimeout(window,0,UIntPtr.Zero,IntPtr.Zero,0x22,1000,out result)==IntPtr.Zero)return false; // WM_NULL, ABORTIFHUNG|ERRORONEXIT
  for(int attempt=0;attempt<20;attempt++){
   if(GetForegroundWindow()==window)return true;
   System.Threading.Thread.Sleep(50);
  }
  return GetForegroundWindow()==window;
 }
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
  $queue=[Collections.Queue]::new();$queue.Enqueue([pscustomobject]@{node=$root;depth=0});$rows=@();$buttons=@();$texts=@();$textElements=@();$actions=@()
  while ($queue.Count -gt 0) {
   if ($rows.Count -ge 128) {return $null}
   $entry=$queue.Dequeue();$node=$entry.node;$v=$node.Current
   if ($v.ProcessId -ne $requestData.pid -or $v.IsPassword) {return $null}
   if ($v.Name.Length -gt 8192) {return $null}
   $rows+= [ordered]@{id=@($node.GetRuntimeId());type=$v.ControlType.ProgrammaticName;name=$v.Name;enabled=$v.IsEnabled;offscreen=$v.IsOffscreen}
   if ($v.ControlType -eq [System.Windows.Automation.ControlType]::Text -and -not $v.IsOffscreen -and $v.Name) {$texts+= $v.Name;$textElements+=$node}
   if ($v.ControlType -eq [System.Windows.Automation.ControlType]::Button -and -not $v.IsOffscreen) {$buttons+= $node}
   if(-not $v.IsOffscreen -and $v.ControlType -notin @([System.Windows.Automation.ControlType]::Window,[System.Windows.Automation.ControlType]::Pane,[System.Windows.Automation.ControlType]::Group,[System.Windows.Automation.ControlType]::Text,[System.Windows.Automation.ControlType]::Image)){$actions+=$node}
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
  return @{window_id=$windowId;handle=$handle;kind=$kind;text=$text;hash=$hash;identity_hash=(HashText $tree);generation=$generation;property=$property;root=$root;text_element=$textElements[0];actions=$actions;buttons=$buttons}

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
 function ActivationOutsideActions($question,$point){
  if(-not $question.text_element.Current.BoundingRectangle.Contains($point)){return $false}
  $rectangles=[Collections.Generic.List[double]]::new()
  foreach($action in $question.actions){$rect=$action.Current.BoundingRectangle;$rectangles.Add($rect.X);$rectangles.Add($rect.Y);$rectangles.Add($rect.Width);$rectangles.Add($rect.Height)}
  return [StartupInput]::OutsideActions($point.X,$point.Y,$rectangles.ToArray())
 }
 function PassiveHit($body,$hit,$root) {
  if($body.Current.IsKeyboardFocusable -or $null -ne [System.Windows.Automation.TreeWalker]::ControlViewWalker.GetFirstChild($body)){return $false}
  foreach($pattern in $body.GetSupportedPatterns()){if($pattern.Id -in @(10000,10001,10002,10003,10004,10005,10010,10015,10016,10017)){return $false}}
  if([System.Windows.Automation.Automation]::Compare($body,$hit)){return $true}
  $parent=[System.Windows.Automation.TreeWalker]::ControlViewWalker.GetParent($body)
  for($depth=0;$depth -lt 8 -and $null -ne $parent;$depth++){
   if($parent.Current.ProcessId -ne $requestData.pid -or $parent.Current.ControlType -notin @([System.Windows.Automation.ControlType]::Pane,[System.Windows.Automation.ControlType]::Window)){return $false}
   if($parent.Current.IsKeyboardFocusable){return $false}
   foreach($pattern in $parent.GetSupportedPatterns()){if($pattern.Id -in @(10000,10001,10002,10003,10004,10005,10010,10015,10016,10017)){return $false}}
   if([System.Windows.Automation.Automation]::Compare($parent,$hit)){return $true}
   if([System.Windows.Automation.Automation]::Compare($parent,$root)){break}
   $parent=[System.Windows.Automation.TreeWalker]::ControlViewWalker.GetParent($parent)
  }
  return $false
 }
 $script:stage='validate_question'
 if ($requestData.operation -ne 'answer' -or $second.hash -ne $requestData.expected_question_sha256 -or $requestData.answer -notin @('allow','deny')) {throw 'stale_or_invalid_answer'}
 $name=if ($requestData.answer -eq 'allow') {'Да'} else {'Нет'}
 $selected=@($second.buttons | Where-Object {$_.Current.Name.Trim() -ceq $name});if ($selected.Count -ne 1) {throw 'ambiguous_button'}
 $script:stage='activate_own_dialog'
 $foregroundReady=[StartupInput]::ActivateOwnWindow($second.handle)
 if(-not $foregroundReady){
  CheckOwner
  if(-not [StartupInput]::InputIdle()){throw 'input_state_busy'}
  $script:visibilityLease=@{handle=$second.handle;property=$second.property;generation=$second.generation;wasTopmost=[StartupInput]::Topmost($second.handle)}
  $owners=@([StartupInput]::VisibilityOwners($second.handle,[uint32]$requestData.pid,$script:visibilityLease.wasTopmost))
  $saved=[ordered]@{window_id=$second.window_id;pid=$requestData.pid;started=$requestData.started;property=$second.property;generation=$second.generation;was_topmost=$script:visibilityLease.wasTopmost;owners=@($owners)}
  $leasePath=[string]$requestData.visibility_lease_path;$stagePath=$leasePath+'.stage'
  if(Test-Path -LiteralPath $leasePath){throw 'visibility_restore_pending'}
  [IO.File]::WriteAllText($stagePath,($saved|ConvertTo-Json -Depth 4 -Compress),[Text.UTF8Encoding]::new($false));[IO.File]::Move($stagePath,$leasePath)
  $script:temporarilyRaised=$true
  if(-not [StartupInput]::RaiseOwnWindow($second.handle)){throw 'foreground_unavailable'}
  $activationPoint=$null
  for($attempt=0;$attempt -lt 10 -and $null -eq $activationPoint;$attempt++){
   $fresh=ReadQuestion $second.window_id
   if($null -eq $fresh -or $fresh.hash -ne $second.hash -or $fresh.identity_hash -ne $second.identity_hash){throw 'question_changed'}
   $rect=$fresh.text_element.Current.BoundingRectangle
   if($rect.Width -le 0 -or $rect.Height -le 0){throw 'point_target_changed'}
   foreach($fy in @(.5,.25,.75)){foreach($fx in @(.5,.25,.75)){
    $candidate=[System.Windows.Point]::new([Math]::Floor($rect.X+$rect.Width*$fx),[Math]::Floor($rect.Y+$rect.Height*$fy));$hit=[System.Windows.Automation.AutomationElement]::FromPoint($candidate)
    if((ActivationOutsideActions $fresh $candidate) -and [StartupInput]::Recipient($second.handle,$candidate.X,$candidate.Y) -and (PassiveHit $fresh.text_element $hit $fresh.root)){$activationPoint=$candidate;break}
   };if($null -ne $activationPoint){break}}
   if($null -eq $activationPoint){[Threading.Thread]::Sleep(50)}
  }
  if($null -eq $activationPoint){throw 'foreground_unavailable'}
  CheckOwner
  $fresh=ReadQuestion $second.window_id
  if($null -eq $fresh -or $fresh.hash -ne $second.hash -or $fresh.identity_hash -ne $second.identity_hash){throw 'question_changed'}
  $hit=[System.Windows.Automation.AutomationElement]::FromPoint($activationPoint)
  if(-not (ActivationOutsideActions $fresh $activationPoint) -or -not [StartupInput]::Recipient($second.handle,$activationPoint.X,$activationPoint.Y) -or -not (PassiveHit $fresh.text_element $hit $fresh.root)){throw 'point_target_changed'}
  $script:inputAttempted=$true;$activationEvents=[StartupInput]::Click($activationPoint.X,$activationPoint.Y)
  if($activationEvents -ne 3){throw 'activation_input_incomplete'}
  $foregroundReady=[StartupInput]::ActivateOwnWindow($second.handle)
  if(-not $foregroundReady){throw 'foreground_unavailable'}
 }
 # Mouse activation does not require prior foreground ownership. The physical
 # recipient must instead be the exact owned HWND AND the verified UIA button.
 $recipientVerified=$false
 for($attempt=0;$attempt -lt 10;$attempt++){
  $bounds=$selected[0].Current.BoundingRectangle;if($bounds.Width -le 0 -or $bounds.Height -le 0){throw 'point_target_changed'};$point=[System.Windows.Point]::new([Math]::Floor($bounds.X+$bounds.Width/2),[Math]::Floor($bounds.Y+$bounds.Height/2));$hit=[System.Windows.Automation.AutomationElement]::FromPoint($point)
  if([System.Windows.Automation.Automation]::Compare($selected[0],$hit) -and [StartupInput]::Recipient($second.handle,$point.X,$point.Y)){$recipientVerified=$true;break}
  [Threading.Thread]::Sleep(50)
 }
 if(-not $recipientVerified){if(-not $foregroundReady){throw 'foreground_unavailable'};throw 'point_target_changed'}
 $script:stage='refresh_before_input'
 $third=ReadQuestion $second.window_id
 if ($null -eq $third -or $third.hash -ne $second.hash -or $third.identity_hash -ne $second.identity_hash) {throw 'question_changed'}
 CheckOwner
 $script:stage='final_hit_test'
 if ([StartupInput]::GetProp($second.handle,$second.property).ToInt64().ToString('x') -ne $second.generation) {throw 'window_generation_changed'}
 $pointNow=$third.buttons | Where-Object {$_.Current.Name.Trim() -ceq $name}
 $boundsFinal=$pointNow.Current.BoundingRectangle;if($boundsFinal.Width -le 0 -or $boundsFinal.Height -le 0){throw 'point_target_changed'};$pointFinal=[System.Windows.Point]::new([Math]::Floor($boundsFinal.X+$boundsFinal.Width/2),[Math]::Floor($boundsFinal.Y+$boundsFinal.Height/2))
 if ($pointFinal.X -ne $point.X -or $pointFinal.Y -ne $point.Y) {throw 'point_moved'}
 $finalHit=[System.Windows.Automation.AutomationElement]::FromPoint($pointFinal)
 if (-not [System.Windows.Automation.Automation]::Compare($pointNow,$finalHit)) {throw 'point_target_changed'}
 if(-not [StartupInput]::Recipient($second.handle,$pointFinal.X,$pointFinal.Y)){throw 'point_target_changed'}
 $foregroundBeforeInput=[StartupInput]::GetForegroundWindow() -eq $second.handle
 $script:stage='input_dispatch'
 $script:inputAttempted=$true;$count=[StartupInput]::Click($point.X,$point.Y)
 $script:stage='observe_departure'
 $departed=$false
 for ($i=0;$i -lt 30;$i++) {try {$current=ReadQuestion $second.window_id;if ($null -eq $current -or $current.text -cne $second.text) {$departed=$true;break}} catch {break};[Threading.Thread]::Sleep(100)}
 @{success=($count -eq 3 -and $departed);temporarily_raised_question=$script:temporarilyRaised;action_dispatched=$true;inserted_events=$count;activation_events=$activationEvents;foreground_before_input=$foregroundBeforeInput;natural_mouse_activation=(-not $foregroundBeforeInput);release_only_after_partial=($count -eq 2);dialog_departed=$departed;ready=$false;outcome_unknown=($count -ne 3 -or -not $departed);question_kind=$second.kind;question_sha256=$second.hash} | ConvertTo-Json -Compress
} catch {
 $exception=$_.Exception;for($depth=0;$depth -lt 4 -and $null -ne $exception.InnerException;$depth++){$exception=$exception.InnerException}
 $reason=$_.Exception.GetType().Name
 if ($exception.Message -in @('identity_changed','executable_changed','artifact_changed','consent_revoked','window_owner_changed','question_changed','stale_or_invalid_answer','ambiguous_button','foreground_unavailable','point_target_changed','point_moved','foreground_changed','window_generation_unavailable','window_generation_changed','input_state_busy','activation_input_incomplete','visibility_owner_changed','visibility_owned_popup_present','visibility_restore_pending')) {$reason=$exception.Message}
 @{success=$false;temporarily_raised_question=$script:temporarilyRaised;stage=$script:stage;state=if ($script:inputAttempted) {'outcome_unknown'} else {'rejected'};action_dispatched=$script:inputAttempted;outcome_unknown=$script:inputAttempted;reason=$reason;provider_exception=$exception.GetType().Name} | ConvertTo-Json -Compress
}
