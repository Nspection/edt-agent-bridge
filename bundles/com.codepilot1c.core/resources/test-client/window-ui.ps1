# SPDX-License-Identifier: AGPL-3.0-only
# Fixed header/menu/known-dialog backend. No caller-supplied coordinates or messages.
$ErrorActionPreference='Stop'
[Console]::InputEncoding=[Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$sent=$false;$phase='admission';$nonce='';$request=$null;$dpi=[IntPtr]::Zero
function Fail([string]$code){throw [InvalidOperationException]::new($code)}
function Rid($e){return (@($e.GetRuntimeId()) -join ',')}
function Same($a,$b){return (($a | ConvertTo-Json -Compress -Depth 20) -ceq ($b | ConvertTo-Json -Compress -Depth 20))}
function Check-Process {
 $p=[Diagnostics.Process]::GetProcessById([int]$request.pid)
 if($p.HasExited -or ([DateTimeOffset]::new($p.StartTime.ToUniversalTime())).ToUnixTimeMilliseconds() -ne [DateTimeOffset]::Parse($request.started).ToUnixTimeMilliseconds()){Fail 'process_identity_changed'}
}
function Deadline {if($clock.ElapsedMilliseconds -gt 8000){Fail 'window_ui_deadline'}}
function Rect($e){$b=$e.Current.BoundingRectangle;return @([int][Math]::Round($b.X),[int][Math]::Round($b.Y),[int][Math]::Round($b.Width),[int][Math]::Round($b.Height))}
function Element($h){$e=[System.Windows.Automation.AutomationElement]::FromHandle($h);if($e.Current.ProcessId -ne [int]$request.pid){Fail 'foreign_uia_element'};return $e}
function Handles($v){return [IntPtr]::new([Convert]::ToInt64($v.Substring(2),16))}
function Name-Condition([string]$name){return [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::NameProperty,$name)}
function Type-Condition($type){return [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::ControlTypeProperty,$type)}
function Frame {
 $found=@()
 foreach($w in [BridgeWindowUi]::Windows([uint32]$request.pid)){
  Deadline
  if($w.Owner -ne '0x0' -or $w.Class -cnotmatch '^(V8Window.*|V8TopLevelFrameSDI)$' -or $w.Minimized){continue}
  $h=Handles $w.Handle;$root=Element $h
  $matches=$root.FindAll([System.Windows.Automation.TreeScope]::Descendants,(Name-Condition $request.source_title))
  if($matches.Count -gt 16){Fail 'caption_scope_ambiguous'}
  foreach($caption in $matches){
   if($caption.Current.ControlType -ne [System.Windows.Automation.ControlType]::Pane -or $caption.Current.IsOffscreen){continue}
   $container=[System.Windows.Automation.TreeWalker]::RawViewWalker.GetParent($caption)
   if($null -eq $container -or $container.Current.ProcessId -ne [int]$request.pid -or (Rid $container) -ceq (Rid $root)){continue}
   $headers=@();$close=@()
   foreach($e in $container.FindAll([System.Windows.Automation.TreeScope]::Children,(Type-Condition ([System.Windows.Automation.ControlType]::Button)))){
    if($e.Current.Name -cin @('Меню','Menu','Действия формы','Form actions')){$headers+=,$e}
    if($e.Current.Name -cin @('Закрыть','Close')){$close+=,$e}
   }
   if($headers.Count -ne 1 -or $close.Count -ne 1){continue}
   $header=$headers[0]
   if($header.Current.IsOffscreen -or $header.Current.ProcessId -ne [int]$request.pid){continue}
   $ref=[ordered]@{window=$w;root_rid=(Rid $root);caption_rid=(Rid $caption);container_rid=(Rid $container);header_rid=(Rid $header);header_name=$header.Current.Name;header_rect=(Rect $header)}
   $found+=,@{reference=$ref;root=$root;header=$header;handle=$h}
  }
 }
 if($found.Count -ne 1){Fail 'source_header_absent_or_ambiguous'}
 return $found[0]
}
function Owned-Popup($w,$frame){
 if($w.Handle -ceq $frame.reference.window.Handle -or !$w.Popup -or $w.Thread -ne $frame.reference.window.Thread){return $false}
 $h=Handles $w.Handle
 for($i=0;$i -lt 8;$i++){
  $h=[BridgeWindowUi]::GetWindow($h,4)
  if($h -eq $frame.handle){return $true}
  if($h -eq [IntPtr]::Zero){return $false}
  $info=[BridgeWindowUi]::Info($h)
  if($info.Process -ne [uint32]$request.pid -or $info.Thread -ne $frame.reference.window.Thread){return $false}
 }
 return $false
}
function Menu($frame){
 $found=@()
 foreach($w in [BridgeWindowUi]::Windows([uint32]$request.pid)){
  Deadline
  if(!(Owned-Popup $w $frame) -or $w.Class -cnotmatch '^(V8Window20000[.]0|V8CommandPanelPopup.*)$'){continue}
  $e=Element (Handles $w.Handle);$menus=$e.FindAll([System.Windows.Automation.TreeScope]::Descendants,(Type-Condition ([System.Windows.Automation.ControlType]::Menu)))
  $visible=@($menus | Where-Object {!$_.Current.IsOffscreen})
  if($visible.Count -ne 1){Fail 'menu_provider_ambiguous'}
  $menu=$visible[0];$items=@();$children=$menu.FindAll([System.Windows.Automation.TreeScope]::Children,[System.Windows.Automation.Condition]::TrueCondition)
  if($children.Count -gt 64){Fail 'menu_item_limit'}
  foreach($item in $children){
   Deadline;$c=$item.Current
   if($c.ControlType -eq [System.Windows.Automation.ControlType]::Separator){continue}
   if($c.ControlType -ne [System.Windows.Automation.ControlType]::MenuItem){Fail 'unsupported_menu_provider_child'}
   if($c.ProcessId -ne [int]$request.pid){Fail 'foreign_menu_item'}
   if($c.IsOffscreen){continue}
   $rect=Rect $item
   if($rect[2] -le 0 -or $rect[3] -le 0 -or $rect[0] -lt $w.X -or $rect[1] -lt $w.Y -or ($rect[0]+$rect[2]) -gt ($w.X+$w.Width) -or ($rect[1]+$rect[3]) -gt ($w.Y+$w.Height)){Fail 'menu_item_visibility_unproven'}
   if(!$c.Name -or $c.Name.Length -gt 256){Fail 'menu_item_name_unproven'}
   $items+=,[ordered]@{name=$c.Name;enabled=$c.IsEnabled;runtime_id=(Rid $item);rectangle=$rect}
  }
  if($items.Count -lt 1){Fail 'empty_visible_menu'}
  $found+=,[ordered]@{window=$w;runtime_id=(Rid $menu);items=$items}
 }
 if($found.Count -gt 1){Fail 'multiple_visible_menus'}
 if($found.Count -eq 1){return $found[0]}
 return $null
}
function Dialog($frame){
 if($request.dialog_title -cnotin @('Настройка формы','Form customization','Customize Form','Настройка списка','List settings')){Fail 'unsupported_system_dialog'}
 $found=@()
 foreach($w in [BridgeWindowUi]::Windows([uint32]$request.pid)){
  Deadline
  if(!(Owned-Popup $w $frame) -or ($w.Class -cnotlike 'V8Window0.*' -and $w.Class -cne 'V8TopLevelFrameSDIsec') -or $w.Class -ceq 'V8Window0.0'){continue}
  $e=Element (Handles $w.Handle)
  if($e.Current.Name -cne $request.dialog_title -or $e.Current.ControlType -ne [System.Windows.Automation.ControlType]::Window -or $e.Current.IsOffscreen){continue}
  $found+=,[ordered]@{window=$w;runtime_id=(Rid $e);title=$e.Current.Name}
 }
 if($found.Count -ne 1){Fail 'system_dialog_absent_or_ambiguous'}
 if([BridgeWindowUi]::Active($frame.reference.window.Thread) -cne $found[0].window.Handle){Fail 'system_dialog_not_active'}
 return $found[0]
}
function Inspect {
 Check-Process;Deadline;$frame=Frame
 $menu=if($request.op -ceq 'dialog'){$null}else{Menu $frame}
 $dialog=if($request.op -ceq 'dialog'){Dialog $frame}else{$null}
 return @{frame=$frame;reference=[ordered]@{frame=$frame.reference;menu=$menu;dialog=$dialog}}
}
function Send-Close($target,$frame){
 $h=Handles $target.window.Handle
 if($h -eq $frame.handle -or !(Same ([BridgeWindowUi]::Info($h)) $target.window)){Fail 'close_target_changed'}
 Check-Process;$script:phase='dispatch';$script:sent=$true;$result=[IntPtr]::Zero
 if([BridgeWindowUi]::SendMessageTimeout($h,16,[IntPtr]::Zero,[IntPtr]::Zero,2,1000,[ref]$result) -eq [IntPtr]::Zero){Fail 'close_delivery_unknown'}
}
try {
 # Parent assigns kill-on-close Job before sending these bytes.
 $raw=[Console]::In.ReadToEnd();if([Text.Encoding]::UTF8.GetByteCount($raw) -gt 65536){Fail 'request_limit'}
 $request=$raw | ConvertFrom-Json;$nonce=[string]$request.nonce
 if($nonce -cnotmatch '^[a-f0-9]{32}$' -or $request.op -cnotin @('menu','dialog') -or $request.mode -cnotin @('probe','open','close') -or !$request.source_title -or $request.source_title.Length -gt 1024 -or !$request.started -or $request.pid -le 0){Fail 'invalid_request'}
 if($request.op -ceq 'dialog' -and $request.mode -ceq 'open'){Fail 'dialog_open_not_supported'}
 Add-Type -AssemblyName UIAutomationClient
 Add-Type -AssemblyName UIAutomationTypes
 Add-Type @"
using System;using System.Text;using System.Collections.Generic;using System.Runtime.InteropServices;
public class BridgeWindowUi {
 public delegate bool Callback(IntPtr w,IntPtr d);
 [StructLayout(LayoutKind.Sequential)] public struct POINT {public int X;public int Y;}
 [StructLayout(LayoutKind.Sequential)] public struct RECT {public int X;public int Y;public int R;public int B;}
 [StructLayout(LayoutKind.Sequential)] public struct GUI {public int Size;public int Flags;public IntPtr Active;public IntPtr Focus;public IntPtr Capture;public IntPtr MenuOwner;public IntPtr MoveSize;public IntPtr Caret;public RECT CaretRect;}
 public class Window {public string Handle;public uint Process;public uint Thread;public string Owner;public string Class;public bool Visible;public bool Minimized;public bool Enabled;public bool Popup;public int X;public int Y;public int Width;public int Height;}
 [DllImport("user32")] public static extern bool EnumWindows(Callback callback,IntPtr data);
 [DllImport("user32")] public static extern bool EnumChildWindows(IntPtr parent,Callback callback,IntPtr data);
 [DllImport("user32")] public static extern uint GetWindowThreadProcessId(IntPtr w,out uint process);
 [DllImport("user32")] public static extern bool IsWindowVisible(IntPtr w);
 [DllImport("user32")] public static extern bool IsWindowEnabled(IntPtr w);
 [DllImport("user32")] public static extern bool IsIconic(IntPtr w);
 [DllImport("user32")] public static extern IntPtr GetWindow(IntPtr w,uint command);
 [DllImport("user32")] static extern int GetWindowLong(IntPtr w,int index);
 [DllImport("user32",CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr w,StringBuilder text,int count);
 [DllImport("user32")] static extern bool GetWindowRect(IntPtr w,out RECT rectangle);
 [DllImport("user32")] static extern bool GetClientRect(IntPtr w,out RECT rectangle);
 [DllImport("user32")] public static extern bool ScreenToClient(IntPtr w,ref POINT point);
 [DllImport("user32")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
 [DllImport("user32")] public static extern IntPtr GetForegroundWindow();
 [DllImport("user32")] static extern bool GetGUIThreadInfo(uint thread,ref GUI info);
 [DllImport("user32")] public static extern IntPtr SendMessageTimeout(IntPtr w,uint message,IntPtr wp,IntPtr lp,uint flags,uint timeout,out IntPtr result);
 public static string Hex(IntPtr w){return "0x"+w.ToInt64().ToString("x");}
 public static string Active(uint thread){var g=new GUI();g.Size=Marshal.SizeOf(typeof(GUI));if(!GetGUIThreadInfo(thread,ref g))throw new InvalidOperationException("thread_state_unavailable");return Hex(g.Active);}
 public static Window Info(IntPtr h){uint pid;uint tid=GetWindowThreadProcessId(h,out pid);RECT r;var name=new StringBuilder(256);if(tid==0||!GetWindowRect(h,out r)||GetClassName(h,name,256)==0)throw new InvalidOperationException("window_identity_unavailable");return new Window{Handle=Hex(h),Process=pid,Thread=tid,Owner=Hex(GetWindow(h,4)),Class=name.ToString(),Visible=IsWindowVisible(h),Minimized=IsIconic(h),Enabled=IsWindowEnabled(h),Popup=((uint)GetWindowLong(h,-16)&0x80000000)!=0,X=r.X,Y=r.Y,Width=r.R-r.X,Height=r.B-r.Y};}
 public static Window[] Windows(uint expected){var result=new List<Window>();Exception failure=null;EnumWindows((w,d)=>{try{uint pid;GetWindowThreadProcessId(w,out pid);if(pid==expected&&IsWindowVisible(w)){if(result.Count>=16)throw new InvalidOperationException("window_inventory_limit");result.Add(Info(w));}}catch(Exception e){failure=e;return false;}return true;},IntPtr.Zero);if(failure!=null)throw failure;return result.ToArray();}
 public static bool ValidPoint(IntPtr root,int x,int y){RECT r;if(!GetClientRect(root,out r)||x<=r.X||y<=r.Y||x>=r.R||y>=r.B||x>32767||y>32767)return false;return true;}
 public static bool CoveredChild(IntPtr root,int x,int y){bool covered=false;EnumChildWindows(root,(w,d)=>{RECT r;if(IsWindowVisible(w)&&GetWindowRect(w,out r)&&x>=r.X&&x<r.R&&y>=r.Y&&y<r.B){covered=true;return false;}return true;},IntPtr.Zero);return covered;}
}
"@
 $clock=[Diagnostics.Stopwatch]::StartNew();$beforeForeground=[BridgeWindowUi]::GetForegroundWindow();$dpi=[BridgeWindowUi]::SetThreadDpiAwarenessContext([IntPtr]::new(-4));if($dpi -eq [IntPtr]::Zero){Fail 'dpi_context_unavailable'}
 $phase='probe';$state=Inspect
 if($request.mode -cne 'probe'){
  if(!$request.reference -or !(Same $state.reference $request.reference)){Fail 'ui_reference_changed'}
  if($request.mode -ceq 'open'){
   if($state.reference.menu){Fail 'menu_already_open'}
   $frame=$state.frame;$header=$frame.header
   if(!$frame.reference.window.Enabled -or !$header.Current.IsEnabled -or [BridgeWindowUi]::Active($frame.reference.window.Thread) -cne $frame.reference.window.Handle){Fail 'source_frame_not_active_or_enabled'}
   $rect=Rect $header;$point=[BridgeWindowUi+POINT]::new();$point.X=$rect[0]+[int]($rect[2]/2);$point.Y=$rect[1]+[int]($rect[3]/2)
   if($rect[2] -le 2 -or $rect[3] -le 2 -or [BridgeWindowUi]::CoveredChild($frame.handle,$point.X,$point.Y)){Fail 'header_point_unproven'}
   if(![BridgeWindowUi]::ScreenToClient($frame.handle,[ref]$point) -or ![BridgeWindowUi]::ValidPoint($frame.handle,$point.X,$point.Y)){Fail 'header_outside_client'}
   $fresh=Inspect;if(!(Same $fresh.reference $state.reference)){Fail 'ui_changed_before_input'};Check-Process
   $phase='dispatch';$sent=$true;$result=[IntPtr]::Zero;$position=[IntPtr]::new(($point.Y -shl 16) -bor ($point.X -band 65535))
   if([BridgeWindowUi]::SendMessageTimeout($frame.handle,513,[IntPtr]::new(1),$position,2,1000,[ref]$result) -eq [IntPtr]::Zero){Fail 'mouse_down_delivery_unknown'}
   if(!(Same ([BridgeWindowUi]::Info($frame.handle)) $frame.reference.window) -or (Rid (Element $frame.handle)) -cne $frame.reference.root_rid){Fail 'root_changed_after_down'}
   if([BridgeWindowUi]::SendMessageTimeout($frame.handle,514,[IntPtr]::Zero,$position,2,1000,[ref]$result) -eq [IntPtr]::Zero){Fail 'mouse_up_delivery_unknown'}
   $phase='postcondition';$state=Inspect;if(!$state.reference.menu){Fail 'menu_open_not_proven'}
   if(!(Same $state.reference.frame $request.reference.frame)){Fail 'source_changed_after_open'}
  }else{
   $target=if($request.op -ceq 'menu'){$state.reference.menu}else{$state.reference.dialog}
   if(!$target){Fail 'close_target_absent'}
   $fresh=Inspect;if(!(Same $fresh.reference $state.reference)){Fail 'ui_changed_before_close'}
   Send-Close $target $state.frame
   $phase='postcondition';$until=[Diagnostics.Stopwatch]::StartNew();$handle=Handles $target.window.Handle
   while([BridgeWindowUi]::IsWindowVisible($handle) -and $until.ElapsedMilliseconds -lt 1000){Start-Sleep -Milliseconds 20}
   Check-Process;if([BridgeWindowUi]::IsWindowVisible($handle)){Fail 'close_not_proven'}
   $state=if($request.op -ceq 'menu'){Inspect}else{@{reference=$request.reference}}
   if($request.op -ceq 'menu' -and $state.reference.menu){Fail 'unexpected_menu_after_close'}
  }
 }
 $reply=[ordered]@{nonce=$nonce;op=$request.op;mode=$request.mode;backend='windows_header_ui';success=$true;phase=$phase;action_dispatched=$sent;reference=$state.reference;foreground_changed=([BridgeWindowUi]::GetForegroundWindow() -ne $beforeForeground);scope='visible_top_level_menu_provider';submenus_enumerated=$false}
}catch{
 $reason='window_ui_provider_failed';$message=[string]$_.Exception.Message;if($message -cmatch '^[a-z][a-z0-9_]{2,100}$'){$reason=$message}
 $reply=[ordered]@{nonce=$nonce;op=$request.op;mode=$request.mode;backend='windows_header_ui';success=$false;phase=$phase;action_dispatched=$sent;reason=$reason}
}finally{if($dpi -ne [IntPtr]::Zero){[void][BridgeWindowUi]::SetThreadDpiAwarenessContext($dpi)}}
[Console]::Out.Write(($reply | ConvertTo-Json -Compress -Depth 20))
