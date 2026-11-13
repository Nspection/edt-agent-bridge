// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import org.junit.Test;
public class BugReportHttpBodyTest {
 static final class Subscription implements java.util.concurrent.Flow.Subscription{boolean cancelled;long requests;public void cancel(){cancelled=true;}public void request(long n){requests+=n;}}
 @Test public void stalledResponseDeadlineCancelsWholeExchange()throws Exception{var exchange=new CompletableFuture<byte[]>();assertThrows(TimeoutException.class,()->BugReportHttpBody.await(exchange,20,TimeUnit.MILLISECONDS));assertTrue(exchange.isCancelled());}
 @Test public void streamingResponseIsBoundedBeforeCopyingChunks()throws Exception{var body=new BugReportHttpBody(4);var subscription=new Subscription();body.onSubscribe(subscription);body.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2,3})));body.onNext(List.of(ByteBuffer.wrap(new byte[]{4,5})));assertTrue(subscription.cancelled);assertThrows(ExecutionException.class,()->body.getBody().toCompletableFuture().get());}
 @Test public void completeResponsePreservesExactBytes()throws Exception{var body=new BugReportHttpBody(4);var subscription=new Subscription();body.onSubscribe(subscription);body.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2}),ByteBuffer.wrap(new byte[]{3,4})));body.onComplete();assertArrayEquals(new byte[]{1,2,3,4},body.getBody().toCompletableFuture().get());assertFalse(subscription.cancelled);}
}
