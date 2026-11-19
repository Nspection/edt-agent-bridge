// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;

import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.emf.ecore.EReference;
import org.osgi.framework.FrameworkUtil;
import org.w3c.dom.Element;
import com._1c.g5.v8.dt.export.IExportFileSupport;
import com._1c.g5.v8.dt.export.IExportOperationFactory;
import com._1c.g5.v8.dt.export.IExportServiceRegistry;
import com._1c.g5.v8.dt.export.IExportStrategy;
import com._1c.g5.v8.dt.export.ZipArchiveBuilder;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.platform.version.Version;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.metadata.MetadataConfigurationCollections;
import com.codepilot1c.core.edt.metadata.MetadataKind;

/** Read-only inventory check before dispatching a native infobase update. */
final class InfobaseSourcePreflight {
    private static final String CONFIGURATION = "src/Configuration/Configuration.mdo";
    private static final int DIAGNOSTIC_LIMIT = 12;
    private static final Map<String,MetadataKind> KINDS = new HashMap<>();
    static {
        for (var kind : MetadataKind.values()) KINDS.put(kind.getFqnPrefix(), kind);
    }

    static void verify(IProject project, EdtMetadataGateway gateway, IProgressMonitor monitor, Version version)
            throws Exception {
        var file = project.getFile(CONFIGURATION);
        var configuration = gateway.getConfigurationProvider().getConfiguration(project);
        if (!file.exists() || configuration == null) throw blocked("source_inventory_unavailable");
        checkCanceled(monitor);
        var expected = sourceInventory(project, configuration, monitor);
        var models = gateway.getBmModelManager();
        boolean full = version != null;
        var support = full ? exportService(IExportServiceRegistry.class).getExportFileSupport(version) : null;
        var paths = models.executeReadOnlyTask(project, tx -> {
            Configuration resolved = tx.toTransactionObject(configuration);
            if (resolved == null) throw blocked("model_inventory_unavailable");
            var objects = new LinkedHashMap<String,MdObject>();
            for (var kind : MetadataKind.values()) {
                for (var object : MetadataConfigurationCollections.topLevelForKind(resolved, kind)) {
                    checkCanceled(monitor);
                    if (object == null || object.eIsProxy() || object.getName() == null) continue;
                    String fqn = kind.getFqnPrefix() + "." + object.getName();
                    objects.put(fqn, object);
                    if (object instanceof Subsystem subsystem) nestedModel(subsystem, fqn, objects);
                }
            }
            verifyModel(expected, objects.keySet());
            var outputs = new LinkedHashMap<String,Path>();
            if (full) {
                for (String fqn : expected) {
                    checkCanceled(monitor);
                    var object = objects.get(fqn);
                    if (!support.isExportable(object, version)) throw mismatch("object_not_exportable", List.of(fqn));
                    Path filePath = support.getOutputPath(object, version);
                    if (filePath == null) throw mismatch("export_path_unavailable", List.of(fqn));
                    outputs.put(fqn, filePath);
                }
            }
            return outputs;
        });
        if (full) verifyExport(configuration, version, paths, monitor);
    }

    private static Set<String> sourceInventory(IProject project, Configuration configuration, IProgressMonitor monitor)
            throws Exception {
        Set<String> expected = new LinkedHashSet<>();
        var file = project.getFile(CONFIGURATION);
        try (var input = file.getContents()) {
            var root = readXml(input);
            if (!"Configuration".equals(root.getLocalName())) throw blocked("invalid_configuration_source");
            for (var child : children(root)) {
                checkCanceled(monitor);
                String tag = child.getLocalName();
                String text = leaf(child);
                if ("content".equals(tag)) {
                    int dot = text.indexOf('.');
                    if (dot > 0 && KINDS.containsKey(text.substring(0, dot))) {
                        if (!validName(text.substring(dot + 1))) throw blocked("invalid_source_declaration");
                        expected.add(text);
                    }
                    continue;
                }
                var feature = configuration.eClass().getEStructuralFeature(tag);
                if (!(feature instanceof EReference ref) || !ref.isMany()
                        || Set.of("defaultRoles", "standaloneConfigurationRestrictionRoles").contains(tag)) continue;
                String type = ref.getEReferenceType().getName();
                if (!KINDS.containsKey(type)) continue;
                String name = text.startsWith(type + ".") ? text.substring(type.length() + 1)
                    : !text.isEmpty() ? text : children(child).stream()
                        .filter(e -> "name".equals(e.getLocalName())).map(InfobaseSourcePreflight::leaf)
                        .findFirst().orElse("");
                if (!validName(name)) throw blocked("invalid_source_declaration_" + type);
                expected.add(type + "." + name);
            }
        }
        for (String fqn : List.copyOf(expected)) {
            if (fqn.startsWith("Subsystem.")) {
                String name = fqn.substring("Subsystem.".length());
                nestedSource(project, "src/Subsystems/" + name, name, fqn, expected, monitor);
            }
        }
        return expected;
    }

    private static void nestedSource(IProject project, String folder, String name, String fqn, Set<String> expected,
            IProgressMonitor monitor) throws Exception {
        checkCanceled(monitor);
        var file = project.getFile(folder + "/" + name + ".mdo");
        if (!file.exists()) throw mismatch("missing_source_fqns", List.of(fqn));
        try (var input = file.getContents()) {
            var root = readXml(input);
            if (!"Subsystem".equals(root.getLocalName())) throw mismatch("invalid_source_fqns", List.of(fqn));
            for (var element : children(root)) {
                if (!"subsystems".equals(element.getLocalName())) continue;
                String child = leaf(element);
                if (!validName(child)) throw mismatch("invalid_nested_subsystem_declaration", List.of(fqn));
                String childFqn = fqn + ".Subsystem." + child;
                expected.add(childFqn);
                nestedSource(project, folder + "/Subsystems/" + child, child, childFqn, expected, monitor);
            }
        }
    }

    private static void nestedModel(Subsystem subsystem, String fqn, Map<String,MdObject> objects) {
        for (var child : subsystem.getSubsystems()) {
            if (child == null || child.eIsProxy() || child.getName() == null) continue;
            String childFqn = fqn + ".Subsystem." + child.getName();
            objects.put(childFqn, child);
            nestedModel(child, childFqn, objects);
        }
    }

    static void verifyModel(Set<String> expected, Set<String> actual) {
        List<String> missing = expected.stream().filter(fqn -> !actual.contains(fqn)).sorted().toList();
        if (!missing.isEmpty()) throw mismatch("missing_bm_fqns", missing);
        List<String> unexpected = actual.stream().filter(fqn -> !expected.contains(fqn)).sorted().toList();
        if (!unexpected.isEmpty()) throw mismatch("unexpected_bm_fqns", unexpected);
    }

    private static void verifyExport(Configuration configuration, Version version, Map<String,Path> expected,
            IProgressMonitor monitor) throws Exception {
        // The synchronization API owns its internal transfer package. This is an independent
        // native SDK export from the checked BM, before any connection or update is dispatched.
        Path root = Files.createTempDirectory("edt-bridge-preflight-").toAbsolutePath().normalize();
        try {
            checkCanceled(monitor);
            var exportMonitor = new NullProgressMonitor() {
                @Override public boolean isCanceled() {
                    return super.isCanceled() || monitor.isCanceled() || Thread.currentThread().isInterrupted();
                }
            };
            var factory = exportService(IExportOperationFactory.class);
            boolean zipped = version.isGreaterThan(Version.V8_3_19);
            Path archive = root.resolve("xml.zip");
            var export = zipped
                ? factory.createExportOperation(version, IExportStrategy.DEFAULT,
                    () -> ZipArchiveBuilder.create(archive, 1, 64L * 1024 * 1024), configuration)
                : factory.createExportOperation(root, version, configuration);
            var status = export.run(exportMonitor);
            checkCanceled(monitor);
            if (status == null || !status.isOK()) throw blocked("sdk_export_failed");
            if (zipped) {
                if (!Files.isRegularFile(archive)) throw blocked("export_archive_missing");
                try (var zip = FileSystems.newFileSystem(archive)) {
                    verifyPackage(zip.getPath("/"), expected);
                }
            } else {
                verifyPackage(root, expected);
            }
        } finally {
            // Files.walk does not follow links. Only the directory created above is traversed.
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (java.io.IOException cleanup) {
                // Keep the outcome of the preflight if a scanner briefly holds an XML file.
                com.codepilot1c.core.logging.VibeLogger.forClass(InfobaseSourcePreflight.class)
                    .warn("Could not remove the owned preflight export directory");
            }
        }
    }

    static void verifyPackage(Path root, Map<String,Path> expected) throws Exception {
        var declared = new TreeSet<String>();
        Path manifest = root.resolve("Configuration.xml");
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) throw blocked("export_manifest_missing");
        collectManifest(manifest, "", declared);
        for (var row : expected.entrySet()) {
            if (row.getKey().startsWith("Subsystem.")) {
                Path file = packagePath(root, row.getValue());
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) collectManifest(file, row.getKey(), declared);
            }
        }
        var missing = new TreeSet<String>();
        for (var row : expected.entrySet()) {
            Path file = packagePath(root, row.getValue());
            if (!declared.contains(row.getKey()) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) == 0) missing.add(row.getKey());
        }
        // For recognized metadata kinds, every declaration must have a known exported file.
        for (String fqn : declared) if (!expected.containsKey(fqn)) missing.add(fqn);
        if (!missing.isEmpty()) throw mismatch("missing_export_fqns", missing);
    }

    private static void collectManifest(Path file, String parent, Set<String> declared) throws Exception {
        try (var input = Files.newInputStream(file)) {
            var root = readXml(input);
            if (!"MetaDataObject".equals(root.getLocalName())) throw blocked("invalid_export_manifest");
            String expectedType = parent.isEmpty() ? "Configuration" : "Subsystem";
            var object = children(root).stream().filter(e -> expectedType.equals(e.getLocalName())).findFirst()
                .orElseThrow(() -> blocked("invalid_export_manifest"));
            for (var section : children(object)) {
                if (!"ChildObjects".equals(section.getLocalName())) continue;
                for (var entry : children(section)) {
                    String type = entry.getLocalName();
                    String name = leaf(entry);
                    if (KINDS.containsKey(type)) {
                        if (!validName(name)) throw blocked("invalid_export_declaration_" + type);
                        declared.add((parent.isEmpty() ? "" : parent + ".") + type + "." + name);
                    }
                }
            }
        }
    }

    private static Path packagePath(Path root, Path relative) {
        if (relative.getRoot() != null) relative = relative.getRoot().relativize(relative);
        Path result = root.resolve(relative.toString().replace((char) 92, '/')).normalize();
        if (!result.startsWith(root) || result.equals(root)) throw blocked("invalid_export_path");
        return result;
    }

    private static <T> T exportService(Class<T> api) throws Exception {
        try {
            var bundle = FrameworkUtil.getBundle(IExportOperationFactory.class);
            var plugin = bundle.loadClass("com._1c.g5.v8.dt.internal.export.ExportPlugin");
            var injector = plugin.getMethod("getInjector").invoke(plugin.getMethod("getDefault").invoke(null));
            var type = bundle.loadClass("com.google.inject.Injector");
            return api.cast(type.getMethod("getInstance", Class.class).invoke(injector, api));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            throw blocked("sdk_export_unavailable");
        }
    }

    private static Element readXml(InputStream input) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(input).getDocumentElement();
    }

    private static List<Element> children(Element element) {
        var result = new ArrayList<Element>();
        for (var child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element node) result.add(node);
        }
        return result;
    }

    private static String leaf(Element element) {
        return children(element).isEmpty() ? element.getTextContent().strip() : "";
    }

    private static boolean validName(String name) {
        return !name.isEmpty() && name.length() <= 192
            && name.codePoints().allMatch(c -> Character.isLetterOrDigit(c) || c == '_');
    }

    private static void checkCanceled(IProgressMonitor monitor) {
        if (monitor.isCanceled() || Thread.currentThread().isInterrupted()) throw new OperationCanceledException();
    }

    private static IllegalStateException mismatch(String reason, Collection<String> fqns) {
        return blocked(reason + "=" + fqns.stream().limit(DIAGNOSTIC_LIMIT).toList() + "; count=" + fqns.size());
    }

    static InfobaseSourceBarrier.PreflightBlocked blocked(String reason) {
        return new InfobaseSourceBarrier.PreflightBlocked("PROJECT_UPDATE_PREFLIGHT_BLOCKED: " + reason
            + "; native infobase update not dispatched");
    }
}
