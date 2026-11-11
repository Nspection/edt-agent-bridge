// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.updates;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.osgi.framework.Version;
import static org.junit.Assert.*;

public class PluginUpdateCheckTest {
    private PluginUpdateCheck.Release read(String text) throws Exception {
        return PluginUpdateCheck.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                Version.parseVersion("1.4.0.20261111reuse1"));
    }
    private String manifest(String version) { return "{\"product\":\"EDT Agent Bridge\",\"version\":\"" + version + "\"}"; }
    @Test public void detectsNewerOsgiQualifier() throws Exception {
        assertTrue(read(manifest("1.4.0.20261111zlockdebug1")).updateAvailable());
    }
    @Test public void equalOlderAndDevelopmentVersionsDoNotOfferDowngrade() throws Exception {
        assertFalse(read(manifest("1.4.0.20261111reuse1")).updateAvailable());
        assertFalse(read(manifest("1.4.0.20261111compact1")).updateAvailable());
        assertFalse(PluginUpdateCheck.read(new ByteArrayInputStream(manifest("1.4.0.20261111reuse1")
                .getBytes(StandardCharsets.UTF_8)), Version.parseVersion("1.4.0.20261111zzupdatesdev1")).updateAvailable());
    }
    @Test public void comparesMajorVersionsNumerically() throws Exception {
        assertTrue(read(manifest("1.10.0")).updateAvailable());
        assertFalse(read(manifest("1.3.99")).updateAvailable());
    }
    @Test public void rejectsMalformedUnexpectedAndMissingVersions() {
        for (String text : new String[] {"{}", "null", "[]", "not json", "{\"product\":\"Other\",\"version\":\"9.0.0\"}",
                "{\"product\":\"EDT Agent Bridge\",\"version\":null}", "{\"product\":\"EDT Agent Bridge\",\"version\":123}"}) {
            assertThrows(IOException.class, () -> read(text));
        }
        for (String version : new String[] {"0.0.0", "1.4", "1.4.0.", "1.4.0/evil", "9999999999999999.0.0"}) {
            assertThrows(IOException.class, () -> read(manifest(version)));
        }
    }
    @Test public void boundsManifestBeforeParsing() {
        assertThrows(IOException.class, () -> read(" ".repeat(PluginUpdateCheck.MAX_MANIFEST_BYTES + 1)));
    }
    @Test public void periodicDueSurvivesClockRollbackAndHonorsDisable() {
        long now = 10 * PluginUpdateCheck.CHECK_INTERVAL_MS;
        assertTrue(PluginUpdateCheck.due(true, 0, now));
        assertFalse(PluginUpdateCheck.due(true, now - 1000, now));
        assertTrue(PluginUpdateCheck.due(true, now - PluginUpdateCheck.CHECK_INTERVAL_MS, now));
        assertTrue(PluginUpdateCheck.due(true, now + 1000, now));
        assertFalse(PluginUpdateCheck.due(false, 0, now));
    }
    @Test public void notifiesOncePerNewVersion() throws Exception {
        var release = read(manifest("1.5.0"));
        assertTrue(PluginUpdateCheck.shouldNotify(release, ""));
        assertTrue(PluginUpdateCheck.shouldNotify(release, "1.4.0"));
        assertFalse(PluginUpdateCheck.shouldNotify(release, "1.5.0"));
        assertFalse(PluginUpdateCheck.shouldNotify(read(manifest("1.3.0")), ""));
    }
    @Test public void ignoresRemoteLinksAndUsesOnlyFixedOfficialSite() throws Exception {
        var release = read(manifest("1.5.0").replace("}", ",\"update_url\":\"https://example.invalid/evil\"}"));
        assertTrue(release.updateAvailable());
        assertEquals("https://nspection.github.io/edt-agent-bridge/", PluginUpdateCheck.UPDATE_SITE.toString());
        assertEquals(PluginUpdateCheck.UPDATE_SITE.resolve("release-manifest.json"), PluginUpdateCheck.MANIFEST);
    }
}
