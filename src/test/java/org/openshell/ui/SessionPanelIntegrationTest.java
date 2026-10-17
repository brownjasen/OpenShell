package org.openshell.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.jediterm.terminal.ui.JediTermWidget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.ssh.SshSession;
import org.openshell.support.LocalSshServer;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class SessionPanelIntegrationTest {
    @TempDir Path directory;
    @Test void rendersWorkbenchFromRealSshStreamAndClosesItsSession() throws Exception {
        String previous = System.getProperty("openshell.configDir");
        System.setProperty("openshell.configDir", directory.resolve("config").toString());
        AtomicReference<SessionPanel> reference = new AtomicReference<>();
        Path root = Files.createDirectory(directory.resolve("server"));
        Files.createDirectories(root.resolve("etc"));
        Files.createDirectories(root.resolve("var"));
        Files.writeString(root.resolve("README.txt"), "isolated SSH test server");
        Files.write(root.resolve("large.bin"), new byte[2000]);
        try (LocalSshServer server = new LocalSshServer(root)) {
            var host = server.host();
            SwingUtilities.invokeAndWait(() -> {
                org.openshell.ui.ThemeUtils.install(true);
                reference.set(new SessionPanel(host, (client, path) -> {}, () -> new SshSession(host, (h, a, f) -> true)));
            });
            SessionPanel panel = reference.get();
            assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
                while (true) {
                    AtomicReference<String> text = new AtomicReference<>("");
                    SwingUtilities.invokeAndWait(() -> {
                        JediTermWidget terminal = find(panel, JediTermWidget.class);
                        if (terminal != null) {
                            var buffer = terminal.getTerminalTextBuffer();
                            buffer.lock(); try { text.set(buffer.getScreenLines().replace("\uE000", "")); } finally { buffer.unlock(); }
                        }
                    });
                    AtomicReference<Boolean> listed = new AtomicReference<>(false);
                    SwingUtilities.invokeAndWait(() -> {
                        JTable table = find(panel, JTable.class);
                        listed.set(table != null && table.getRowCount() >= 4);
                    });
                    if (text.get().contains("RED") && text.get().contains("中文") && listed.get()) break;
                    Thread.sleep(30);
                }
            });
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(panel.isConnected());
                TransferPanel transfers = find(panel, TransferPanel.class);
                JTabbedPane tools = (JTabbedPane) transfers.getParent();
                tools.setSelectedComponent(transfers);
                findVisibleButton(transfers, "去文件页").doClick();
                assertSame(find(panel, SftpPanel.class), tools.getSelectedComponent(), "The guidance must open this session's file browser");
                JTable files = find(panel, JTable.class);
                // Attach the header explicitly when rendering an undisplayed table.
                ((JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, files)).setColumnHeaderView(files.getTableHeader());
                files.getRowSorter().setSortKeys(java.util.List.of(new javax.swing.RowSorter.SortKey(1, SortOrder.ASCENDING)));
                assertEquals("large.bin", files.getValueAt(files.getRowCount() - 1, 0), "Sort sizes numerically, not as formatted text");
                files.getRowSorter().setSortKeys(java.util.List.of(new javax.swing.RowSorter.SortKey(0, SortOrder.ASCENDING)));
                panel.setSize(1100, 700); layout(panel);
                JSplitPane split = find(panel, JSplitPane.class);
                assertNotNull(split);
                split.setDividerLocation(0.64); layout(panel);
                // The local command row should meet the divider without an
                // always-reserved notification row, even after a message.
                TerminalPanel commandPanel = find(panel, TerminalPanel.class);
                assertNoGapBelowCommandInput(commandPanel);
                assertTrue(commandPanel.stageCommand("pwd"));
                layout(panel);
                assertNoGapBelowCommandInput(commandPanel);
                BufferedImage image = new BufferedImage(1100, 700, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics(); panel.printAll(graphics); graphics.dispose();
                try {
                    Path output = Path.of("target/verification/session-workbench" + imageSuffix() + ".png");
                    Files.createDirectories(output.getParent()); ImageIO.write(image, "png", output.toFile());
                } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            });
            SwingUtilities.invokeAndWait(() -> { panel.setSize(670, 450); layout(panel); });
            SwingUtilities.invokeAndWait(() -> {
                layout(panel);
                JTable compactFiles = find(panel, JTable.class);
                BufferedImage compact = new BufferedImage(670, 450, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = compact.createGraphics(); panel.printAll(graphics); graphics.dispose();
                try { ImageIO.write(compact, "png", Path.of("target/verification/session-workbench-compact" + imageSuffix() + ".png").toFile()); }
                catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                JSplitPane compactSplit = find(panel, JSplitPane.class);
                assertTrue(compactFiles.getVisibleRect().height >= compactFiles.getRowHeight(),
                        "Compact split must keep a file row visible: viewport=" + compactFiles.getVisibleRect()
                                + ", split=" + compactSplit.getSize() + ", topMin=" + compactSplit.getTopComponent().getMinimumSize()
                                + ", bottomMin=" + compactSplit.getBottomComponent().getMinimumSize());
            });
            SwingUtilities.invokeAndWait(panel::close);
            assertFalse(panel.isConnected());
        } finally {
            if (reference.get() != null) SwingUtilities.invokeAndWait(reference.get()::close);
            if (previous == null) System.clearProperty("openshell.configDir"); else System.setProperty("openshell.configDir", previous);
        }
    }
    private static String imageSuffix() {
        return System.getProperty("flatlaf.uiScale") == null ? "" : "-scale-" + System.getProperty("flatlaf.uiScale");
    }
    private static void assertNoGapBelowCommandInput(TerminalPanel panel) {
        JTextField input = find(panel, JTextField.class);
        Rectangle bounds = SwingUtilities.convertRectangle(input.getParent(), input.getBounds(), panel);
        assertTrue(panel.getHeight() - bounds.y - bounds.height <= 8,
                "A feedback message must not reserve a blank strip under the command input");
    }
    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }
    private static <T> T find(Container container, Class<T> type) {
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) { T result = find(nested, type); if (result != null) return result; }
        }
        return null;
    }
    private static JButton findVisibleButton(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (!child.isVisible()) continue;
            if (child instanceof JButton button && text.equals(button.getText())) return button;
            if (child instanceof Container nested) { JButton button = findVisibleButton(nested, text); if (button != null) return button; }
        }
        return null;
    }
}
