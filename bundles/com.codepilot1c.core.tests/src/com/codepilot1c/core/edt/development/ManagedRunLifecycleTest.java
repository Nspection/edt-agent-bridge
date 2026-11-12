// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
public class ManagedRunLifecycleTest {
 private void verify(boolean beforeReceipt)throws Exception {
  var lifecycle=RuntimeProcessLifecycle.global();lifecycle.begin();Path root=Files.createTempDirectory("managed-run-lifecycle-");
  Path script=root.resolve("controller.ps1");
  Files.writeString(script,"""
   param($Action,$Workspace,$RunId)
   $ErrorActionPreference='Stop'
   $state=Join-Path $Workspace 'child.json'
   if($Action -eq 'Start') {
    $p=Start-Process -FilePath (Join-Path $env:SYSTEMROOT 'System32/ping.exe') -ArgumentList '-n 120 127.0.0.1' -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $Workspace 'child.log') -RedirectStandardError (Join-Path $Workspace 'child.err')
    @{Id=$p.Id;StartedTicks=$p.StartTime.ToUniversalTime().Ticks;RunId=$RunId}|ConvertTo-Json|Set-Content ($state+'.tmp');[IO.File]::Move($state+'.tmp',$state)
    if(Test-Path (Join-Path $Workspace 'wait')){Start-Sleep -Seconds 25}
    @{Status='running';RunId=$RunId}|ConvertTo-Json
   } elseif($Action -eq 'Stop') {
    if(Test-Path $state){$s=Get-Content $state -Raw|ConvertFrom-Json;if($s.RunId -cne $RunId){throw 'Stale fixture cleanup'};$p=Get-Process -Id $s.Id -ErrorAction SilentlyContinue;if($p -and $p.StartTime.ToUniversalTime().Ticks -eq [long]$s.StartedTicks){Stop-Process -Id $p.Id};@{Status='stopped';RunId=$RunId}|ConvertTo-Json}else{@{Status='absent';RunId=$RunId}|ConvertTo-Json}
   }
   """);
  if(beforeReceipt)Files.writeString(root.resolve("wait"),"");
  var controller=new ManagedVanessaProcess(script,lifecycle.epoch());var pool=Executors.newSingleThreadExecutor();ProcessHandle child=null;
  try {
   Future<?> started=pool.submit(()->{try{controller.call("Start",root,Map.of("RunId","owned-fixture"));}catch(Exception failure){if(!beforeReceipt)throw new RuntimeException(failure);}});
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
   while(!Files.isRegularFile(root.resolve("child.json"))&&System.nanoTime()<deadline)Thread.sleep(20);
   assertTrue(Files.isRegularFile(root.resolve("child.json")));
   Map<?,?> receipt=new com.google.gson.Gson().fromJson(Files.readString(root.resolve("child.json")),Map.class);
   child=ProcessHandle.of(((Number)receipt.get("Id")).longValue()).orElseThrow();assertTrue(child.isAlive());
   if(!beforeReceipt)started.get(10,TimeUnit.SECONDS);
   var failures=lifecycle.shutdown();assertTrue(failures.toString(),failures.isEmpty());
   started.get(10,TimeUnit.SECONDS);
   child.onExit().get(5,TimeUnit.SECONDS);assertFalse(child.isAlive());
  } finally {
   if(child!=null&&child.isAlive()){child.destroyForcibly();child.onExit().get(5,TimeUnit.SECONDS);}pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS);lifecycle.shutdown();lifecycle.begin();
   try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList()){for(int attempt=0;;attempt++){try{Files.deleteIfExists(path);break;}catch(java.io.IOException busy){if(attempt>=39)throw busy;Thread.sleep(50);}}}}
  }
 }
 @Test public void managerSurvivesHelperButNotLifecycleShutdown()throws Exception{verify(false);}
 @Test public void shutdownBeforeStartupReceiptStillStopsOwnedManager()throws Exception{verify(true);}
 @Test public void changedControllerIsRejectedBeforeProcessResolution()throws Exception {
  Path root=Files.createTempDirectory("controller-integrity-");Path script=root.resolve("fixture.ps1");
  try{Files.writeString(script,"throw 'must never execute'");var controller=new ManagedVanessaProcess(script,RuntimeProcessLifecycle.global().epoch());Files.writeString(script,"throw 'changed'");var failure=assertThrows(IllegalStateException.class,()->controller.call("Status",root,Map.of()));assertTrue(failure.getMessage().contains("resource changed"));}
  finally{Files.deleteIfExists(script);Files.deleteIfExists(root);}
 }
}
