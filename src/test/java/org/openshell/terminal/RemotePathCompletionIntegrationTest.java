package org.openshell.terminal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.ssh.SshSession;
import org.openshell.support.LocalSshServer;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class RemotePathCompletionIntegrationTest {
    @TempDir Path directory;
    @Test void completesHomeAndReportedDirectoriesQuotesNamesAndNeverExecutesShellCommands() throws Exception {
        String previous = System.getProperty("openshell.configDir");
        System.setProperty("openshell.configDir", directory.resolve("config").toString());
        Path root = Files.createDirectory(directory.resolve("remote"));
        Files.writeString(root.resolve("a b'中.txt"), "fixture");
        Files.createDirectory(root.resolve("folder"));
        Files.writeString(root.resolve("folder").resolve("nested.txt"), "nested");
        try (LocalSshServer server = new LocalSshServer(root)) {
            SshSession connection = new SshSession(server.host(), (h, a, f) -> true);
            try {
                connection.connect();
                try (RemotePathCompletion completion = new RemotePathCompletion(connection.getRawSession())) {
                    var quoted = completion.complete("cat a", 5, null);
                    assertEquals(1, quoted.size());
                    assertEquals("cat 'a b'\\''中.txt'", quoted.get(0).draft());
                    var nested = completion.complete("cat n", 5, "/folder");
                    assertEquals("cat 'nested.txt'", nested.get(0).draft());
                    var folder = completion.complete("cd /f", 5, null);
                    assertTrue(folder.get(0).directory());
                    assertEquals("cd '/folder/'", folder.get(0).draft());
                    assertThrows(Exception.class, () -> completion.complete("cat $HOME/a", 11, null));
                    assertThrows(Exception.class, () -> completion.complete("cat ab trailing", 5, null));
                    assertTrue(server.commands.isEmpty(), "Completion must use SFTP rather than shell execution");
                    assertNull(server.shell.received.poll(150, java.util.concurrent.TimeUnit.MILLISECONDS));
                }
                assertTrue(connection.isConnected(), "Closing a completion channel must preserve the terminal");
            } finally { connection.disconnect(); }
        } finally {
            if (previous == null) System.clearProperty("openshell.configDir");
            else System.setProperty("openshell.configDir", previous);
        }
    }
}
