package com.codepilot1c.core.edt.development;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import com.google.gson.*;
public class NativeHostStatusSnapshotTest {
 @Test public void fullAndCleanupPollingNeverWaitForLifecycleOrCallReadiness()throws Exception {
  for(String phase:List.of("updating_base","removing_from_base")){
   var lock=new NativeHostLock();var held=new CountDownLatch(1);var release=new CountDownLatch(1);var errors=new AtomicReference<Throwable>();
   Thread writer=new Thread(()->{try(var scope=lock.hold()){held.countDown();release.await();}catch(Throwable error){errors.set(error);}});writer.start();assertTrue(held.await(2,TimeUnit.SECONDS));
   var reads=new AtomicInteger();var fresh=new AtomicInteger();var record=new JsonObject();record.addProperty("state",phase);record.addProperty("host_id","a".repeat(32));
   var pool=Executors.newSingleThreadExecutor();
   try {
    var status=pool.submit(()->NativeHostStatusSnapshot.observe(lock,()->{reads.incrementAndGet();return record;},()->{fresh.incrementAndGet();throw new AssertionError("Must not enter SDK/BM/readiness while FULL is blocked");})).get(1,TimeUnit.SECONDS);
    assertEquals(phase,status.get("state"));assertEquals(false,status.get("execution_allowed"));assertEquals(false,status.get("readiness_verified"));assertEquals(true,status.get("lifecycle_busy"));assertEquals(1,reads.get());assertEquals(0,fresh.get());
   }finally {release.countDown();writer.join(2000);pool.shutdownNow();}assertNull(errors.get());
   assertEquals(Map.of("fresh",true),NativeHostStatusSnapshot.observe(lock,()->record,()->Map.of("fresh",true)));
  }
 }
 @Test public void sameOwnerRetainsFreshAdmissionAndNestedCleanupLocks()throws Exception {
  var lock=new NativeHostLock();try(var outer=lock.hold()){try(var inner=lock.hold()){assertEquals(Map.of("fresh",true),NativeHostStatusSnapshot.observe(lock,()->{throw new AssertionError();},()->Map.of("fresh",true)));}}
  try(var next=lock.tryHold()){assertNotNull(next);}
 }
}
