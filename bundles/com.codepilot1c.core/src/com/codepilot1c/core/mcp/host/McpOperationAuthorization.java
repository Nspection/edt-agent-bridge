// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.mcp.host;

/** Live authorization shared by orchestration and direct runtime entry points. */
public final class McpOperationAuthorization {
    private McpOperationAuthorization() {}
    public static void requireAllowed(String tool) {
        requireAllowed(tool,McpHostConfigStore.getInstance().load());
    }
    static void requireAllowed(String tool,McpHostConfig config){
        if(config.getMutationPolicy()!=McpHostConfig.MutationPolicy.ALLOW
                || !new DefaultMcpToolExposurePolicy(config).isExposed(tool))
            throw new IllegalStateException("Operation is not currently allowed: "+tool);
    }
}
