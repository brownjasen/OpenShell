package org.openshell.ssh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.support.LocalSshServer;
import org.openshell.terminal.SshTtyConnector;
import com.jediterm.core.util.TermSize;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SshSessionIntegrationTest {
    @TempDir Path directory;
    @Test void authenticatesTransportsInputResizesPtyAndReusesKnownKey() throws Exception {
        String before = System.getProperty("openshell.configDir");
        System.setProperty("openshell.configDir", directory.resolve("config").toString());
        try (LocalSshServer server = new LocalSshServer(directory)) {
            AtomicInteger confirmations = new AtomicInteger();
            SshSession connection = new SshSession(server.host(), (h, a, f) -> { confirmations.incrementAndGet(); return true; });
            try {
                connection.connect(); assertTrue(connection.isConnected());
                SshTtyConnector connector = new SshTtyConnector(connection, () -> {});
                connector.write("pwd\r");
                assertEquals((int)'p', server.shell.received.poll(3, TimeUnit.SECONDS));
                connector.resize(new TermSize(93, 26));
                assertTrue(server.shell.resized.await(3, TimeUnit.SECONDS));
                assertEquals("93", server.shell.environment.getEnv().get("COLUMNS"));
                assertEquals("26", server.shell.environment.getEnv().get("LINES"));
            } finally { connection.disconnect(); }
            assertFalse(connection.isConnected());
            SshSession second = new SshSession(server.host(), (h, a, f) -> { confirmations.incrementAndGet(); return false; });
            try { second.connect(); assertTrue(second.isConnected()); }
            finally { second.disconnect(); }
            assertEquals(1, confirmations.get());
        } finally {
            if (before == null) System.clearProperty("openshell.configDir"); else System.setProperty("openshell.configDir", before);
        }
    }

    @Test void authenticationFailureReleasesTransportAndRejectsFurtherWrites() throws Exception {
        try (LocalSshServer server = new LocalSshServer(directory)) {
            var host = server.host(); host.setPassword("incorrect");
            SshSession connection = new SshSession(host, (h, a, f) -> true);
            assertThrows(Exception.class, connection::connect);
            assertFalse(connection.isConnected());
            assertFalse(connection.getRawSession().isConnected());
            assertThrows(Exception.class, () -> connection.sendCommand("pwd"));
        }
    }

    @Test void unsupportedMonitorCommandDoesNotRetryInATightLoop() throws Exception {
        var originalError = System.err;
        var errorClosed = new java.util.concurrent.atomic.AtomicBoolean();
        var diagnostics = new java.io.PrintStream(new java.io.ByteArrayOutputStream()) {
            @Override public void close() { errorClosed.set(true); super.close(); }
        };
        try (LocalSshServer server = new LocalSshServer(directory)) {
            SshSession connection = new SshSession(server.host(), (h, a, f) -> true);
            ServerMonitor monitor = null;
            try {
                connection.connect();
                System.setErr(diagnostics);
                monitor = new ServerMonitor(connection.getRawSession());
                monitor.start((cpu, mem, used, total, disk, diskUsed, diskTotal, rx, tx, load) -> {});
                assertNotNull(server.commands.poll(3, TimeUnit.SECONDS));
                assertNull(server.commands.poll(300, TimeUnit.MILLISECONDS), "EOF must back off before opening another exec channel");
                assertFalse(errorClosed.get(), "Closing the monitor channel must not close the application's System.err");
            } finally {
                if (monitor != null) monitor.stop();
                connection.disconnect();
            }
        } finally { System.setErr(originalError); diagnostics.close(); }
    }
}
