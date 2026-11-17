// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class SpreadsheetArtifactTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void choosesExistingCodecAndCreatesOnlyMxlx() {
        assertEquals(SpreadsheetArtifact.Format.MXL, SpreadsheetArtifact.select("src/A", true, false, false).format());
        assertEquals("src/A/Template.mxlx", SpreadsheetArtifact.select("src/A", false, true, false).path());
        assertEquals(SpreadsheetArtifact.Format.MXLX, SpreadsheetArtifact.select("src/A", false, false, true).format());
        assertThrows(IllegalArgumentException.class, () -> SpreadsheetArtifact.select("src/A", true, true, true));
        assertThrows(IllegalArgumentException.class, () -> SpreadsheetArtifact.select("src/A", false, false, false));
    }
    @Test public void secureXmlHonorsEncodingAndRejectsDtdAndWrongRoot() throws Exception {
        String xml = "<?xml version='1.0' encoding='UTF-16'?><document xmlns='http://v8.1c.ru/8.2/data/spreadsheet'/>";
        SpreadsheetArtifact.checkedXml(xml.getBytes(StandardCharsets.UTF_16));
        assertThrows(java.io.IOException.class, () -> SpreadsheetArtifact.checkedXml("<wrong/>".getBytes(StandardCharsets.UTF_8)));
        assertThrows(java.io.IOException.class, () -> SpreadsheetArtifact.checkedXml(("<!DOCTYPE document [<!ENTITY x SYSTEM 'file:///not-read'>]>" +
                "<document xmlns='http://v8.1c.ru/8.2/data/spreadsheet'>&x;</document>").getBytes(StandardCharsets.UTF_8)));
        assertThrows(java.io.IOException.class, () -> SpreadsheetArtifact.checkedXml("<document".getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void boundsReadsAndRejectsTraversal() throws Exception {
        Path root = temporary.getRoot().toPath().toRealPath();
        assertEquals(root.resolve("src/Template.mxlx"), SpreadsheetArtifact.checkedPath(root, "src/Template.mxlx"));
        assertThrows(java.io.IOException.class, () -> SpreadsheetArtifact.checkedPath(root, "../escape.mxlx"));
        Path file = root.resolve("large.mxlx");
        Files.write(file, new byte[SpreadsheetArtifact.MAX_BYTES + 1]);
        assertThrows(java.io.IOException.class, () -> SpreadsheetArtifact.readBounded(file));
    }
    @Test public void rejectsSymbolicLinksWhenHostPermitsThem() throws Exception {
        Path root = temporary.getRoot().toPath().toRealPath();
        Path target = Files.createDirectory(root.resolve("target"));
        Path link = root.resolve("link");
        try { Files.createSymbolicLink(link, target); }
        catch (java.io.IOException | UnsupportedOperationException | SecurityException e) { Assume.assumeNoException(e); }
        assertThrows(java.io.IOException.class, () -> SpreadsheetArtifact.checkedPath(root, "link/Template.mxlx"));
    }
    @Test public void acceptsCommonTemplatesInBothContracts() {
        new InspectTemplateRequest("own", "CommonTemplate.Макет").validate();
        new RenderTemplateRequest("own", "CommonTemplate.Макет", List.of(Map.of("name", "Шапка", "rows", List.of(List.of("Text"))))).validate();
    }
}
