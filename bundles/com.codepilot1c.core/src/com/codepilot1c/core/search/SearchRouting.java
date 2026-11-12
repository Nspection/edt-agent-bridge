// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.search;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative lexical routing. Explicit legacy modes never change. */
public final class SearchRouting {
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*)*");
    public record Route(String requested, String selected, String reason) { }
    private SearchRouting() { }
    public static Route select(String requested, String query) {
        String mode = requested == null || requested.isBlank() ? "text" : requested.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("text", "regex", "bm25", "auto").contains(mode))
            throw new IllegalArgumentException("searchType must be text, regex, bm25 or auto");
        if (!"auto".equals(mode)) return new Route(mode, mode, "explicit_or_legacy_default");
        if (query == null || query.isBlank() || query.length() > 1000)
            throw new IllegalArgumentException("auto query must contain 1..1000 characters");
        boolean identifier = IDENTIFIER.matcher(query).matches();
        return new Route(mode, identifier ? "text" : "bm25",
                identifier ? "identifier_literal_substring" : "lexical_terms_ranked");
    }
}
