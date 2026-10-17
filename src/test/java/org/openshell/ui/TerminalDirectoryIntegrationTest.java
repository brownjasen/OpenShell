package org.openshell.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.ssh.SshSession;
import org.openshell.support.LocalSshServer;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class TerminalDirectoryIntegrationTest {
    @TempDir Path directory;
    @Test void integrationIsOnlyStagedAndCompletionOnlyChangesTheLocalDraft() throws Exception {
        String previous = System.getProperty("openshell.configDir");
        System.setProperty("openshell.configDir", directory.resolve("config").toString());
        Path root = Files.createDirectory(directory.resolve("remote"));
        Files.createDirectory(root.resolve("work"));
        Files.writeString(root.resolve("work").resolve("唯一 文件.txt"), "fixture");
        try (LocalSshServer server = new LocalSshServer(root)) {
            SshSession connection = new SshSession(server.host(), (h, a, f) -> true);
            AtomicReference<TerminalPanel> ref = new AtomicReference<>();
            try {
                connection.connect();
                SwingUtilities.invokeAndWait(() -> ref.set(new TerminalPanel(connection, () -> {}, () -> new Clipboard("isolated"))));
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> { while (server.shell == null) Thread.sleep(10); });
                CountDownLatch observed = new CountDownLatch(1);
                SwingUtilities.invokeAndWait(() -> {
                    assertTrue(ref.get().stageDirectoryIntegration());
                    assertTrue(draft(ref.get()).getText().contains("PROMPT_COMMAND"));
                    assertFalse(ref.get().stageDirectoryIntegration(), "An existing draft must not be replaced");
                    draft(ref.get()).setText("");
                    ref.get().onDirectoryChanged(path -> {
                        assertTrue(SwingUtilities.isEventDispatchThread());
                        assertEquals("/work", path); observed.countDown();
                    });
                });
                assertNull(server.shell.received.poll(100, TimeUnit.MILLISECONDS), "Staging integration must not execute it");
                server.shell.send("\u001b]7;file:///work\u0007");
                assertTrue(observed.await(5, TimeUnit.SECONDS));
                SwingUtilities.invokeAndWait(() -> {
                    assertEquals("/work", ref.get().getCurrentDirectory());
                    JTextField draft = draft(ref.get()); draft.setText("cat 唯"); draft.setCaretPosition(draft.getText().length());
                    draft.getActionMap().get("remote-path").actionPerformed(null);
                });
                assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
                    while (true) {
                        AtomicReference<String> text = new AtomicReference<>();
                        SwingUtilities.invokeAndWait(() -> text.set(draft(ref.get()).getText()));
                        if ("cat '唯一 文件.txt'".equals(text.get())) break;
                        Thread.sleep(20);
                    }
                });
                assertNull(server.shell.received.poll(100, TimeUnit.MILLISECONDS), "Completion must not submit the draft");
            } finally {
                if (ref.get() != null) SwingUtilities.invokeAndWait(ref.get()::close);
                connection.disconnect();
            }
        } finally {
            if (previous == null) System.clearProperty("openshell.configDir"); else System.setProperty("openshell.configDir", previous);
        }
    }
    private static JTextField draft(Container root) {
        for (Component component : root.getComponents()) {
            if (component instanceof JTextField input && input.getActionMap().get("remote-path") != null) return input;
            if (component instanceof Container nested) { JTextField found = draft(nested); if (found != null) return found; }
        }
        return null;
    }
}
