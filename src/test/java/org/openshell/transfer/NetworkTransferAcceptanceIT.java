package org.openshell.transfer;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.support.LocalSshServer;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.openshell.transfer.TransferManager.*;

/** Explicit acceptance suite: run with -Dtest=NetworkTransferAcceptanceIT. Never uses saved hosts/configuration. */
class NetworkTransferAcceptanceIT {
    private static final long MIB = 1024L * 1024;
    @TempDir Path directory;

    @Test @Timeout(90)
    void streamed256MiBUploadAndDownloadHaveIdenticalSha256() throws Exception {
        Path root = Files.createDirectory(directory.resolve("server"));
        Path source = generate(directory.resolve("large-source.bin"), 256 * MIB);
        String expected = sha256(source);
        try (LocalSshServer server = new LocalSshServer(root)) {
            Session session = connect(server.server.getPort());
            try (TransferManager queue = queue(session)) {
                long uploadStarted = System.nanoTime();
                Snapshot uploaded = await(queue, queue.submit(Direction.UPLOAD, source, "/large.bin"), State.COMPLETED, 35);
                assertEquals(256 * MIB, Files.size(root.resolve("large.bin")));
                assertEquals(expected, sha256(root.resolve("large.bin")));
                report("256MiB-upload", source, uploadStarted, uploaded);
                Path download = directory.resolve("large-download.bin");
                long downloadStarted = System.nanoTime();
                Snapshot downloaded = await(queue, queue.submit(Direction.DOWNLOAD, download, "/large.bin"), State.COMPLETED, 35);
                assertEquals(256 * MIB, Files.size(download)); assertEquals(expected, sha256(download));
                report("256MiB-download", download, downloadStarted, downloaded);
                assertEquals(0, partFiles(directory), "Successful transfers must publish and remove their staging files");
            } finally { session.disconnect(); }
        }
    }

    @Test @Timeout(90)
    void delayedConnectionCutMidUploadPreservesRemoteDestinationAndFreshConnectionRequeuesSuccessfully() throws Exception {
        Path root = Files.createDirectory(directory.resolve("server"));
        Path source = generate(directory.resolve("upload-source.bin"), 32 * MIB);
        Path target = Files.writeString(root.resolve("target.bin"), "existing remote destination must survive network failure");
        String original = sha256(target), expected = sha256(source);
        try (LocalSshServer server = new LocalSshServer(root)) {
            try (CuttingProxy proxy = new CuttingProxy(server.server.getPort())) {
                Session broken = connect(proxy.port());
                try (TransferManager queue = queue(broken)) {
                    proxy.arm(CuttingProxy.Flow.UPLOAD, 768 * 1024, 10);
                    long started = System.nanoTime();
                    Snapshot failed = await(queue, queue.submit(Direction.UPLOAD, source, "/target.bin"), State.FAILED, 25);
                    assertTrue(proxy.cut(), "Proxy must actually sever the encrypted SSH transport mid-transfer");
                    assertTrue(failed.done() > 0 && failed.done() < Files.size(source), "Failure must happen after partial file progress: " + failed);
                    assertEquals(original, sha256(target));
                    long abandoned = partFiles(root);
                    if (abandoned > 0) assertTrue(failed.error().contains("临时文件可能保留"), "Orphaned staging file must be reported: " + failed.error());
                    System.out.printf(Locale.ROOT,
                            "NETWORK_ACCEPTANCE mid-upload-cut delayMs=10 proxyBytes=%d progress=%d seconds=%.3f preserved=true stagingFiles=%d error=%s%n",
                            proxy.cutBytes(), failed.done(), seconds(started), abandoned, failed.error());
                } finally { broken.disconnect(); }
            }
            Session fresh = connect(server.server.getPort());
            try (TransferManager queue = queue(fresh)) {
                long started = System.nanoTime();
                Snapshot result = await(queue, queue.submit(Direction.UPLOAD, source, "/target.bin"), State.COMPLETED, 25);
                assertEquals(expected, sha256(target)); report("fresh-upload-requeue", source, started, result);
            } finally { fresh.disconnect(); }
        }
    }

    @Test @Timeout(90)
    void delayedConnectionCutMidDownloadPreservesLocalDestinationAndFreshConnectionRequeuesSuccessfully() throws Exception {
        Path root = Files.createDirectory(directory.resolve("server"));
        Path remote = generate(root.resolve("source.bin"), 32 * MIB);
        Path target = Files.writeString(directory.resolve("download-target.bin"), "existing local destination must survive network failure");
        String original = sha256(target), expected = sha256(remote);
        try (LocalSshServer server = new LocalSshServer(root)) {
            try (CuttingProxy proxy = new CuttingProxy(server.server.getPort())) {
                Session broken = connect(proxy.port());
                try (TransferManager queue = queue(broken)) {
                    proxy.arm(CuttingProxy.Flow.DOWNLOAD, 768 * 1024, 10);
                    long started = System.nanoTime();
                    Snapshot failed = await(queue, queue.submit(Direction.DOWNLOAD, target, "/source.bin"), State.FAILED, 25);
                    assertTrue(proxy.cut());
                    assertTrue(failed.done() > 0 && failed.done() < Files.size(remote), "Failure must happen after partial file progress: " + failed);
                    assertEquals(original, sha256(target));
                    assertEquals(0, partFiles(directory), "Interrupted download must delete local staging data");
                    System.out.printf(Locale.ROOT,
                            "NETWORK_ACCEPTANCE mid-download-cut delayMs=10 proxyBytes=%d progress=%d seconds=%.3f preserved=true error=%s%n",
                            proxy.cutBytes(), failed.done(), seconds(started), failed.error());
                } finally { broken.disconnect(); }
            }
            Session fresh = connect(server.server.getPort());
            try (TransferManager queue = queue(fresh)) {
                long started = System.nanoTime();
                Snapshot result = await(queue, queue.submit(Direction.DOWNLOAD, target, "/source.bin"), State.COMPLETED, 25);
                assertEquals(expected, sha256(target)); report("fresh-download-requeue", target, started, result);
                assertEquals(0, partFiles(directory));
            } finally { fresh.disconnect(); }
        }
    }

    private static Session connect(int port) throws Exception {
        Session session = new JSch().getSession("test", "127.0.0.1", port);
        // Every key and listener belongs to the generated, isolated loopback fixture.
        session.setConfig("StrictHostKeyChecking", "no"); session.setPassword("test");
        session.setServerAliveInterval(2000); session.setServerAliveCountMax(2);
        try { session.connect(5000); return session; }
        catch (Exception failure) { session.disconnect(); throw failure; }
    }
    private static TransferManager queue(Session session) {
        return new TransferManager(() -> new SftpTransfer(session), task -> new Decision(Choice.REPLACE, false));
    }
    private static Snapshot await(TransferManager queue, UUID id, State state, long timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        Snapshot result;
        while (!(result = queue.find(id)).state().terminal() && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(state, result.state(), result.toString()); return result;
    }
    private static Path generate(Path path, long size) throws IOException {
        byte[] buffer = new byte[64 * 1024]; new Random(0x51f7).nextBytes(buffer);
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(path))) {
            for (long remaining = size; remaining > 0; remaining -= buffer.length)
                output.write(buffer, 0, (int) Math.min(remaining, buffer.length));
        }
        return path;
    }
    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[64 * 1024];
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            int count; while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static long partFiles(Path path) throws IOException {
        try (var files = Files.walk(path)) { return files.filter(p -> p.getFileName().toString().endsWith(".part")).count(); }
    }
    private static double seconds(long started) { return (System.nanoTime() - started) / 1_000_000_000.0; }
    private static void report(String name, Path source, long started, Snapshot result) throws IOException {
        double elapsed = seconds(started), sizeMiB = Files.size(source) / (double) MIB;
        System.out.printf(Locale.ROOT, "NETWORK_ACCEPTANCE %s bytes=%d seconds=%.3f MiBps=%.2f done=%d sha256=matched%n",
                name, Files.size(source), elapsed, sizeMiB / elapsed, result.done());
    }

    /** Test-only TCP bridge; delay and RST apply to the real encrypted socket, not fake SFTP callbacks. */
    private static final class CuttingProxy implements AutoCloseable {
        enum Flow { UPLOAD, DOWNLOAD }
        private final ServerSocket listener;
        private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "OpenShell-AcceptanceProxy"); thread.setDaemon(true); return thread;
        });
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean closed = new AtomicBoolean(), didCut = new AtomicBoolean();
        private final AtomicLong uploaded = new AtomicLong(), downloaded = new AtomicLong(), cutBytes = new AtomicLong();
        private volatile Flow cutFlow;
        private volatile long threshold, delayMillis;
        CuttingProxy(int targetPort) throws IOException {
            listener = new ServerSocket(); listener.bind(new InetSocketAddress("127.0.0.1", 0));
            workers.execute(() -> {
                try {
                    Socket client = listener.accept(); sockets.add(client); client.setTcpNoDelay(true);
                    Socket upstream = new Socket(); sockets.add(upstream);
                    upstream.connect(new InetSocketAddress("127.0.0.1", targetPort), 5000); upstream.setTcpNoDelay(true);
                    workers.execute(() -> forward(client, upstream, Flow.UPLOAD));
                    workers.execute(() -> forward(upstream, client, Flow.DOWNLOAD));
                } catch (IOException | RejectedExecutionException ignored) { disconnectSockets(); }
            });
        }
        int port() { return listener.getLocalPort(); }
        void arm(Flow flow, long threshold, long delayMillis) {
            uploaded.set(0); downloaded.set(0); this.threshold = threshold; this.delayMillis = delayMillis; cutFlow = flow;
        }
        boolean cut() { return didCut.get(); }
        long cutBytes() { return cutBytes.get(); }
        private void forward(Socket from, Socket to, Flow direction) {
            byte[] buffer = new byte[32 * 1024];
            try {
                InputStream input = from.getInputStream(); OutputStream output = to.getOutputStream();
                int count;
                while (!closed.get() && (count = input.read(buffer)) >= 0) {
                    if (count == 0) continue;
                    Flow active = cutFlow;
                    if (active != null && delayMillis > 0) Thread.sleep(delayMillis);
                    if (active != null) {
                        long bytes = (direction == Flow.UPLOAD ? uploaded : downloaded).addAndGet(count);
                        if (direction == active && bytes >= threshold && didCut.compareAndSet(false, true)) {
                            cutBytes.set(bytes); disconnectSockets(); return;
                        }
                    }
                    output.write(buffer, 0, count); output.flush();
                }
            } catch (IOException ignored) {
                // Socket failure is the deliberate network fault under test.
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { disconnectSockets(); }
        }
        private void disconnectSockets() {
            for (Socket socket : sockets) {
                try { socket.setSoLinger(true, 0); } catch (SocketException ignored) {}
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
        @Override public void close() throws Exception {
            closed.set(true); listener.close(); disconnectSockets(); workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS), "Test TCP proxy threads must terminate");
        }
    }
}
