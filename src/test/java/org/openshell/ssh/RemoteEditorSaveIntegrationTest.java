package org.openshell.ssh;

import com.jcraft.jsch.JSch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.support.LocalSshServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import static org.junit.jupiter.api.Assertions.*;

class RemoteEditorSaveIntegrationTest {
    @TempDir Path root;

    @Test void canonicalFileIdentityCoversExistingAndMissingNamesAndRejectsDirectorySegments() throws Exception {
        Files.createDirectory(root.resolve("dir")); Files.writeString(root.resolve("draft.txt"), "before");
        try (LocalSshServer server = new LocalSshServer(root)) {
            var session = new JSch().getSession("test", "127.0.0.1", server.server.getPort());
            session.setConfig("StrictHostKeyChecking", "no"); session.setPassword("test"); session.connect(5000);
            SftpClient client = new SftpClient(session);
            try {
                assertEquals(client.canonicalFilePath("/draft.txt"), client.canonicalFilePath("/dir/../draft.txt"));
                assertEquals(client.canonicalFilePath("/missing.txt"), client.canonicalFilePath("/dir/../missing.txt"));
                assertThrows(Exception.class, () -> client.canonicalFilePath("/dir/.."));
                assertThrows(Exception.class, () -> client.canonicalFilePath("/dir/"));
            } finally { client.disconnect(); session.disconnect(); }
        }
    }

    @Test void detectsSameSizeSameTimestampChangesAndCanExplicitlyReplaceLatestVersion() throws Exception {
        Path file = root.resolve("文稿[1].txt"); Files.writeString(file, "first");
        FileTime timestamp = Files.getLastModifiedTime(file);
        try (LocalSshServer server = new LocalSshServer(root)) {
            var session = new JSch().getSession("test", "127.0.0.1", server.server.getPort());
            session.setConfig("StrictHostKeyChecking", "no"); session.setPassword("test"); session.connect(5000);
            SftpClient client = new SftpClient(session);
            try {
                var baseline = client.readFileSnapshot("/文稿[1].txt");
                Files.writeString(file, "other"); Files.setLastModifiedTime(file, timestamp);
                var conflict = assertThrows(SftpClient.RemoteFileConflictException.class,
                        () -> client.saveFileSafely("/文稿[1].txt", "mine".getBytes(StandardCharsets.UTF_8), baseline));
                assertEquals("other", Files.readString(file));
                assertEquals("other", new String(conflict.current().content(), StandardCharsets.UTF_8));
                client.saveFileSafely("/文稿[1].txt", "mine".getBytes(StandardCharsets.UTF_8), conflict.current());
                assertEquals("mine", Files.readString(file));
                try (var paths = Files.list(root)) { assertEquals(1, paths.count(), "Staging files must be removed"); }
            } finally { client.disconnect(); session.disconnect(); }
        }
    }

    @Test void deletedOrNewlyCreatedTargetsRequireASecondExplicitDecision() throws Exception {
        Path file = root.resolve("draft.txt"); Files.writeString(file, "original");
        try (LocalSshServer server = new LocalSshServer(root)) {
            var session = new JSch().getSession("test", "127.0.0.1", server.server.getPort());
            session.setConfig("StrictHostKeyChecking", "no"); session.setPassword("test"); session.connect(5000);
            SftpClient client = new SftpClient(session);
            try {
                var baseline = client.readFileSnapshot("/draft.txt"); Files.delete(file);
                assertThrows(SftpClient.RemoteFileConflictException.class,
                        () -> client.saveFileSafely("/draft.txt", new byte[]{1}, baseline));
                assertFalse(Files.exists(file));
                assertNull(client.readFileSnapshot("/copy.txt")); Files.writeString(root.resolve("copy.txt"), "new owner");
                assertThrows(SftpClient.RemoteFileConflictException.class,
                        () -> client.saveFileSafely("/copy.txt", new byte[]{1}, null));
                assertEquals("new owner", Files.readString(root.resolve("copy.txt")));
            } finally { client.disconnect(); session.disconnect(); }
        }
    }

    @Test void failingToStageKeepsOriginalAndOriginalPermissions() throws Exception {
        Path file = root.resolve("draft.txt"); Files.writeString(file, "original");
        try (LocalSshServer server = new LocalSshServer(root)) {
            var session = new JSch().getSession("test", "127.0.0.1", server.server.getPort());
            session.setConfig("StrictHostKeyChecking", "no"); session.setPassword("test"); session.connect(5000);
            SftpClient client = new SftpClient(session);
            try {
                var baseline = client.readFileSnapshot("/draft.txt");
                assertThrows(Exception.class, () -> client.saveFileSafely("/missing/copy.txt", new byte[]{1}, null));
                assertEquals("original", Files.readString(file));
                client.saveFileSafely("/draft.txt", "saved".getBytes(StandardCharsets.UTF_8), baseline);
                assertEquals(baseline.permissions(), client.readFileSnapshot("/draft.txt").permissions());
            } finally { client.disconnect(); session.disconnect(); }
        }
    }
}
