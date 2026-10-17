package org.openshell.ssh;

import com.jcraft.jsch.JSch;
import org.apache.sshd.server.*;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.support.LocalSshServer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class ServerMonitorLifecycleTest {
    @TempDir Path root;
    @Test void stopRestartAndSelectionOwnChannelsAndResetBaselinesWithoutDuplicateStreams() throws Exception {
        try (LocalSshServer server = new LocalSshServer(root)) {
            List<Stream> streams = new CopyOnWriteArrayList<>();
            server.server.setCommandFactory((channel, command) -> { Stream stream = new Stream(command); streams.add(stream); return stream; });
            var session = new JSch().getSession("test", "127.0.0.1", server.server.getPort());
            session.setConfig("StrictHostKeyChecking", "no"); session.setPassword("test"); session.connect(5000);
            ServerMonitor monitor = new ServerMonitor(session); Recorder first = new Recorder(), second = new Recorder();
            try {
                monitor.start(first); await(() -> first.cpu.size() == 1); monitor.start(first);
                assertEquals(1, streams.size()); assertTrue(Double.isNaN(first.cpu.get(0)));
                monitor.stop(); await(() -> streams.get(0).destroyed);
                int previousCallbacks = first.cpu.size();
                monitor.start(second); await(() -> second.cpu.size() == 1);
                assertTrue(Double.isNaN(second.cpu.get(0)), "A resumed stream must not reuse old CPU counters");
                monitor.setSelection("eth0", "/mnt/data disk"); await(() -> streams.size() == 3 && streams.get(2).started);
                await(() -> second.cpu.size() == 2);
                assertTrue(Double.isNaN(second.cpu.get(1)), "Changing NIC/mount must reset the baseline");
                assertTrue(streams.get(2).command.contains("'/mnt/data disk'"));
                assertEquals(previousCallbacks, first.cpu.size(), "Stopped listener must not get callbacks from a newer run");
                monitor.stop(); await(() -> streams.get(2).destroyed); assertFalse(monitor.isRunning());
            } finally { monitor.stop(); session.disconnect(); }
        }
    }
    @Test void discoversInterfacesAndMountsUsingBoundedUtf8Exec() throws Exception {
        try (LocalSshServer server = new LocalSshServer(root)) {
            server.server.setCommandFactory((channel, command) -> new Stream(command) {
                @Override public void start(ChannelSession channel, Environment environment) throws IOException {
                    output.write(("eth0: 1 0 0 0 0 0 0 0 2 0 0 0 0 0 0 0\n---\n"
                            + "Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/a 1024 512 512 50% /mnt/数据 disk\n").getBytes(StandardCharsets.UTF_8));
                    output.flush(); callback.onExit(0);
                }
            });
            var session = new JSch().getSession("test", "127.0.0.1", server.server.getPort());
            session.setConfig("StrictHostKeyChecking", "no"); session.setPassword("test"); session.connect(5000);
            try {
                var resources = new ServerMonitor(session).discoverResources();
                assertEquals(List.of("eth0"), resources.networkInterfaces());
                assertEquals(List.of("/", "/mnt/数据 disk"), resources.mountPoints());
            } finally { session.disconnect(); }
        }
    }
    private static class Recorder implements ServerMonitor.MonitorListener {
        final List<Double> cpu = new CopyOnWriteArrayList<>();
        public void onUpdate(double value, double mem, long used, long total, double disk, double diskUsed, double diskTotal, double rx, double tx, String load) { cpu.add(value); }
    }
    private static class Stream implements Command {
        final String command; OutputStream output; ExitCallback callback; volatile boolean started, destroyed;
        Stream(String command) { this.command = command; }
        public void setInputStream(InputStream input) {}
        public void setOutputStream(OutputStream output) { this.output = output; }
        public void setErrorStream(OutputStream output) {}
        public void setExitCallback(ExitCallback callback) { this.callback = callback; }
        public void start(ChannelSession channel, Environment environment) throws IOException {
            started = true;
            output.write((String.join("\n", MonitorSamplesTest.frame(1024, 2048, 4000, 5000)) + "\n===\n").getBytes(StandardCharsets.UTF_8)); output.flush();
        }
        public void destroy(ChannelSession channel) { destroyed = true; }
    }
    private interface Check { boolean ready() throws Exception; }
    private static void await(Check check) { assertTimeoutPreemptively(Duration.ofSeconds(6), () -> { while (!check.ready()) Thread.sleep(20); }); }
}
