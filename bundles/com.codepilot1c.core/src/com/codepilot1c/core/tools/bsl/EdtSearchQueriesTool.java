// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.tools.bsl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;

import com.codepilot1c.core.edt.development.AgentResponses;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;

@ToolMeta(name = "edt_search_queries", category = "bsl", tags = {"read-only", "workspace", "edt"})
public final class EdtSearchQueriesTool extends AbstractTool {
    private static final int MAX_FILES = 12000;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final long MAX_SCAN_BYTES = 96L * 1024 * 1024;

    @Override public String getDescription() {
        return "Search saved query text inside Form.form dynamic lists and Template.dcs datasets. "
                + "Returns a bounded list with source path, owner and query line.";
    }

    @Override public String getParameterSchema() {
        return """
                {"type":"object","additionalProperties":false,"required":["project","query"],"properties":{
                "project":{"type":"string"},"query":{"type":"string","minLength":1,"maxLength":240},
                "kind":{"enum":["all","form","dcs"]},"path_filter":{"type":"string"},
                "limit":{"type":"integer","minimum":1,"maximum":100}}}
                """;
    }

    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return AgentResponses.success(search(params), params.getRaw());
            } catch (Exception failure) {
                return ToolResult.failure("EDT_SEARCH_QUERIES: " + failure.getMessage());
            }
        });
    }

    private static Map<String, Object> search(ToolParameters params) throws Exception {
        var project = ResourcesPlugin.getWorkspace().getRoot().getProject(params.requireString("project"));
        if (!project.isOpen()) throw new IllegalArgumentException("EDT project is missing or closed");
        String needle = params.requireString("query").strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty() || needle.length() > 240 || needle.indexOf('\n') >= 0 || needle.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Provide one line of query text");
        }
        String kind = params.optString("kind", "all");
        if (!java.util.Set.of("all", "form", "dcs").contains(kind)) throw new IllegalArgumentException("Unknown query kind");
        String pathFilter = params.optString("path_filter", "").toLowerCase(Locale.ROOT);
        int limit = Math.max(1, Math.min(100, params.optInt("limit", 20)));
        var src = project.getFolder("src");
        if (!src.exists()) throw new IllegalArgumentException("Project has no src folder");

        var search = new Scan(needle, limit);
        src.accept(resource -> {
            if (search.finished() || resource.isLinked() || resource.isDerived()) return false;
            if (!(resource instanceof IFile file)) return true;
            String path = file.getProjectRelativePath().toPortableString();
            if (!path.toLowerCase(Locale.ROOT).contains(pathFilter)) return false;
            if ("Form.form".equals(file.getName()) && !"dcs".equals(kind)) search.file(file, "form", "queryText");
            if ("Template.dcs".equals(file.getName()) && !"form".equals(kind)) search.file(file, "dcs", "query");
            return false;
        }, IResource.DEPTH_INFINITE, false);
        return Map.of("matches", search.matches.subList(0, Math.min(limit, search.matches.size())),
                "scanned", search.scanned, "skipped_files", search.skipped,
                "has_more", search.matches.size() > limit, "complete", !search.finished() && search.skipped == 0);
    }

    private static final class Scan {
        private final String needle;
        private final int limit;
        private final XMLInputFactory xml = XMLInputFactory.newDefaultFactory();
        private final List<Map<String, Object>> matches = new ArrayList<>();
        private int scanned;
        private int skipped;
        private long totalBytes;

        private Scan(String needle, int limit) {
            this.needle = needle;
            this.limit = limit;
            xml.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            xml.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        }

        private boolean finished() {
            return matches.size() > limit || scanned >= MAX_FILES || totalBytes >= MAX_SCAN_BYTES;
        }

        private void file(IFile file, String kind, String queryTag) {
            scanned++;
            var fileMatches = new ArrayList<Map<String, Object>>();
            try (var input = file.getContents()) {
                byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                totalBytes += bytes.length;
                if (bytes.length > MAX_BYTES) {
                    skipped++;
                    return;
                }
                var reader = xml.createXMLStreamReader(new java.io.ByteArrayInputStream(bytes));
                try {
                    var stack = new ArrayDeque<XmlNode>();
                    while (reader.hasNext()) {
                        int event = reader.next();
                        if (event == XMLStreamConstants.START_ELEMENT) {
                            String tag = reader.getLocalName();
                            if (tag.equals("name") && !stack.isEmpty()
                                    && (java.util.Set.of("attributes", "dataSet").contains(stack.peek().tag)
                                            || isDataSetItem(stack.peek()))) {
                                stack.peek().name = reader.getElementText().strip();
                            } else if (tag.equals(queryTag) && queryOwner(stack, kind)) {
                                int line = reader.getLocation().getLineNumber();
                                String owner = stack.stream().filter(node -> !node.name.isBlank())
                                        .map(node -> node.name).findFirst().orElse("");
                                addMatches(fileMatches, file, kind, owner, line, reader.getElementText());
                            } else {
                                stack.push(new XmlNode(tag, reader.getAttributeValue(
                                        javax.xml.XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type")));
                            }
                        } else if (event == XMLStreamConstants.END_ELEMENT) {
                            stack.pop();
                        } else if (event == XMLStreamConstants.DTD) {
                            throw new IllegalArgumentException("DOCTYPE is not supported");
                        }
                    }
                } finally {
                    reader.close();
                }
                matches.addAll(fileMatches);
            } catch (Exception unavailable) {
                skipped++;
            }
        }

        private static boolean isDataSetItem(XmlNode node) {
            return "item".equals(node.tag) && ("DataSetQuery".equals(node.type)
                    || node.type != null && node.type.endsWith(":DataSetQuery"));
        }

        private static boolean queryOwner(ArrayDeque<XmlNode> stack, String kind) {
            String expected = kind.equals("form") ? "extInfo" : "dataSet";
            return stack.stream().anyMatch(node -> expected.equals(node.tag)
                    && (!kind.equals("form") || "DynamicListExtInfo".equals(node.type)
                            || node.type != null && node.type.endsWith(":DynamicListExtInfo")));
        }

        private void addMatches(List<Map<String, Object>> fileMatches, IFile file, String kind,
                String owner, int xmlLine, String text) {
            String[] lines = text.split("\\R", -1);
            for (int i = 0; i < lines.length && matches.size() + fileMatches.size() <= limit; i++) {
                String lower = lines[i].toLowerCase(Locale.ROOT);
                if (!lower.contains(needle)) continue;
                var row = new LinkedHashMap<String, Object>();
                row.put("path", file.getProjectRelativePath().toPortableString());
                row.put("kind", kind);
                row.put("owner", owner);
                row.put("xml_query_start_line", xmlLine);
                row.put("query_line", i + 1);
                String excerpt = lines[i].strip();
                row.put("excerpt", excerpt.substring(0, Math.min(excerpt.length(), 240)));
                fileMatches.add(row);
            }
        }
    }

    private static final class XmlNode {
        final String tag;
        final String type;
        String name = "";
        XmlNode(String tag, String type) {
            this.tag = tag;
            this.type = type;
        }
    }
}
