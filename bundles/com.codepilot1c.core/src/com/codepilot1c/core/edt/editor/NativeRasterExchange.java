// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.editor;
import java.util.concurrent.Callable;
/** SDK layout may swallow callback exceptions. Keep an independent terminal result and restore output once. */
final class NativeRasterExchange<T> {
 @FunctionalInterface interface Decode<T>{T read(String response)throws Exception;}
 private int passes;private T value;private Exception failure;
 void reject(Exception error){if(failure==null)failure=error;value=null;}
 String paint(Callable<String> buffered,Decode<T> decode,Callable<?> restore)throws Exception {
  if(failure!=null)throw failure;
  if(++passes>4){var error=new FormProjectionGeneration.Unverified("native_capture_render_retry_limit");reject(error);throw error;}
  try{String response=buffered.call();value=decode.read(response);return response;}
  catch(Exception error){failure=error;throw error;}
  finally{try{restore.call();}catch(Exception error){var rejected=new FormProjectionGeneration.Unverified("native_view_restore_unverified");if(failure!=null)rejected.addSuppressed(failure);rejected.addSuppressed(error);failure=rejected;value=null;throw rejected;}}
 }
 T require()throws Exception{if(failure!=null)throw failure;if(value==null)throw new FormProjectionGeneration.Unverified("native_capture_not_produced");return value;}
}
