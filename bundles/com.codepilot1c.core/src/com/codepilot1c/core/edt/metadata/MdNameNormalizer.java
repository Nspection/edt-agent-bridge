/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.codepilot1c.core.edt.metadata;
/** The EDT-MCP writer uses this tolerance only for existing-name lookup. */
final class MdNameNormalizer {
    private MdNameNormalizer(){}
    static String normalizeYo(String value){return value==null?null:value.replace('\u0451','\u0435').replace('\u0401','\u0415');}
}
