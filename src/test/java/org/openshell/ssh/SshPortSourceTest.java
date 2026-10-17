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
import static org.junit.jupiter.api.Assertions.*;

class SshPortSourceTest {
    @TempDir Path directory;
    LocalSshServer server;
    SshSession connection;
    String oldConfig;
    @BeforeEach void connect() throws Exception {
        oldConfig = System.getProperty("openshell.configDir");
        System.setProperty("openshell.configDir", directory.resolve("config").toString());
        server = new LocalSshServer(directory); connection = new SshSession(server.host(), (h, a, f) -> true); connection.connect();
    }
    @AfterEach void disconnect() throws Exception {
        try { if (connection != null) connection.disconnect(); if (server != null) server.close(); }
        finally { if (oldConfig == null) System.clearProperty("openshell.configDir"); else System.setProperty("openshell.configDir", oldConfig); }
    }
    @Test void usesExplicitFormatMarkerAndDecodesUtf8OnASeparateChannel() throws Exception {
        server.server.setCommandFactory((channel, command) -> {
            assertEquals(SshPortSource.COMMAND, command);
            return new OutputCommand("OSPORTS SS\ntcp LISTEN 0 20 [::]:8080 [::]:* users:((\"中文程序\",pid=91,fd=3))\n", "", 0, null);
        });
        try (var source = new SshPortSource(connection.getRawSession())) {
            var rows = source.load(); assertEquals(1, rows.size()); assertEquals(8080, rows.get(0).port()); assertEquals("中文程序", rows.get(0).process());
            assertTrue(server.shell.received.isEmpty(), "Monitoring must never type into the interactive shell");
        }
    }
    @Test void parsesNetstatFallbackAndReportsRemoteExitErrors() throws Exception {
        server.server.setCommandFactory((c, s) -> new OutputCommand("OSPORTS NETSTAT\nudp 0 0 0.0.0.0:53 0.0.0.0:* 7/dns\n", "", 0, null));
        try (var source = new SshPortSource(connection.getRawSession())) { assertEquals(7, source.load().get(0).pid()); }
        server.server.setCommandFactory((c, s) -> new OutputCommand("", "没有权限", 1, null));
        try (var source = new SshPortSource(connection.getRawSession())) { assertTrue(assertThrows(IOException.class, source::load).getMessage().contains("没有权限")); }
    }
    @Test void timeoutIsBoundedAndCloseCancelsAnActiveChannel() throws Exception {
        server.server.setCommandFactory((c, s) -> new OutputCommand("", "", -1, null));
        try (var source = new SshPortSource(connection.getRawSession(), 500)) { assertTrue(assertThrows(IOException.class, source::load).getMessage().contains("超时")); }
        CountDownLatch started = new CountDownLatch(1);
        server.server.setCommandFactory((c, s) -> new OutputCommand("", "", -1, started));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (var source = new SshPortSource(connection.getRawSession())) {
            Future<?> pending = worker.submit(() -> { try { return source.load(); } catch (Exception e) { throw new RuntimeException(e); } });
            assertTrue(started.await(2, TimeUnit.SECONDS)); source.close();
            assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS)); assertThrows(IOException.class, source::load);
        } finally { worker.shutdownNow(); }
    }
    private static final class OutputCommand implements Command {
        private final String text, error; private final int status; private final CountDownLatch started;
        private OutputStream out, err; private ExitCallback callback;
        OutputCommand(String text, String error, int status, CountDownLatch started) { this.text = text; this.error = error; this.status = status; this.started = started; }
        public void setInputStream(InputStream in) {}
        public void setOutputStream(OutputStream out) { this.out = out; }
        public void setErrorStream(OutputStream err) { this.err = err; }
        public void setExitCallback(ExitCallback callback) { this.callback = callback; }
        public void start(ChannelSession channel, Environment environment) throws IOException {
            if (started != null) started.countDown(); out.write(text.getBytes(StandardCharsets.UTF_8)); out.flush();
            err.write(error.getBytes(StandardCharsets.UTF_8)); err.flush(); if (status >= 0) callback.onExit(status);
        }
        public void destroy(ChannelSession channel) {}
    }
}
