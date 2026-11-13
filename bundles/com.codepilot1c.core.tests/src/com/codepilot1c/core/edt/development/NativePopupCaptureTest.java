// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;
import com.google.gson.*;

public class NativePopupCaptureTest {
    private JsonObject receipt() {
        return JsonParser.parseString("{\"scope\":\"own_window_composite\",\"window_count\":2,\"popup_count\":1,\"armed\":true,\"pixel_content_verified\":false,\"captured_at_ms\":123456}").getAsJsonObject();
    }
    @Test public void legacyScreenshotMakesNoPopupClaim() {
        var receipt=NativeHostChannel.captureMetadata(null);
        assertEquals("main_only_legacy",receipt.get("scope").getAsString());
        assertFalse(receipt.has("popup_count"));assertFalse(receipt.get("pixel_content_verified").getAsBoolean());
    }
    @Test public void exactCaptureTimeAndInventorySurviveValidation() {
        assertEquals(receipt(),NativeHostChannel.captureMetadata(receipt().toString()));
    }
    @Test public void rejectsCoercionsAndImpossibleInventory() {
        for(String key:List.of("window_count","popup_count","captured_at_ms","armed","pixel_content_verified")) {
            var invalid=receipt();invalid.addProperty(key,invalid.get(key).getAsString());
            reject(invalid);
        }
        for(String key:List.of("window_count","popup_count","captured_at_ms")) {
            var invalid=receipt();invalid.addProperty(key,1.5);reject(invalid);
        }
        var invalid=receipt();invalid.addProperty("window_count",1);invalid.addProperty("popup_count",0);reject(invalid);
        invalid=receipt();invalid.addProperty("pixel_content_verified",true);reject(invalid);
        invalid=receipt();invalid.addProperty("captured_at_ms",System.currentTimeMillis()+60000);reject(invalid);
    }
    private void reject(JsonObject invalid) {
        try{NativeHostChannel.captureMetadata(invalid.toString());fail(invalid.toString());}catch(IllegalArgumentException expected){}
    }
    @Test public void popupIsOneClientExecuteNotAPollOrServerOperation() {
        for(String kind:List.of("wait","assert","read")) {
            try{NativeHostBatchPlan.payload(Map.of("steps",List.of(Map.of("code","x","kind",kind,"screenshot",true,"screenshot_expect","popup"))));fail(kind);}catch(IllegalArgumentException expected){}
        }
        try{NativeHostBatchPlan.payload(Map.of("steps",List.of(Map.of("code","x","context","server","screenshot",true,"screenshot_expect","popup"))));fail();}catch(IllegalArgumentException expected){}
        assertTrue(NativeHostBatchPlan.payload(Map.of("steps",List.of(Map.of("code","x","screenshot",true,"screenshot_expect","popup")))).contains("popup"));
    }
    @Test public void requiredPopupNeedsBothActualHostAndNativeCapabilities() {
        var input=Map.<String,Object>of("steps",List.of(Map.of("code","x","screenshot",true,"screenshot_expect","popup")));
        for(Map<String,Object> identity:List.<Map<String,Object>>of(Map.of(),Map.of("native_features",List.of("screenshot_png","armed_popup_capture_v1")),Map.of("host_features",List.of("batch_popup_capture_v1")))) {
            try{NativeHostBatchPlan.requireCapabilities(input,identity);fail();}catch(IllegalStateException expected){}
        }
        NativeHostBatchPlan.requireCapabilities(input,Map.of("native_features",List.of("screenshot_png","armed_popup_capture_v1"),"host_features",List.of("batch_popup_capture_v1")));
    }
    @Test public void windowsCommandQuotingPreservesTrailingSlashesAndQuotes() {
        assertEquals("ENTERPRISE",IsolatedDesktopProcess.quote("ENTERPRISE"));
        assertEquals("/DisableStartupDialogs",IsolatedDesktopProcess.quote("/DisableStartupDialogs"));
        assertEquals("\"C:\\dir with space\\\\\"",IsolatedDesktopProcess.quote("C:\\dir with space\\"));
        assertEquals("\"say \\\"hi\\\"\"",IsolatedDesktopProcess.quote("say \"hi\""));
        try{IsolatedDesktopProcess.quote("a\0b");fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void descriptorAcceptsLegacyAndCurrentFeaturesButRejectsUnknownContracts() {
        for(var features:List.of(List.of("screenshot_png"),List.of("screenshot_png","own_window_composite_v1","armed_popup_capture_v1")))
            assertEquals(features,new NativePackageService.Descriptor("v1.4.0.test","a".repeat(64),"b".repeat(64),features).features());
        try{new NativePackageService.Descriptor("v1.4.0.test","a".repeat(64),"b".repeat(64),List.of("screenshot_png","unknown"));fail();}catch(IllegalArgumentException expected){}
    }

}
