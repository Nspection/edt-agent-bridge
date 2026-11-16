// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.editor;
import java.util.List;import java.util.concurrent.Callable;import java.util.function.Predicate;
/** The SDK owns its hidden popup children. Capture permits bounded native cache growth, never replacement/disposal. */
final class NativeRasterPopupPool {
 static final int LIMIT=4;
 static void grow(Callable<? extends List<?>> inventory,Callable<?> add,Predicate<Object> ownedHiddenChild)throws Exception {
  int before=inventory.call().size();if(before>=LIMIT)throw new FormProjectionGeneration.Unverified("native_capture_popup_limit");
  add.call();List<?> after=inventory.call();
  if(after.size()!=before+1||after.size()>LIMIT||!ownedHiddenChild.test(after.get(before)))throw new FormProjectionGeneration.Unverified("native_capture_popup_ownership_changed");
 }
}
