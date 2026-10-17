package org.openshell.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.ssh.SshSession;
import org.openshell.support.LocalSshServer;
import org.openshell.transfer.*;
import javax.swing.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.openshell.transfer.TransferManager.*;

class SftpTransferWorkflowTest {
    @TempDir Path directory;
    @Test void uploadedFilesKeepTheirCapturedDirectoryWhileTheBrowserNavigatesAndOpensTheQueue() throws Exception {
        String before = System.getProperty("openshell.configDir");
        System.setProperty("openshell.configDir", directory.resolve("config").toString());
        Path remote = Files.createDirectory(directory.resolve("server"));
        Files.createDirectory(remote.resolve("a")); Files.createDirectory(remote.resolve("b"));
        Path local = Files.writeString(directory.resolve("中文.txt"), "队列上传内容");
        AtomicReference<SftpPanel> files = new AtomicReference<>();
        AtomicReference<TransferPanel> transfers = new AtomicReference<>();
        AtomicReference<JTabbedPane> tabs = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try (LocalSshServer server = new LocalSshServer(remote)) {
            SshSession connection = new SshSession(server.host(), (h, a, f) -> true); connection.connect();
            var queue = new TransferManager(() -> new Transport() {
                final SftpTransfer delegate = new SftpTransfer(connection.getRawSession());
                public void execute(Request r, Control c) throws Exception { started.countDown(); release.await(); delegate.execute(r, c); }
                public void close() { release.countDown(); delegate.close(); }
            }, task -> { throw new AssertionError("New target must not request overwrite"); });
            try {
                SwingUtilities.invokeAndWait(() -> {
                    files.set(new SftpPanel(connection, (client, path) -> {}));
                    transfers.set(new TransferPanel(queue)); tabs.set(new JTabbedPane());
                    tabs.get().addTab("文件", files.get()); tabs.get().addTab("传输", transfers.get());
                    transfers.get().onChanged(files.get()::onTransfersChanged);
                    files.get().setTransfers(transfers.get(), () -> tabs.get().setSelectedComponent(transfers.get()));
                    files.get().enqueueUploads(List.of(local.toFile()), "/a");

                });
                assertTrue(started.await(2, TimeUnit.SECONDS));
                await(() -> onEdt(() -> transfers.get() == tabs.get().getSelectedComponent()));
                SwingUtilities.invokeAndWait(() -> { tabs.get().setSelectedComponent(files.get()); files.get().loadDirectoryAsync("/b"); });
                await(() -> onEdt(() -> "/b".equals(UiRenderingRegressionTest.find(files.get(), JTextField.class).getText())));
                release.countDown();
                await(() -> queue.snapshots().get(0).state().terminal());
                assertEquals(State.COMPLETED, queue.snapshots().get(0).state(), queue.snapshots().get(0).error());
                assertEquals("队列上传内容", Files.readString(remote.resolve("a/中文.txt")));
                assertFalse(Files.exists(remote.resolve("b/中文.txt")));
                SwingUtilities.invokeAndWait(() -> {
                    assertSame(files.get(), tabs.get().getSelectedComponent(), "Completion must not steal focus from the file browser");
                    assertEquals("/b", UiRenderingRegressionTest.find(files.get(), JTextField.class).getText());
                });
            } finally {
                release.countDown(); SwingUtilities.invokeAndWait(() -> { if (files.get() != null) files.get().close(); if (transfers.get() != null) transfers.get().close(); });
                queue.close(); connection.disconnect();
            }
        } finally { if (before == null) System.clearProperty("openshell.configDir"); else System.setProperty("openshell.configDir", before); }
    }
    private static boolean onEdt(java.util.function.BooleanSupplier check) throws Exception {
        AtomicBoolean result = new AtomicBoolean(); SwingUtilities.invokeAndWait(() -> result.set(check.getAsBoolean())); return result.get();
    }
    private interface Check { boolean ready() throws Exception; }
    private static void await(Check check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!check.ready() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(check.ready());
    }
}
