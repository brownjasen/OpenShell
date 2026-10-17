package org.openshell.ui;

import com.jediterm.terminal.ui.JediTermWidget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.ssh.SshSession;
import org.openshell.storage.UiPreferences;
import org.openshell.support.LocalSshServer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.*;
import java.text.AttributedString;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class TerminalInputIntegrationTest {
    @TempDir Path directory;
    private final Clipboard clipboard = new Clipboard("OpenShell test clipboard");

    @Test void shellEditingKeysReachTheServerInsteadOfLocalSearchAndClear() throws Exception {
        withTerminal((panel, server) -> {
            var screen = find(panel, JediTermWidget.class).getTerminalPanel();
            key(screen, KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK, '\u0003');
            key(screen, KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK, '\u000c');
            key(screen, KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK, '\u0006');
            key(screen, KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK, '\u0001');
            key(screen, KeyEvent.VK_TAB, 0, '\t');
            assertBytes(server, "\u0003\u000c\u0006\u0001\t");
        });
    }

    @Test void editingARecalledCommandMakesItTheNewDraft() throws Exception {
        withTerminal((panel, server) -> {
            // The command is recorded through the same path as the send button.
            SwingUtilities.invokeAndWait(() -> panel.executeCommandLine("pwd"));
            assertBytes(server, "pwd\r");
            await(() -> UiPreferences.current().snapshot().histories.values().stream().anyMatch(v -> v.contains("pwd")));
            SwingUtilities.invokeAndWait(() -> {
                JTextField input = find(panel, JTextField.class);
                input.setText("old draft");
                input.getActionMap().get("history-1").actionPerformed(null);
                assertEquals("pwd", input.getText());
                input.setText("edited draft");
                input.getActionMap().get("history1").actionPerformed(null);
                assertEquals("edited draft", input.getText(), "Down must not restore a stale pre-edit draft");
                input.getActionMap().get("history-1").actionPerformed(null);
                input.getActionMap().get("history1").actionPerformed(null);
                assertEquals("edited draft", input.getText());
            });
        });
    }

    @Test void repeatedEnterInOneEventDoesNotSubmitTheSameDraftTwice() throws Exception {
        withTerminal((panel, server) -> {
            SwingUtilities.invokeAndWait(() -> {
                JTextField input = find(panel, JTextField.class);
                input.setText("echo once");
                input.postActionEvent();
                input.postActionEvent();
            });
            assertBytes(server, "echo once\r");
            assertNull(server.shell.received.poll(300, TimeUnit.MILLISECONDS), "Do not enqueue duplicate sends while a send is pending");
        });
    }

    @Test void copiesChineseAndPastesUnicodeWithoutSendingAnExtraEnter() throws Exception {
        withTerminal((panel, server) -> {
            var screen = find(panel, JediTermWidget.class).getTerminalPanel();
            key(screen, KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, 'C');
            assertNull(clipboard.getContents(null), "Copy without selection must leave clipboard unchanged");
            SwingUtilities.invokeAndWait(screen::selectAll);
            key(screen, KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, 'C');
            String copied = (String) clipboard.getData(DataFlavor.stringFlavor);
            assertTrue(copied.contains("RED\n中文"), copied);
            assertEquals("RED\n中文\n$ ", copied, "Select all should omit unused screen rows");
            assertFalse(copied.contains("\u001b["), "Copy must contain rendered text, not ANSI escapes");
            assertNull(server.shell.received.poll(100, TimeUnit.MILLISECONDS));
            clipboard.setContents(new StringSelection("echo 中文"), null);
            key(screen, KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, 'V');
            assertBytes(server, "echo 中文");
            assertNull(server.shell.received.poll(150, TimeUnit.MILLISECONDS), "Single-line paste must not execute the command");
            clipboard.setContents(new StringSelection("tail"), null);
            key(screen, KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK, KeyEvent.CHAR_UNDEFINED);
            assertBytes(server, "tail");
        });
    }

    @Test void normalizesWindowsNewlinesAndHonorsRemoteBracketedPasteMode() throws Exception {
        withTerminal((panel, server) -> {
            var widget = find(panel, JediTermWidget.class);
            var screen = widget.getTerminalPanel();
            clipboard.setContents(new StringSelection("first\r\n第二行\rlast\n"), null);
            key(screen, KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK, KeyEvent.CHAR_UNDEFINED);
            assertBytes(server, "first\r第二行\rlast\r");
            assertNull(server.shell.received.poll(100, TimeUnit.MILLISECONDS), "CRLF must produce one line separator");
            server.shell.send("\u001b[?2004hPASTE-READY");
            await(() -> screenText(widget).contains("PASTE-READY"));
            clipboard.setContents(new StringSelection("one\r\ntwo"), null);
            key(screen, KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, 'V');
            assertBytes(server, "\u001b[200~one\rtwo\u001b[201~");
            server.shell.send("\u001b[?2004lPASTE-OFF");
            await(() -> screenText(widget).contains("PASTE-OFF"));
            clipboard.setContents(new StringSelection("plain"), null);
            key(screen, KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, 'V');
            assertBytes(server, "plain");
        });
    }

    @Test void chineseImeCompositionIsNotSentUntilCommitted() throws Exception {
        withTerminal((panel, server) -> {
            var screen = find(panel, JediTermWidget.class).getTerminalPanel();
            SwingUtilities.invokeAndWait(() -> screen.dispatchEvent(new InputMethodEvent(screen,
                    InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, new AttributedString("中文").getIterator(), 0, null, null)));
            assertNull(server.shell.received.poll(100, TimeUnit.MILLISECONDS));
            SwingUtilities.invokeAndWait(() -> screen.dispatchEvent(new InputMethodEvent(screen,
                    InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, new AttributedString("中文").getIterator(), 2, null, null)));
            assertBytes(server, "中文");
        });
    }

    @Test void localShortcutsDoNotLeakToShellAndCtrlCStillInterruptsWithSelection() throws Exception {
        withTerminal((panel, server) -> {
            var widget = find(panel, JediTermWidget.class);
            var screen = widget.getTerminalPanel();
            key(screen, KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, 'A');
            key(screen, KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK, '\u0003');
            assertBytes(server, "\u0003");
            key(screen, KeyEvent.VK_F6, 0, KeyEvent.CHAR_UNDEFINED);
            key(screen, KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, 'F');
            assertNull(server.shell.received.poll(150, TimeUnit.MILLISECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(count(widget, JTextField.class) > 0, "Find shortcut opens the local search field");
                JTextField input = find(panel, JTextField.class);
                // Find search belongs to the widget; locate the command draft outside it.
                for (Component child : panel.getComponents()) if (child instanceof Container nested && child != widget) {
                    JTextField candidate = find(nested, JTextField.class);
                    if (candidate != null) input = candidate;
                }
                input.setText("keep me");
                Object action = input.getInputMap().get(KeyStroke.getKeyStroke("ESCAPE"));
                input.getActionMap().get(action).actionPerformed(null);
                assertEquals("keep me", input.getText());
            });
        });
    }

    @Test void aNewDraftTypedDuringSendingSurvivesCompletion() throws Exception {
        withTerminal((panel, server) -> {
            SwingUtilities.invokeAndWait(() -> {
                JTextField input = find(panel, JTextField.class);
                input.setText("first"); input.postActionEvent(); input.setText("second");
            });
            assertBytes(server, "first\r");
            await(() -> UiPreferences.current().snapshot().histories.values().stream().anyMatch(v -> v.contains("first")));
            SwingUtilities.invokeAndWait(() -> assertEquals("second", find(panel, JTextField.class).getText()));
        });
    }

    @Test void disconnectedDraftStaysEditableAndNeitherEnterNorPasteWritesToShell() throws Exception {
        withTerminal((panel, server) -> {
            SwingUtilities.invokeAndWait(() -> {
                JTextField input = find(panel, JTextField.class);
                input.setText("unsent"); panel.setDisconnected("断开");
                assertTrue(input.isEnabled());
                input.postActionEvent();
                assertEquals("unsent", input.getText());
                assertFalse(button(panel, "发送").isEnabled());
            });
            clipboard.setContents(new StringSelection("do not send"), null);
            key(find(panel, JediTermWidget.class).getTerminalPanel(), KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK, KeyEvent.CHAR_UNDEFINED);
            assertNull(server.shell.received.poll(150, TimeUnit.MILLISECONDS));
            assertTrue(UiPreferences.current().snapshot().histories.isEmpty());
        });
    }

    @Test void toolbarCopyAndPasteUseTheSameTerminalActionsAsTheKeyboard() throws Exception {
        withTerminal((panel, server) -> {
            SwingUtilities.invokeAndWait(() -> {
                find(panel, JediTermWidget.class).getTerminalPanel().selectAll();
                button(panel, "复制").doClick();
            });
            assertTrue(((String) clipboard.getData(DataFlavor.stringFlavor)).contains("中文"));
            clipboard.setContents(new StringSelection("from button"), null);
            SwingUtilities.invokeAndWait(() -> button(panel, "粘贴").doClick());
            assertBytes(server, "from button");
        });
    }

    @Test void alternateScreenKeepsApplicationArrowsAndShellControlKeys() throws Exception {
        withTerminal((panel, server) -> {
            var widget = find(panel, JediTermWidget.class);
            server.shell.send("\u001b[?1h\u001b[?1049h");
            await(() -> widget.getTerminalTextBuffer().isUsingAlternateBuffer());
            var screen = widget.getTerminalPanel();
            key(screen, KeyEvent.VK_UP, 0, KeyEvent.CHAR_UNDEFINED);
            key(screen, KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK, '\u000c');
            key(screen, KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK, '\u0016');
            assertBytes(server, "\u001bOA\u000c\u0016");
            server.shell.send("\u001b[?1049l\u001b[?1l");
            await(() -> !widget.getTerminalTextBuffer().isUsingAlternateBuffer());
            key(screen, KeyEvent.VK_UP, InputEvent.CTRL_DOWN_MASK, KeyEvent.CHAR_UNDEFINED);
            assertBytes(server, "\u001b[1;5A");
        });
    }

    @Test void multilineDraftSendsOneCarriageReturnPerLineAndClearsOnlyAfterSuccess() throws Exception {
        withTerminal((panel, server) -> {
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(panel.stageCommand("echo 一\r\necho 二\rlast\n"));
                JTextArea editor = find(panel, JTextArea.class);
                assertEquals("echo 一\necho 二\nlast", editor.getText());
                editor.getActionMap().get("send").actionPerformed(null);
                assertFalse(button(panel, "发送全部").isEnabled());
            });
            assertBytes(server, "echo 一\recho 二\rlast\r");
            await(() -> UiPreferences.current().snapshot().histories.values().stream().anyMatch(v -> v.contains("echo 一\necho 二\nlast")));
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("", find(panel, JTextArea.class).getText());
                assertTrue(button(panel, "发送全部").isEnabled());
            });
        });
    }

    private interface Scenario { void run(TerminalPanel panel, LocalSshServer server) throws Exception; }
    private void withTerminal(Scenario test) throws Exception {
        String previous = System.getProperty("openshell.configDir");
        System.setProperty("openshell.configDir", directory.resolve("config").toString());
        try (LocalSshServer server = new LocalSshServer(Files.createDirectory(directory.resolve("remote")))) {
            SshSession connection = new SshSession(server.host(), (h, a, f) -> true);
            AtomicReference<TerminalPanel> ref = new AtomicReference<>();
            try {
                connection.connect();
                SwingUtilities.invokeAndWait(() -> ref.set(new TerminalPanel(connection, () -> {}, () -> clipboard)));
                await(() -> {
                    String text = screenText(find(ref.get(), JediTermWidget.class));
                    return text.contains("RED") && text.contains("中文");
                });
                test.run(ref.get(), server);
            } finally {
                if (ref.get() != null) SwingUtilities.invokeAndWait(ref.get()::close);
                connection.disconnect();
            }
        } finally {
            if (previous == null) System.clearProperty("openshell.configDir");
            else System.setProperty("openshell.configDir", previous);
        }
    }
    private static void key(com.jediterm.terminal.ui.TerminalPanel target, int code, int modifiers, char typed) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            KeyEvent pressed = new KeyEvent(target, KeyEvent.KEY_PRESSED, 1, modifiers, code, typed);
            target.processKeyEvent(pressed);
            if (typed == KeyEvent.CHAR_UNDEFINED) return;
            KeyEvent text = new KeyEvent(target, KeyEvent.KEY_TYPED, 1, modifiers, KeyEvent.VK_UNDEFINED, typed);
            target.processKeyEvent(text);
        });
    }
    private static void assertBytes(LocalSshServer server, String expected) throws Exception {
        for (byte b : expected.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            assertEquals(b & 255, server.shell.received.poll(3, TimeUnit.SECONDS));
    }
    private static String screenText(JediTermWidget widget) {
        var buffer = widget.getTerminalTextBuffer();
        buffer.lock();
        try { return buffer.getScreenLines().replace("\uE000", ""); }
        finally { buffer.unlock(); }
    }
    private static void await(java.util.function.BooleanSupplier check) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> { while (!check.getAsBoolean()) Thread.sleep(20); });
    }
    private static <T> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) { T value = find(nested, type); if (value != null) return value; }
        }
        return null;
    }
    private static int count(Container root, Class<?> type) {
        int result = 0;
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) result++;
            if (child instanceof Container nested) result += count(nested, type);
        }
        return result;
    }
    private static JButton button(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && button.getText().equals(text)) return button;
            if (child instanceof Container nested) { JButton found = button(nested, text); if (found != null) return found; }
        }
        return null;
    }
}
