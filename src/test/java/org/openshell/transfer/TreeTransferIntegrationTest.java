package org.openshell.transfer;

import com.jcraft.jsch.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.support.LocalSshServer;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.openshell.transfer.TransferManager.*;

class TreeTransferIntegrationTest {
    @TempDir Path temp;
    LocalSshServer server; Session session; Path root;
    @BeforeEach void connect() throws Exception {
        root = Files.createDirectory(temp.resolve("remote")); server = new LocalSshServer(root);
        session = new JSch().getSession("test", "127.0.0.1", server.server.getPort());
        session.setConfig("StrictHostKeyChecking", "no"); session.setPassword("test"); session.connect(5000);
    }
    @AfterEach void close() throws Exception { session.disconnect(); server.close(); }
    @Test void nestedUnicodeAndEmptyDirectoriesRoundTripWithKnownBatchTotals() throws Exception {
        Path source = Files.createDirectories(temp.resolve("本地/空目录" )).getParent();
        Files.createDirectories(source.resolve("nested")); Files.writeString(source.resolve("nested/a[1].txt"), "你好");
        List<Request> upload = TreeTransferPlanner.upload(List.of(source), "/", UUID.randomUUID(), () -> false);
        try (var queue = queue()) {
            var ids = queue.submitAll(upload); await(queue, ids);
            assertTrue(Files.isDirectory(root.resolve("本地/空目录")));
            assertEquals("你好", Files.readString(root.resolve("本地/nested/a[1].txt")));
            assertEquals(6, queue.batches().get(0).total());
        }
        Path dest = Files.createDirectory(temp.resolve("download"));
        try (var planner = new TreeTransferPlanner(session); var queue = queue()) {
            var plan = planner.download(List.of("/本地"), dest, UUID.randomUUID(), () -> false);
            await(queue, queue.submitAll(plan));
            assertTrue(Files.isDirectory(dest.resolve("本地/空目录")));
            assertEquals("你好", Files.readString(dest.resolve("本地/nested/a[1].txt")));
        }
    }
    @Test void unsafeNamesAndCancelledPlanningNeverProduceAPlan() throws Exception {
        Path dest = Files.createDirectory(temp.resolve("dest"));
        for (String name : List.of("../escape", "C:drive", "a\\b", "NUL", "trailing.", "a/b"))
            assertThrows(Exception.class, () -> TransferPaths.child(dest, name), name);
        Path source = Files.writeString(temp.resolve("a"), "a");
        assertThrows(java.io.InterruptedIOException.class,
                () -> TreeTransferPlanner.upload(List.of(source), "/", UUID.randomUUID(), () -> true));
    }
    @Test void retryBatchOnlyRetriesFailedItems() throws Exception {
        Path good = Files.writeString(temp.resolve("good"), "good"); Path missing = temp.resolve("missing"); UUID batch = UUID.randomUUID();
        try (var queue = queue()) {
            var ids = queue.submitAll(List.of(new Request(Direction.UPLOAD, good, "/good", batch), new Request(Direction.UPLOAD, missing, "/later", batch)));
            TransferManagerTest.await(queue, ids.get(0), State.COMPLETED); TransferManagerTest.await(queue, ids.get(1), State.FAILED);
            Files.writeString(missing, "restored"); var retries = queue.retryFailedBatch(batch); assertEquals(1, retries.size());
            TransferManagerTest.await(queue, retries.get(0), State.COMPLETED); assertEquals("restored", Files.readString(root.resolve("later")));
        }
    }
    @Test void directoriesChangedAfterPlanningFailInsteadOfReportingAnEmptySuccess() throws Exception {
        Path local = Files.createDirectory(temp.resolve("folder"));
        var upload = TreeTransferPlanner.upload(List.of(local), "/", UUID.randomUUID(), () -> false);
        Files.delete(local);
        try (var queue = queue()) {
            var ids = queue.submitAll(upload); TransferManagerTest.await(queue, ids.get(0), State.FAILED);
            assertFalse(Files.exists(root.resolve("folder")));
        }
        Files.createDirectory(root.resolve("changed")); Path download = Files.createDirectory(temp.resolve("download"));
        try (var planner = new TreeTransferPlanner(session); var queue = queue()) {
            var plan = planner.download(List.of("/changed"), download, UUID.randomUUID(), () -> false);
            Files.delete(root.resolve("changed")); Files.writeString(root.resolve("changed"), "became a file");
            var ids = queue.submitAll(plan); TransferManagerTest.await(queue, ids.get(0), State.FAILED);
            assertFalse(Files.exists(download.resolve("changed")));
        }
    }
    TransferManager queue() { return new TransferManager(() -> new SftpTransfer(session), r -> new Decision(Choice.REPLACE, false)); }
    void await(TransferManager queue, List<UUID> ids) throws Exception { for (UUID id : ids) TransferManagerTest.await(queue, id, State.COMPLETED); }
}
