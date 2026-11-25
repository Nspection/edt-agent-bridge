package com.codepilot1c.core.tools.bsl;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import org.eclipse.core.runtime.FileLocator;

import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.Version;

import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.Gson;

@ToolMeta(name = "edt_platform_help", category = "bsl", tags = {"read-only", "edt"})
public final class EdtPlatformHelpTool extends AbstractTool {
    private static final String DOC_BUNDLE = "com._1c.g5.v8.dt.platform.doc";
    private static final String API_ROOT = "nl/ru/html/SyntaxHelperContext/";
    private static final int TITLE_SCAN_BYTES = 8192;
    private static final int MAX_PAGE_BYTES = 1024 * 1024;
    private static final int MAX_TEXT = 12000;
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern LEGACY_TITLE = Pattern.compile(
            "(?is)<h1\\b[^>]*\\bclass\\s*=\\s*[\"']V8SH_pagetitle[\"'][^>]*>(.*?)</h1>");
    private static final Pattern HIDDEN_HTML = Pattern.compile("(?is)<(?:head|script|style)\\b[^>]*>.*?</(?:head|script|style)\\s*>");
    private static final Pattern BLOCK = Pattern.compile("(?i)</?(?:div|p|li|tr|h[1-6]|pre|br)\\b[^>]*>");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private final Map<Bundle, List<Page>> indexes = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();

    private record Page(String title, String path) { }

    @Override
    public String getDescription() {
        return "Find a 1C platform API signature in EDT Syntax Helper (Russian or English), then read a matching page.";
    }

    @Override
    public String getParameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "query": {"type":"string","minLength":1,"maxLength":200},
                "path": {"type":"string","description":"Exact page path from a search result"},
                "bundle": {"type":"string","description":"Documentation bundle from the result; defaults to the generic reference"},
                "language": {"type":"string","enum":["ru","en"],"description":"Language of the returned page; default ru"},
                "limit": {"type":"integer","minimum":1,"maximum":20}
              },
              "oneOf": [{"required":["query"],"not":{"required":["path"]}},{"required":["path"],"not":{"required":["query"]}}]
            }
            """;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            var own = FrameworkUtil.getBundle(EdtPlatformHelpTool.class);
            var context = own == null ? null : own.getBundleContext();
            String bundleName = params.optString("bundle", DOC_BUNDLE);
            if (context == null || !(DOC_BUNDLE.equals(bundleName)
                    || bundleName.matches(Pattern.quote(DOC_BUNDLE) + "_v8_\\d+_\\d+"))) {
                return ToolResult.failure("Syntax Helper documentation bundle unavailable");
            }
            var candidates = java.util.Arrays.stream(context.getBundles())
                    .filter(bundle -> bundle.getState() != Bundle.UNINSTALLED)
                    .filter(bundle -> bundleName.equals(bundle.getSymbolicName())
                            || DOC_BUNDLE.equals(bundleName) && !params.getRaw().containsKey("bundle")
                                    && documentationVersion(bundle) != null)
                    .sorted(Comparator.comparing((Bundle bundle) -> bundleName.equals(bundle.getSymbolicName()))
                            .thenComparing(bundle -> java.util.Objects.requireNonNullElse(
                                    documentationVersion(bundle), Version.emptyVersion))
                            .thenComparing(Bundle::getVersion).reversed()).toList();
            if (candidates.isEmpty()) return ToolResult.failure("Syntax Helper documentation bundle is not installed: " + bundleName);

            String language = params.optString("language", "ru");
            if (!language.equals("ru") && !language.equals("en")) return ToolResult.failure("language must be ru or en");
            String path = params.optString("path", "").strip();
            String query = params.optString("query", "").strip();
            if (path.isEmpty() == query.isEmpty() || query.length() > 200) {
                return ToolResult.failure("Supply one of query (up to 200 characters) or path");
            }
            Bundle docs = null;
            List<Page> pages = List.of();
            for (Bundle candidate : candidates) {
                var entries = indexes.computeIfAbsent(candidate, EdtPlatformHelpTool::index);
                if (entries.isEmpty()) continue;
                docs = candidate;
                pages = entries;
                break;
            }
            if (docs == null) return ToolResult.failure("No API pages available in EDT Syntax Helper");
            String selectedBundle = docs.getSymbolicName();
            if (!path.isEmpty()) {
                var page = pages.stream().filter(item -> item.path().equals(path)).findFirst().orElse(null);
                if (page == null) return ToolResult.failure("Use the exact path from a Syntax Helper search result");
                String translated = path.replaceFirst("^/nl/ru/", "/nl/" + language + "/");
                String html = read(docs.getEntry(translated));
                if (html == null) return ToolResult.failure("Syntax Helper page is unavailable for language " + language);
                String full = plain(BLOCK.matcher(HIDDEN_HTML.matcher(html).replaceAll("")).replaceAll("\n"));
                String title = title(html);
                return result(Map.of("bundle", selectedBundle, "path", path, "language", language,
                        "title", title.isEmpty() ? page.title() : title,
                        "text", full.substring(0, Math.min(full.length(), MAX_TEXT)), "truncated", full.length() > MAX_TEXT));
            }

            String[] terms = query.toLowerCase(Locale.ROOT).split("[\\s.]+");
            if (terms.length == 0) return ToolResult.failure("Specify a platform API name to search");
            int limit = Math.min(20, Math.max(1, params.optInt("limit", 10)));
            String phrase = String.join(" ", terms);
            var hits = pages.stream().filter(page -> {
                String title = page.title().toLowerCase(Locale.ROOT);
                for (String term : terms) if (!title.contains(term)) return false;
                return true;
            }).sorted(Comparator.comparing((Page page) -> !(" "
                    + page.title().toLowerCase(Locale.ROOT).replaceAll("[.()]+", " ") + " ")
                    .contains(" " + phrase + " "))).limit(limit + 1L).toList();
            return result(Map.of("bundle", selectedBundle, "indexed", pages.size(),
                    "hits", hits.subList(0, Math.min(limit, hits.size())), "has_more", hits.size() > limit));
        });
    }

    private static Version documentationVersion(Bundle bundle) {
        String name = bundle.getSymbolicName();
        String prefix = DOC_BUNDLE + "_v";
        if (name == null || !name.matches(Pattern.quote(prefix) + "8_\\d+_\\d+")) return null;
        return Version.parseVersion(name.substring(prefix.length()).replace('_', '.'));
    }

    private ToolResult result(Map<String, ?> value) {
        var json = gson.toJsonTree(value).getAsJsonObject();
        return ToolResult.success(json.toString(), ToolResult.ToolResultType.CODE, json);
    }

    private static List<Page> index(Bundle bundle) {
        var pages = new ArrayList<Page>();
        var file = FileLocator.getBundleFileLocation(bundle);
        if (file.isPresent() && file.get().isFile()) {
            // Open the installed archive once instead of reopening it for every topic.
            try (var archive = new ZipFile(file.get())) {
                var entries = archive.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (!entry.getName().startsWith(API_ROOT) || !entry.getName().endsWith(".html")) continue;
                    try (var stream = archive.getInputStream(entry)) {
                        addTitle(pages, "/" + entry.getName(), stream.readNBytes(TITLE_SCAN_BYTES));
                    }
                }
            } catch (IOException unavailable) {
                pages.clear();
            }
        }
        if (pages.isEmpty()) {
            var entries = bundle.findEntries(API_ROOT, "*.html", true);
            if (entries != null) while (entries.hasMoreElements()) {
                URL entry = entries.nextElement();
                try (var stream = entry.openStream()) {
                    addTitle(pages, entry.getPath(), stream.readNBytes(TITLE_SCAN_BYTES));
                } catch (IOException unavailable) {
                    // One unavailable topic does not prevent searching the other topics.
                }
            }
        }
        pages.sort(Comparator.comparing(Page::title, String.CASE_INSENSITIVE_ORDER).thenComparing(Page::path));
        return List.copyOf(pages);
    }

    private static void addTitle(List<Page> pages, String path, byte[] prefix) {
        String title = title(new String(prefix, StandardCharsets.UTF_8));
        if (!title.isEmpty()) pages.add(new Page(title, path));
    }

    private static String title(String html) {
        var modern = TITLE.matcher(html);
        if (modern.find()) return plain(modern.group(1));
        var legacy = LEGACY_TITLE.matcher(html);
        return legacy.find() ? plain(legacy.group(1)) : "";
    }

    private static String read(URL page) {
        if (page == null) return null;
        try (var stream = page.openStream()) {
            byte[] bytes = stream.readNBytes(MAX_PAGE_BYTES + 1);
            return bytes.length <= MAX_PAGE_BYTES ? new String(bytes, StandardCharsets.UTF_8) : null;
        } catch (IOException unavailable) {
            return null;
        }
    }

    private static String plain(String html) {
        return TAG.matcher(html).replaceAll("").replace("&nbsp;", " ").replace("&#160;", " ")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
                .replaceAll("[\\t \\x0B]+", " ").replaceAll("\\n[ \\n]+", "\n").strip();
    }
}
