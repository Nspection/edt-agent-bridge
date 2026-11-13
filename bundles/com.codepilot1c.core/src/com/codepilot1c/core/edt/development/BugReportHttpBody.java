// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
/** Size-bounded HTTP subscriber; the entire exchange is awaited with cancellation. */
final class BugReportHttpBody implements HttpResponse.BodySubscriber<byte[]> {
 private final int limit;private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
 private final CompletableFuture<byte[]> body=new CompletableFuture<>();private Flow.Subscription subscription;
 BugReportHttpBody(int limit){this.limit=limit;}
 public CompletionStage<byte[]> getBody(){return body;}
 public void onSubscribe(Flow.Subscription value){if(subscription!=null){value.cancel();return;}subscription=value;if(body.isDone())value.cancel();else value.request(1);}
 public void onNext(List<ByteBuffer> chunks){
  if(body.isDone())return;long size=bytes.size();for(var chunk:chunks){size+=chunk.remaining();if(size>limit){subscription.cancel();body.completeExceptionally(new IllegalStateException("GitHub response exceeds limit"));return;}}
  for(var chunk:chunks){byte[] value=new byte[chunk.remaining()];chunk.get(value);bytes.writeBytes(value);}subscription.request(1);
 }
 public void onError(Throwable failure){body.completeExceptionally(new IllegalStateException("GitHub response incomplete"));}
 public void onComplete(){body.complete(bytes.toByteArray());}
 static <T>T await(CompletableFuture<T> exchange,long timeout,TimeUnit unit)throws Exception{
  try{return exchange.get(timeout,unit);}catch(InterruptedException|TimeoutException failure){exchange.cancel(true);throw failure;}
 }
}
