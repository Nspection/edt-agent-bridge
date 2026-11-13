// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.function.*;
/** Stops old callbacks from reviving after restart, even before queued cancellation runs. */
public final class UiPollLifecycle {
 private long generation;private Runnable callback;private Consumer<Runnable> cancel;
 public synchronized void start(BiConsumer<Integer,Runnable> timer,Consumer<Runnable> cancel,Runnable work){
  if(callback!=null)return;long ticket=++generation;this.cancel=cancel;
  callback=new Runnable(){public void run(){if(!current(ticket))return;try{work.run();}finally{if(current(ticket))timer.accept(1800,this);}}};timer.accept(500,callback);
 }
 private synchronized boolean current(long ticket){return callback!=null&&generation==ticket;}
 public void stop(){Runnable old;Consumer<Runnable> cancellation;synchronized(this){generation++;old=callback;callback=null;cancellation=cancel;cancel=null;}if(old!=null)cancellation.accept(old);}
}
