// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.ui.startup;
import org.eclipse.ui.IStartup;
import org.eclipse.swt.widgets.Display;
import com.codepilot1c.ui.bridge.SpreadsheetMergeUi;
/** Optional editor contribution; does not replace any chat/startup service. */
public final class NativeToolsStartup implements IStartup {
    @Override public void earlyStartup(){Display.getDefault().asyncExec(()->SpreadsheetMergeUi.start());}
}
