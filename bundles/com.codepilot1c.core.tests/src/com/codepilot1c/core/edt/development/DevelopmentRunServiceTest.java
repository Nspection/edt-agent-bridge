package com.codepilot1c.core.edt.development;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Test;
public class DevelopmentRunServiceTest {
    static class Fake implements DevelopmentRunService.Backend {
        final ConcurrentMap<Path,String> ids=new ConcurrentHashMap<>();final AtomicInteger starts=new AtomicInteger(),stops=new AtomicInteger(),step=new AtomicInteger(),continued=new AtomicInteger();
        final AtomicBoolean paused=new AtomicBoolean(),completed=new AtomicBoolean(),visual=new AtomicBoolean(),updating=new AtomicBoolean();final List<String> actions=new CopyOnWriteArrayList<>();
        final CountDownLatch updateEntered=new CountDownLatch(1),updateRelease=new CountDownLatch(1);int tests=1;volatile NullProgressMonitor monitor;
        public void validate(DevelopmentRunService.Profile p){}
        public void update(DevelopmentRunService.Profile p,NullProgressMonitor m) throws Exception {monitor=m;updateEntered.countDown();updateRelease.await();if(m.isCanceled()){throw new CancellationException();}}
        public Map<String,Object> debug(DevelopmentRunService.Profile p){return Map.of("debug_server_urls",List.of("http://localhost:1550"));}
        public boolean suspended(DevelopmentRunService.Profile p){return paused.get();}
        public Map<String,Object> managed(String action,Path workspace,Map<String,String> args) {
            actions.add(action);
            if(action.equals("Start")){starts.incrementAndGet();ids.put(workspace,args.get("RunId"));}
            if(action.equals("Stop") && ids.containsKey(workspace)){stops.incrementAndGet();}
            if(action.equals("Continue")){if(!"hash".equals(args.get("ScreenshotSha256"))){throw new IllegalArgumentException("Screenshot changed");}continued.incrementAndGet();visual.set(false);}
            String status=completed.get()?"passed":"running";
            Map<String,Object> out=new LinkedHashMap<>();out.put("Status",status);out.put("RunId",ids.getOrDefault(workspace,""));out.put("Progress",Map.of("Phase",visual.get()?"awaiting_visual_review":"scenario_running","CurrentStep","step-"+step.get()));out.put("Report",Map.of("Tests",tests,"Failures",0));return out;
        }
    }
    static class Fixture implements AutoCloseable {
        final Path folder;final ExecutorService workers=Executors.newFixedThreadPool(2);final Fake backend=new Fake();final DevelopmentRunService service;
        Fixture() throws Exception {folder=Files.createTempDirectory("codepilot-run-test-");service=new DevelopmentRunService(folder,backend,workers);}
        Map<String,Object> arguments(String key){Map<String,Object> a=new HashMap<>();a.put("project","Fixture");a.put("idempotency_key",key);a.put("platform_bin",folder.toString());a.put("infobase_path",folder.resolve("ibase").toString());a.put("vanessa_epf",folder.resolve("va.epf").toString());a.put("params_template",folder.resolve("va.json").toString());a.put("update_db",false);return a;}
        String start(String key) throws Exception{return (String)service.start(arguments(key)).get("run_id");}
        Map<String,Object> waitPhase(String id,String phase) throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);Map<String,Object>s=null;while(System.nanoTime()<end){s=service.status(id,0);if(phase.equals(s.get("phase"))){return s;}Thread.sleep(10);}throw new AssertionError("Expected "+phase+" got "+s);}
        public void close(){backend.updateRelease.countDown();backend.completed.set(true);workers.shutdownNow();try{workers.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    }
    @Test public void retryIsIdempotentAndChangedArgumentsAreRejected() throws Exception {
        try(Fixture f=new Fixture()){String id=f.start("same");f.waitPhase(id,"scenario_running");assertEquals(id,f.service.start(f.arguments("same")).get("run_id"));assertEquals(1,f.backend.starts.get());var changed=f.arguments("same");changed.put("timeout_s",100);assertThrows(IllegalArgumentException.class,()->f.service.start(changed));f.backend.completed.set(true);var done=f.waitPhase(id,"completed");assertEquals(Boolean.TRUE,((Map<?,?>)done.get("managed")).get("owned_processes_closed"));assertEquals(List.of(),((Map<?,?>)done.get("managed")).get("OwnedPids"));}
    }
    @Test public void sharedInfobaseCannotBeStartedTwice() throws Exception {
        try(Fixture f=new Fixture()){String id=f.start("first");var second=f.arguments("second");second.put("project","Another");assertThrows(IllegalStateException.class,()->f.service.start(second));f.service.cancel(id);f.waitPhase(id,"cancelled");}
    }
    @Test public void sharedManagerBlocksEvenWithDifferentTargets() throws Exception {
        try(Fixture f=new Fixture()){var first=f.arguments("first");first.put("manager_infobase_path",f.folder.resolve("manager").toString());String id=(String)f.service.start(first).get("run_id");var second=f.arguments("second");second.put("project","Another");second.put("infobase_path",f.folder.resolve("different").toString());second.put("manager_infobase_path",f.folder.resolve("manager").toString());assertThrows(IllegalStateException.class,()->f.service.start(second));f.service.cancel(id);f.waitPhase(id,"cancelled");f.backend.completed.set(true);String next=(String)f.service.start(second).get("run_id");f.waitPhase(next,"completed");}
    }
    @Test public void persistenceFailureReleasesBothInfobaseLeases() throws Exception {
        try(Fixture f=new Fixture()){Files.delete(f.folder.resolve("runs"));assertThrows(Exception.class,()->f.start("broken"));Files.createDirectory(f.folder.resolve("runs"));f.backend.completed.set(true);String id=f.start("recovered");f.waitPhase(id,"completed");}
    }
    @Test public void pauseAndResumeAreExplicitAndWatchdogClockIsCorrelated() throws Exception {
        try(Fixture f=new Fixture()){f.backend.paused.set(true);String id=f.start("pause");f.waitPhase(id,"paused_by_debugger");assertTrue(f.backend.actions.contains("PauseClock"));f.backend.paused.set(false);f.waitPhase(id,"scenario_running");assertTrue(f.backend.actions.contains("ResumeClock"));f.backend.completed.set(true);f.waitPhase(id,"completed");}
    }
    @Test public void stepChangesProduceNewCursorEventsWithoutPhaseChange() throws Exception {
        try(Fixture f=new Fixture()){String id=f.start("steps");var before=f.waitPhase(id,"scenario_running");long cursor=((Number)before.get("event_cursor")).longValue();f.backend.step.incrementAndGet();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);Map<String,Object> after;do{Thread.sleep(20);after=f.service.status(id,cursor);}while(((List<?>)after.get("events")).isEmpty() && System.nanoTime()<end);assertFalse(((List<?>)after.get("events")).isEmpty());f.backend.completed.set(true);f.waitPhase(id,"completed");}
    }
    @Test public void cancelledUpdateWaitsForBackendAndDoesNotStartClient() throws Exception {
        try(Fixture f=new Fixture()){var args=f.arguments("update");args.put("update_db",true);String id=(String)f.service.start(args).get("run_id");assertTrue(f.backend.updateEntered.await(2,TimeUnit.SECONDS));assertEquals("cancel_requested",f.service.cancel(id).get("phase"));assertTrue(f.backend.monitor.isCanceled());assertEquals(0,f.backend.starts.get());f.backend.updateRelease.countDown();f.waitPhase(id,"cancelled");assertEquals(0,f.backend.starts.get());}
    }
    @Test public void staleVisualContinuationIsRejected() throws Exception {
        try(Fixture f=new Fixture()){f.backend.visual.set(true);String id=f.start("visual");var status=f.waitPhase(id,"awaiting_visual_review");assertThrows(IllegalStateException.class,()->f.service.continueRun(id,"old-run","gate","hash"));assertThrows(IllegalArgumentException.class,()->f.service.continueRun(id,(String)status.get("managed_run_id"),"gate","wrong"));f.service.continueRun(id,(String)status.get("managed_run_id"),"gate","hash");assertEquals(1,f.backend.continued.get());f.backend.completed.set(true);f.waitPhase(id,"completed");}
    }
    @Test public void emptyPassingReportIsFailureAndOwnedClientIsStopped() throws Exception {
        try(Fixture f=new Fixture()){f.backend.tests=0;f.backend.completed.set(true);String id=f.start("empty");var status=f.waitPhase(id,"failed");assertTrue(status.get("error").toString().contains("no executed tests"));assertTrue(f.backend.stops.get()>0);}
    }
    @Test public void controllerReplacementDoesNotStopForeignClient() throws Exception {
        try(Fixture f=new Fixture()){String id=f.start("replacement");var status=f.waitPhase(id,"scenario_running");Path controller=Path.of((String)status.get("controller_workspace"));f.backend.ids.put(controller,"foreign-run");f.waitPhase(id,"failed");assertEquals(0,f.backend.stops.get());}
    }
    @Test public void pendingPersistedRunReportsInterruptedAfterRestart() throws Exception {
        try(Fixture f=new Fixture()){String id=f.start("restart");f.waitPhase(id,"scenario_running");var restarted=new DevelopmentRunService(f.folder,f.backend,f.workers);assertEquals("interrupted_by_restart",restarted.status(id,0).get("phase"));f.backend.completed.set(true);f.waitPhase(id,"completed");}
    }
}
