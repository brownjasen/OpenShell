package org.openshell.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.session.SessionState;
import org.openshell.ssh.SshSession;
import org.openshell.storage.UiPreferences;
import org.openshell.support.LocalSshServer;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Acceptance expectations. The reconnect assertion intentionally exposes lost unsent drafts. */
class ReconnectDraftAcceptanceIT {
    @TempDir Path temporary;

    @Test void actualRemoteDisconnectAndReconnectMustRetainSingleAndMultilineUnsentDrafts() throws Exception {
        String previous = System.getProperty("openshell.configDir");
        System.setProperty("openshell.configDir", temporary.resolve("config").toString());
        AtomicReference<SessionPanel> panel = new AtomicReference<>();
        try (LocalSshServer server = new LocalSshServer(Files.createDirectory(temporary.resolve("remote")))) {
            var host = server.host();
            try {
                onEdt(() -> panel.set(new SessionPanel(host, (client, path) -> {}, () -> new SshSession(host, (h, a, f) -> true))));
                await(() -> onEdtValue(() -> panel.get().isConnected() && find(panel.get(), TerminalPanel.class) != null));
                TerminalPanel oldTerminal = onEdtValue(() -> find(panel.get(), TerminalPanel.class));
                String single = "echo 尚未发送 single draft";
                String multiline = "printf '%s\\n' '第一行'\nprintf '%s\\n' '第二行'\n";
                onEdt(() -> {
                    find(oldTerminal, JTextField.class).setText(single);
                    find(oldTerminal, JTextArea.class).setText(multiline);
                    findButton(oldTerminal, "多行").doClick();
                });
                var firstShell = server.shell;
                assertNull(firstShell.received.poll(150, TimeUnit.MILLISECONDS), "Preparing drafts must not send commands");

                closeServerSessions(server);
                await(() -> panel.get().getSessionState() == SessionState.DISCONNECTED);
                onEdt(() -> {
                    assertEquals(single, find(oldTerminal, JTextField.class).getText(), "Remote EOF itself must keep the single draft");
                    assertEquals(multiline, find(oldTerminal, JTextArea.class).getText(), "Remote EOF itself must keep the multiline draft");
                    panel.get().reconnect();
                });
                await(() -> onEdtValue(() -> panel.get().isConnected() && find(panel.get(), TerminalPanel.class) != null
                        && find(panel.get(), TerminalPanel.class) != oldTerminal));
                TerminalPanel resumed = onEdtValue(() -> find(panel.get(), TerminalPanel.class));
                assertNull(server.shell.received.poll(150, TimeUnit.MILLISECONDS), "Reconnect must not automatically send preserved drafts");
                assertTrue(UiPreferences.current().history(host.getId(), "").isEmpty(), "Unsent drafts must not become executed history");
                onEdt(() -> assertAll("Reconnect must preserve both unsent buffers",
                        () -> assertEquals(single, find(resumed, JTextField.class).getText(), "Single-line draft was lost during reconnect"),
                        () -> assertEquals(multiline, find(resumed, JTextArea.class).getText(), "Multiline draft was lost during reconnect")));
            } finally { if (panel.get() != null) onEdt(panel.get()::close); }
        } finally { restore(previous); }
    }

    @Test void anOpenDirtyRemoteEditorTabKeepsItsBufferAfterTransportClosesAndSaveFails() throws Exception {
        exerciseDirtyEditor(false);
    }

    @Test void anExistingDirtyEditorMustBeSaveableAndRemainOwnedByItsSessionAfterReconnect() throws Exception {
        exerciseDirtyEditor(true);
    }

    private void exerciseDirtyEditor(boolean reconnect) throws Exception {
        String previous = System.getProperty("openshell.configDir");
        System.setProperty("openshell.configDir", temporary.resolve("config").toString());
        Path remote = Files.createDirectory(temporary.resolve("remote")); Path file = remote.resolve("draft.txt"); Files.writeString(file, "remote original");
        AtomicReference<SessionPanel> panel = new AtomicReference<>(); AtomicReference<RemoteEditorPanel> editor = new AtomicReference<>();
        JTabbedPane tabs = new JTabbedPane();
        try (LocalSshServer server = new LocalSshServer(remote)) {
            var host = server.host();
            try {
                onEdt(() -> {
                    panel.set(new SessionPanel(host, (client, path) -> {
                        editor.set(new RemoteEditorPanel(client, path)); tabs.addTab("draft.txt", editor.get());
                    }, () -> new SshSession(host, (h, a, f) -> true)));
                    tabs.addTab(host.getName(), panel.get());
                });
                await(() -> onEdtValue(() -> {
                    SftpPanel files = find(panel.get(), SftpPanel.class); return files != null && find(files, JTable.class).getRowCount() == 1;
                }));
                onEdt(() -> {
                    JTable files = find(find(panel.get(), SftpPanel.class), JTable.class); files.setRowSelectionInterval(0, 0);
                    files.getActionMap().get("openSelected").actionPerformed(new ActionEvent(files, 0, "openSelected"));
                });
                await(() -> onEdtValue(() -> editor.get() != null && "remote original".equals(find(editor.get(), JTextArea.class).getText())));
                onEdt(() -> find(editor.get(), JTextArea.class).setText("local unsaved 中文\n"));
                closeServerSessions(server); await(() -> panel.get().getSessionState() == SessionState.DISCONNECTED);
                onEdt(() -> {
                    assertEquals(2, tabs.getTabCount(), "Transport loss must not remove an unsaved editor tab");
                    assertEquals("local unsaved 中文\n", find(editor.get(), JTextArea.class).getText());
                    JTextArea text = find(editor.get(), JTextArea.class);
                    text.getActionMap().get("saveFile").actionPerformed(new ActionEvent(text, 0, "saveFile"));
                });
                await(() -> onEdtValue(() -> hasLabel(editor.get(), "保存失败")));
                onEdt(() -> {
                    assertEquals("local unsaved 中文\n", find(editor.get(), JTextArea.class).getText());
                    assertTrue(hasLabel(editor.get(), "* /draft.txt"), "Failed save must keep the editor dirty");
                });
                assertEquals("remote original", Files.readString(file), "A disconnected save must not change the remote file");
                if (reconnect) {
                    TerminalPanel oldTerminal = onEdtValue(() -> find(panel.get(), TerminalPanel.class));
                    onEdt(panel.get()::reconnect);
                    await(() -> onEdtValue(() -> panel.get().isConnected() && find(panel.get(), TerminalPanel.class) != null
                            && find(panel.get(), TerminalPanel.class) != oldTerminal));
                    onEdt(() -> {
                        JTextArea text = find(editor.get(), JTextArea.class);
                        text.getActionMap().get("saveFile").actionPerformed(new ActionEvent(text, 0, "saveFile"));
                    });
                    await(() -> onEdtValue(() -> hasLabel(editor.get(), "保存失败") || hasLabel(editor.get(), "已安全保存")));
                    assertAll("A surviving editor tab must be associated with the reconnected session and saveable",
                            () -> assertTrue(onEdtValue(() -> panel.get().ownsClient(editor.get().getSftpClient())),
                                    "The editor became orphaned: MainWindow uses ownsClient to find associated dirty tabs"),
                            () -> assertEquals("local unsaved 中文\n", Files.readString(file),
                                    "Explicit Ctrl+S after successful reconnect must save the retained editor draft"),
                            () -> assertEquals("local unsaved 中文\n", onEdtValue(() -> find(editor.get(), JTextArea.class).getText()),
                                    "Save failure must still retain the local buffer"));
                }
            } finally { if (panel.get() != null) onEdt(panel.get()::close); }
        } finally { restore(previous); }
    }

    private static void closeServerSessions(LocalSshServer server) throws Exception {
        for (var session : server.server.getActiveSessions()) session.close(true).await(5000);
    }
    private static void restore(String previous) {
        if (previous == null) System.clearProperty("openshell.configDir"); else System.setProperty("openshell.configDir", previous);
    }
    private interface Check { boolean ready() throws Exception; }
    private interface Read<T> { T get(); }
    private static void await(Check check) { assertTimeoutPreemptively(Duration.ofSeconds(8), () -> { while (!check.ready()) Thread.sleep(20); }); }
    private static void onEdt(Runnable task) throws Exception { SwingUtilities.invokeAndWait(task); }
    private static <T> T onEdtValue(Read<T> read) throws Exception {
        AtomicReference<T> result = new AtomicReference<>(); onEdt(() -> result.set(read.get())); return result.get();
    }
    private static <T> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) { T result = find(nested, type); if (result != null) return result; }
        }
        return null;
    }
    private static JButton findButton(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) return button;
            if (child instanceof Container nested) { JButton result = findButton(nested, text); if (result != null) return result; }
        }
        return null;
    }
    private static boolean hasLabel(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label && label.getText().contains(text)) return true;
            if (child instanceof Container nested && hasLabel(nested, text)) return true;
        }
        return false;
    }
}
