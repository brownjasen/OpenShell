package org.openshell.transfer;

import com.jcraft.jsch.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.support.LocalSshServer;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.openshell.transfer.TransferManager.*;

class TreeTransferBoundaryTest {
    @TempDir Path directory;
    @Test void remoteAncestorChecksRejectLinksAndFilesBeforeRequestingAnyDescendantWithoutOsSymlinkPrivileges() throws Exception {
        for (int unsafeType : new int[]{0120000, 0100000}) {
            List<String> inspected = new ArrayList<>();
            SftpATTRS directoryAttrs = attributes(0040000), unsafeAttrs = attributes(unsafeType);
            ChannelSftp channel = new ChannelSftp() {
                @Override public SftpATTRS lstat(String path) {
                    inspected.add(path);
                    if (path.equals("/safe")) return directoryAttrs;
                    if (path.equals("/safe/shortcut")) return unsafeAttrs;
                    fail("No descendant of an unsafe ancestor may be inspected: " + path); return null;
                }
                @Override public void rm(String path) { fail("Ancestor validation must never delete a leaf: " + path); }
            };
            IOException failure = assertThrows(IOException.class,
                    () -> TransferPaths.checkRemoteParents(channel, "/safe/shortcut/child/leaf.txt"));
            assertTrue(failure.getMessage().contains("shortcut"));
            assertEquals(List.of("/safe", "/safe/shortcut"), inspected);
            inspected.clear();
            assertThrows(IOException.class, () -> TransferPaths.checkRemoteParents(channel, "/safe/../leaf.txt"));
            assertTrue(inspected.isEmpty(), "Traversal must fail before any remote request");
        }
    }

    private static SftpATTRS attributes(int type) throws Exception {
        var constructor = SftpATTRS.class.getDeclaredConstructor(); constructor.setAccessible(true);
        SftpATTRS attrs = constructor.newInstance();
        var permissions = SftpATTRS.class.getDeclaredField("permissions"); permissions.setAccessible(true); permissions.setInt(attrs, type);
        attrs.setPERMISSIONS(0755); return attrs;
    }

    @Test void rejectsForeignNamesTraversalAndReservedWindowsNames() throws Exception {
        for (String name : List.of("..", ".", "../escape", "a/b", "a\\b", "C:escape", "CON", "nul.txt", "LPT1.log", "COM¹", "COM².txt", "COM³", "LPT¹", "LPT².log", "LPT³", "tail.", "tail ", "bad\0name"))
            assertThrows(IOException.class, () -> TransferPaths.child(directory, name), name);
        assertEquals(directory.resolve("中文 文件.txt"), TransferPaths.child(directory, "中文 文件.txt"));
    }

    @Test void rejectsLocalSymlinkAncestorsWhenSupportedByTheFilesystem() throws Exception {
        Path target = Files.createDirectory(directory.resolve("target"));
        try { Files.createSymbolicLink(directory.resolve("shortcut"), target); }
        catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
            Assumptions.abort("Local filesystem cannot create symbolic links: " + unsupported.getMessage());
        }
        assertThrows(IOException.class, () -> TransferPaths.child(directory.resolve("shortcut"), "escape.txt"));
    }

    @Test void uploadPlanContainsEmptyDirectoriesAndRejectsDuplicateTargetsAndCancellation() throws Exception {
        Path source = Files.createDirectory(directory.resolve("folder")); Files.createDirectory(source.resolve("empty"));
        Files.writeString(source.resolve("中文.txt"), "hello"); UUID batch = UUID.randomUUID();
        List<Request> plan = TreeTransferPlanner.upload(List.of(source), "/destination", batch, () -> false);
        assertEquals(3, plan.size()); assertEquals(Kind.DIRECTORY, plan.get(0).kind());
        assertTrue(plan.stream().anyMatch(r -> r.kind() == Kind.DIRECTORY && r.remote().equals("/destination/folder/empty")));
        assertTrue(plan.stream().allMatch(r -> r.batch().equals(batch)));
        assertThrows(IOException.class, () -> TreeTransferPlanner.upload(List.of(source, source), "/destination", batch, () -> false));
        assertThrows(InterruptedIOException.class, () -> TreeTransferPlanner.upload(List.of(source), "/destination", batch, () -> true));
    }

    @Test void uploadPlanningNeverFollowsSymbolicLinkEntries() throws Exception {
        Path source = Files.createDirectory(directory.resolve("folder")); Path outside = Files.writeString(directory.resolve("outside.txt"), "secret");
        try { Files.createSymbolicLink(source.resolve("shortcut.txt"), outside); }
        catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
            Assumptions.abort("Local filesystem cannot create symbolic links: " + unsupported.getMessage());
        }
        assertThrows(IOException.class, () -> TreeTransferPlanner.upload(List.of(source), "/destination", UUID.randomUUID(), () -> false));
    }

    @Test void uploadPlanFailsAtDepthLimitInsteadOfSilentlyOmittingNestedFiles() throws Exception {
        Path source = Files.createDirectory(directory.resolve("deep")), nested = source;
        try {
            for (int i = 0; i < 130; i++) nested = Files.createDirectory(nested.resolve("d"));
            Files.writeString(nested.resolve("leaf.txt"), "must not be silently omitted");
        } catch (IOException unsupported) {
            Assumptions.abort("Local filesystem cannot create the deep fixture: " + unsupported.getMessage());
        }
        IOException failure = assertThrows(IOException.class,
                () -> TreeTransferPlanner.upload(List.of(source), "/destination", UUID.randomUUID(), () -> false));
        assertTrue(failure.getMessage().contains("128"), failure.getMessage());
    }

    @Test void downloadPlannerRejectsRemoteAncestorSymlinksBeforeEnumeratingTheirTargets() throws Exception {
        Path root = Files.createDirectory(directory.resolve("server")); Files.createDirectory(root.resolve("protected"));
        Files.writeString(root.resolve("protected/secret.txt"), "secret");
        try { Files.createSymbolicLink(root.resolve("shortcut"), Path.of("protected")); }
        catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
            Assumptions.abort("Local filesystem cannot create symbolic links: " + unsupported.getMessage());
        }
        try (LocalSshServer server = new LocalSshServer(root)) {
            Session session = new JSch().getSession("test", "127.0.0.1", server.server.getPort());
            session.setConfig("StrictHostKeyChecking", "no"); session.setPassword("test"); session.connect(5000);
            try (TreeTransferPlanner planner = new TreeTransferPlanner(session)) {
                assertThrows(IOException.class, () -> planner.download(List.of("/shortcut/secret.txt"), directory.resolve("downloads"), UUID.randomUUID(), () -> false));
            } finally { session.disconnect(); }
        }
    }

    @Test void cancellingOneBatchStopsItsRunningAndQueuedItemsWithoutCancellingAnotherBatch() throws Exception {
        CountDownLatch started = new CountDownLatch(1), closed = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger opened = new java.util.concurrent.atomic.AtomicInteger();
        try (TransferManager manager = new TransferManager(() -> {
            boolean first = opened.getAndIncrement() == 0;
            return new Transport() {
                public void execute(Request request, Control control) throws Exception {
                    if (first) { started.countDown(); assertTrue(closed.await(2, TimeUnit.SECONDS)); control.checkCancelled(); }
                    else control.progress(1, 1);
                }
                public void close() { if (first) closed.countDown(); }
            };
        }, row -> new Decision(Choice.REPLACE, false))) {
            UUID batch = UUID.randomUUID(), other = UUID.randomUUID();
            List<UUID> ids = manager.submitAll(List.of(new Request(Direction.UPLOAD, directory.resolve("a"), "/a", batch),
                    new Request(Direction.UPLOAD, directory.resolve("b"), "/b", batch),
                    new Request(Direction.UPLOAD, directory.resolve("c"), "/c", other)));
            assertTrue(started.await(2, TimeUnit.SECONDS)); manager.cancelBatch(batch);
            TransferManagerTest.await(manager, ids.get(0), State.CANCELLED); TransferManagerTest.await(manager, ids.get(1), State.CANCELLED);
            TransferManagerTest.await(manager, ids.get(2), State.COMPLETED); assertEquals(2, opened.get());
        }
    }
}
