/*
 * Copyright (c) 2024 Example
 * Modified for EDT Agent Bridge, 2026.
 * SPDX-License-Identifier: AGPL-3.0-only
 * Original authorship retained; see THIRD_PARTY_NOTICES.md and LICENSE.
 */
package com.codepilot1c.ui.internal;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.agent.profiles.DynamicToolCapability;
import com.codepilot1c.ui.tools.GetDiagnosticsTool;
/** Minimal EDT workbench integration. No chat, accounts, providers or remote agent. */
public final class VibeUiPlugin extends AbstractUIPlugin {
 public static final String PLUGIN_ID="com.codepilot1c.ui";
 private static VibeUiPlugin plugin;
 public void start(BundleContext context)throws Exception{super.start(context);plugin=this;ToolRegistry.getInstance().registerDynamicTool(new GetDiagnosticsTool(),DynamicToolCapability.READ_ONLY);}
 public void stop(BundleContext context)throws Exception{com.codepilot1c.ui.bridge.BridgeUpdates.getInstance().stop();com.codepilot1c.core.edt.development.UiLagMonitor.stop();com.codepilot1c.ui.bridge.SpreadsheetMergeUi.stop();plugin=null;super.stop(context);}
 public static VibeUiPlugin getDefault(){return plugin;}
 public static void log(Throwable error){com.codepilot1c.core.internal.VibeCorePlugin.logError(error);}
 public static void log(String text){com.codepilot1c.core.internal.VibeCorePlugin.logInfo(text);}
}
