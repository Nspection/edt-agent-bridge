/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.startup;

import java.util.concurrent.CompletableFuture;

import org.eclipse.ui.IStartup;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.mcp.host.McpHostManager;

public class McpHostStartup implements IStartup {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(McpHostStartup.class);

    @Override
    public void earlyStartup() {
        if (org.eclipse.ui.PlatformUI.isWorkbenchRunning()) {
            var workbench = org.eclipse.ui.PlatformUI.getWorkbench();
            workbench.getDisplay().asyncExec(() -> {
                try { com.codepilot1c.ui.bridge.BridgeWorkbenchMigration.run(workbench); com.codepilot1c.ui.bridge.SpreadsheetMergeUi.start(); }
                catch (RuntimeException error) { LOG.error("Failed to retire old CodePilot panels", error); }
            });
        }
        CompletableFuture.runAsync(() -> {
            try {
                try { com.codepilot1c.core.edt.compare.SpreadsheetMergeBridge.install(); } catch (RuntimeException e) { LOG.warn("Spreadsheet integration unavailable: " + e.getMessage()); }
                McpHostManager.getInstance().startIfEnabled();
            } catch (Exception e) {
                LOG.error("Failed to auto-start MCP host", e); //$NON-NLS-1$
            }
        });
    }
}
