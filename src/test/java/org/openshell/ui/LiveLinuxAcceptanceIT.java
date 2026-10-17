package org.openshell.ui;

import com.fasterxml.jackson.databind.*;
import com.jcraft.jsch.*;
import com.jediterm.terminal.ui.JediTermWidget;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.model.HostConfig;
import org.openshell.ssh.*;
import org.openshell.transfer.*;
import javax.swing.*;
import java.awt.datatransfer.Clipboard;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.openshell.ui.DailyWorkflowAcceptanceIT.*;
import static org.openshell.transfer.TransferManager.*;

/** Explicit opt-in only, with a reviewed saved-host name and expected address. Never modifies saved configuration or keys. */
class LiveLinuxAcceptanceIT {
    @TempDir Path temp;
    SshSession connection; SftpClient sftp; String before; String remoteDirectory;
    @BeforeEach void connectAuthorizedTarget() throws Exception {
        before = System.getProperty("openshell.configDir");
        Assumptions.assumeTrue(Boolean.getBoolean("openshell.acceptance.live"), "Requires explicit live-host authorization");
        String hostName = System.getProperty("openshell.acceptance.hostName", "");
        String expectedHost = System.getProperty("openshell.acceptance.expectedHost", "");
        assertFalse(hostName.isBlank(), "Explicit reviewed saved-host name is required");
        assertFalse(expectedHost.isBlank(), "Explicit reviewed server address is required");
        Path userConfig = Path.of(System.getProperty("user.home"), ".openshell");
        ObjectMapper json = new ObjectMapper(); JsonNode chosen = null;
        for (JsonNode node : json.readTree(userConfig.resolve("hosts.json").toFile()))
            if (hostName.equals(node.path("name").asText())) { chosen = node; break; }
        assertNotNull(chosen, "Authorized named host must exist");
        HostConfig host = json.treeToValue(chosen, HostConfig.class);
        assertEquals(expectedHost, host.getHost(), "Stop if the authorized named target changed");
        Path config = Files.createDirectory(temp.resolve("config"));
        Files.copy(Path.of(System.getProperty("openshell.acceptance.approvedKnownHosts")), config.resolve("known_hosts"));
        System.setProperty("openshell.configDir", config.toString());
        connection = new SshSession(host); connection.connect(); sftp = new SftpClient(connection.getRawSession());
        System.out.println("LIVE_ACCEPTANCE connected=true known_host_verified=true negotiated_host_key=" + connection.getRawSession().getHostKey().getType());
    }
    @AfterEach void cleanOnlyOwnedDirectory() throws Exception {
        try {
            if (sftp != null && remoteDirectory != null) {
                assertTrue(remoteDirectory.matches("/tmp/openshell-acceptance-[0-9a-f-]{36}"));
                assertEquals(remoteDirectory, sftp.resolvePath(remoteDirectory));
                for (var file : sftp.listFiles(remoteDirectory)) {
                    assertFalse(file.isDirectory(), "Refuse unexpected recursive deletion");
                    sftp.delete(remoteDirectory + "/" + file.getName(), false);
                }
                sftp.delete(remoteDirectory, true);
                System.out.println("LIVE_ACCEPTANCE owned_test_directory_cleaned=true");
            }
        } finally {
            if (sftp != null) sftp.disconnect(); if (connection != null) connection.disconnect();
            if (before == null) System.clearProperty("openshell.configDir"); else System.setProperty("openshell.configDir", before);
        }
    }
    void directory() throws Exception { remoteDirectory = "/tmp/openshell-acceptance-" + UUID.randomUUID(); sftp.mkdir(remoteDirectory); }

    @Test @Timeout(80) void realBashVimTopResizeAndDirectoryEvents() throws Exception {
        directory(); AtomicReference<TerminalPanel> view = new AtomicReference<>();
        edt(() -> { ThemeUtils.install(true); view.set(new TerminalPanel(connection, () -> {}, () -> new Clipboard("Isolated live acceptance clipboard"))); });
        TerminalPanel terminal = view.get();
        try {
            String ready = "LIVE_READY_" + UUID.randomUUID().toString().replace("-", "");
            connection.sendCommand("PS1='OS_ACCEPT> '; PS2='OS_MORE> '; stty -echo; printf '\\n" + ready + "\\n'\r");
            await(() -> onEdt(() -> text(terminal).lines().anyMatch(line -> line.strip().equals(ready))));
            connection.sendCommand("vim -Nu NONE -n -i NONE -- " + quote(remoteDirectory + "/vim.txt") + "\r");
            await(() -> onEdt(() -> widget(terminal).getTerminalTextBuffer().isUsingAlternateBuffer()));
            connection.sendCommand("iVim 验收中文\r第二行\u001b:wq\r");
            await(() -> onEdt(() -> !widget(terminal).getTerminalTextBuffer().isUsingAlternateBuffer()));
            assertEquals("Vim 验收中文\n第二行\n", new String(sftp.readFileSnapshot(remoteDirectory + "/vim.txt").content(), StandardCharsets.UTF_8));
            System.out.println("LIVE_ACCEPTANCE vim_fullscreen_unicode_save_and_exit=PASS");

            connection.sendCommand("LC_ALL=C top -d 1\r");
            await(() -> onEdt(() -> text(terminal).contains("Tasks:") && text(terminal).contains("top -")));
            connection.sendCommand("q");
            await(() -> onEdt(() -> !widget(terminal).getTerminalTextBuffer().isUsingAlternateBuffer()
                    && text(terminal).lines().anyMatch(line -> line.strip().startsWith("OS_ACCEPT>"))));
            String afterTop = "AFTER_TOP_" + UUID.randomUUID().toString().replace("-", "");
            connection.sendCommand("printf '\\n" + afterTop + "\\n'\r");
            await(() -> onEdt(() -> text(terminal).lines().anyMatch(line -> line.strip().equals(afterTop))));
            System.out.println("LIVE_ACCEPTANCE top_interactive_render_and_quit=PASS");

            connection.resizePty(100, 28, 0, 0); connection.sendCommand("stty size\r");
            await(() -> onEdt(() -> text(terminal).lines().anyMatch(line -> line.strip().equals("28 100"))));
            AtomicReference<String> directory = new AtomicReference<>();
            edt(() -> { terminal.onDirectoryChanged(directory::set); assertTrue(terminal.stageDirectoryIntegration()); field(terminal, "commandInput", JTextField.class).postActionEvent(); });
            await(() -> directory.get() != null);
            connection.sendCommand("cd -- " + quote(remoteDirectory) + "\r"); await(() -> remoteDirectory.equals(directory.get()));
            System.out.println("LIVE_ACCEPTANCE real_PTY_resize_and_Bash_OSC7=PASS");
            System.out.println("LIVE_ACCEPTANCE tmux=" + (exec("command -v tmux >/dev/null 2>&1 && printf present || printf unavailable").contains("unavailable") ? "NOT_INSTALLED_NOT_TESTED" : "AVAILABLE_NOT_YET_EXERCISED"));
        } finally { edt(terminal::close); }
    }

    @Test @Timeout(300) void realSftpSafeEditAndEightMiBRoundTrip() throws Exception {
        directory(); Path source = temp.resolve("source.bin"); byte[] chunk = new byte[64 * 1024]; new java.util.Random(137).nextBytes(chunk);
        try (OutputStream output = Files.newOutputStream(source)) { for (int i=0; i<128; i++) output.write(chunk); }
        long started = System.nanoTime();
        try (var queue = new TransferManager(() -> new SftpTransfer(connection.getRawSession()), row -> new Decision(Choice.REPLACE, false))) {
            UUID upload = queue.submit(Direction.UPLOAD, source, remoteDirectory + "/upload.bin");
            awaitTransfer(queue, upload);
            Path destination = temp.resolve("roundtrip.bin"); UUID download = queue.submit(Direction.DOWNLOAD, destination, remoteDirectory + "/upload.bin");
            awaitTransfer(queue, download); assertEquals(digest(source), digest(destination));
        }
        String file = remoteDirectory + "/editor.conf";
        var initial = sftp.saveFileSafely(file, "初始\n".getBytes(StandardCharsets.UTF_8), null);
        var updated = sftp.saveFileSafely(file, "真实安全保存\n".getBytes(StandardCharsets.UTF_8), initial);
        assertEquals("真实安全保存\n", new String(updated.content(), StandardCharsets.UTF_8));
        assertThrows(SftpClient.RemoteFileConflictException.class, () -> sftp.saveFileSafely(file, "must not overwrite".getBytes(StandardCharsets.UTF_8), initial));
        assertEquals("真实安全保存\n", new String(sftp.readFileSnapshot(file).content(), StandardCharsets.UTF_8));
        System.out.printf(Locale.ROOT,"LIVE_ACCEPTANCE SFTP_8MiB_roundtrip_SHA256_and_safe_edit_conflict=PASS elapsed_seconds=%.3f%n", (System.nanoTime()-started)/1e9);
    }

    @Test @Timeout(100) void realReconnectKeepsDraftsAndExistingEditorWithOfflineConflictProtection() throws Exception {
        directory();
        String path = remoteDirectory + "/reconnect.txt";
        sftp.saveFileSafely(path, "original\n".getBytes(StandardCharsets.UTF_8), null);
        AtomicReference<SessionPanel> panel = new AtomicReference<>();
        AtomicReference<RemoteEditorPanel> editor = new AtomicReference<>();
        AtomicInteger conflicts = new AtomicInteger();
        var dialogs = new RemoteEditorPanel.EditorDialogs() {
            public RemoteEditorPanel.ConflictChoice conflict(java.awt.Component parent, String file, boolean deleted) {
                conflicts.incrementAndGet(); return RemoteEditorPanel.ConflictChoice.CANCEL;
            }
            public boolean discard(java.awt.Component parent) { throw new AssertionError("Unexpected discard"); }
            public String saveAs(java.awt.Component parent, String file) { throw new AssertionError("Unexpected save as"); }
            public void compare(java.awt.Component parent, String local, String remote) { throw new AssertionError("Unexpected compare"); }
        };
        try {
            edt(() -> panel.set(new SessionPanel(connection.getConfig(),
                    (client, file) -> editor.set(new RemoteEditorPanel(client, file, dialogs)))));
            await(() -> onEdt(() -> panel.get().isConnected() && field(panel.get(), "sftpPanel", SftpPanel.class) != null));
            edt(() -> field(panel.get(), "sftpPanel", SftpPanel.class).loadDirectoryAsync(remoteDirectory));
            await(() -> onEdt(() -> {
                SftpPanel browser = field(panel.get(), "sftpPanel", SftpPanel.class);
                return remoteDirectory.equals(field(browser, "currentDirectory", String.class))
                        && field(browser, "fileTable", JTable.class).getRowCount() == 1;
            }));
            edt(() -> {
                JTable files = field(field(panel.get(), "sftpPanel", SftpPanel.class), "fileTable", JTable.class);
                files.setRowSelectionInterval(0, 0);
                files.getActionMap().get("openSelected").actionPerformed(new java.awt.event.ActionEvent(files, 0, "openSelected"));
            });
            await(() -> onEdt(() -> editor.get() != null && field(editor.get(), "loaded", Boolean.class)));
            String single = "printf unsent > " + quote(remoteDirectory + "/must-not-execute");
            String multiline = "printf '多行草稿\\n'\nprintf '仍未发送\\n'";
            edt(() -> {
                TerminalPanel terminal = field(panel.get(), "terminalPanel", TerminalPanel.class);
                field(terminal, "commandInput", JTextField.class).setText(single);
                field(terminal, "commandEditor", JTextArea.class).setText(multiline);
                field(editor.get(), "editorArea", JTextArea.class).setText("saved after reconnect 中文\n");
            });
            for (int cycle = 0; cycle < 2; cycle++) {
                AtomicReference<SshSession> previousTransport = new AtomicReference<>();
                edt(() -> previousTransport.set(field(panel.get(), "sshSession", SshSession.class)));
                SshSession previous = previousTransport.get();
                previous.disconnect(); // Close only the transport created by this test, never other user sessions.
                await(() -> panel.get().getSessionState() == org.openshell.session.SessionState.DISCONNECTED);
                if (cycle == 1) {
                    sftp.saveFileSafely(path, "external edit while offline\n".getBytes(StandardCharsets.UTF_8), sftp.readFileSnapshot(path));
                }
                edt(panel.get()::reconnect);
                await(() -> onEdt(() -> panel.get().isConnected() && field(panel.get(), "terminalPanel", TerminalPanel.class) != null
                        && field(panel.get(), "sshSession", SshSession.class) != previous));
                edt(() -> {
                    TerminalPanel resumed = field(panel.get(), "terminalPanel", TerminalPanel.class);
                    assertEquals(single, field(resumed, "commandInput", JTextField.class).getText());
                    assertEquals(multiline, field(resumed, "commandEditor", JTextArea.class).getText());
                    assertTrue(panel.get().ownsClient(editor.get().getSftpClient()));
                    JTextArea area = field(editor.get(), "editorArea", JTextArea.class);
                    area.getActionMap().get("saveFile").actionPerformed(new java.awt.event.ActionEvent(area, 0, "saveFile"));
                });
                if (cycle == 0) {
                    await(() -> onEdt(() -> !field(editor.get(), "modified", Boolean.class)));
                    assertEquals("saved after reconnect 中文\n", new String(sftp.readFileSnapshot(path).content(), StandardCharsets.UTF_8));
                    edt(() -> field(editor.get(), "editorArea", JTextArea.class).setText("local unsaved conflict\n"));
                } else {
                    await(() -> conflicts.get() == 1);
                    assertEquals("external edit while offline\n", new String(sftp.readFileSnapshot(path).content(), StandardCharsets.UTF_8));
                    edt(() -> {
                        assertTrue(field(editor.get(), "modified", Boolean.class));
                        assertEquals("local unsaved conflict\n", field(editor.get(), "editorArea", JTextArea.class).getText());
                    });
                }
                assertTrue(sftp.listFiles(remoteDirectory).stream().noneMatch(file -> file.getName().equals("must-not-execute")));
            }
            System.out.println("LIVE_ACCEPTANCE reconnect_twice_drafts_no_autosend_existing_editor_save_and_offline_conflict=PASS");
        } finally { if (panel.get() != null) edt(panel.get()::close); }
    }

    @Test @Timeout(55) void actualLinuxProcessPortsAndMonitorPauseResume() throws Exception {
        try (var source = new SshProcessSource(connection.getRawSession())) {
            var rows = source.load(); assertFalse(rows.isEmpty());
            for (var row : rows) assertTrue(row.cpuPercent() == null || Double.isFinite(row.cpuPercent()) && row.cpuPercent() >= 0, "Invalid CPU sample");
            System.out.println("LIVE_ACCEPTANCE linux_process_sampling=PASS rows=" + rows.size());
        }
        try (var source = new SshPortSource(connection.getRawSession())) {
            var rows = source.load(); assertFalse(rows.isEmpty());
            assertTrue(rows.stream().allMatch(row -> row.port() >= 0 && row.port() <= 65535));
            System.out.println("LIVE_ACCEPTANCE linux_port_sampling=PASS endpoints=" + rows.size());
        }
        ServerMonitor monitor = new ServerMonitor(connection.getRawSession()); AtomicInteger samples = new AtomicInteger(); AtomicBoolean invalid = new AtomicBoolean();
        ServerMonitor.MonitorListener listener = (cpu,mem,used,total,disk,du,dt,rx,tx,load) -> {
            if (mem < 0 || mem > 100 || total <= 0 || disk < 0 || disk > 100) invalid.set(true); samples.incrementAndGet();
        };
        try {
            var resources = monitor.discoverResources(); assertFalse(resources.networkInterfaces().isEmpty()); assertTrue(resources.mountPoints().contains("/"));
            monitor.start(listener); await(() -> samples.get() >= 2); monitor.stop(); int paused = samples.get(); Thread.sleep(1800); assertEquals(paused,samples.get());
            String nic = resources.networkInterfaces().stream().filter(name -> !name.equals("lo")).findFirst().orElse("lo");
            monitor.setSelection(nic, "/"); monitor.start(listener); await(() -> samples.get() >= paused + 2); assertFalse(invalid.get());
            System.out.println("LIVE_ACCEPTANCE monitor_resources_samples_pause_resume_and_NIC_selection=PASS");
        } finally { monitor.stop(); }
    }
    static JediTermWidget widget(TerminalPanel terminal) { return UiRenderingRegressionTest.find(terminal, JediTermWidget.class); }
    static String quote(String text) { return "'" + text.replace("'", "'\"'\"'") + "'"; }
    String exec(String command) throws Exception {
        ChannelExec exec = (ChannelExec) connection.getRawSession().openChannel("exec"); exec.setCommand(command); exec.setInputStream(null); exec.setErrStream(OutputStream.nullOutputStream());
        InputStream input = exec.getInputStream(); var timer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
        timer.schedule(exec::disconnect, 8, TimeUnit.SECONDS);
        try { exec.connect(4000); return new String(input.readNBytes(65536), StandardCharsets.UTF_8); } finally { exec.disconnect(); timer.shutdownNow(); }
    }
    static void awaitTransfer(TransferManager queue, UUID id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120); long next = 0;
        while (!queue.find(id).state().terminal() && System.nanoTime() < deadline) {
            if (System.nanoTime() >= next) { var row = queue.find(id); System.out.printf(Locale.ROOT, "LIVE_TRANSFER direction=%s state=%s bytes=%d total=%d rate_Bps=%.1f%n", row.request().direction(), row.state(), row.done(), row.total(), row.bytesPerSecond()); next=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); }
            Thread.sleep(40);
        }
        var row=queue.find(id); assertEquals(State.COMPLETED,row.state(), "bytes="+row.done()+" total="+row.total()+" error="+row.error());
    }
    static String digest(Path path) throws Exception { MessageDigest sha=MessageDigest.getInstance("SHA-256"); try(InputStream input=Files.newInputStream(path)){ byte[] bytes=new byte[65536]; int n; while((n=input.read(bytes))>=0) if(n>0) sha.update(bytes,0,n); } return HexFormat.of().formatHex(sha.digest()); }
}
