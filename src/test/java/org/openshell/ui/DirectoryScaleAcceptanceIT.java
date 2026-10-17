package org.openshell.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.ssh.SshSession;
import org.openshell.support.LocalSshServer;
import javax.swing.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.openshell.ui.AcceptanceUiProbe.*;

/** Explicit-only acceptance: 10,000 actual files over loopback SFTP and real Swing table operations. */
class DirectoryScaleAcceptanceIT {
    @TempDir Path directory;
    @Test @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void tenThousandFileListingSortingFilteringAndSelectionRemainResponsive() throws Exception {
        String previous = System.getProperty("openshell.configDir");
        System.setProperty("openshell.configDir", directory.resolve("isolated-config").toString());
        Path remote = Files.createDirectory(directory.resolve("remote"));
        long preparation = System.nanoTime();
        for (int i = 0; i < 10000; i++) Files.writeString(remote.resolve(String.format(Locale.ROOT, "file-%05d-中文.txt", i)), "x".repeat(i % 101));
        long preparationMs = millis(System.nanoTime() - preparation);
        AtomicReference<SftpPanel> files = new AtomicReference<>();
        List<String> metrics = new ArrayList<>();
        metrics.add("Directory scale acceptance: actual loopback SSH/SFTP + headless Swing components");
        metrics.add("fixture_files=10000; fixture_creation_ms=" + preparationMs + "; max_EDT_lag_budget_ms=2000");
        metrics.add("listing_timing_boundary=combined SSH/SFTP fixture enumeration, round trips and initial Swing population; no separate source-only timing captured");
        try (LocalSshServer server = new LocalSshServer(remote)) {
            SshSession connection = new SshSession(server.host(), (h, a, f) -> true);
            try {
                connection.connect();
                edt(() -> { ThemeUtils.install(true); new JPanel().getPreferredSize(); return null; });
                try (Heartbeat heartbeat = new Heartbeat()) {
                    long listing = System.nanoTime();
                    edt(() -> { files.set(new SftpPanel(connection, (client, path) -> {})); return null; });
                    await(() -> edt(() -> table(files.get()).getRowCount() == 10000), 60, "Actual SFTP listing must display all 10000 files");
                    metrics.add("listing_and_initial_table_ms=" + millis(System.nanoTime() - listing));
                    timed(metrics, "numeric_sort", () -> {
                        JTable table = table(files.get());
                        table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(1, SortOrder.DESCENDING)));
                        assertEquals(100L, table.getValueAt(0, 1));
                        assertEquals(0L, table.getValueAt(table.getRowCount() - 1, 1));
                    });
                    timed(metrics, "filter_to_1000", () -> {
                        field(files.get(), "filterField", JTextField.class).setText("file-09");
                        assertEquals(1000, table(files.get()).getRowCount());
                    });
                    timed(metrics, "sorted_multiselection", () -> {
                        JTable table = table(files.get());
                        table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.DESCENDING)));
                        assertEquals("file-09999-中文.txt", table.getValueAt(0, 0));
                        assertEquals("file-09000-中文.txt", table.getValueAt(999, 0));
                        table.changeSelection(0, 0, false, false); table.changeSelection(999, 0, true, false);
                        assertEquals(List.of("file-09999-中文.txt", "file-09000-中文.txt"),
                                files.get().selectedItems().stream().map(item -> item.getName()).toList());
                        table.changeSelection(0, 0, false, false); table.changeSelection(49, 0, false, true);
                        assertEquals(50, files.get().selectedItems().size());
                        assertEquals("file-09950-中文.txt", files.get().selectedItems().get(49).getName());
                    });
                    timed(metrics, "restore_10000", () -> {
                        field(files.get(), "filterField", JTextField.class).setText("");
                        assertEquals(10000, table(files.get()).getRowCount());
                    });
                    heartbeat.drain();
                    metrics.add("heartbeat_samples=" + heartbeat.samples.get() + "; max_EDT_queue_lag_ms=" + millis(heartbeat.maximumLag.get()));
                    metrics.add("heap_used_bytes=" + heap() + "; app_threads=" + appThreads());
                    report("acceptance-directory-scale.txt", String.join("\n", metrics));
                    assertTrue(heartbeat.maximumLag.get() <= TimeUnit.SECONDS.toNanos(2),
                            "An EDT stall exceeded the 2 s usability budget; measured " + millis(heartbeat.maximumLag.get()) + " ms");
                    assertTrue(heartbeat.samples.get() > 0, "EDT heartbeat must have observed actual queued callbacks");
                }
            } finally {
                if (files.get() != null) edt(() -> { files.get().close(); return null; });
                connection.disconnect();
            }
            await(() -> server.server.getActiveSessions().isEmpty(), 8, "Directory fixture must have no retained SSH sessions");
        } finally {
            if (previous == null) System.clearProperty("openshell.configDir"); else System.setProperty("openshell.configDir", previous);
        }
    }
    private static JTable table(SftpPanel panel) { return field(panel, "fileTable", JTable.class); }
    private static void timed(List<String> metrics, String name, Runnable action) throws Exception {
        long duration = edt(() -> { long start = System.nanoTime(); action.run(); return millis(System.nanoTime() - start); });
        metrics.add(name + "_EDT_ms=" + duration);
    }
}
