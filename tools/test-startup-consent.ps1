# SPDX-License-Identifier: AGPL-3.0-only
param([string]$Repository=(Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference='Stop'
# Compile the actual bundled C# adapter; do not execute the UIA script or send input.
$source=[IO.File]::ReadAllText((Join-Path $Repository 'bundles/com.codepilot1c.core/resources/bootstrap-consent.ps1'))
$start=$source.IndexOf(' Add-Type @"');$end=$source.IndexOf(' [void][StartupInput]::SetProcessDPIAware()')
if($start -lt 0 -or $end -le $start){throw 'Bundled helper compilation boundaries missing'}
Invoke-Expression $source.Substring($start,$end-$start)
$count=0
if(-not [StartupInput]::SafeInputState(0,[IntPtr]::Zero,$false)){throw 'Idle input was rejected'};$count++
if(-not [StartupInput]::SafeInputState(1,[IntPtr]::Zero,$false)){throw 'Caret blinking was treated as capture'};$count++
foreach($flag in @(2,4,8,16)){if([StartupInput]::SafeInputState($flag,[IntPtr]::Zero,$false)){throw 'Menu/move input was accepted'};$count++}
if([StartupInput]::SafeInputState(0,[IntPtr]1,$false)){throw 'Captured mouse input was accepted'};$count++
if([StartupInput]::SafeInputState(0,[IntPtr]::Zero,$true)){throw 'Held modifier/button was accepted'};$count++
if([IntPtr]::Size -eq 8){if([Runtime.InteropServices.Marshal]::SizeOf([type][StartupInput+Input]) -ne 40 -or [Runtime.InteropServices.Marshal]::SizeOf([type][StartupInput+GuiThread]) -ne 72){throw 'Windows x64 input ABI mismatch'};$count++}
# A coarse Pane hit must never permit activation inside an answer/action rectangle.
if([StartupInput]::OutsideActions(50,50,[double[]]@(40,40,30,20))){throw 'Overlapping answer rectangle accepted'};$count++
if(-not [StartupInput]::OutsideActions(10,10,[double[]]@(40,40,30,20))){throw 'Passive point outside action rejected'};$count++
if([StartupInput]::OutsideActions(50,50,[double[]]@(0,0,0,20))){throw 'Unknown action bounds accepted'};$count++
if([StartupInput]::OutsideActions([double]::NaN,50,[double[]]@())){throw 'Nonphysical point accepted'};$count++
'PASS: '+$count+' startup input contracts; no UI input dispatched'
