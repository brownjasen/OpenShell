package org.openshell.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.model.HostConfig;
import org.openshell.ssh.SshSession;
import org.openshell.storage.*;
import org.openshell.support.LocalSshServer;
import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceInteractionTest {
    @TempDir Path directory;
    private interface Check { boolean ready() throws Exception; }
    private static void await(Check check) { assertTimeoutPreemptively(Duration.ofSeconds(7), () -> { while (!check.ready()) Thread.sleep(20); }); }

    @Test void stagedDirectoryCommandDoesNotSendOrReplaceADraftAndSuccessfulInputIsRemembered() throws Exception {
        String previous = System.getProperty("openshell.configDir");
        System.setProperty("openshell.configDir", directory.resolve("config").toString());
        try (LocalSshServer server = new LocalSshServer(Files.createDirectory(directory.resolve("remote")))) {
            var connection = new SshSession(server.host(), (h, a, f) -> true);
            connection.connect();
            AtomicReference<TerminalPanel> panel = new AtomicReference<>();
            try {
                SwingUtilities.invokeAndWait(() -> panel.set(new TerminalPanel(connection)));
                SwingUtilities.invokeAndWait(() -> {
                    assertTrue(panel.get().stageCommand("pwd"));
                    assertFalse(panel.get().stageCommand("cd /etc"));
                    assertEquals("pwd", find(panel.get(), JTextField.class).getText());
                });
                assertNull(server.shell.received.poll(150, TimeUnit.MILLISECONDS), "Staging must not write to the shell");
                SwingUtilities.invokeAndWait(() -> find(panel.get(), JTextField.class).postActionEvent());
                assertEquals((int)'p', server.shell.received.poll(3, TimeUnit.SECONDS));
                await(() -> UiPreferences.current().history(connection.getConfig().getId(), "").contains("pwd"));
                assertEquals(java.util.List.of("pwd"), new UiPreferences(directory.resolve("config/workspace.json"))
                        .history(connection.getConfig().getId(), "pw"));
                SwingUtilities.invokeAndWait(() -> {
                    JTextField input = find(panel.get(), JTextField.class);
                    input.setText("echo unfinished");
                    input.getActionMap().get("history-1").actionPerformed(null);
                    assertEquals("pwd", input.getText());
                    input.getActionMap().get("history1").actionPerformed(null);
                    assertEquals("echo unfinished", input.getText());
                });
            } finally { if (panel.get() != null) SwingUtilities.invokeAndWait(panel.get()::close); connection.disconnect(); }
        } finally { restore(previous); }
    }

    @Test void restoresPerHostModeRatioAndMonitorAfterClosingTheView() throws Exception {
        String previous = System.getProperty("openshell.configDir");
        System.setProperty("openshell.configDir", directory.resolve("config").toString());
        try (LocalSshServer server = new LocalSshServer(Files.createDirectory(directory.resolve("remote")))) {
            var host = server.host();
            UiPreferences.current().update(state -> {
                state.view(host.getId()).layoutMode = 2; state.view(host.getId()).terminalRatio = 0.73;
                state.view(host.getId()).monitorVisible = false;
            });
            AtomicReference<SessionPanel> panel = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> panel.set(new SessionPanel(host, (c, p) -> {}, () -> new SshSession(host, (h, a, f) -> true))));
            try {
                await(() -> { AtomicReference<Boolean> ready = new AtomicReference<>(false);
                    SwingUtilities.invokeAndWait(() -> ready.set(find(panel.get(), JSplitPane.class) != null)); return ready.get(); });
                SwingUtilities.invokeAndWait(() -> {
                    JSplitPane split = find(panel.get(), JSplitPane.class);
                    assertNull(split.getTopComponent(), "Saved file-only mode must be restored");
                    assertFalse(find(panel.get(), MonitorPanel.class).isVisible());
                    panel.get().setSize(1100, 1200); layout(panel.get());
                    JComboBox<?> modes = find(panel.get(), JComboBox.class); modes.setSelectedIndex(0); layout(panel.get());
                    double ratio = split.getDividerLocation() / (double)(split.getHeight() - split.getDividerSize());
                    assertEquals(0.73, ratio, 0.02);
                });
            } finally { SwingUtilities.invokeAndWait(panel.get()::close); }
            assertEquals(0, new UiPreferences(directory.resolve("config/workspace.json")).snapshot().view(host.getId()).layoutMode);
            assertEquals(0.73, new UiPreferences(directory.resolve("config/workspace.json")).snapshot().view(host.getId()).terminalRatio);
        } finally { restore(previous); }
    }

    @Test void nestedHostTreeAndRecentConnectionsSurviveReload() throws Exception {
        String previous = System.getProperty("openshell.configDir");
        System.setProperty("openshell.configDir", directory.resolve("config").toString());
        try {
            HostConfig host = new HostConfig("示例主机", "127.0.0.1", 22, "test"); host.setGroup("生产/web/api");
            HostStorage.saveHosts(java.util.List.of(host));
            SwingUtilities.invokeAndWait(() -> {
                HostTreePanel panel = new HostTreePanel(config -> {});
                JTree tree = find(panel, JTree.class);
                DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
                assertEquals("生产", root.getChildAt(0).toString());
                assertEquals("web", root.getChildAt(0).getChildAt(0).toString());
                panel.recordConnection(host.getId(), 12345);
                HostTreePanel reloaded = new HostTreePanel(config -> {});
                DefaultMutableTreeNode newRoot = (DefaultMutableTreeNode) find(reloaded, JTree.class).getModel().getRoot();
                assertEquals("最近连接", newRoot.getChildAt(0).toString());
                assertEquals(12345, HostStorage.loadHosts().get(0).getLastConnected());
            });
        } finally { restore(previous); }
    }
    @Test void fileColumnsHiddenFilesAndTreeWidthAreRestoredWithoutLosingSessionLayout() throws Exception {
        String previous = System.getProperty("openshell.configDir");
        System.setProperty("openshell.configDir", directory.resolve("config").toString());
        try (LocalSshServer server = new LocalSshServer(Files.createDirectory(directory.resolve("remote")))) {
            var host = server.host();
            var connection = new SshSession(host, (h, a, f) -> true); connection.connect();
            UiPreferences.current().update(state -> state.view(host.getId()).terminalRatio = 0.71);
            AtomicReference<SftpPanel> panel = new AtomicReference<>();
            AtomicReference<java.util.List<Integer>> widths = new AtomicReference<>();
            try {
                SwingUtilities.invokeAndWait(() -> {
                    panel.set(new SftpPanel(connection, (c, p) -> {}));
                    panel.get().setSize(850, 350); layout(panel.get());
                    find(panel.get(), JSplitPane.class).setDividerLocation(146);
                    find(panel.get(), JCheckBox.class).doClick();
                    JTable table = find(panel.get(), JTable.class);
                    int[] sizes = {320, 110, 95, 160};
                    var values = new java.util.ArrayList<Integer>();
                    for (int i = 0; i < sizes.length; i++) {
                        table.getColumnModel().getColumn(i).setWidth(sizes[i]); values.add(sizes[i]);
                    }
                    widths.set(values); panel.get().close();
                    var saved = new UiPreferences(directory.resolve("config/workspace.json")).snapshot().view(host.getId());
                    assertEquals(widths.get(), saved.columns); assertTrue(saved.showHidden);
                    assertEquals(146, saved.directoryTreeWidth); assertEquals(0.71, saved.terminalRatio);
                    panel.set(new SftpPanel(connection, (c, p) -> {}));
                    assertTrue(find(panel.get(), JCheckBox.class).isSelected());
                    assertEquals(146, find(panel.get(), JSplitPane.class).getDividerLocation());
                    JTable reloaded = find(panel.get(), JTable.class);
                    for (int i = 0; i < sizes.length; i++) assertEquals(sizes[i], reloaded.getColumnModel().getColumn(i).getPreferredWidth());
                });
            } finally { if (panel.get() != null) SwingUtilities.invokeAndWait(panel.get()::close); connection.disconnect(); }
        } finally { restore(previous); }
    }
    private static void restore(String previous) { if (previous == null) System.clearProperty("openshell.configDir"); else System.setProperty("openshell.configDir", previous); }
    private static void layout(Container root) { root.doLayout(); for (Component c : root.getComponents()) if (c instanceof Container nested) layout(nested); }
    private static <T> T find(Container root, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) return type.cast(c);
            if (c instanceof Container nested) { T value = find(nested, type); if (value != null) return value; }
        }
        return null;
    }
}
