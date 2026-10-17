package org.openshell.ui;

import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.ui.JediTermWidget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.model.HostConfig;
import org.openshell.session.SessionState;
import org.openshell.ssh.SshSession;
import org.openshell.support.LocalSshServer;
import javax.swing.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.openshell.ui.AcceptanceUiProbe.*;

/** Explicit-only, bounded 70-second soak of eight genuine SessionPanel SSH/SFTP connections. */
class SessionSoakAcceptanceIT {
    @TempDir Path directory;
    @Test @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void eightSessionsStreamUnicodeAndSurviveThreeTransportReconnectCyclesWithoutRetainedSessions() throws Exception {
        String previous = System.getProperty("openshell.configDir");
        System.setProperty("openshell.configDir", directory.resolve("isolated-config").toString());
        List<LocalSshServer> servers = new ArrayList<>();
        List<SessionPanel> panels = new ArrayList<>();
        List<SshSession> connections = new CopyOnWriteArrayList<>();
        List<String> metrics = new ArrayList<>();
        long baselineThreads = appThreads(), baselineAllThreads = Thread.getAllStackTraces().size();
        long baselineHeap = heap(), peakHeap = baselineHeap, peakThreads = baselineThreads;
        int frames = 0, reconnectCycles = 0, inputChecks = 0;
        AtomicReference<String> lastRenderMismatch = new AtomicReference<>("");
        long start = 0, duration = 0;
        metrics.add("Eight-session acceptance: actual loopback SSH/SFTP + headless SessionPanel components");
        metrics.add("duration_target_seconds=70; concurrent_sessions=8; forced_disconnect_reconnect_cycles=3");
        metrics.add("boundary=hidden-page monitors and auto-refresh remain paused; native window interaction not simulated");
        try (Heartbeat heartbeat = new Heartbeat()) {
            try {
                edt(() -> { ThemeUtils.install(true); return null; });
                for (int index = 0; index < 8; index++) {
                    Path root = Files.createDirectory(directory.resolve("remote-" + index));
                    Files.writeString(root.resolve("fixture-中文.txt"), "isolated");
                    LocalSshServer server = new LocalSshServer(root); servers.add(server);
                    HostConfig host = server.host(); host.setName("Acceptance session " + index);
                    panels.add(edt(() -> new SessionPanel(host, (client, path) -> {}, () -> {
                        SshSession connection = new SshSession(host, (h, a, f) -> true); connections.add(connection); return connection;
                    })));
                }
                awaitReady(panels, servers);
                start = System.nanoTime();
                long nextReconnect = start + TimeUnit.SECONDS.toNanos(18);
                long nextInput = start;
                int tick = 0;
                while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(70)) {
                    if (reconnectCycles < 3 && System.nanoTime() >= nextReconnect) {
                        for (LocalSshServer server : servers) server.server.getActiveSessions().forEach(session -> session.close(true));
                        await(() -> edt(() -> panels.stream().allMatch(panel -> panel.getSessionState() == SessionState.DISCONNECTED
                                        || panel.getSessionState() == SessionState.FAILED)), 12,
                                "All eight panels must observe the forced transport disconnection");
                        await(() -> servers.stream().allMatch(server -> server.server.getActiveSessions().isEmpty()), 8,
                                "Retired transports must close before the next connection cycle");
                        edt(() -> { panels.forEach(SessionPanel::reconnect); return null; });
                        awaitReady(panels, servers); reconnectCycles++;
                        nextReconnect = start + TimeUnit.SECONDS.toNanos(18L * (reconnectCycles + 1));
                    }
                    int frame = tick++;
                    for (int index = 0; index < 8; index++) servers.get(index).shell.send("\u001b[2J\u001b[H\u001b[31mROUND-"
                            + frame + "-SESSION-" + index + "\u001b[0m\r\n中文-验证-é\r\n$ ");
                    await(() -> edt(() -> {
                        for (int index = 0; index < panels.size(); index++) {
                            TerminalPanel terminal = field(panels.get(index), "terminalPanel", TerminalPanel.class);
                            JediTermWidget widget = find(terminal, JediTermWidget.class);
                            var buffer = widget.getTerminalTextBuffer(); buffer.lock();
                            try {
                                String rawScreen = buffer.getScreenLines();
                                // JediTerm stores a U+E000 continuation cell after each wide glyph.
                                // Compare rendered text, as the existing terminal integration tests do.
                                String screen = rawScreen.replace("\uE000", "");
                                if (!screen.contains("ROUND-" + frame + "-SESSION-" + index) || !screen.contains("中文-验证-é")
                                        || !TerminalColor.index(1).equals(buffer.getStyleAt(0, 0).getForeground())) {
                                    String mismatch = "session=" + index + "; width=" + buffer.getWidth() + "; height=" + buffer.getHeight()
                                            + "; first_foreground=" + buffer.getStyleAt(0, 0).getForeground()
                                            + "; foreground_matches_expected=" + TerminalColor.index(1).equals(buffer.getStyleAt(0, 0).getForeground())
                                            + "; raw_screen=" + rawScreen.replace("\uE000", "<WIDE_CELL>").replace("\n", "\\n");
                                    lastRenderMismatch.set(mismatch);
                                    return false;
                                }
                            } finally { buffer.unlock(); }
                        }
                        return true;
                    }), 8, "All terminals must render this cycle's ANSI and Unicode frame; latest buffer diagnostic follows in failure artifact");
                    frames += 8;
                    if (System.nanoTime() >= nextInput) {
                        String command = "acceptance-input-" + frame;
                        edt(() -> { for (SessionPanel panel : panels) field(panel, "terminalPanel", TerminalPanel.class).executeCommandLine(command); return null; });
                        for (LocalSshServer server : servers) {
                            for (byte value : (command + "\r").getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                assertEquals(value & 255, server.shell.received.poll(3, TimeUnit.SECONDS), "Input must reach each live SSH shell exactly");
                            assertNull(server.shell.received.poll(), "One explicit command must not produce duplicate input");
                        }
                        inputChecks += 8; nextInput = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    }
                    peakHeap = Math.max(peakHeap, heap()); peakThreads = Math.max(peakThreads, appThreads());
                    Thread.sleep(180);
                }
                duration = millis(System.nanoTime() - start);
                assertEquals(3, reconnectCycles, "The bounded soak must actually complete all reconnection cycles");
                assertEquals(32, connections.size(), "Eight initial connections and three full reconnection cycles expected");
            } finally {
                try {
                    edt(() -> { panels.forEach(SessionPanel::close); return null; });
                    await(() -> servers.stream().allMatch(server -> server.server.getActiveSessions().isEmpty()), 12,
                            "Closed workbenches must retain no server-side SSH sessions");
                    assertTrue(connections.stream().noneMatch(SshSession::isConnected), "All created client SSH sessions must close");
                } finally {
                    metrics.add("server_sessions_before_fixture_stop=" + servers.stream().mapToLong(server -> server.server.getActiveSessions().size()).sum());
                    java.io.IOException cleanupFailure = null;
                    for (LocalSshServer server : servers) {
                        try { server.close(); } catch (java.io.IOException failure) { cleanupFailure = failure; }
                    }
                    panels.clear(); connections.clear(); servers.clear();
                    if (cleanupFailure != null) throw cleanupFailure;
                }
            }
            heartbeat.drain();
            long ownedBaseline = baselineThreads;
            await(() -> appThreads() <= ownedBaseline + 2, 10, "Per-session OpenShell threads must retire after closing all workbenches: " + appThreadNames());
            System.gc(); Thread.sleep(250); edt(() -> null);
            metrics.add("actual_soak_ms=" + duration + "; reconnect_cycles=" + reconnectCycles + "; rendered_terminal_frames=" + frames + "; verified_input_commands=" + inputChecks);
            metrics.add("heap_before_bytes=" + baselineHeap + "; peak_heap_bytes=" + peakHeap + "; heap_after_close_gc_bytes=" + heap());
            metrics.add("app_threads_before=" + baselineThreads + "; peak_app_threads=" + peakThreads + "; app_threads_after=" + appThreads());
            metrics.add("all_threads_before=" + baselineAllThreads + "; all_threads_after=" + Thread.getAllStackTraces().size());
            metrics.add("remaining_server_sessions=0; remaining_connected_client_sessions=0; remaining_app_thread_names=" + appThreadNames());
            metrics.add("heartbeat_samples=" + heartbeat.samples.get() + "; max_EDT_queue_lag_ms=" + millis(heartbeat.maximumLag.get()));
            report("acceptance-session-soak.txt", String.join("\n", metrics));
        } catch (Exception | AssertionError failure) {
            metrics.add("failure=" + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            if (!lastRenderMismatch.get().isEmpty()) metrics.add("last_render_diagnostic=" + lastRenderMismatch.get());
            metrics.add("observed_soak_ms=" + (start == 0 ? 0 : millis(System.nanoTime() - start))
                    + "; reconnect_cycles=" + reconnectCycles + "; rendered_terminal_frames=" + frames);
            metrics.add("heap_before_bytes=" + baselineHeap + "; peak_heap_bytes=" + peakHeap + "; heap_at_failure_bytes=" + heap());
            metrics.add("app_threads_before=" + baselineThreads + "; peak_app_threads=" + peakThreads + "; app_threads_at_failure=" + appThreads());
            report("acceptance-session-soak.txt", String.join("\n", metrics));
            throw failure;
        } finally {
            if (previous == null) System.clearProperty("openshell.configDir"); else System.setProperty("openshell.configDir", previous);
        }
    }
    private static void awaitReady(List<SessionPanel> panels, List<LocalSshServer> servers) throws Exception {
        AtomicReference<String> state = new AtomicReference<>("");
        try { await(() -> edt(() -> {
            for (int index = 0; index < panels.size(); index++) {
                SessionPanel panel = panels.get(index);
                if (!panel.isConnected()) { state.set("session=" + index + "; state=" + panel.getSessionState() + "; label=" + field(panel, "statusLabel", JLabel.class).getToolTipText()); return false; }
                SftpPanel files = field(panel, "sftpPanel", SftpPanel.class);
                if (files == null || field(files, "fileTable", JTable.class).getRowCount() != 1) {
                    state.set("session=" + index + "; file_rows=" + (files == null ? "no panel" : field(files, "fileTable", JTable.class).getRowCount())
                            + "; SFTP_status=" + (files == null ? "no panel" : field(files, "statusLabel", JLabel.class).getText())); return false;
                }
            }
            boolean ready = servers.stream().allMatch(server -> server.shell != null && server.server.getActiveSessions().size() == 1);
            if (!ready) state.set("fixture_active_session_counts=" + servers.stream().map(server -> server.server.getActiveSessions().size()).toList());
            return ready;
        }), 20, "All eight actual SSH terminals and SFTP views must be ready");
        } catch (AssertionError failure) { System.out.println("SOAK_READINESS_DIAGNOSTIC=" + state.get()); throw failure; }
    }
    private static <T> T find(java.awt.Container root, Class<T> type) {
        for (java.awt.Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof java.awt.Container nested) { T found = find(nested, type); if (found != null) return found; }
        }
        return null;
    }
}
