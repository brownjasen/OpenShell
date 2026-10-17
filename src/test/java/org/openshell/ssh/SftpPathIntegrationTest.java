package org.openshell.ssh;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SftpPathIntegrationTest {
    @TempDir Path directory;

    @Test void expandingAnotherDirectoryDoesNotChangeFileOperationDestination() throws Exception {
        Path root = Files.createDirectory(directory.resolve("root"));
        Files.createDirectories(root.resolve("a"));
        Files.createDirectories(root.resolve("b"));
        org.openshell.support.LocalSshServer fixture = new org.openshell.support.LocalSshServer(root);
        SshServer server = fixture.server;
        Session session = new JSch().getSession("test", "127.0.0.1", server.getPort());
        // Isolated ephemeral test server only; product trust is tested separately.
        session.setConfig("StrictHostKeyChecking", "no");
        session.setPassword("test");
        try {
            session.connect(5000);
            SftpClient client = new SftpClient(session);
            try {
                client.connect();
                client.cd("/a");
                client.listFiles("/b");
                assertEquals("/a", client.getCurrentPath(), "Tree enumeration must not navigate the browser");
                client.createEmptyFile("marker.txt");
                assertTrue(Files.exists(root.resolve("a/marker.txt")));
                assertFalse(Files.exists(root.resolve("b/marker.txt")));
            } finally { client.disconnect(); }
        } finally {
            session.disconnect();
            fixture.close();
        }
    }
}
