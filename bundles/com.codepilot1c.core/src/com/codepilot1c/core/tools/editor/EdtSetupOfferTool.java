// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.editor;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.runtime.preferences.ConfigurationScope;
import org.osgi.service.prefs.BackingStoreException;

import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.Gson;

@ToolMeta(name = "edt_setup_offer", category = "workspace", mutating = true, tags = {"workspace", "edt"})
public final class EdtSetupOfferTool extends AbstractTool {
    private static final String TOPIC = "codex_open_in_edt";
    private static final String OFFERED_KEY = "setupOffer.codexOpenInEdt.v1";

    @Override
    public String getDescription() {
        return "Offer optional Codex Open in EDT setup at most once per EDT configuration. Call only when Codex users ask "
                + "about opening code from chat in EDT. If suggested=true, ask whether to configure it. "
                + "Does not install the command or modify Codex settings. Use the editor guide for manual setup at any time.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {"type":"object","additionalProperties":false,"required":["topic"],"properties":{
                  "topic":{"const":"codex_open_in_edt"}}}
                """;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters parameters) {
        return CompletableFuture.supplyAsync(() -> {
            if (!Map.of("topic", TOPIC).equals(parameters.getRaw())) {
                return ToolResult.failure("Expected topic=codex_open_in_edt");
            }
            try {
                return ToolResult.success(new Gson().toJson(offer()));
            } catch (BackingStoreException unavailable) {
                return ToolResult.failure("Could not persist the one-time EDT setup offer");
            }
        });
    }

    private static synchronized Map<String, Object> offer() throws BackingStoreException {
        var preferences = ConfigurationScope.INSTANCE.getNode("com.codepilot1c.core");
        if (preferences.getBoolean(OFFERED_KEY, false)) {
            return Map.of("suggested", false);
        }
        preferences.putBoolean(OFFERED_KEY, true);
        try {
            preferences.flush();
        } catch (BackingStoreException unavailable) {
            preferences.remove(OFFERED_KEY);
            throw unavailable;
        }
        return Map.of("suggested", true, "requires_user_confirmation", true,
                "message", "Offer to add 1C:EDT to Codex's Open in menu. Only configure it after the user agrees.",
                "guide_call", Map.of("tool", "edt.help", "arguments",
                        Map.of("args", Map.of("action", "guide", "topic", "editor"))));
    }
}
