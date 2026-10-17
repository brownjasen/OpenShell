package org.openshell.ui;

import com.jcraft.jsch.JSch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.ssh.SftpClient;
import org.openshell.support.LocalSshServer;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class RemoteEditorIntegrationTest {
    @TempDir Path directory;
    @Test void editsAndSavesUtf8ThroughRealSftp() throws Exception {
        Path file = directory.resolve("draft.txt"); Files.writeString(file, "旧内容");
        try (LocalSshServer server = new LocalSshServer(directory)) {
            var session = new JSch().getSession("test", "127.0.0.1", server.server.getPort());
            session.setConfig("StrictHostKeyChecking", "no"); session.setPassword("test");
            SftpClient client = new SftpClient(session);
            try {
                session.connect(5000); client.connect();
                AtomicReference<RemoteEditorPanel> panel = new AtomicReference<>();
                AtomicReference<JTextArea> editor = new AtomicReference<>();
                SwingUtilities.invokeAndWait(() -> {
                    panel.set(new RemoteEditorPanel(client, "/draft.txt")); editor.set(findEditor(panel.get()));
                });
                await(() -> {
                    AtomicReference<String> text = new AtomicReference<>();
                    SwingUtilities.invokeAndWait(() -> text.set(editor.get().getText()));
                    return "旧内容".equals(text.get());
                });
                SwingUtilities.invokeAndWait(() -> {
                    editor.get().setText("更新后的中文\nline two\n");
                    editor.get().getActionMap().get("saveFile").actionPerformed(new ActionEvent(editor.get(), 0, "saveFile"));
                });
                await(() -> "更新后的中文\nline two\n".equals(Files.readString(file)));
            } finally { client.disconnect(); session.disconnect(); }
        }
    }
    @Test void failedLoadDisablesEditingAndSave() throws Exception {
        try (LocalSshServer server = new LocalSshServer(directory)) {
            var session = new JSch().getSession("test", "127.0.0.1", server.server.getPort());
            session.setConfig("StrictHostKeyChecking", "no"); session.setPassword("test");
            SftpClient client = new SftpClient(session);
            try {
                session.connect(5000); client.connect();
                AtomicReference<RemoteEditorPanel> panel = new AtomicReference<>();
                SwingUtilities.invokeAndWait(() -> panel.set(new RemoteEditorPanel(client, "/missing.txt")));
                await(() -> {
                    AtomicReference<Boolean> failed = new AtomicReference<>(false);
                    SwingUtilities.invokeAndWait(() -> failed.set(hasFailure(panel.get())));
                    return failed.get();
                });
                SwingUtilities.invokeAndWait(() -> assertFalse(findEditor(panel.get()).isEditable(), "Failed downloads must not leave an editable empty document"));
                assertFalse(Files.exists(directory.resolve("missing.txt")));
            } finally { client.disconnect(); session.disconnect(); }
        }
    }
    private static JTextArea findEditor(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTextArea area) return area;
            if (child instanceof Container nested) { var result = findEditor(nested); if (result != null) return result; }
        }
        return null;
    }
    private static boolean hasFailure(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label && label.getText().contains("拉取文件失败")) return true;
            if (child instanceof Container nested && hasFailure(nested)) return true;
        }
        return false;
    }
    private interface Check { boolean ready() throws Exception; }
    private static void await(Check condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> { while (!condition.ready()) Thread.sleep(20); });
    }
}
