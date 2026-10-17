package org.openshell.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.model.HostConfig;
import org.openshell.ssh.SshSession;
import org.openshell.storage.UiPreferences;
import org.openshell.support.LocalSshServer;
import javax.swing.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises actual SFTP listings and streamed terminal directory events with isolated settings. */
class SftpBatchSelectionIntegrationTest {
    @TempDir Path directory;

    @Test void ctrlAndShiftSelectionMapCorrectFilesAfterSortingAndFiltering() throws Exception {
        withFixture((server, host) -> {
            SshSession connection = new SshSession(host, (h, a, f) -> true);
            AtomicReference<SftpPanel> files = new AtomicReference<>();
            try {
                connection.connect();
                SwingUtilities.invokeAndWait(() -> files.set(new SftpPanel(connection, (client, path) -> {})));
                await(() -> onEdt(() -> table(files.get()).getRowCount() == 3));
                SwingUtilities.invokeAndWait(() -> {
                    JTable table = table(files.get());
                    assertEquals(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION, table.getSelectionModel().getSelectionMode());
                    table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(1, SortOrder.ASCENDING)));
                    assertEquals(List.of("alpha.txt", "zeta.txt", "beta.txt"), visibleNames(table));
                    table.changeSelection(0, 0, false, false);
                    table.changeSelection(2, 0, true, false); // Ctrl toggles a second non-contiguous row.
                    assertEquals(List.of("alpha.txt", "beta.txt"), selectedNames(files.get()));
                    table.changeSelection(0, 0, false, false);
                    table.changeSelection(2, 0, false, true); // Shift extends the contiguous range.
                    assertEquals(List.of("alpha.txt", "zeta.txt", "beta.txt"), selectedNames(files.get()));
                    field(files.get(), "filterField", JTextField.class).setText("ta");
                    table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.DESCENDING)));
                    assertEquals(List.of("zeta.txt", "beta.txt"), visibleNames(table));
                    table.changeSelection(0, 0, false, false);
                    table.changeSelection(1, 0, true, false);
                    assertEquals(List.of("zeta.txt", "beta.txt"), selectedNames(files.get()));
                    table.changeSelection(0, 0, true, false); // Ctrl removes a selected row.
                    assertEquals(List.of("beta.txt"), selectedNames(files.get()));
                });
            } finally {
                if (files.get() != null) SwingUtilities.invokeAndWait(files.get()::close);
                connection.disconnect();
            }
        });
    }

    @Test void sessionWiringFollowsOnlyWhenEnabledAndRetainsPerHostBookmarksAndPreference() throws Exception {
        withFixture((server, host) -> {
            Path root = directory.resolve("remote");
            Files.createDirectory(root.resolve("first")); Files.createDirectory(root.resolve("second"));
            AtomicReference<SessionPanel> session = new AtomicReference<>();
            try {
                SwingUtilities.invokeAndWait(() -> session.set(new SessionPanel(host, (client, path) -> {},
                        () -> new SshSession(host, (h, a, f) -> true))));
                await(() -> onEdt(() -> session.get().isConnected()
                        && field(session.get(), "sftpPanel", SftpPanel.class) != null
                        && table(field(session.get(), "sftpPanel", SftpPanel.class)).getRowCount() == 5));
                SftpPanel files = field(session.get(), "sftpPanel", SftpPanel.class);
                TerminalPanel terminal = field(session.get(), "terminalPanel", TerminalPanel.class);
                SwingUtilities.invokeAndWait(() -> {
                    assertFalse(field(files, "followTerminal", JCheckBoxMenuItem.class).isSelected(), "Following must default off");
                    assertTrue(UiPreferences.current().snapshot().view(host.getId()).directoryBookmarks.isEmpty());
                });
                server.shell.send("\u001b]7;file:///first\u0007");
                await(() -> onEdt(() -> "/first".equals(terminal.getCurrentDirectory())));
                SwingUtilities.invokeAndWait(() -> {
                    assertEquals("/", field(files, "pathField", JTextField.class).getText(), "An OSC event alone must not navigate");
                    field(files, "followTerminal", JCheckBoxMenuItem.class).doClick();
                });
                await(() -> onEdt(() -> "/first".equals(field(files, "pathField", JTextField.class).getText())));
                SwingUtilities.invokeAndWait(() -> field(files, "followTerminal", JCheckBoxMenuItem.class).doClick());
                server.shell.send("\u001b]7;file:///second\u001b\\");
                await(() -> onEdt(() -> "/second".equals(terminal.getCurrentDirectory())));
                SwingUtilities.invokeAndWait(() -> {
                    assertEquals("/first", field(files, "pathField", JTextField.class).getText());
                    field(files, "followTerminal", JCheckBoxMenuItem.class).doClick();
                });
                await(() -> onEdt(() -> "/second".equals(field(files, "pathField", JTextField.class).getText())));
                SwingUtilities.invokeAndWait(() -> UiPreferences.current().update(state ->
                        state.view(host.getId()).directoryBookmarks = new java.util.ArrayList<>(List.of("/first", "/second"))));
                SwingUtilities.invokeAndWait(session.get()::close); session.set(null);
                SwingUtilities.invokeAndWait(() -> session.set(new SessionPanel(host, (client, path) -> {},
                        () -> new SshSession(host, (h, a, f) -> true))));
                await(() -> onEdt(() -> field(session.get(), "sftpPanel", SftpPanel.class) != null));
                SwingUtilities.invokeAndWait(() -> {
                    SftpPanel reopened = field(session.get(), "sftpPanel", SftpPanel.class);
                    assertTrue(field(reopened, "followTerminal", JCheckBoxMenuItem.class).isSelected());
                    assertEquals(List.of("/first", "/second"), UiPreferences.current().snapshot().view(host.getId()).directoryBookmarks);
                    assertTrue(UiPreferences.current().snapshot().view("another-host").directoryBookmarks.isEmpty());
                    assertFalse(UiPreferences.current().snapshot().view("another-host").followTerminal);
                });
            } finally { if (session.get() != null) SwingUtilities.invokeAndWait(session.get()::close); }
        });
    }

    private interface Scenario { void run(LocalSshServer server, HostConfig host) throws Exception; }
    private void withFixture(Scenario scenario) throws Exception {
        String previous = System.getProperty("openshell.configDir");
        System.setProperty("openshell.configDir", directory.resolve("config").toString());
        Path root = Files.createDirectory(directory.resolve("remote"));
        Files.writeString(root.resolve("alpha.txt"), "a");
        Files.writeString(root.resolve("beta.txt"), "bbb");
        Files.writeString(root.resolve("zeta.txt"), "zz");
        try (LocalSshServer server = new LocalSshServer(root)) { scenario.run(server, server.host()); }
        finally {
            if (previous == null) System.clearProperty("openshell.configDir"); else System.setProperty("openshell.configDir", previous);
        }
    }
    private static JTable table(SftpPanel panel) { return field(panel, "fileTable", JTable.class); }
    private static List<String> selectedNames(SftpPanel panel) { return panel.selectedItems().stream().map(item -> item.getName()).toList(); }
    private static List<String> visibleNames(JTable table) {
        return java.util.stream.IntStream.range(0, table.getRowCount()).mapToObj(row -> String.valueOf(table.getValueAt(row, 0))).toList();
    }
    private static <T> T field(Object owner, String name, Class<T> type) {
        try { var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(owner)); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private interface Check { boolean ready() throws Exception; }
    private static void await(Check check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!check.ready() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(check.ready(), "Expected asynchronous SFTP/terminal state was not reached");
    }
    private static boolean onEdt(java.util.function.BooleanSupplier check) throws Exception {
        AtomicReference<Boolean> result = new AtomicReference<>(); SwingUtilities.invokeAndWait(() -> result.set(check.getAsBoolean())); return result.get();
    }
}
