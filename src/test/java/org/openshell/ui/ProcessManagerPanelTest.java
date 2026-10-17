package org.openshell.ui;

import org.junit.jupiter.api.Test;
import org.openshell.ssh.*;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ProcessManagerPanelTest {
    private static final class Source implements ProcessSource {
        final AtomicInteger loads = new AtomicInteger();
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        volatile boolean closed;
        public List<LinuxProcessSampler.ProcessInfo> load() throws Exception {
            loads.incrementAndGet(); started.countDown(); release.await(); return rows();
        }
        public void kill(int pid) { fail("No kill may be sent by sorting or selecting"); }
        public void close() { closed = true; }
    }
    static List<LinuxProcessSampler.ProcessInfo> rows() {
        return List.of(row(9, 9.0, 16.0, 102400, 90000), row(80, 80.0, 2.0, 2048, 12),
                row(100, 100.0, 8.0, 51200, 4000), row(5, null, 1.0, 1000, 1));
    }
    private static LinuxProcessSampler.ProcessInfo row(int pid, Double cpu, double memory, long rss, long elapsed) {
        return new LinuxProcessSampler.ProcessInfo(pid, "azureuser", cpu, memory, rss, elapsed, "中文 worker " + pid, 10);
    }

    @Test void sortsNumericallyInBothDirectionsAndKeepsSortFilterAndSelectionOnRefresh() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Source source = new Source();
            try (ProcessManagerPanel panel = new ProcessManagerPanel(source)) {
                panel.replaceProcesses(rows());
                JTable table = find(panel, JTable.class);
                assertEquals(100, table.getValueAt(0, 0), "Default CPU descending, unknown CPU last");
                assertNull(table.getValueAt(3, 2));
                clickHeader(table, 2);
                assertEquals(9, table.getValueAt(1, 0), "9 must sort before 80 and 100");
                clickHeader(table, 2);
                assertEquals(100, table.getValueAt(0, 0));
                table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(3, SortOrder.DESCENDING)));
                assertEquals(9, table.getValueAt(0, 0));
                table.getRowSorter().toggleSortOrder(3);
                assertEquals(5, table.getValueAt(0, 0));
                table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(4, SortOrder.DESCENDING)));
                assertEquals(9, table.getValueAt(0, 0));
                table.getSelectionModel().setSelectionInterval(1, 1);
                Object selectedPid = table.getValueAt(1, 0);
                panel.replaceProcesses(List.of(row(9, 0.0, 16.0, 1, 90002), row(100, 3.0, 8.0, 60000, 4002), row(80, 4.0, 2.0, 70000, 14)));
                assertEquals(4, table.getRowSorter().getSortKeys().get(0).getColumn());
                assertEquals(selectedPid, table.getValueAt(table.getSelectedRow(), 0));
                find(panel, JTextField.class).setText("中文 worker 80");
                assertEquals(1, table.getRowCount());
                panel.replaceProcesses(rows());
                assertEquals(1, table.getRowCount());
                assertEquals(80, table.getValueAt(0, 0));
                find(panel, JTextField.class).setText("");
                table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(5, SortOrder.DESCENDING)));
                assertEquals(9, table.getValueAt(0, 0), "Duration sorts by seconds, including days");
                assertTrue(((JLabel) table.prepareRenderer(table.getCellRenderer(0, 5), 0, 5)).getText().contains("1天"));
                assertEquals(0, source.loads.get(), "Hidden process tab must not start SSH collection");
            }
        });
    }

    private static void clickHeader(JTable table, int column) {
        table.setSize(1400, 200); table.doLayout();
        var header = table.getTableHeader(); header.setSize(1400, 30);
        int x = (int) header.getHeaderRect(column).getCenterX();
        header.dispatchEvent(new java.awt.event.MouseEvent(header, java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, x, 15, 1, false, java.awt.event.MouseEvent.BUTTON1));
    }

    @Test void rendersReadableChineseAndNumericColumnsInBothThemes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (boolean dark : new boolean[]{true, false}) {
                    ThemeUtils.install(dark);
                    try (ProcessManagerPanel panel = new ProcessManagerPanel(new Source())) {
                        find(panel, JToggleButton.class).setSelected(false);
                        panel.replaceProcesses(rows());
                        panel.setSize(com.formdev.flatlaf.util.UIScale.scale(1250), com.formdev.flatlaf.util.UIScale.scale(320));
                        panel.addNotify(); layout(panel);
                        JTable table = find(panel, JTable.class);
                        assertEquals(-1, table.getFont().canDisplayUpTo("中文 worker azureuser"));
                        JLabel header = (JLabel) table.getTableHeader().getDefaultRenderer()
                                .getTableCellRendererComponent(table, "CPU %", false, false, -1, 2);
                        assertNotNull(header.getIcon(), "Sorted column must show an ascending/descending indicator");
                        var picture = new java.awt.image.BufferedImage(panel.getWidth(), panel.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                        var graphics = picture.createGraphics(); panel.paint(graphics); graphics.dispose();
                        var path = java.nio.file.Path.of("target/verification/processes-" + (dark ? "dark" : "light")
                                + "-" + System.getProperty("flatlaf.uiScale", "1.0") + ".png");
                        java.nio.file.Files.createDirectories(path.getParent());
                        javax.imageio.ImageIO.write(picture, "png", path.toFile());
                        find(panel, JToggleButton.class).setSelected(true);
                        layout(panel);
                        graphics = picture.createGraphics(); panel.paint(graphics); graphics.dispose();
                        javax.imageio.ImageIO.write(picture, "png", path.resolveSibling(path.getFileName().toString().replace(".png", "-auto-on.png")).toFile());
                        panel.removeNotify();
                    }
                }
            } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            finally { ThemeUtils.install(true); }
        });
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }

    @Test void refreshControlStatesAreExplicitEvenWhileAnExistingSampleIsRunning() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try (ProcessManagerPanel panel = new ProcessManagerPanel(new Source())) {
                JToggleButton toggle = find(panel, JToggleButton.class);
                assertEquals("自动刷新：开 · 3秒", toggle.getText());
                assertTrue(toggle.getAccessibleContext().getAccessibleDescription().contains("已开启"));
                toggle.doClick();
                assertEquals("自动刷新：关", toggle.getText());
                panel.refreshProcesses();
                toggle.doClick();
                assertEquals("自动刷新：开 · 3秒", toggle.getText());
                toggle.doClick();
                assertEquals("自动刷新：关", toggle.getText());
                assertTrue(hasLabel(panel, "自动刷新已关闭"));
                assertTrue(hasLabel(panel, "正在"), "Turning automatic refresh off must still show the in-flight sample");
                panel.close();
                assertEquals("自动刷新：已停止", toggle.getText());
                assertFalse(toggle.isEnabled());
            }
        });
    }

    @Test void lastSuccessfulUpdateIsVisibleAndDoesNotChangeWhenFilteringOrPausing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try (ProcessManagerPanel panel = new ProcessManagerPanel(new Source())) {
                assertTrue(hasLabel(panel, "尚未更新"));
                panel.replaceProcesses(rows());
                String before = labelText(panel, "最近更新 ");
                assertTrue(before.matches("最近更新 \\d{2}:\\d{2}:\\d{2}"));
                find(panel, JTextField.class).setText("80");
                find(panel, JToggleButton.class).doClick();
                assertEquals(before, labelText(panel, "最近更新 "));
            }
        });
    }

    private static boolean hasLabel(Container root, String text) { return labelText(root, text) != null; }
    private static String labelText(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label && label.getText() != null && label.getText().contains(text)) return label.getText();
            if (child instanceof Container nested) { String found = labelText(nested, text); if (found != null) return found; }
        }
        return null;
    }

    @Test void failedSampleKeepsItsErrorAndLastSuccessfulTimeWhenAutomaticRefreshIsToggled() throws Exception {
        ProcessSource source = new ProcessSource() {
            public List<LinuxProcessSampler.ProcessInfo> load() throws Exception { throw new java.io.IOException("测试采集失败"); }
            public void kill(int pid) { fail("Must not terminate any process"); }
            public void close() {}
        };
        AtomicReference<ProcessManagerPanel> reference = new AtomicReference<>();
        AtomicReference<String> successfulTime = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ProcessManagerPanel panel = new ProcessManagerPanel(source); reference.set(panel);
            panel.replaceProcesses(rows()); successfulTime.set(labelText(panel, "最近更新 "));
            panel.refreshProcesses();
        });
        try {
            AtomicBoolean failed = new AtomicBoolean();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            do {
                SwingUtilities.invokeAndWait(() -> failed.set(hasLabel(reference.get(), "测试采集失败")));
                if (failed.get()) break;
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            assertTrue(failed.get());
            SwingUtilities.invokeAndWait(() -> {
                ProcessManagerPanel panel = reference.get();
                find(panel, JToggleButton.class).doClick();
                assertTrue(hasLabel(panel, "自动刷新已关闭"));
                assertTrue(hasLabel(panel, "测试采集失败"));
                assertEquals(successfulTime.get(), labelText(panel, "最近更新 "));
                assertEquals(rows().size(), find(panel, JTable.class).getRowCount());
            });
        } finally { SwingUtilities.invokeAndWait(() -> reference.get().close()); }
    }

    @Test void refreshIsSingleFlightAndCloseRejectsLateResults() throws Exception {
        Source source = new Source(); AtomicReference<ProcessManagerPanel> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { reference.set(new ProcessManagerPanel(source)); reference.get().refreshProcesses(); });
        assertTrue(source.started.await(3, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> { reference.get().refreshProcesses(); reference.get().close(); });
        source.release.countDown();
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(0, find(reference.get(), JTable.class).getRowCount());
            assertEquals(1, source.loads.get()); assertTrue(source.closed);
        });
    }

    @Test void showingStartsSamplingWhileHiddenAndPausedTabsStopPolling() throws Exception {
        Source source = new Source(); source.release.countDown();
        AtomicReference<ProcessManagerPanel> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ProcessManagerPanel panel = new ProcessManagerPanel(source); reference.set(panel); panel.addNotify();
            assertTrue(panel.isShowing());
        });
        try {
            assertTrue(source.started.await(2, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> reference.get().setVisible(false));
            Thread.sleep(3200);
            assertEquals(1, source.loads.get(), "A hidden tab must stop periodic SSH requests");
            SwingUtilities.invokeAndWait(() -> reference.get().setVisible(true));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (source.loads.get() < 2 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(2, source.loads.get());
            SwingUtilities.invokeAndWait(() -> find(reference.get(), JToggleButton.class).doClick());
            Thread.sleep(3200);
            assertEquals(2, source.loads.get(), "Pause must stop periodic SSH requests");
        } finally {
            SwingUtilities.invokeAndWait(() -> { reference.get().close(); reference.get().removeNotify(); });
        }
    }

    private static <T> T find(Container root, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) return type.cast(c);
            if (c instanceof Container container) { T result = findOrNull(container, type); if (result != null) return result; }
        }
        throw new AssertionError("Missing " + type);
    }
    private static <T> T findOrNull(Container root, Class<T> type) {
        try { return find(root, type); } catch (AssertionError ignored) { return null; }
    }
}
