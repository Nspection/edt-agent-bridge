package com.codepilot1c.core.edt.debug;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Path;
import org.junit.Test;
import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.ast.SetBreakpointRequest;

public class EdtDebugServiceBreakpointTest {
    private static final String MODULE = "Catalogs/Items/Forms/List/Module.bsl";

    @Test public void createsRegisteredConditionalBreakpoint() {
        Fixture f = new Fixture(true);
        var result = f.service.setBreakpoint(new SetBreakpointRequest("Test", MODULE, 2, "Count = 3", true));
        assertTrue(result.created());
        assertEquals("41", result.breakpointId());
        assertEquals(1, f.manager.points.size());
        assertEquals("Count = 3", f.manager.points.get(0).condition);
        assertEquals(2, f.manager.points.get(0).marker.line);
    }

    @Test public void repeatedCallUpdatesExistingMarkerAcrossPathFormats() {
        Fixture f = new Fixture(true);
        var first = f.service.setBreakpoint(new SetBreakpointRequest("Test", MODULE, 2, "Count = 3", true));
        var second = f.service.setBreakpoint(new SetBreakpointRequest("Test", "src/" + MODULE, 2, null, false));
        assertFalse(second.created());
        assertEquals(first.breakpointId(), second.breakpointId());
        assertEquals(1, f.manager.points.size());
        assertFalse(f.manager.points.get(0).enabled);
        assertEquals("", f.manager.points.get(0).condition);
    }

    @Test public void windowsSeparatorsUseSameSourceLocation() {
        Fixture f = new Fixture(true);
        f.service.setBreakpoint(new SetBreakpointRequest("Test", MODULE.replace('/', '\\'), 2, null, true));
        assertEquals("src/" + MODULE, f.requestedFile);
    }

    @Test public void rejectsMissingFileAndLineOutsideSourceBeforeCreatingMarker() {
        Fixture missing = new Fixture(false);
        assertThrows(EdtAstException.class, () -> missing.service.setBreakpoint(
                new SetBreakpointRequest("Test", MODULE, 2, null, true)));
        Fixture shortFile = new Fixture(true);
        assertThrows(EdtAstException.class, () -> shortFile.service.setBreakpoint(
                new SetBreakpointRequest("Test", MODULE, 4, null, true)));
        assertEquals(0, missing.factory.calls);
        assertEquals(0, shortFile.factory.calls);
    }

    @Test public void rejectsAbsoluteAndTraversalPaths() {
        for (String path : List.of("../Module.bsl", "src/../Module.bsl", "/tmp/Module.bsl",
                "C:\\temp\\Module.bsl", "src//Module.bsl", "src/./Module.bsl", "Module.txt")) {
            Fixture f = new Fixture(true);
            assertThrows(path, EdtAstException.class, () -> f.service.setBreakpoint(
                    new SetBreakpointRequest("Test", path, 1, null, true)));
            assertEquals(0, f.factory.calls);
        }
    }

    @Test public void failedConfigurationDeletesUnregisteredMarker() {
        Fixture f = new Fixture(true);
        f.factory.failCondition = true;
        assertThrows(EdtAstException.class, () -> f.service.setBreakpoint(
                new SetBreakpointRequest("Test", MODULE, 2, "bad", true)));
        assertTrue(f.factory.last.deleted);
        assertTrue(f.manager.points.isEmpty());
    }

    static final class Fixture {
        final Manager manager = new Manager();
        final Factory factory = new Factory();
        final IProject project;
        final IFile file;
        final EdtDebugService service;
        String requestedFile;
        Fixture(boolean exists) {
            project = (IProject) Proxy.newProxyInstance(IProject.class.getClassLoader(), new Class<?>[]{IProject.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getName" -> "Test";
                        case "exists", "isOpen" -> true;
                        case "getFile" -> { requestedFile = args[0].toString(); yield file(); }
                        case "toString" -> "TestProject";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
            file = (IFile) Proxy.newProxyInstance(IFile.class.getClassLoader(), new Class<?>[]{IFile.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "exists" -> exists;
                        case "getContents" -> new ByteArrayInputStream("first\r\nsecond\r\nthird\r\n".getBytes(StandardCharsets.UTF_8));
                        case "getProject" -> project;
                        case "getProjectRelativePath" -> new Path("src/" + MODULE);
                        case "toString" -> MODULE;
                        default -> null;
                    });
            service = new EdtDebugService(null) {
                @Override protected IProject requireProject(String name) { return project; }
                @Override protected Object breakpointManager() { return manager; }
                @Override protected Object breakpointFactory() { return factory; }
            };
        }
        IFile file() { return file; }
    }
    public static final class Manager {
        final List<Point> points = new ArrayList<>();
        public Object[] getBreakpoints() { return points.toArray(); }
        public void addBreakpoint(Object point) { points.add((Point) point); }
    }
    public static final class Factory {
        int calls; boolean failCondition; Point last;
        public Object createLineBreakpoint(IResource file, int line) {
            calls++; last = new Point((IFile) file, line, failCondition); return last;
        }
    }
    public static final class Point {
        final Marker marker; boolean enabled; String condition; boolean deleted; final boolean fail;
        Point(IFile file, int line, boolean fail) { marker = new Marker(file, line); this.fail = fail; }
        public void setCondition(String value) { if (fail) throw new IllegalStateException("condition rejected"); condition = value; }
        public void setEnabled(boolean value) { enabled = value; }
        public void delete() { deleted = true; }
        public Marker getMarker() { return marker; }
        public boolean isEnabled() { return enabled; }
        public String getCondition() { return condition; }
        public String getModelIdentifier() { return "com._1c.g5.v8.dt.debug"; }
    }
    public static final class Marker {
        final IFile file; final int line;
        Marker(IFile file, int line) { this.file = file; this.line = line; }
        public IFile getResource() { return file; }
        public long getId() { return 41; }
        public int getAttribute(String key, int fallback) { return "lineNumber".equals(key) ? line : fallback; }
    }
}
