// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.util.*;
import java.util.function.LongSupplier;
import java.util.concurrent.TimeUnit;

/** One monotonic startup budget; scheduling slices never shorten a complete wire reply. */
public final class DirectClientStartup {
    private DirectClientStartup() {}
    public interface Probe { Map<String,Object> read(int timeoutMs) throws Exception; }
    public interface Verify { void check() throws Exception; }

    public static final class Budget {
        private final LongSupplier clock;
        private final long begin, deadline;
        private final int seconds;
        public Budget(int seconds, LongSupplier clock) {
            if(seconds<1 || seconds>180)throw new IllegalArgumentException("Startup budget outside1..180 seconds");
            this.clock=clock;this.seconds=seconds;this.begin=clock.getAsLong();
            this.deadline=begin+TimeUnit.SECONDS.toNanos(seconds);
        }
        public int remainingMillis() {
            long remaining=deadline-clock.getAsLong();
            return remaining<=0 ? 0 : (int)Math.min(180000,(remaining+999999)/1000000);
        }
        public long elapsedMillis(){return Math.max(0,TimeUnit.NANOSECONDS.toMillis(clock.getAsLong()-begin));}
    }
    public static int timeout(Map<String,Object> args) {
        if(!args.containsKey("readiness_timeout_s"))return 90;
        try{int n=new java.math.BigDecimal(args.get("readiness_timeout_s").toString()).intValueExact();if(n<10||n>180)throw new ArithmeticException();return n;}
        catch(Exception e){throw new IllegalArgumentException("readiness_timeout_s must be integer10..180");}
    }
    public static String kind(Map<String,Object> args) {
        Object kind=args.getOrDefault("client_kind","thin");
        if(!Set.of("thin","thick").contains(kind))throw new IllegalArgumentException("client_kind must be thin/thick");
        return (String)kind;
    }
    public static Map<String,Object> await(Probe probe,Verify verify,int seconds,LongSupplier clock)throws Exception {
        return await(probe,verify,new Budget(seconds,clock));
    }
    public static Map<String,Object> await(Probe probe,Verify verify,Budget budget)throws Exception {
        int attempts=0;String last="not_observed";
        while(budget.remainingMillis()>0) {
            verify.check();int remaining=budget.remainingMillis();if(remaining==0)break;
            var result=probe.read(remaining);attempts++;last=Objects.toString(result.get("state"),"unknown");
            if(Boolean.TRUE.equals(result.get("success"))) {
                verify.check();return Map.of("ready",true,"state","ready","observations",attempts,"elapsed_ms",budget.elapsedMillis(),"observation",result);
            }
            // Only a complete, observational negative response permits another read.
            if(!"condition_timeout".equals(last) || !Boolean.FALSE.equals(result.get("outcome_unknown")) || !Boolean.FALSE.equals(result.get("action_dispatched")))
                return Map.of("ready",false,"state","startup_transport_unavailable","observations",attempts,"last_state",last,"observation",result,"retry_safe",false);
            Thread.sleep(50);
        }
        return Map.of("ready",false,"state","startup_ui_timeout","observations",attempts,"last_state",last,"readiness_timeout_s",budget.seconds,"retry_safe",false,"execution_state","not_proven","ui_mutation_dispatched",false);
    }
}
