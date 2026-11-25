// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.jobs.MultiRule;
import org.eclipse.core.runtime.Status;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;

import com._1c.g5.v8.dt.bsl.compare.BslCompareUtils;
import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Module;
import com.codepilot1c.core.edt.ast.BmSyncHelper;

public final class BslMethodEditService {

    private record Range(int start, int end, boolean function) { }

    public Map<String, Object> execute(String action, String projectName, String path, String name,
            String expectedSha, String replacement, String turnId) throws Exception {
        if (!path.startsWith("src/") || !path.endsWith(".bsl")) {
            throw new IllegalArgumentException("Use a project-relative src/.../*.bsl path");
        }
        var project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.isOpen()) throw new IllegalArgumentException("EDT project is missing or closed: " + projectName);
        var file = EdtChangeSetService.file(project, path);
        EdtChangeSetService.ensureNotDirty(file);
        byte[] bytes = EdtChangeSetService.read(file);
        String hash = EdtChangeSetService.hash(bytes);
        if (!action.equals("read") && !hash.equalsIgnoreCase(expectedSha)) {
            throw new IllegalStateException("SOURCE_CHANGED: read the method again before replacing it");
        }

        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        String bom = text.startsWith("\uFEFF") ? "\uFEFF" : "";
        String source = text.substring(bom.length());
        Range original = method(source, name, false);
        if (action.equals("read")) {
            return Map.of("source_sha256", hash, "path", path, "name", name,
                    "text", source.substring(original.start(), original.end()),
                    "line", 1 + source.substring(0, original.start()).chars().filter(ch -> ch == '\n').count());
        }

        String originalText = source.substring(original.start(), original.end());
        if (replacement.equals(originalText)) {
            return Map.of("path", path, "name", name, "source_sha256", hash, "result_sha256", hash,
                    "changed", false, "applied", false);
        }
        String newline = source.contains("\r\n") ? "\r\n" : "\n";
        String proposed = replacement.strip().replace("\r\n", "\n").replace('\r', '\n').replace("\n", newline);
        Range candidate = method(proposed, name, true);
        if (candidate.function() != original.function()) {
            throw new IllegalArgumentException("Replacement must preserve procedure/function kind");
        }
        String updated = source.substring(0, original.start()) + proposed + source.substring(original.end());
        method(updated, name, false);
        byte[] after = (bom + updated).getBytes(StandardCharsets.UTF_8);
        if (after.length > 1024 * 1024) throw new IllegalArgumentException("Updated module exceeds 1 MB");
        String resultHash = EdtChangeSetService.hash(after);
        boolean changed = !hash.equals(resultHash);
        if (action.equals("apply") && changed) {
            write(file, bytes, after, turnId);
            BmSyncHelper.flushAfterWrite(file);
        }
        return Map.of("path", path, "name", name, "source_sha256", hash, "result_sha256", resultHash,
                "changed", changed, "applied", action.equals("apply") && changed);
    }

    private static void write(IFile file, byte[] expected, byte[] updated, String turnId) throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        var rules = workspace.getRuleFactory();
        var rule = MultiRule.combine(rules.refreshRule(file), rules.modifyRule(file));
        var failure = new AtomicReference<Exception>();
        Runnable edit = () -> {
            // Avoid keeping the UI busy during a conflicting workspace operation.
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(500);
            var progress = new NullProgressMonitor() {
                @Override public boolean isCanceled() {
                    return super.isCanceled() || System.nanoTime() >= deadline;
                }
            };
            try {
                workspace.run(monitor -> {
                    try {
                        EdtChangeSetService.ensureNotDirty(file);
                        if (!Arrays.equals(expected, EdtChangeSetService.read(file))) {
                            throw new IllegalStateException("SOURCE_CHANGED: read the method again before replacing it");
                        }
                        BslReviewService.trackWrite(turnId, file);
                        file.setContents(new ByteArrayInputStream(updated), IResource.KEEP_HISTORY, new NullProgressMonitor());
                    } catch (Exception error) {
                        throw new CoreException(Status.error(error.getMessage(), error));
                    }
                }, rule, IWorkspace.AVOID_UPDATE, progress);
            } catch (OperationCanceledException busy) {
                failure.set(new IllegalStateException("WORKSPACE_BUSY: retry after the EDT workspace operation settles", busy));
            } catch (Exception error) {
                failure.set(error);
            }
        };
        if (PlatformUI.isWorkbenchRunning() && Display.getCurrent() == null) {
            PlatformUI.getWorkbench().getDisplay().syncExec(edit);
        } else {
            edit.run();
        }
        if (failure.get() != null) throw failure.get();
    }

    private static Range method(String text, String name, boolean standalone) throws Exception {
        var parsed = BslCompareUtils.parseBslModuleContent(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        if (parsed == null || parsed.hasSyntaxErrors() || !(parsed.getRootASTElement() instanceof Module module)) {
            throw new IllegalArgumentException("EDT could not parse the BSL source");
        }
        var matches = module.allMethods().stream().filter(item -> name.equalsIgnoreCase(item.getName())).toList();
        if (matches.size() != 1 || standalone && module.allMethods().size() != 1) {
            throw new IllegalArgumentException("Expected one procedure or function named " + name);
        }
        var item = matches.get(0);
        var node = NodeModelUtils.getNode(item);
        if (node == null || standalone && (!text.substring(0, node.getOffset()).isBlank()
                || !text.substring(node.getEndOffset()).isBlank())) {
            throw new IllegalArgumentException("Replacement must contain only the named method");
        }
        return new Range(node.getOffset(), node.getEndOffset(), item instanceof Function);
    }
}
