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

class RemoteEditorConflictIntegrationTest {
    @TempDir Path root;

    @Test void saveAsCanonicalAliasCannotOverwriteOriginalOrMarkLocalBufferSaved() throws Exception {
        Files.writeString(root.resolve("draft.txt"), "before"); Files.createDirectory(root.resolve("dir"));
        withEditor((panel, editor, dialogs) -> {
            dialogs.destination = "/dir/../draft.txt";
            onEdt(() -> { editor.setText("local unsaved"); findSaveAs(panel).doClick(); });
            await(() -> {
                AtomicReference<Boolean> rejected = new AtomicReference<>(false);
                onEdt(() -> rejected.set(hasStatus(panel, "另存为路径实际指向原文件"))); return rejected.get();
            });
            assertEquals("before", Files.readString(root.resolve("draft.txt")));
            assertEquals(0, dialogs.decisions, "An original alias must fail before offering overwrite");
            onEdt(() -> { assertEquals("local unsaved", editor.getText()); assertTrue(hasStatus(panel, "* /draft.txt")); });
        });
    }

    @Test void compareAndCancelKeepBothRemoteAndDirtyLocalBufferThenExplicitOverwriteWorks() throws Exception {
        Files.writeString(root.resolve("draft.txt"), "before");
        withEditor((panel, editor, dialogs) -> {
            Files.writeString(root.resolve("draft.txt"), "remote");
            dialogs.choices.add(RemoteEditorPanel.ConflictChoice.COMPARE);
            dialogs.choices.add(RemoteEditorPanel.ConflictChoice.CANCEL);
            onEdt(() -> { editor.setText("local 修改"); save(editor); });
            await(() -> dialogs.comparisons == 1 && dialogs.decisions == 2);
            assertEquals("remote", Files.readString(root.resolve("draft.txt")));
            onEdt(() -> assertEquals("local 修改", editor.getText()));
            assertEquals("local 修改", dialogs.local); assertEquals("remote", dialogs.remote);
            dialogs.choices.add(RemoteEditorPanel.ConflictChoice.OVERWRITE);
            onEdt(() -> save(editor));
            try {
                await(() -> "local 修改".equals(Files.readString(root.resolve("draft.txt"))));
            } catch (AssertionError failure) {
                onEdt(() -> System.out.println("EDITOR_CONFLICT_OVERWRITE_DIAGNOSTIC decisions=" + dialogs.decisions
                        + " queued=" + dialogs.choices + " save_enabled=" + findSave(panel).isEnabled()
                        + " status=" + statusText(panel)));
                throw failure;
            }
        });
    }

    @Test void rejectingReloadProtectsDirtyTextAndSaveAsPublishesCopyOnly() throws Exception {
        Files.writeString(root.resolve("draft.txt"), "before");
        withEditor((panel, editor, dialogs) -> {
            Files.writeString(root.resolve("draft.txt"), "remote");
            dialogs.choices.add(RemoteEditorPanel.ConflictChoice.RELOAD); dialogs.discard = false;
            onEdt(() -> { editor.setText("local"); save(editor); });
            await(() -> dialogs.discards == 1);
            onEdt(() -> assertEquals("local", editor.getText()));
            dialogs.choices.add(RemoteEditorPanel.ConflictChoice.SAVE_AS); dialogs.destination = "/copy.txt";
            onEdt(() -> save(editor));
            await(() -> Files.exists(root.resolve("copy.txt")));
            assertEquals("local", Files.readString(root.resolve("copy.txt")));
            assertEquals("remote", Files.readString(root.resolve("draft.txt")));
            await(() -> {
                AtomicReference<Boolean> enabled = new AtomicReference<>(false);
                onEdt(() -> enabled.set(findSave(panel).isEnabled())); return enabled.get();
            });
            dialogs.choices.add(RemoteEditorPanel.ConflictChoice.RELOAD); dialogs.discard = true;
            onEdt(() -> save(editor));
            await(() -> onEdtText(editor).equals("remote"));
        });
    }

    private void withEditor(Scenario scenario) throws Exception {
        try (LocalSshServer server = new LocalSshServer(root)) {
            var session = new JSch().getSession("test", "127.0.0.1", server.server.getPort());
            session.setConfig("StrictHostKeyChecking", "no"); session.setPassword("test"); session.connect(5000);
            SftpClient client = new SftpClient(session);
            try {
                Dialogs dialogs = new Dialogs(); AtomicReference<RemoteEditorPanel> panel = new AtomicReference<>();
                onEdt(() -> panel.set(new RemoteEditorPanel(client, "/draft.txt", dialogs)));
                JTextArea editor = findEditor(panel.get()); await(() -> onEdtText(editor).equals("before"));
                scenario.run(panel.get(), editor, dialogs);
                await(() -> {
                    AtomicReference<Boolean> enabled = new AtomicReference<>(false);
                    onEdt(() -> enabled.set(findSave(panel.get()).isEnabled())); return enabled.get();
                });
            } finally { client.disconnect(); session.disconnect(); }
        }
    }
    private static class Dialogs implements RemoteEditorPanel.EditorDialogs {
        final java.util.Queue<RemoteEditorPanel.ConflictChoice> choices = new java.util.concurrent.ConcurrentLinkedQueue<>();
        volatile int comparisons, decisions, discards;
        volatile boolean discard;
        volatile String destination, local, remote;
        public RemoteEditorPanel.ConflictChoice conflict(Component parent, String path, boolean deleted) {
            decisions++; var choice = choices.poll(); return choice == null ? RemoteEditorPanel.ConflictChoice.CANCEL : choice;
        }
        public boolean discard(Component parent) { discards++; return discard; }
        public String saveAs(Component parent, String path) { return destination; }
        public void compare(Component parent, String local, String remote) { this.local = local; this.remote = remote; comparisons++; }
    }
    private interface Scenario { void run(RemoteEditorPanel panel, JTextArea editor, Dialogs dialogs) throws Exception; }
    private interface Check { boolean ready() throws Exception; }
    private static void await(Check check) {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> { while (!check.ready()) Thread.sleep(20); });
    }
    private static void onEdt(Runnable task) throws Exception { SwingUtilities.invokeAndWait(task); }
    private static String onEdtText(JTextArea area) throws Exception {
        AtomicReference<String> result = new AtomicReference<>(); onEdt(() -> result.set(area.getText())); return result.get();
    }
    private static void save(JTextArea area) { area.getActionMap().get("saveFile").actionPerformed(new ActionEvent(area, 0, "saveFile")); }
    private static JTextArea findEditor(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTextArea area) return area;
            if (child instanceof Container nested) { var found = findEditor(nested); if (found != null) return found; }
        }
        return null;
    }
    private static JButton findSave(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && button.getText().startsWith("保存并上传")) return button;
            if (child instanceof Container nested) { var found = findSave(nested); if (found != null) return found; }
        }
        return null;
    }
    private static JButton findSaveAs(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && button.getText().equals("另存为副本")) return button;
            if (child instanceof Container nested) { var found = findSaveAs(nested); if (found != null) return found; }
        }
        return null;
    }
    private static boolean hasStatus(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label && label.getText().contains(text)) return true;
            if (child instanceof Container nested && hasStatus(nested, text)) return true;
        }
        return false;
    }
    private static String statusText(Container root) {
        StringBuilder result = new StringBuilder();
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label) result.append(label.getText()).append(" | ");
            if (child instanceof Container nested) result.append(statusText(nested));
        }
        return result.toString();
    }
}
