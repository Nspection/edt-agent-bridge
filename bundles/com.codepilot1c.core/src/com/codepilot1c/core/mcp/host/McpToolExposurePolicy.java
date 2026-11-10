package com.codepilot1c.core.mcp.host;

import java.util.Map;

public interface McpToolExposurePolicy {

    boolean isExposed(String toolName);

    /** Facade visibility is separate from canonical operation authorization. */
    default boolean isFacadeAllowed(String facadeName){return true;}

    boolean requiresConfirmation(String toolName, Map<String, Object> args);

    boolean isDestructive(String toolName);
}
