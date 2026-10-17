package org.openshell.ui;

import com.jcraft.jsch.JSch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.ssh.SftpClient;
import org.openshell.support.LocalSshServer;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class RemoteEditorUtf8SafetyTest {
    @TempDir Path root;
    @Test void malformedUtf8NeverBecomesAnEditableReplacementCharacterDocument() throws Exception {
        byte[] original = {(byte) 0xc3, 0x28}; Files.write(root.resolve("binary.txt"), original);
        try (LocalSshServer server = new LocalSshServer(root)) {
            var session = new JSch().getSession("test", "127.0.0.1", server.server.getPort());
            session.setConfig("StrictHostKeyChecking", "no"); session.setPassword("test"); session.connect(5000);
            SftpClient client = new SftpClient(session);
            try {
                AtomicReference<RemoteEditorPanel> panel = new AtomicReference<>();
                SwingUtilities.invokeAndWait(() -> panel.set(new RemoteEditorPanel(client, "/binary.txt")));
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    while (true) {
                        AtomicReference<Boolean> failed = new AtomicReference<>(false);
                        SwingUtilities.invokeAndWait(() -> failed.set(hasFailure(panel.get())));
                        if (failed.get()) break;
                        Thread.sleep(20);
                    }
                });
                SwingUtilities.invokeAndWait(() -> assertFalse(findEditor(panel.get()).isEditable()));
                assertArrayEquals(original, Files.readAllBytes(root.resolve("binary.txt")));
            } finally { client.disconnect(); session.disconnect(); }
        }
    }
    private static boolean hasFailure(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label && label.getText().startsWith("拉取文件失败")) return true;
            if (child instanceof Container nested && hasFailure(nested)) return true;
        }
        return false;
    }
    private static JTextArea findEditor(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTextArea area) return area;
            if (child instanceof Container nested) { JTextArea area = findEditor(nested); if (area != null) return area; }
        }
        return null;
    }
}
