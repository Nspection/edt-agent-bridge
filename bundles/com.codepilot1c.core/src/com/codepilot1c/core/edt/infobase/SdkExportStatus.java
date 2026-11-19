// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.eclipse.core.runtime.IStatus;
import com.codepilot1c.core.logging.LogSanitizer;

/** A bounded snapshot of EDT's export status for logs and MCP receipts. */
final class SdkExportStatus {
    private static final int MAX_NODES = 8;
    private static final int MAX_DEPTH = 3;
    private static final int MAX_TEXT_LENGTH = 240;
    private static final Pattern CREDENTIAL = Pattern.compile(
        "(?:^|[\\s\"'(])/(?:p|n)(?=[\\s\"'=]|[^/\\\\\\s]+(?:\\s|$))"
        + "|(?:password|pwd|passphrase|secret|token|authorization|api[_ -]?key|usr|user(?:name)?|пароль|логин)[\"']?\\s*[:=]"
        + "|\\bbearer\\s+|://[^/\\s]*@",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private int nodesLeft = MAX_NODES;

    static Map<String,Object> snapshot(IStatus status) {
        return status == null ? Map.of("available", false) : new SdkExportStatus().node(status, 0);
    }

    private Map<String,Object> node(IStatus status, int depth) {
        nodesLeft--;
        var data = new LinkedHashMap<String,Object>();
        data.put("severity", status.getSeverity());
        data.put("plugin", safeText(status.getPlugin()));
        data.put("code", status.getCode());
        data.put("message", safeText(status.getMessage()));
        if (status.getException() != null) data.put("exception", exception(status.getException(), 0));

        var children = status.getChildren();
        if (children.length > 0) {
            var details = new ArrayList<Map<String,Object>>();
            for (var child : children) {
                if (depth >= MAX_DEPTH || nodesLeft == 0) {
                    data.put("children_truncated", true);
                    break;
                }
                details.add(node(child, depth + 1));
            }
            if (!details.isEmpty()) data.put("children", details);
        }
        return data;
    }

    private static Map<String,Object> exception(Throwable failure, int depth) {
        var data = new LinkedHashMap<String,Object>();
        data.put("type", safeText(failure.getClass().getName()));
        data.put("message", safeText(failure.getMessage()));
        if (failure.getCause() != null) {
            if (depth + 1 < MAX_DEPTH) data.put("cause", exception(failure.getCause(), depth + 1));
            else data.put("cause_truncated", true);
        }
        return data;
    }

    private static String safeText(String text) {
        if (text == null) return "";
        if (CREDENTIAL.matcher(text).find()) return "[REDACTED]";
        var sanitized = LogSanitizer.redactSecrets(text).replaceAll("[\\p{Cntrl}]", " ").strip();
        return LogSanitizer.truncate(sanitized, MAX_TEXT_LENGTH);
    }
}
