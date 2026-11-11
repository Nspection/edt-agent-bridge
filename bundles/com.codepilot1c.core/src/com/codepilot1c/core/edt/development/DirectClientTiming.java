// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.*;
/** Explicit per-packet timing. One consistent60s default for the packet and complete wire replies. */
public final class DirectClientTiming {
 private DirectClientTiming(){}
 public static int seconds(Map<String,Object>a){return integer(a,"operation_timeout_s",60,5,300);}
 public static int integer(Map<String,Object>a,String key,int fallback,int min,int max){if(!a.containsKey(key))return fallback;Object value=a.get(key);if(!(value instanceof Number n))throw new IllegalArgumentException(key+" must be an integer");try{int result=new java.math.BigDecimal(n.toString()).intValueExact();if(result<min||result>max)throw new ArithmeticException();return result;}catch(ArithmeticException e){throw new IllegalArgumentException(key+" outside "+min+".."+max);}}
 public static void validate(Map<String,Object>a){int seconds=seconds(a);if(a.containsKey("wait_timeout_ms")&&integer(a,"wait_timeout_ms",0,0,300000)>seconds*1000)throw new IllegalArgumentException("wait_timeout_ms exceeds operation_timeout_s; increase the operation budget explicitly");}
 public static int replySeconds(Map<String,Object>a){if("ready".equals(a.get("operation"))&&a.get("startup_timeout_ms") instanceof Number n)return Math.min(195,Math.max(35,n.intValue()/1000+15));return seconds(a)+10;}
}
