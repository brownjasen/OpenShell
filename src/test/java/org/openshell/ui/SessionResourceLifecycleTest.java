package org.openshell.ui;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.ssh.*;
import org.openshell.support.LocalSshServer;
import javax.swing.*;
import java.awt.event.HierarchyEvent;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class SessionResourceLifecycleTest {
    @TempDir Path temp;
    static class VisibleSession extends SessionPanel {
        boolean shown;
        VisibleSession(org.openshell.model.HostConfig host) { super(host, (c,p) -> {}, () -> new SshSession(host, (h,a,f) -> true)); }
        @Override public boolean isShowing() { return shown; }
        void showing(boolean value) {
            shown = value;
            var event = new HierarchyEvent(this, HierarchyEvent.HIERARCHY_CHANGED, this, null, HierarchyEvent.SHOWING_CHANGED);
            for (var listener : getHierarchyListeners()) listener.hierarchyChanged(event);
        }
    }
    @Test void invisibleSessionNeverSamplesAndVisibilityAndCheckboxControlItsSingleMonitor() throws Exception {
        String before = System.getProperty("openshell.configDir"); System.setProperty("openshell.configDir", temp.resolve("config").toString());
        AtomicReference<VisibleSession> view = new AtomicReference<>();
        try (var server = new LocalSshServer(Files.createDirectory(temp.resolve("remote")))) {
            SwingUtilities.invokeAndWait(() -> view.set(new VisibleSession(server.host())));
            long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(6);
            while (!view.get().isConnected() && System.nanoTime() < end) Thread.sleep(10);
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(view.get().isConnected());
                ServerMonitor monitor = field(view.get(), "monitor", ServerMonitor.class);
                assertFalse(monitor.isRunning(), "Hidden sessions must not launch resource probes");
                view.get().showing(true); assertTrue(monitor.isRunning());
                JCheckBox toggle = field(view.get(), "showMonitor", JCheckBox.class);
                toggle.doClick(); assertFalse(monitor.isRunning());
                toggle.doClick(); assertTrue(monitor.isRunning());
                view.get().showing(false); assertFalse(monitor.isRunning());
                view.get().showing(true); assertTrue(monitor.isRunning());
                view.get().close(); assertFalse(monitor.isRunning());
                view.get().showing(true); assertFalse(monitor.isRunning());
            });
        } finally {
            if (view.get() != null) SwingUtilities.invokeAndWait(view.get()::close);
            if (before == null) System.clearProperty("openshell.configDir"); else System.setProperty("openshell.configDir", before);
        }
    }
    static <T> T field(Object instance, String name, Class<T> type) {
        try { var f = SessionPanel.class.getDeclaredField(name); f.setAccessible(true); return type.cast(f.get(instance)); }
        catch (Exception e) { throw new AssertionError(e); }
    }
}
