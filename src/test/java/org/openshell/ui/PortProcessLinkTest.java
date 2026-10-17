package org.openshell.ui;

import org.junit.jupiter.api.Test;
import org.openshell.ssh.*;
import org.openshell.ssh.PortParser.PortEntry;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class PortProcessLinkTest {
    private static LinuxProcessSampler.ProcessInfo process(int pid) {
        return new LinuxProcessSampler.ProcessInfo(pid, "test", 0.0, 1, 1024, 1, "worker " + pid, pid);
    }
    @Test void explicitPortActionSelectsExactPidInsteadOfMatchingOtherPidSubstrings() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ProcessSource processSource = new ProcessSource() {
                public List<LinuxProcessSampler.ProcessInfo> load() { return List.of(process(80), process(808)); }
                public void kill(int pid) { fail("Viewing a port must never kill a process"); }
                public void close() {}
            };
            PortSource portSource = new PortSource() {
                public List<PortEntry> load() { fail("Hidden port view must not collect"); return List.of(); }
                public void close() {}
            };
            try (ProcessManagerPanel processes = new ProcessManagerPanel(processSource); PortMonitorPanel ports = new PortMonitorPanel(portSource)) {
                processes.replaceProcesses(List.of(process(80), process(808)));
                ports.replacePorts(List.of(new PortEntry("TCP", "::", 443, "LISTEN", 80, "worker")));
                ports.setProcessSelectionConsumer(processes::showPid);
                JTable portTable = find(ports, JTable.class); portTable.setRowSelectionInterval(0, 0);
                button(ports, "查看进程").doClick();
                JTable processTable = find(processes, JTable.class);
                assertEquals(1, processTable.getRowCount()); assertEquals(80, processTable.getValueAt(0, 0)); assertEquals(0, processTable.getSelectedRow());
                find(processes, JTextField.class).setText(""); assertEquals(2, processTable.getRowCount(), "User can leave exact-PID view using the search clear button");
            }
        });
    }
    @Test void pendingPidSelectionIsAppliedWhenRequestedProcessArrivesInRefreshedData() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<ProcessManagerPanel> reference = new AtomicReference<>();
        ProcessSource source = new ProcessSource() {
            public List<LinuxProcessSampler.ProcessInfo> load() throws Exception { started.countDown(); release.await(); return List.of(process(77), process(770)); }
            public void kill(int pid) { fail("Navigation must never kill a process"); }
            public void close() { release.countDown(); }
        };
        SwingUtilities.invokeAndWait(() -> { ProcessManagerPanel panel = new ProcessManagerPanel(source); reference.set(panel); panel.showPid(77); });
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS)); release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2); AtomicBoolean selected = new AtomicBoolean();
            do {
                SwingUtilities.invokeAndWait(() -> { JTable table = find(reference.get(), JTable.class); selected.set(table.getRowCount() == 1 && table.getSelectedRow() == 0 && table.getValueAt(0, 0).equals(77)); });
                if (!selected.get()) Thread.sleep(10);
            } while (!selected.get() && System.nanoTime() < deadline);
            assertTrue(selected.get());
        } finally { SwingUtilities.invokeAndWait(() -> reference.get().close()); }
    }
    private static JButton button(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) return button;
            if (child instanceof Container nested) { JButton found = button(nested, text); if (found != null) return found; }
        } return null;
    }
    private static <T> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) { T found = find(nested, type); if (found != null) return found; }
        } return null;
    }
}
