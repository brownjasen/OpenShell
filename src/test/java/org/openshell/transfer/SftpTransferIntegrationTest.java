package org.openshell.transfer;

import com.jcraft.jsch.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.ssh.SftpClient;
import org.openshell.support.LocalSshServer;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.openshell.transfer.TransferManager.*;

class SftpTransferIntegrationTest {
    @TempDir Path directory;
    Path root;
    LocalSshServer server;
    Session session;
    @BeforeEach void connect() throws Exception {
        root = Files.createDirectory(directory.resolve("server")); server = new LocalSshServer(root);
        session = new JSch().getSession("test", "127.0.0.1", server.server.getPort());
        session.setConfig("StrictHostKeyChecking", "no"); session.setPassword("test"); session.connect(5000);
    }
    @AfterEach void disconnect() throws Exception { if (session != null) session.disconnect(); if (server != null) server.close(); }
    TransferManager queue(Choice choice) { return new TransferManager(() -> new SftpTransfer(session), row -> new Decision(choice, false)); }

    @Test void uploadKeepBothAndDownloadReplacePublishCompleteFiles() throws Exception {
        Files.writeString(root.resolve("文件.txt"), "old");
        Path local = Files.writeString(directory.resolve("文件.txt"), "新的内容");
        try (var queue = queue(Choice.KEEP_BOTH)) {
            UUID upload = queue.submit(Direction.UPLOAD, local, "/文件.txt"); TransferManagerTest.await(queue, upload, State.COMPLETED);
            assertEquals("old", Files.readString(root.resolve("文件.txt")));
            assertEquals("新的内容", Files.readString(root.resolve("文件 (1).txt")));
            assertEquals("/文件 (1).txt", queue.find(upload).destination());
        }
        Path destination = Files.writeString(directory.resolve("download.txt"), "previous local");
        try (var queue = queue(Choice.REPLACE)) {
            UUID download = queue.submit(Direction.DOWNLOAD, destination, "/文件 (1).txt"); TransferManagerTest.await(queue, download, State.COMPLETED);
            assertEquals("新的内容", Files.readString(destination));
            assertEquals(Files.size(destination), queue.find(download).done());
        }
        assertNoTemporaryFiles();
    }

    @Test void missingDownloadNeverTruncatesAnExistingLocalFileAndSkipDoesNotWrite() throws Exception {
        Path target = Files.writeString(directory.resolve("target.txt"), "keep local");
        try (var queue = queue(Choice.REPLACE)) {
            UUID id = queue.submit(Direction.DOWNLOAD, target, "/missing.txt"); TransferManagerTest.await(queue, id, State.FAILED);
            assertEquals("keep local", Files.readString(target));
        }
        Files.writeString(root.resolve("old.txt"), "keep remote");
        try (var queue = queue(Choice.SKIP)) {
            UUID id = queue.submit(Direction.UPLOAD, target, "/old.txt"); TransferManagerTest.await(queue, id, State.SKIPPED);
            assertEquals("keep remote", Files.readString(root.resolve("old.txt")));
        }
        assertNoTemporaryFiles();
    }

    @Test void browseUsesAnIndependentChannelWhileUploadIsHeldAndCancellationPreservesTarget() throws Exception {
        Files.writeString(root.resolve("target.bin"), "keep remote");
        Path local = Files.write(directory.resolve("large.bin"), new byte[1024 * 1024]);
        CountDownLatch progress = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean blocked = new AtomicBoolean();
        try (var queue = queue(Choice.REPLACE)) {
            queue.setListener(rows -> {
                if (rows.stream().anyMatch(row -> row.state() == State.RUNNING && row.done() > 0) && blocked.compareAndSet(false, true)) {
                    progress.countDown(); try { release.await(4, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            });
            UUID id = queue.submit(Direction.UPLOAD, local, "/target.bin");
            try {
                assertTrue(progress.await(3, TimeUnit.SECONDS));
                SftpClient browser = new SftpClient(session);
                ExecutorService browse = Executors.newSingleThreadExecutor();
                try { assertFalse(browse.submit(() -> browser.listFiles("/")).get(2, TimeUnit.SECONDS).isEmpty()); }
                finally { browser.disconnect(); browse.shutdownNow(); }
                assertTrue(queue.cancel(id));
            } finally { release.countDown(); }
            TransferManagerTest.await(queue, id, State.CANCELLED);
            assertEquals("keep remote", Files.readString(root.resolve("target.bin")));
            UUID retry = queue.retry(id); TransferManagerTest.await(queue, retry, State.COMPLETED);
            assertEquals(Files.size(local), Files.size(root.resolve("target.bin")));
        }
        assertNoTemporaryFiles();
    }

    @Test void cancelledDownloadPreservesLocalTargetAndChangedDestinationIsNotReplaced() throws Exception {
        Files.write(root.resolve("large.bin"), new byte[512 * 1024]);
        Path local = Files.writeString(directory.resolve("target.bin"), "keep local");
        try (var queue = queue(Choice.REPLACE)) {
            AtomicBoolean once = new AtomicBoolean();
            queue.setListener(rows -> rows.stream().filter(r -> r.state() == State.RUNNING && r.done() > 0).findFirst().ifPresent(r -> {
                if (once.compareAndSet(false, true)) queue.cancel(r.id());
            }));
            UUID id = queue.submit(Direction.DOWNLOAD, local, "/large.bin"); TransferManagerTest.await(queue, id, State.CANCELLED);
            assertEquals("keep local", Files.readString(local));
        }
        try (var queue = queue(Choice.REPLACE)) {
            AtomicBoolean once = new AtomicBoolean();
            queue.setListener(rows -> {
                if (rows.stream().anyMatch(r -> r.state() == State.RUNNING && r.done() > 0) && once.compareAndSet(false, true))
                    try { Files.writeString(local, "concurrent new content"); } catch (Exception e) { throw new RuntimeException(e); }
            });
            UUID id = queue.submit(Direction.DOWNLOAD, local, "/large.bin"); TransferManagerTest.await(queue, id, State.FAILED);
            assertEquals("concurrent new content", Files.readString(local));
            assertTrue(queue.find(id).error().contains("变化"));
        }
        assertNoTemporaryFiles();
    }

    void assertNoTemporaryFiles() throws Exception {
        try (var files = Files.walk(directory)) { assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".part"))); }
    }

    @Test void remoteDestinationChangeAndUnsupportedSafeRenamePreserveTheOriginal() throws Exception {
        Path local = Files.write(directory.resolve("source.bin"), new byte[128 * 1024]);
        Files.writeString(root.resolve("target.bin"), "old remote");
        try (var queue = queue(Choice.REPLACE)) {
            AtomicBoolean once = new AtomicBoolean();
            queue.setListener(rows -> {
                if (rows.stream().anyMatch(r -> r.state() == State.RUNNING && r.done() > 0) && once.compareAndSet(false, true))
                    try { Files.writeString(root.resolve("target.bin"), "new concurrent remote contents"); } catch (Exception e) { throw new RuntimeException(e); }
            });
            UUID id = queue.submit(Direction.UPLOAD, local, "/target.bin"); TransferManagerTest.await(queue, id, State.FAILED);
            assertTrue(queue.find(id).error().contains("变化"));
            assertEquals("new concurrent remote contents", Files.readString(root.resolve("target.bin")));
        }
        // Remove posix-rename from the negotiated capabilities of new SFTP channels on this SSH session.
        server.server.getActiveSessions().forEach(remoteSession ->
                org.apache.sshd.sftp.SftpModuleProperties.OPENSSH_EXTENSIONS.set(remoteSession, "fsync@openssh.com=1"));
        try (var queue = queue(Choice.REPLACE)) {
            UUID id = queue.submit(Direction.UPLOAD, local, "/target.bin"); TransferManagerTest.await(queue, id, State.FAILED);
            assertTrue(queue.find(id).error().contains("不支持安全覆盖替换"), queue.find(id).error());
            assertEquals("new concurrent remote contents", Files.readString(root.resolve("target.bin")));
        }
        assertNoTemporaryFiles();
    }

    @Test void emptyFilesAndDownloadKeepBothPublishCorrectly() throws Exception {
        Path empty = Files.createFile(directory.resolve("empty.txt"));
        Files.writeString(root.resolve("server.txt"), "remote");
        Files.writeString(directory.resolve("server.txt"), "keep local");
        try (var queue = queue(Choice.KEEP_BOTH)) {
            UUID upload = queue.submit(Direction.UPLOAD, empty, "/empty.txt"); TransferManagerTest.await(queue, upload, State.COMPLETED);
            assertEquals(0, Files.size(root.resolve("empty.txt")));
            UUID download = queue.submit(Direction.DOWNLOAD, directory.resolve("server.txt"), "/server.txt");
            TransferManagerTest.await(queue, download, State.COMPLETED);
            assertEquals("keep local", Files.readString(directory.resolve("server.txt")));
            assertEquals("remote", Files.readString(directory.resolve("server (1).txt")));
        }
        assertNoTemporaryFiles();
    }
}
