// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.updates;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import com.google.gson.JsonParser;
import org.osgi.framework.Version;

/** Read-only release discovery. Installation always belongs to the native p2 wizard. */
public final class PluginUpdateCheck {
    public static final URI UPDATE_SITE = URI.create("https://nspection.github.io/edt-agent-bridge/");
    public static final URI MANIFEST = UPDATE_SITE.resolve("release-manifest.json");
    public static final String FEATURE_ID = "com.codepilot1c.feature.feature.group";
    public static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;
    public static final int MAX_MANIFEST_BYTES = 256 * 1024;

    public record Release(Version installed, Version available) {
        public boolean updateAvailable() { return available.compareTo(installed) > 0; }
    }

    private PluginUpdateCheck() { }

    public static boolean due(boolean enabled, long lastAttempt, long now) {
        return enabled && (lastAttempt <= 0 || now < lastAttempt || now - lastAttempt >= CHECK_INTERVAL_MS);
    }

    public static boolean shouldNotify(Release release, String lastNotified) {
        return release.updateAvailable() && !release.available().toString().equals(lastNotified);
    }

    public static Release read(InputStream input, Version installed) throws IOException {
        byte[] data = input.readNBytes(MAX_MANIFEST_BYTES + 1);
        if (data.length > MAX_MANIFEST_BYTES) throw new IOException("Release manifest exceeds size limit");
        try {
            var json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!json.has("product") || !json.get("product").isJsonPrimitive()
                    || !json.get("product").getAsString().equals("EDT Agent Bridge")
                    || !json.has("version") || !json.get("version").isJsonPrimitive()
                    || !json.getAsJsonPrimitive("version").isString()) {
                throw new IllegalArgumentException("Not a Bridge release manifest");
            }
            String text = json.get("version").getAsString();
            if (text.length() > 128 || !text.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:\\.[A-Za-z0-9_-]+)?")) {
                throw new IllegalArgumentException("Invalid release version");
            }
            Version available = Version.parseVersion(text);
            if (available.equals(Version.emptyVersion)) throw new IllegalArgumentException("Empty release version");
            return new Release(installed, available);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid Bridge release manifest", invalid);
        }
    }

    public static Release fetch(Version installed) throws IOException {
        var connection = (HttpURLConnection) MANIFEST.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "EDT-Agent-Bridge-update-check");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Release site unavailable");
            try (var input = connection.getInputStream()) { return read(input, installed); }
        } finally { connection.disconnect(); }
    }
}
