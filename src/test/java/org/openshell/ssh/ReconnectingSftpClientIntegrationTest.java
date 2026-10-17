package org.openshell.ssh;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.support.LocalSshServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ReconnectingSftpClientIntegrationTest {
    @TempDir Path root;

    @Test void rebindingOnEdtDoesNotWaitForOldSftpReadAndOldCallCannotJumpToNewConnection() throws Exception {
        Files.writeString(root.resolve("draft.txt"), "original");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean blockFirst = new AtomicBoolean(true); AtomicInteger reads = new AtomicInteger();
        try (LocalSshServer server = new LocalSshServer(root); ReconnectingSftpClient bridge = new ReconnectingSftpClient()) {
            var factory = new org.apache.sshd.sftp.server.SftpSubsystemFactory.Builder().build();
            factory.addSftpEventListener(new org.apache.sshd.sftp.server.SftpEventListener() {
                @Override public void reading(org.apache.sshd.server.session.ServerSession session, String handle,
                        org.apache.sshd.sftp.server.FileHandle file, long offset, byte[] bytes, int start, int length) throws java.io.IOException {
                    reads.incrementAndGet();
                    if (blockFirst.compareAndSet(true, false)) {
                        entered.countDown();
                        try { if (!release.await(6, TimeUnit.SECONDS)) throw new java.io.IOException("Test read release timed out"); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.io.IOException(interrupted); }
                    }
                }
            });
            server.server.setSubsystemFactories(List.of(factory));
            Session first = connect(server), second = connect(server);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                bridge.bind(first); bridge.connect();
                Future<?> oldRead = worker.submit(() -> { try { return bridge.readFileSnapshot("/draft.txt"); } catch (Exception error) { throw new CompletionException(error); } });
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertTimeoutPreemptively(java.time.Duration.ofMillis(500), () -> javax.swing.SwingUtilities.invokeAndWait(() -> bridge.bind(second)));
                assertThrows(ExecutionException.class, () -> oldRead.get(3, TimeUnit.SECONDS));
                assertEquals(1, reads.get(), "The retired read must not auto-retry on the newly bound SFTP transport");
                release.countDown();
                assertEquals("original", new String(bridge.readFileSnapshot("/draft.txt").content(), StandardCharsets.UTF_8));
                assertTrue(bridge.usesSession(second));
            } finally { release.countDown(); worker.shutdownNow(); first.disconnect(); second.disconnect(); }
        }
    }

    @Test void stableBridgeReadsSavesSuspendsAndRebindsWithoutDroppingOriginalConflictVersion() throws Exception {
        Files.writeString(root.resolve("draft.txt"), "original");
        try (LocalSshServer server = new LocalSshServer(root); ReconnectingSftpClient bridge = new ReconnectingSftpClient()) {
            Session first = connect(server), second = null;
            try {
                bridge.bind(first); bridge.connect(); var original = bridge.readFileSnapshot("/draft.txt");
                bridge.suspend(); assertThrows(Exception.class, () -> bridge.readFileSnapshot("/draft.txt"));
                first.disconnect(); second = connect(server); bridge.bind(second);
                assertTrue(bridge.usesSession(second)); assertFalse(bridge.usesSession(first));
                Files.writeString(root.resolve("draft.txt"), "external");
                var conflict = assertThrows(SftpClient.RemoteFileConflictException.class,
                        () -> bridge.saveFileSafely("/draft.txt", "local".getBytes(StandardCharsets.UTF_8), original));
                assertEquals("external", Files.readString(root.resolve("draft.txt")));
                bridge.saveFileSafely("/draft.txt", "local".getBytes(StandardCharsets.UTF_8), conflict.current());
                assertEquals("local", Files.readString(root.resolve("draft.txt")));
                bridge.close(); Session rebound = second;
                assertThrows(IllegalStateException.class, () -> bridge.bind(rebound));
                assertThrows(Exception.class, bridge::connect);
                assertNotNull(bridge.getHome()); assertNotNull(bridge.getCurrentPath());
            } finally { first.disconnect(); if (second != null) second.disconnect(); }
        }
    }

    @Test void wrongEndpointOrUserNeverReplacesTheActiveTransport() throws Exception {
        Path firstRoot = Files.createDirectory(root.resolve("first")), otherRoot = Files.createDirectory(root.resolve("other"));
        Files.writeString(firstRoot.resolve("draft.txt"), "right host"); Files.writeString(otherRoot.resolve("draft.txt"), "wrong host");
        try (LocalSshServer firstServer = new LocalSshServer(firstRoot); LocalSshServer otherServer = new LocalSshServer(otherRoot);
             ReconnectingSftpClient bridge = new ReconnectingSftpClient()) {
            Session first = connect(firstServer), other = connect(otherServer);
            try {
                bridge.bind(first); var version = bridge.readFileSnapshot("/draft.txt");
                assertThrows(IllegalArgumentException.class, () -> bridge.bind(other));
                Session differentUser = new JSch().getSession("different-user", "127.0.0.1", firstServer.server.getPort());
                assertThrows(IllegalArgumentException.class, () -> bridge.bind(differentUser));
                Session differentHost = new JSch().getSession("test", "localhost", firstServer.server.getPort());
                assertThrows(IllegalArgumentException.class, () -> bridge.bind(differentHost));
                assertTrue(bridge.usesSession(first)); assertFalse(bridge.usesSession(other));
                bridge.saveFileSafely("/draft.txt", "only right host".getBytes(StandardCharsets.UTF_8), version);
                assertEquals("only right host", Files.readString(firstRoot.resolve("draft.txt")));
                assertEquals("wrong host", Files.readString(otherRoot.resolve("draft.txt")));
            } finally { first.disconnect(); other.disconnect(); }
        }
    }

    @Test void delegatesFileOperationsAndGettersNeverNeedANetworkWhileSuspended() throws Exception {
        Files.writeString(root.resolve("source.txt"), "中文");
        try (LocalSshServer server = new LocalSshServer(root); ReconnectingSftpClient bridge = new ReconnectingSftpClient()) {
            Session session = connect(server);
            try {
                bridge.bind(session); bridge.mkdir("/folder"); bridge.cd("/folder");
                assertEquals("/folder", bridge.getCurrentPath()); bridge.createEmptyFile("empty.txt");
                assertEquals(List.of("empty.txt"), bridge.listFiles("/folder").stream().map(SftpClient.FileItem::getName).toList());
                bridge.rename("/folder/empty.txt", "/folder/renamed.txt"); bridge.chmod(0644, "/folder/renamed.txt");
                bridge.uploadFile(root.resolve("source.txt").toFile(), "/folder/upload.txt");
                Path download = root.resolve("downloaded.txt"); bridge.downloadFile("/folder/upload.txt", download.toFile());
                assertEquals("中文", Files.readString(download));
                assertEquals("/folder/upload.txt", bridge.canonicalFilePath("/folder/../folder/upload.txt"));
                bridge.delete("/folder/renamed.txt", false); bridge.cdUp(); assertEquals("/", bridge.resolvePath("."));
                bridge.suspend(); session.disconnect();
                assertTimeoutPreemptively(java.time.Duration.ofMillis(300), () -> { assertNotNull(bridge.getHome()); assertNotNull(bridge.getCurrentPath()); assertFalse(bridge.usesSession(session)); });
            } finally { session.disconnect(); }
        }
    }
    private static Session connect(LocalSshServer server) throws Exception {
        Session session = new JSch().getSession("test", "127.0.0.1", server.server.getPort());
        session.setConfig("StrictHostKeyChecking", "no"); session.setPassword("test"); session.connect(5000); return session;
    }
}
