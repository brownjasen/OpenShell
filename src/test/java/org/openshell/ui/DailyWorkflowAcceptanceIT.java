package org.openshell.ui;

import com.jediterm.terminal.ui.JediTermWidget;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.session.SessionState;
import org.openshell.ssh.*;
import org.openshell.support.LocalSshServer;
import org.openshell.transfer.*;
import javax.swing.*;
import java.awt.event.ActionEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.openshell.transfer.TransferManager.*;

/** Explicit acceptance suite: real transport and Swing actions, not native keyboard injection or a Linux shell. */
class DailyWorkflowAcceptanceIT {
    @TempDir Path temp;
    @Test @Timeout(60) void connectSendEditUploadDownloadDisconnectReconnectAndClose() throws Exception {
        String before = System.getProperty("openshell.configDir"); System.setProperty("openshell.configDir", temp.resolve("config").toString());
        Path root = Files.createDirectory(temp.resolve("remote")); Files.createDirectory(root.resolve("workspace"));
        Files.writeString(root.resolve("workspace/app.conf"), "port=8080\n");
        Path source = Files.createDirectories(temp.resolve("local/项目/empty")).getParent();
        Files.writeString(source.resolve("中文.txt"), "真正经由隔离 SFTP 往返的 UTF-8 数据\n");
        AtomicReference<SessionPanel> view = new AtomicReference<>(); AtomicReference<RemoteEditorPanel> editor = new AtomicReference<>();
        try (LocalSshServer server = new LocalSshServer(root)) {
            var host = server.host();
            edt(() -> { ThemeUtils.install(true); view.set(new SessionPanel(host, (client, path) -> editor.set(new RemoteEditorPanel(client, path)), () -> new SshSession(host, (h,a,f) -> true))); });
            await(() -> onEdt(() -> view.get().isConnected() && field(view.get(), "sftpPanel", SftpPanel.class) != null));
            TerminalPanel terminal = field(view.get(), "terminalPanel", TerminalPanel.class);
            await(() -> onEdt(() -> text(terminal).contains("中文")));
            String command = "printf '%s\\n' '中文工作流'";
            edt(() -> { assertTrue(terminal.stageCommand(command)); field(terminal, "commandInput", JTextField.class).postActionEvent(); });
            byte[] expected = (command + "\r").getBytes(StandardCharsets.UTF_8);
            for (byte b : expected) assertEquals(b & 255, server.shell.received.poll(3, TimeUnit.SECONDS), "Draft bytes must reach this connection exactly once");
            assertNull(server.shell.received.poll(100, TimeUnit.MILLISECONDS));
            System.out.println("ACCEPTANCE workflow command UTF8 bytes verified (fixture does not execute Linux commands)");

            SftpPanel files = field(view.get(), "sftpPanel", SftpPanel.class);
            edt(() -> files.loadDirectoryAsync("/workspace"));
            await(() -> onEdt(() -> "/workspace".equals(field(files, "pathField", JTextField.class).getText()) && field(files, "fileTable", JTable.class).getRowCount() == 1));
            edt(() -> {
                JTable table = field(files, "fileTable", JTable.class); table.setRowSelectionInterval(0,0);
                table.getActionMap().get("openSelected").actionPerformed(new ActionEvent(table, 0, "openSelected"));
            });
            await(() -> onEdt(() -> editor.get() != null && field(editor.get(), "loaded", Boolean.class)));
            String edited = "port=8081\nnote=中文保存\n";
            edt(() -> { JTextArea area = field(editor.get(), "editorArea", JTextArea.class); area.setText(edited); area.getActionMap().get("saveFile").actionPerformed(new ActionEvent(area,0,"saveFile")); });
            await(() -> onEdt(() -> !field(editor.get(), "isSaving", Boolean.class) && !field(editor.get(), "modified", Boolean.class)));
            assertEquals(edited, Files.readString(root.resolve("workspace/app.conf")));

            TransferPanel transfers = field(view.get(), "transferPanel", TransferPanel.class);
            edt(() -> files.enqueueUploads(List.of(source.toFile()), "/workspace"));
            await(() -> !transfers.manager().snapshots().isEmpty() && transfers.manager().pendingCount() == 0);
            for (var row : transfers.manager().snapshots()) assertEquals(State.COMPLETED, row.state(), row.error());
            assertTrue(Files.isDirectory(root.resolve("workspace/项目/empty")));
            assertEquals(Files.readString(source.resolve("中文.txt")), Files.readString(root.resolve("workspace/项目/中文.txt")));
            Path download = Files.createDirectory(temp.resolve("download"));
            SshSession connected = field(view.get(), "sshSession", SshSession.class);
            try (var planner = new TreeTransferPlanner(connected.getRawSession()); var queue = new TransferManager(() -> new SftpTransfer(connected.getRawSession()), r -> { throw new AssertionError("New destination"); })) {
                var ids = queue.submitAll(planner.download(List.of("/workspace/项目"), download, UUID.randomUUID(), () -> false));
                await(() -> queue.pendingCount() == 0);
                for (var id : ids) assertEquals(State.COMPLETED, queue.find(id).state(), queue.find(id).error());
                assertTrue(Files.isDirectory(download.resolve("项目/empty")));
                assertEquals(Files.readString(source.resolve("中文.txt")), Files.readString(download.resolve("项目/中文.txt")));
            }
            server.server.getActiveSessions().forEach(s -> s.close(true));
            await(() -> view.get().getSessionState() == SessionState.DISCONNECTED);
            await(() -> onEdt(() -> transfers.manager().isClosed()));
            edt(() -> view.get().reconnect());
            await(() -> onEdt(() -> view.get().isConnected() && field(view.get(), "terminalPanel", TerminalPanel.class) != terminal));
            TerminalPanel replacement = field(view.get(), "terminalPanel", TerminalPanel.class);
            await(() -> onEdt(() -> text(replacement).contains("中文")));
            edt(() -> { assertTrue(replacement.stageCommand("reconnected-marker")); field(replacement,"commandInput",JTextField.class).postActionEvent(); });
            for (byte b : "reconnected-marker\r".getBytes(StandardCharsets.UTF_8)) assertEquals(b & 255, server.shell.received.poll(3, TimeUnit.SECONDS));
            edt(view.get()::close); assertEquals(SessionState.CLOSED, view.get().getSessionState());
            await(() -> server.server.getActiveSessions().isEmpty());
            System.out.println("ACCEPTANCE workflow edit/tree upload/tree download/remote-close/reconnect/close PASS");
        } finally {
            if (view.get() != null) edt(view.get()::close);
            if (before == null) System.clearProperty("openshell.configDir"); else System.setProperty("openshell.configDir", before);
        }
    }
    static String text(TerminalPanel panel) {
        var widget = UiRenderingRegressionTest.find(panel, JediTermWidget.class); var buffer = widget.getTerminalTextBuffer();
        buffer.lock(); try { return buffer.getScreenLines().replace("\uE000", ""); } finally { buffer.unlock(); }
    }
    static <T> T field(Object value, String name, Class<T> type) {
        try { var f = value.getClass().getDeclaredField(name); f.setAccessible(true); return type.cast(f.get(value)); }
        catch(Exception e) { throw new AssertionError(e); }
    }
    interface Check { boolean get() throws Exception; }
    static void await(Check condition) throws Exception {
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while (!condition.get() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.get(), "Expected asynchronous workflow state not reached");
    }
    static void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    static boolean onEdt(java.util.function.BooleanSupplier action) throws Exception { AtomicReference<Boolean> r = new AtomicReference<>(); edt(() -> r.set(action.getAsBoolean())); return r.get(); }
}
