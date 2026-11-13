// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.concurrent.ConcurrentHashMap;
/** A finishing attempt can only release its own generation, never a resumed attempt. */
final class ActiveUiRunLeases {
 private final ConcurrentHashMap<String,Lease> active=new ConcurrentHashMap<>();
 boolean contains(String key){return active.containsKey(key);}
 Lease claim(String key){var lease=new Lease(key);if(active.putIfAbsent(key,lease)!=null)throw new IllegalStateException("UI run already active");return lease;}
 final class Lease implements AutoCloseable {
  private final String key;private Lease(String key){this.key=key;}
  public void close(){active.remove(key,this);}
 }
}
