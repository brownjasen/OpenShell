package org.openshell.ui;

import org.junit.jupiter.api.Test;
import org.openshell.transfer.TransferManager;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.openshell.transfer.TransferManager.*;

class TransferPanelTest {
    @Test void emptyQueueExplainsHowToStartAndEntryButtonsInvokeTheConfiguredActions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var queue = new TransferManager(() -> { throw new AssertionError("Guidance must not start transfers"); }, row -> new Decision(Choice.SKIP, false));
            try (TransferPanel panel = new TransferPanel(queue)) {
                AtomicInteger uploads = new AtomicInteger(), files = new AtomicInteger();
                panel.setFileActions(files::incrementAndGet, uploads::incrementAndGet);
                assertTrue(hasVisibleLabel(panel, "还没有传输任务"));
                assertFalse(visibleButton(panel, "上传文件").isEnabled(), "Wait for a known upload destination");
                panel.setUploadDirectory("/home/azureuser/项目");
                assertTrue(hasVisibleLabel(panel, "上传到：/home/azureuser/项目"));
                visibleButton(panel, "上传文件").doClick(); visibleButton(panel, "去文件页").doClick();
                assertEquals(1, uploads.get()); assertEquals(1, files.get());
                assertFalse(isVisibleWithin(UiRenderingRegressionTest.find(panel, JTable.class), panel));
                panel.close(); panel.updateRows(queue.snapshots());
                assertTrue(hasVisibleLabel(panel, "连接已断开"));
                assertFalse(visibleButton(panel, "上传文件").isEnabled());
            }
        });
    }

    @Test void guidanceReturnsAfterRecordsAreClearedAndTaskControlsExplainTheirEffect() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var queue = new TransferManager(() -> { throw new AssertionError(); }, row -> new Decision(Choice.SKIP, false));
            try (TransferPanel panel = new TransferPanel(queue)) {
                Request request = new Request(Direction.UPLOAD, Path.of("文件.txt"), "/文件.txt", UUID.randomUUID());
                panel.updateRows(List.of(new Snapshot(UUID.randomUUID(), request, State.COMPLETED, 0, 0, 0, "/文件.txt", "")));
                JTable table = UiRenderingRegressionTest.find(panel, JTable.class);
                assertTrue(isVisibleWithin(table, panel));
                assertFalse(hasVisibleLabel(panel, "还没有传输任务"));
                table.setRowSelectionInterval(0, 0);
                assertTrue(button(panel, "清理记录").getToolTipText().contains("不删除文件"));
                assertFalse(button(panel, "从头重试").isEnabled());
                panel.updateRows(List.of());
                assertTrue(hasVisibleLabel(panel, "还没有传输任务"));
                assertFalse(isVisibleWithin(table, panel));
            }
        });
    }

    private static boolean isVisibleWithin(Component child, Container root) {
        for (Component c = child; c != root; c = c.getParent()) if (c == null || !c.isVisible()) return false;
        return true;
    }
    private static JButton visibleButton(Container root, String name) {
        for (Component c : root.getComponents()) {
            if (!c.isVisible()) continue;
            if (c instanceof JButton b && name.equals(b.getText())) return b;
            if (c instanceof Container nested) { JButton b = visibleButton(nested, name); if (b != null) return b; }
        }
        return null;
    }
    private static boolean hasVisibleLabel(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (!c.isVisible()) continue;
            if (c instanceof JLabel label && label.getText() != null && label.getText().contains(text)) return true;
            if (c instanceof Container nested && hasVisibleLabel(nested, text)) return true;
        }
        return false;
    }
    @Test void sortedSelectionCancelsTheCorrectQueuedTaskAndRetryCreatesAFreshTask() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        var queue = new TransferManager(() -> new Transport() {
            public void execute(Request request, Control control) throws Exception { started.countDown(); release.await(); control.committing(); }
            public void close() { release.countDown(); }
        }, row -> new Decision(Choice.SKIP, false));
        AtomicReference<TransferPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panel.set(new TransferPanel(queue)));
        try {
            UUID active = queue.submit(Direction.UPLOAD, Path.of("a.txt"), "/a.txt");
            assertTrue(started.await(2, TimeUnit.SECONDS));
            UUID waiting = queue.submit(Direction.UPLOAD, Path.of("z.txt"), "/z.txt");
            SwingUtilities.invokeAndWait(() -> {
                panel.get().updateRows(queue.snapshots()); JTable table = UiRenderingRegressionTest.find(panel.get(), JTable.class);
                table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(1, SortOrder.DESCENDING)));
                table.setRowSelectionInterval(0, 0); button(panel.get(), "取消选中").doClick();
            });
            assertEquals(State.CANCELLED, queue.find(waiting).state()); assertEquals(State.RUNNING, queue.find(active).state());
            release.countDown(); await(() -> queue.find(active).state() == State.COMPLETED);
            SwingUtilities.invokeAndWait(() -> {
                panel.get().updateRows(queue.snapshots());
                assertTrue(button(panel.get(), "从头重试").isEnabled()); button(panel.get(), "从头重试").doClick();
            });
            await(() -> queue.snapshots().size() == 3 && queue.snapshots().get(2).state() == State.COMPLETED);
            assertNotEquals(waiting, queue.snapshots().get(2).id());
        } finally { release.countDown(); SwingUtilities.invokeAndWait(() -> panel.get().close()); }
    }

    @Test void rendersProgressCompletionAndFailureWithPersistentSelectionInBothThemes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (boolean dark : new boolean[]{true, false}) {
                    ThemeUtils.install(dark);
                    var queue = new TransferManager(() -> { throw new AssertionError("Rendering must not open a channel"); }, row -> new Decision(Choice.SKIP, false));
                    try (TransferPanel panel = new TransferPanel(queue)) {
                        panel.setFileActions(() -> {}, () -> {}); panel.setUploadDirectory("/home/azureuser/项目");
                        UUID active = UUID.randomUUID();
                        Request request = new Request(Direction.UPLOAD, Path.of("中文日志.log"), "/home/user/中文日志.log", UUID.randomUUID());
                        panel.updateRows(List.of(new Snapshot(active, request, State.RUNNING, 1024, 4096, 2048, request.remote(), ""),
                                new Snapshot(UUID.randomUUID(), request, State.COMPLETED, 4096, 4096, 0, request.remote(), ""),
                                new Snapshot(UUID.randomUUID(), request, State.FAILED, 1024, 4096, 0, request.remote(), "没有写入权限；原目标保留")));
                        JTable table = UiRenderingRegressionTest.find(panel, JTable.class);
                        table.setRowSelectionInterval(0, 0);
                        assertEquals(25, ((JProgressBar) table.prepareRenderer(table.getCellRenderer(0, 2), 0, 2)).getValue());
                        assertEquals(100, ((JProgressBar) table.prepareRenderer(table.getCellRenderer(1, 2), 1, 2)).getValue());
                        assertEquals(-1, table.getFont().canDisplayUpTo("中文日志"));
                        panel.setSize(com.formdev.flatlaf.util.UIScale.scale(1250), com.formdev.flatlaf.util.UIScale.scale(300));
                        panel.addNotify(); layout(panel);
                        var picture = new java.awt.image.BufferedImage(panel.getWidth(), panel.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                        var graphics = picture.createGraphics(); panel.paint(graphics); graphics.dispose();
                        Path path = Path.of("target/verification/transfers-" + (dark ? "dark" : "light") + "-" + System.getProperty("flatlaf.uiScale", "1.0") + ".png");
                        Files.createDirectories(path.getParent()); javax.imageio.ImageIO.write(picture, "png", path.toFile());
                        panel.updateRows(List.of()); layout(panel);
                        graphics = picture.createGraphics(); panel.paint(graphics); graphics.dispose();
                        javax.imageio.ImageIO.write(picture, "png", path.resolveSibling(path.getFileName().toString().replace("transfers-", "transfers-empty-")).toFile());
                        panel.removeNotify();
                    }
                }
            } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            finally { ThemeUtils.install(true); }
        });
    }
    private static JButton button(Container root, String name) {
        for (Component c : root.getComponents()) {
            if (c instanceof JButton button && name.equals(button.getText())) return button;
            if (c instanceof Container nested) { JButton found = button(nested, name); if (found != null) return found; }
        }
        return null;
    }
    private static void layout(Container container) { container.doLayout(); for (Component c : container.getComponents()) if (c instanceof Container child) layout(child); }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
}
