// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.updates;
import java.util.List;
import org.junit.Test;
import org.eclipse.equinox.p2.metadata.*;
import org.eclipse.equinox.p2.operations.Update;
import com.codepilot1c.ui.bridge.BridgePluginUpdatePlan;
import static org.junit.Assert.*;

public class BridgePluginUpdatePlanTest {
    private IInstallableUnit unit(String id, String version) {
        var d = new MetadataFactory.InstallableUnitDescription();
        d.setId(id); d.setVersion(Version.create(version));
        return MetadataFactory.createInstallableUnit(d);
    }
    private Update update(String id, String oldVersion, String newVersion) {
        return new Update(unit(id, oldVersion), unit(id, newVersion));
    }
    @Test public void selectsOnlyBridgeRootNotOtherInstalledProducts() {
        var bridge = unit(PluginUpdateCheck.FEATURE_ID, "1.4.0");
        assertEquals(List.of(bridge), BridgePluginUpdatePlan.bridgeRoots(List.of(
                unit("com._1c.product", "9.0.0"), bridge, unit("com.codepilot1c.core", "1.4.0"))));
    }
    @Test public void choosesHighestBridgeUpgradeOnly() {
        var latest = update(PluginUpdateCheck.FEATURE_ID, "1.4.0", "1.6.0");
        assertArrayEquals(new Update[] {latest}, BridgePluginUpdatePlan.newerBridgeUpdates(new Update[] {
                update("other", "1.0.0", "99.0.0"), latest, update(PluginUpdateCheck.FEATURE_ID, "1.4.0", "1.5.0")},
                org.osgi.framework.Version.parseVersion("1.4.0")));
    }
    @Test public void neverDowngradesRunningOrAlreadyInstalledVersion() {
        assertEquals(0, BridgePluginUpdatePlan.newerBridgeUpdates(new Update[] {
                update(PluginUpdateCheck.FEATURE_ID, "1.4.0", "1.5.0")}, org.osgi.framework.Version.parseVersion("1.6.0")).length);
        assertEquals(0, BridgePluginUpdatePlan.newerBridgeUpdates(new Update[] {
                update(PluginUpdateCheck.FEATURE_ID, "1.6.0", "1.5.0")}, org.osgi.framework.Version.parseVersion("1.4.0")).length);
        assertEquals(0, BridgePluginUpdatePlan.newerBridgeUpdates(null, org.osgi.framework.Version.parseVersion("1.4.0")).length);
    }
    @Test public void rejectsForeignReplacementEvenWhenSourceIsBridge() {
        var wrong = new Update(unit(PluginUpdateCheck.FEATURE_ID,"1.4.0"),unit("other","9.0.0"));
        assertEquals(0,BridgePluginUpdatePlan.newerBridgeUpdates(new Update[]{wrong},org.osgi.framework.Version.parseVersion("1.4.0")).length);
    }
}
