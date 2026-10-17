package org.openshell.ssh;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.support.LocalSshServer;
import org.apache.sshd.server.*;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.channel.ChannelSession;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SshProcessSourceTest {
    @TempDir Path directory;
    LocalSshServer server;
    SshSession connection;
    String oldConfig;

    @BeforeEach void connect() throws Exception {
        oldConfig = System.getProperty("openshell.configDir");
        System.setProperty("openshell.configDir", directory.resolve("config").toString());
        server = new LocalSshServer(directory);
        connection = new SshSession(server.host(), (h, a, f) -> true);
        connection.connect();
    }
    @AfterEach void disconnect() throws Exception {
        try { if (connection != null) connection.disconnect(); if (server != null) server.close(); }
        finally { if (oldConfig == null) System.clearProperty("openshell.configDir"); else System.setProperty("openshell.configDir", oldConfig); }
    }

    @Test void readsTwoUtf8SnapshotsOverSshAndCalculatesIntervalCpu() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.server.setCommandFactory((channel, command) -> {
            assertEquals(SshProcessSource.SNAPSHOT_COMMAND, command);
            int sample = calls.getAndIncrement();
            return new OutputCommand(LinuxProcessSamplerTest.snapshot(1000 + sample * 2, 1000 + sample * 50, 100), "", 0, null);
        });
        try (var source = new SshProcessSource(connection.getRawSession())) {
            var rows = source.load();
            assertEquals(2, calls.get()); assertEquals(25.0, rows.get(0).cpuPercent(), 0.001);
            assertEquals("/opt/中文 app --test", rows.get(0).command());
        }
    }

    @Test void nonzeroRemoteExitIsAnErrorIncludingStderr() throws Exception {
        server.server.setCommandFactory((c, s) -> new OutputCommand("", "没有权限", 1, null));
        try (var source = new SshProcessSource(connection.getRawSession())) {
            IOException error = assertThrows(IOException.class, () -> source.execute("test command"));
            assertTrue(error.getMessage().contains("没有权限"));
        }
    }

    @Test void hungCommandTimesOutAndCloseCancelsAnActiveRead() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        server.server.setCommandFactory((c, s) -> new OutputCommand("", "", -1, started));
        try (var source = new SshProcessSource(connection.getRawSession(), 500)) {
            assertTrue(assertThrows(IOException.class, () -> source.execute("hung")).getMessage().contains("超时"));
        }
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch secondStarted = new CountDownLatch(1);
        server.server.setCommandFactory((c, s) -> new OutputCommand("", "", -1, secondStarted));
        try (var source = new SshProcessSource(connection.getRawSession())) {
            Future<String> pending = worker.submit(() -> source.execute("cancelled"));
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
            source.close();
            assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
            assertThrows(IOException.class, () -> source.execute("closed"));
        } finally { worker.shutdownNow(); }
    }

    private static final class OutputCommand implements Command {
        private final String text, error;
        private final int status;
        private final CountDownLatch started;
        private OutputStream out, err;
        private ExitCallback callback;
        OutputCommand(String text, String error, int status, CountDownLatch started) {
            this.text = text; this.error = error; this.status = status; this.started = started;
        }
        public void setInputStream(InputStream in) {}
        public void setOutputStream(OutputStream out) { this.out = out; }
        public void setErrorStream(OutputStream err) { this.err = err; }
        public void setExitCallback(ExitCallback callback) { this.callback = callback; }
        public void start(ChannelSession channel, Environment environment) throws IOException {
            if (started != null) started.countDown();
            out.write(text.getBytes(StandardCharsets.UTF_8)); out.flush();
            err.write(error.getBytes(StandardCharsets.UTF_8)); err.flush();
            if (status >= 0) callback.onExit(status);
        }
        public void destroy(ChannelSession channel) {}
    }
}
