// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/** Own handles only. Closing a lifecycle never deletes metadata, bases or journals. */
public final class RuntimeProcessLifecycle {
    private static final RuntimeProcessLifecycle GLOBAL=new RuntimeProcessLifecycle();
    private final Map<String,AutoCloseable> resources=new LinkedHashMap<>();
    private long epoch;
    private boolean accepting=true;
    public static RuntimeProcessLifecycle global(){return GLOBAL;}
    public synchronized long epoch(){return epoch;}
    public synchronized void begin(){epoch++;accepting=true;}
    public synchronized void require(long ticket){if(!accepting||ticket!=epoch)throw new IllegalStateException("Runtime lifecycle stopped or replaced; no late launch");}
    public synchronized <T> T launch(long ticket,Callable<T> action)throws Exception{require(ticket);return action.call();}
    public synchronized void own(long ticket,String key,AutoCloseable resource){require(ticket);if(resources.putIfAbsent(key,resource)!=null)throw new IllegalStateException("Owned runtime resource already registered");}
    public <T extends AutoCloseable> T create(long ticket,String key,Callable<T> factory)throws Exception {
        return launch(ticket,()->{T resource=factory.call();try{own(ticket,key,resource);return resource;}
            catch(RuntimeException|Error failure){try{resource.close();}catch(Exception closeFailure){failure.addSuppressed(closeFailure);}throw failure;}});
    }
    public void ownProcess(long ticket,String key,Process child){
        try{own(ticket,key,()->{if(child.isAlive()){child.destroy();if(!child.waitFor(3,TimeUnit.SECONDS)){child.destroyForcibly();if(!child.waitFor(3,TimeUnit.SECONDS))throw new IllegalStateException("Own process did not stop");}}});}
        catch(RuntimeException failure){child.destroyForcibly();throw failure;}
    }
    public synchronized void forget(String key){resources.remove(key);}
    public void closeOwned(String key)throws Exception{
        AutoCloseable resource;synchronized(this){resource=resources.get(key);}
        if(resource!=null){resource.close();synchronized(this){resources.remove(key,resource);}}
    }
    public List<String> shutdown(){
        Map<String,AutoCloseable> snapshot;
        synchronized(this){accepting=false;epoch++;snapshot=new LinkedHashMap<>(resources);}
        // Never wait for processes or acquire a model/file lock under the launch gate.
        var failures=new ArrayList<String>();
        var closing=new ArrayList<>(snapshot.entrySet());Collections.reverse(closing);
        for(var entry:closing)try{entry.getValue().close();synchronized(this){resources.remove(entry.getKey(),entry.getValue());}}
        catch(Exception failure){failures.add(failure.getClass().getSimpleName());}
        return List.copyOf(failures);
    }
}
