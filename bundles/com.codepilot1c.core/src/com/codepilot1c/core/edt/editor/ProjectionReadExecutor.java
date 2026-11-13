// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.editor;
import java.util.concurrent.*;
import com.codepilot1c.core.edt.development.RuntimeProcessLifecycle;
/** A bounded reader owned by one plugin lifetime. Stopped epochs cannot deliver values. */
final class ProjectionReadExecutor {
 private final RuntimeProcessLifecycle lifecycle;private long epoch=Long.MIN_VALUE;private ThreadPoolExecutor executor;
 ProjectionReadExecutor(RuntimeProcessLifecycle lifecycle){this.lifecycle=lifecycle;}
 private synchronized ThreadPoolExecutor pool(long ticket)throws Exception {
  lifecycle.require(ticket);
  if(executor==null||epoch!=ticket||executor.isShutdown()){
   var created=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),task->{var thread=new Thread(task,"edt-preview-authority-read");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
   try{lifecycle.own(ticket,"editor-preview-authority-"+ticket,()->created.shutdownNow());}catch(RuntimeException failure){created.shutdownNow();throw failure;}
   executor=created;epoch=ticket;
  }
  executor.purge();return executor;
 }
 <T> CompletableFuture<T> submit(long ticket,Callable<T> read)throws Exception {
  var pool=pool(ticket);var result=new CompletableFuture<T>();
  var task=new FutureTask<Void>(()->{if(!result.isCancelled())try{lifecycle.require(ticket);T value=read.call();lifecycle.require(ticket);result.complete(value);}catch(Throwable failure){result.completeExceptionally(failure);}return null;});
  result.whenComplete((value,failure)->{if(result.isCancelled())task.cancel(true);});
  try{pool.execute(task);}catch(RejectedExecutionException full){throw new FormProjectionGeneration.Unverified("authoritative_read_queue_busy");}
  return result;
 }
}
