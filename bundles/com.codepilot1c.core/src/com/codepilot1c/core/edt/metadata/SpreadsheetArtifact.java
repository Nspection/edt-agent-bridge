// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.metadata;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.eclipse.core.resources.*;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.moxel.*;
import com.codepilot1c.core.edt.compare.SpreadsheetMergeCodec;

/** One format-aware boundary for legacy template creation, reading and full replacement. */
final class SpreadsheetArtifact {
    static final int MAX_BYTES = SpreadsheetMergeCodec.MAX_BYTES;
    enum Format { MXL, MXLX }
    record Selection(String path, Format format) {}
    private final IProject project;
    private final String directory;
    private final Map<String, String> snapshot;
    final Selection selection;

    static Selection select(String directory, boolean mxl, boolean mxlx, boolean create) {
        if (mxl && mxlx) throw new IllegalArgumentException("Ambiguous template: both Template.mxl and Template.mxlx exist");
        if (!mxl && !mxlx && !create) throw new IllegalArgumentException("Spreadsheet template artifact not found");
        Format format = mxl ? Format.MXL : Format.MXLX;
        return new Selection(directory + (format == Format.MXL ? "/Template.mxl" : "/Template.mxlx"), format);
    }

    static SpreadsheetArtifact resolve(IProject project, String directory, boolean create) throws Exception {
        if (directory == null) throw new IllegalArgumentException("Cannot resolve template directory");
        return new SpreadsheetArtifact(project, directory, create);
    }

    private SpreadsheetArtifact(IProject project, String directory, boolean create) throws Exception {
        this.project = project;
        this.directory = directory;
        snapshot = snapshot();
        selection = select(directory, snapshot.get("Template.mxl") != null, snapshot.get("Template.mxlx") != null, create);
    }

    static Path checkedPath(Path root, String relative) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        Path target = normalized.resolve(relative).normalize();
        if (!target.startsWith(normalized) || target.equals(normalized)) throw new IOException("Template escapes project");
        // toRealPath also detects Windows junctions, which isSymbolicLink alone misses.
        if (!normalized.toRealPath().equals(normalized)) throw new IOException("Linked project root is unsupported");
        Path current = normalized;
        for (Path segment : normalized.relativize(target)) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(current) || !current.toRealPath().equals(current)))
                throw new IOException("Linked template path is unsupported");
        }
        return target;
    }

    boolean exists() { return snapshot.values().stream().anyMatch(Objects::nonNull); }

    private Path path(String name) throws IOException {
        if (project.getLocation() == null || project.isLinked()) throw new IOException("Local unlinked project required");
        IFile file = project.getFile(directory + "/" + name);
        for (IResource resource = file; resource != null && resource != project; resource = resource.getParent())
            if (resource.isLinked()) throw new IOException("Linked template resource is unsupported");
        return checkedPath(project.getLocation().toFile().toPath(), directory + "/" + name);
    }

    private Map<String, String> snapshot() throws Exception {
        var result = new LinkedHashMap<String, String>();
        for (String name : List.of("Template.mxl", "Template.mxlx")) {
            Path file = path(name);
            String hash = null;
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Template is not a regular file");
                hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(readBounded(file)));
            }
            result.put(name, hash);
        }
        return result;
    }

    static byte[] readBounded(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Template exceeds 2 MiB; use bounded edt_spreadsheet inspection");
            return bytes;
        }
    }

    private static Resource resource(Format format, IDtProject context) {
        if (context == null) throw new IllegalStateException("EDT project context required for spreadsheet codec");
        if (format == Format.MXLX) {
            var resource = new MoxelResourceMxlx(URI.createURI("memory:/Template.mxlx"));
            resource.setDtProject(context);
            return resource;
        }
        var resource = new MoxelResourceMxl(URI.createURI("memory:/Template.mxl"));
        resource.setDtProject(context);
        return resource;
    }

    static SpreadsheetDocument decode(byte[] bytes, Format format, IDtProject context) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Template exceeds 2 MiB");
        if (format == Format.MXLX) {
            // XML preflight rejects DTD/entities before the native loader can resolve anything.
            // Decode according to XML declaration/BOM inside the secure parser, not a guessed charset.
            checkedXml(bytes);
        }
        Resource resource = resource(format, context);
        resource.load(new ByteArrayInputStream(bytes), Map.of());
        if (resource.getContents().size() != 1 || !(resource.getContents().get(0) instanceof SpreadsheetDocument sheet)
                || !resource.getErrors().isEmpty()) throw new IOException("Expected one valid SpreadsheetDocument");
        return sheet;
    }

    static void checkedXml(byte[] bytes) throws IOException {
        try {
            var f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setXIncludeAware(false);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = f.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            });
            var element = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
            if (!"document".equals(element.getLocalName()) || !"http://v8.1c.ru/8.2/data/spreadsheet".equals(element.getNamespaceURI()))
                throw new IOException("Expected spreadsheet XML root");
        } catch (Exception e) { throw new IOException("Invalid or unsafe spreadsheet XML", e); }
    }

    SpreadsheetDocument load(IDtProject context) throws Exception {
        String name = selection.format() == Format.MXL ? "Template.mxl" : "Template.mxlx";
        byte[] bytes = readBounded(path(name));
        if (!snapshot.equals(snapshot())) throw new IOException("Template changed during read; retry inspection");
        return decode(bytes, selection.format(), context);
    }

    void save(SpreadsheetDocument sheet, IDtProject context) throws Exception {
        ensureNoDirtyProjectEditors();
        Resource resource = resource(selection.format(), context);
        resource.getContents().add(sheet); // Detached generated document only; never move a BM object here.
        var out = new ByteArrayOutputStream();
        resource.save(out, Map.of("ENCODING", "UTF-8"));
        byte[] bytes = out.toByteArray();
        decode(bytes, selection.format(), context); // Validate before touching any source file.
        project.getWorkspace().run(monitor -> {
            try {
                if (!snapshot.equals(snapshot())) throw new IOException("Template changed before write; retry from fresh state");
                IFile file = project.getFile(selection.path());
                createParents(file.getParent());
                // Recheck links/siblings after parent creation; keep history for full replacement.
                if (!snapshot.equals(snapshot())) throw new IOException("Template changed before write");
                try (var input = new ByteArrayInputStream(bytes)) {
                    if (file.exists()) file.setContents(input, IResource.KEEP_HISTORY, monitor);
                    else file.create(input, false, monitor);
                }
            } catch (Exception e) {
                throw new org.eclipse.core.runtime.CoreException(new org.eclipse.core.runtime.Status(
                    org.eclipse.core.runtime.IStatus.ERROR, "com.codepilot1c.core", "Cannot safely save spreadsheet", e));
            }
        }, project, IWorkspace.AVOID_UPDATE, null);
    }

    private void ensureNoDirtyProjectEditors() throws Exception {
        if (!org.eclipse.ui.PlatformUI.isWorkbenchRunning()) return;
        var workbench = org.eclipse.ui.PlatformUI.getWorkbench();
        var result = new java.util.concurrent.CompletableFuture<Void>();
        Runnable inspect = () -> {
            try {
                for (var window : workbench.getWorkbenchWindows()) for (var page : window.getPages())
                    for (var reference : page.getEditorReferences()) {
                        var editor = reference.getEditor(false);
                        if (editor == null || !editor.isDirty()) continue;
                        IFile file = editor.getEditorInput().getAdapter(IFile.class);
                        if (file == null || project.equals(file.getProject()))
                            throw new IllegalStateException("Unsaved project editor; save or close it before replacing a template");
                    }
                result.complete(null);
            } catch (Throwable error) { result.completeExceptionally(error); }
        };
        if (workbench.getDisplay().getThread() == Thread.currentThread()) inspect.run();
        else workbench.getDisplay().asyncExec(inspect);
        result.get(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static void createParents(IContainer container) throws org.eclipse.core.runtime.CoreException {
        if (container instanceof IFolder folder && !folder.exists()) {
            createParents(folder.getParent());
            folder.create(false, true, null);
        }
    }
}
