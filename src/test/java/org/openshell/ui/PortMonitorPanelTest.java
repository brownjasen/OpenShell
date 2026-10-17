package org.openshell.ui;

import org.junit.jupiter.api.Test;
import org.openshell.ssh.*;
import org.openshell.ssh.PortParser.PortEntry;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class PortMonitorPanelTest {
    private static class Source implements PortSource {
        final AtomicInteger loads = new AtomicInteger();
        volatile boolean closed;
        public List<PortEntry> load() { loads.incrementAndGet(); return rows(); }
        public void close() { closed = true; }
    }
    static List<PortEntry> rows() {
        return List.of(new PortEntry("TCP", "0.0.0.0", 100, "LISTEN", 1000, "中文 worker"),
                new PortEntry("TCP6", "::", 9, "LISTEN", 90, "sshd"),
                new PortEntry("UDP", "127.0.0.1", 80, "UNCONN", null, "不可见（可能需要权限）"));
    }
    @Test void sortsPortsNumericallyPreservesSelectionFilterAndOnlyExplicitProcessActionCallsBack() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Source source = new Source();
            try (PortMonitorPanel panel = new PortMonitorPanel(source)) {
                panel.replacePorts(rows()); JTable table = find(panel, JTable.class);
                assertEquals(9, table.getValueAt(0, 2));
                table.getRowSorter().toggleSortOrder(2); assertEquals(100, table.getValueAt(0, 2));
                table.setRowSelectionInterval(0, 0); panel.replacePorts(List.of(rows().get(2), rows().get(0), rows().get(1)));
                assertEquals(100, table.getValueAt(table.getSelectedRow(), 2));
                AtomicInteger selectedPid = new AtomicInteger(); panel.setProcessSelectionConsumer(selectedPid::set);
                assertEquals(0, selectedPid.get()); button(panel, "查看进程").doClick(); assertEquals(1000, selectedPid.get());
                find(panel, JTextField.class).setText("中文"); assertEquals(1, table.getRowCount());
                panel.replacePorts(rows()); assertEquals(1, table.getRowCount());
                assertFalse(table.isCellEditable(0, 0)); assertEquals(0, source.loads.get(), "Hidden tab must not collect remotely");
                find(panel, JTextField.class).setText("UNCONN"); table.setRowSelectionInterval(0, 0);
                assertFalse(button(panel, "查看进程").isEnabled());
                JToggleButton auto = find(panel, JToggleButton.class); assertEquals("自动刷新：开 · 3秒", auto.getText());
                auto.doClick(); assertEquals("自动刷新：关", auto.getText());
                panel.close(); assertTrue(source.closed); assertEquals("自动刷新：已停止", auto.getText()); assertFalse(auto.isEnabled());
            }
        });
    }

    @Test void failedRefreshRetainsResultsAndSuccessfulTimeAcrossFilteringAndPause() throws Exception {
        AtomicReference<PortMonitorPanel> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            PortMonitorPanel panel = new PortMonitorPanel(new PortSource() {
                public List<PortEntry> load() throws Exception { throw new java.io.IOException("模拟超时"); }
                public void close() {}
            }); reference.set(panel); panel.replacePorts(rows()); panel.refreshPorts();
        });
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            AtomicBoolean failed = new AtomicBoolean();
            do {
                SwingUtilities.invokeAndWait(() -> failed.set(label(reference.get(), "模拟超时") != null));
                if (!failed.get()) Thread.sleep(10);
            } while (!failed.get() && System.nanoTime() < deadline);
            assertTrue(failed.get());
            SwingUtilities.invokeAndWait(() -> {
                PortMonitorPanel panel = reference.get(); String timestamp = label(panel, "最近更新");
                find(panel, JTextField.class).setText("80"); find(panel, JToggleButton.class).doClick();
                assertEquals(timestamp, label(panel, "最近更新")); assertNotNull(label(panel, "模拟超时"));
                assertEquals(1, find(panel, JTable.class).getRowCount());
            });
        } finally { SwingUtilities.invokeAndWait(() -> reference.get().close()); }
    }
    private static JButton button(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) return button;
            if (child instanceof Container nested) { JButton found = button(nested, text); if (found != null) return found; }
        } return null;
    }
    private static String label(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label && label.getText() != null && label.getText().contains(text)) return label.getText();
            if (child instanceof Container nested) { String found = label(nested, text); if (found != null) return found; }
        } return null;
    }
    private static <T> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) { T found = find(nested, type); if (found != null) return found; }
        } return null;
    }
}
