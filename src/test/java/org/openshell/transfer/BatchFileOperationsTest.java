package org.openshell.transfer;

import com.jcraft.jsch.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.support.LocalSshServer;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BatchFileOperationsTest {
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

    @Test void deletesExactlyCapturedFilesAndEmptyDirectoriesAndReportsNonemptyFailureIndividually() throws Exception {
        Files.writeString(root.resolve("中文 a.txt"), "selected"); Files.createDirectory(root.resolve("empty"));
        Files.createDirectory(root.resolve("populated")); Files.writeString(root.resolve("populated/keep.txt"), "inside");
        Files.writeString(root.resolve("unselected.txt"), "untouched");
        var result = BatchFileOperations.delete(session, List.of("/中文 a.txt", "/populated", "/empty", "/missing.txt"));
        assertEquals(List.of("/中文 a.txt", "/empty"), result.deleted());
        assertTrue(result.failures().containsKey("/populated")); assertTrue(result.failures().containsKey("/missing.txt"));
        assertFalse(Files.exists(root.resolve("中文 a.txt"))); assertFalse(Files.exists(root.resolve("empty")));
        assertEquals("inside", Files.readString(root.resolve("populated/keep.txt")));
        assertEquals("untouched", Files.readString(root.resolve("unselected.txt")));
    }

    @Test void rejectsRootAliasesAndRelativeTraversalBeforeOpeningAChannel() {
        for (String path : List.of("/", "/.", "//", "/safe/../keep", "/safe/.", "relative", "/bad\0name"))
            assertThrows(java.io.IOException.class, () -> BatchFileOperations.delete(null, List.of(path)), path);
    }

    @Test void refusesDeletionThroughDirectorySymlinkAncestors() throws Exception {
        Files.createDirectory(root.resolve("protected")); Files.writeString(root.resolve("protected/keep.txt"), "protected");
        try { Files.createSymbolicLink(root.resolve("shortcut"), Path.of("protected")); }
        catch (java.io.IOException | UnsupportedOperationException | SecurityException unsupported) {
            Assumptions.abort("Local filesystem cannot create symbolic links: " + unsupported.getMessage());
        }
        var result = BatchFileOperations.delete(session, List.of("/shortcut/keep.txt"));
        assertTrue(result.deleted().isEmpty()); assertTrue(result.failures().containsKey("/shortcut/keep.txt"));
        assertEquals("protected", Files.readString(root.resolve("protected/keep.txt")));
    }
}
