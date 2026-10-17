package org.openshell.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.model.HostConfig;
import org.openshell.ssh.SshSession;
import org.openshell.storage.UiPreferences;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.FocusEvent;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Drafts stay in memory: reconnect restoration cannot send or serialize their contents. */
class TerminalDraftStateTest {
    @TempDir Path temporary;

    @Test void roundTripsBothBuffersSelectionsLocalOptionsAndPreferredInputWithoutPersistenceOrSending() throws Exception {
        isolated(() -> {
            HostConfig host = new HostConfig();
            FakeSession originalSession = new FakeSession(host), restoredSession = new FakeSession(host);
            TerminalPanel original = panel(originalSession), restored = null;
            try {
                onEdt(() -> {
                    JTextField single = field(original, "commandInput", JTextField.class);
                    JTextArea multi = field(original, "commandEditor", JTextArea.class);
                    single.setText("echo UNSENT_ONLY_单行 "); multi.setText("第一行 UNSENT_ONLY\n第二行\n");
                    selection(single, 3, 12); selection(multi, 10, 2);
                    field(original, "clearAfterSend", JCheckBox.class).doClick();
                    field(original, "ignoreEmpty", JCheckBox.class).doClick();
                    field(original, "sendMode", JComboBox.class).setSelectedIndex(1);
                    button(original, "多行").doClick();
                    for (var listener : multi.getFocusListeners()) listener.focusGained(new FocusEvent(multi, FocusEvent.FOCUS_GAINED));
                });
                TerminalPanel.DraftState saved = read(original::captureDraft);
                assertEquals(TerminalPanel.InputTarget.MULTILINE, saved.preferredInput());
                assertTrue(saved.multilineVisible()); assertFalse(saved.clearAfterSend()); assertTrue(saved.ignoreEmpty());
                assertEquals(1, saved.sendMode());
                UiPreferences.current().update(state -> { state.multiline = false; state.clearAfterSend = true; state.ignoreEmpty = false; state.sendMode = 0; });
                Path workspace = temporary.resolve("config/workspace.json");
                byte[] diskBefore = Files.readAllBytes(workspace);
                assertFalse(Files.readString(workspace).contains("UNSENT_ONLY"));
                onEdt(original::close);
                restored = panel(restoredSession); TerminalPanel fresh = restored;
                Component ownerBefore = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                onEdt(() -> {
                    assertTrue(fresh.restoreDraft(saved));
                    assertEquals(saved, fresh.captureDraft(), "Restore must keep both caret direction and selection, not only text");
                    assertSame(ownerBefore, KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner(), "Restore must not request input focus");
                });
                assertArrayEquals(diskBefore, Files.readAllBytes(workspace), "Restoring local options must not fire persistence listeners");
                assertTrue(UiPreferences.current().history(host.getId(), "").isEmpty());
                assertFalse(restoredSession.wrote.await(150, java.util.concurrent.TimeUnit.MILLISECONDS), "Restore must not schedule an asynchronous command send");
                assertEquals(0, originalSession.sent.size()); assertEquals(0, restoredSession.sent.size());
            } finally { onEdt(original::close); if (restored != null) { TerminalPanel done = restored; onEdt(done::close); } }
        });
    }

    @Test void neverOverwritesNewSingleLineOrWhitespaceMultilineInputAndSnapshotsAreImmutable() throws Exception {
        isolated(() -> {
            HostConfig host = new HostConfig();
            TerminalPanel source = panel(new FakeSession(host));
            TerminalPanel singleTarget = panel(new FakeSession(host)), multiTarget = panel(new FakeSession(host));
            try {
                onEdt(() -> field(source, "commandInput", JTextField.class).setText("retained reconnect draft"));
                TerminalPanel.DraftState saved = read(source::captureDraft);
                onEdt(() -> {
                    field(source, "commandInput", JTextField.class).setText("later mutation");
                    field(singleTarget, "commandInput", JTextField.class).setText("new user draft");
                    assertFalse(singleTarget.restoreDraft(saved));
                    assertEquals("new user draft", field(singleTarget, "commandInput", JTextField.class).getText());
                    assertEquals("", field(singleTarget, "commandEditor", JTextArea.class).getText());
                    field(multiTarget, "commandEditor", JTextArea.class).setText("   \n");
                    assertFalse(multiTarget.restoreDraft(saved));
                    assertEquals("   \n", field(multiTarget, "commandEditor", JTextArea.class).getText());
                    assertEquals("", field(multiTarget, "commandInput", JTextField.class).getText());
                    assertEquals("retained reconnect draft", saved.singleLine().text());
                    assertThrows(UnsupportedOperationException.class, () -> saved.history().add("mutation"));
                    source.close(); assertFalse(source.restoreDraft(saved));
                });
            } finally { onEdt(() -> { source.close(); singleTarget.close(); multiTarget.close(); }); }
        });
    }

    @Test void historyNavigationReturnsToTheOriginalUnsentDraftAfterRestoring() throws Exception {
        isolated(() -> {
            HostConfig host = new HostConfig();
            UiPreferences.current().recordCommand(host.getId(), "executed one");
            UiPreferences.current().recordCommand(host.getId(), "executed two");
            FakeSession restoredSession = new FakeSession(host);
            TerminalPanel original = panel(new FakeSession(host)), restored = panel(restoredSession);
            try {
                onEdt(() -> {
                    JTextField input = field(original, "commandInput", JTextField.class);
                    input.setText("UNSENT_HISTORY_DRAFT"); input.getActionMap().get("history-1").actionPerformed(null);
                    assertEquals("executed two", input.getText());
                });
                TerminalPanel.DraftState saved = read(original::captureDraft);
                onEdt(() -> {
                    assertTrue(restored.restoreDraft(saved));
                    JTextField input = field(restored, "commandInput", JTextField.class);
                    input.getActionMap().get("history1").actionPerformed(null);
                    assertEquals("UNSENT_HISTORY_DRAFT", input.getText());
                });
                assertFalse(Files.readString(temporary.resolve("config/workspace.json")).contains("UNSENT_HISTORY_DRAFT"));
                assertEquals(java.util.List.of("executed one", "executed two"), UiPreferences.current().history(host.getId(), ""));
                assertEquals(0, restoredSession.sent.size());
            } finally { onEdt(() -> { original.close(); restored.close(); }); }
        });
    }

    private void isolated(Checked task) throws Exception {
        String previous = System.getProperty("openshell.configDir");
        System.setProperty("openshell.configDir", temporary.resolve("config").toString());
        try { task.run(); } finally { if (previous == null) System.clearProperty("openshell.configDir"); else System.setProperty("openshell.configDir", previous); }
    }
    private static TerminalPanel panel(FakeSession session) throws Exception {
        return read(() -> new TerminalPanel(session, () -> {}, () -> { throw new AssertionError("Draft restoration must not access the real clipboard"); }));
    }
    private static void selection(JTextComponent input, int dot, int mark) { input.getCaret().setDot(mark); input.getCaret().moveDot(dot); }
    private interface Checked { void run() throws Exception; }
    private interface Read<T> { T read(); }
    private static void onEdt(Runnable task) throws Exception { SwingUtilities.invokeAndWait(task); }
    private static <T> T read(Read<T> read) throws Exception { AtomicReference<T> value = new AtomicReference<>(); onEdt(() -> value.set(read.read())); return value.get(); }
    private static <T> T field(Object owner, String name, Class<T> type) {
        try { var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(owner)); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static JButton button(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) return button;
            if (child instanceof Container nested) { JButton result = button(nested, text); if (result != null) return result; }
        }
        return null;
    }
    private static final class FakeSession extends SshSession {
        private final PipedInputStream input = new PipedInputStream();
        private final PipedOutputStream hold;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        final java.util.concurrent.CountDownLatch wrote = new java.util.concurrent.CountDownLatch(1);
        FakeSession(HostConfig host) throws IOException { super(host); hold = new PipedOutputStream(input); }
        @Override public InputStream getInputStream() { return input; }
        @Override public boolean isConnected() { return true; }
        @Override public void resizePty(int cols, int rows, int width, int height) {}
        @Override public void sendBytes(byte[] bytes, int offset, int length) { sent.write(bytes, offset, length); wrote.countDown(); }
        @Override public void closeShell() { try { hold.close(); } catch (IOException ignored) {} }
    }
}
