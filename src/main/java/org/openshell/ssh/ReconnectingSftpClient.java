package org.openshell.ssh;

import com.jcraft.jsch.Session;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stable identity for editor tabs belonging to one logical SSH session.
 * One call captures one physical client, never retries on a new transport, and rejects retired results.
 */
public final class ReconnectingSftpClient extends SftpClient implements AutoCloseable {
    private static final ExecutorService RETIRED = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "OpenShell-EditorSftpRetire"); thread.setDaemon(true); return thread;
    });
    private final Object lifecycle = new Object();
    private volatile Binding current;
    private Endpoint endpoint;
    private boolean closed;
    private volatile String lastHome = "/", lastPath = "/";

    private record Endpoint(String host, int port, String user) {
        static Endpoint of(Session session) {
            return new Endpoint(Objects.requireNonNull(session.getHost(), "SSH 主机不能为空").toLowerCase(Locale.ROOT),
                    session.getPort(), Objects.requireNonNull(session.getUserName(), "SSH 用户不能为空"));
        }
    }
    private static final class Binding {
        final Session session;
        final SftpClient delegate;
        volatile String home, path = "/";
        Binding(Session session, String home) { this.session = session; this.delegate = new SftpClient(session); this.home = home; }
    }
    @FunctionalInterface private interface Operation<T> { T run(SftpClient client) throws Exception; }

    public ReconnectingSftpClient() { super(null); }

    /** No network I/O or client monitor is acquired while changing the binding. */
    public void bind(Session session) {
        Objects.requireNonNull(session, "SSH 会话不能为空"); Endpoint proposed = Endpoint.of(session); Binding retired;
        synchronized (lifecycle) {
            if (closed) throw new IllegalStateException("编辑器 SFTP 会话已关闭");
            if (endpoint != null && !endpoint.equals(proposed))
                throw new IllegalArgumentException("拒绝把编辑器绑定到不同的主机、端口或用户");
            if (current != null && current.session == session) return;
            if (endpoint == null) endpoint = proposed;
            retired = current; current = new Binding(session, lastHome);
        }
        retire(retired);
    }

    /** Drop the physical transport while retaining the logical editor identity and endpoint guard. */
    public void suspend() {
        Binding retired;
        synchronized (lifecycle) { retired = current; current = null; }
        retire(retired);
    }

    @Override public void close() {
        Binding retired;
        synchronized (lifecycle) { if (closed) return; closed = true; retired = current; current = null; }
        retire(retired);
    }
    @Override public void disconnect() { close(); }

    private static void retire(Binding retired) {
        if (retired != null) RETIRED.execute(retired.delegate::disconnect);
    }
    private Binding capture() throws IOException {
        synchronized (lifecycle) {
            if (closed) throw new IOException("编辑器 SFTP 会话已关闭");
            if (current == null) throw new IOException("SSH 连接已断开，请重新连接后再保存；本地修改已保留");
            return current;
        }
    }
    private <T> T call(Operation<T> operation) throws Exception {
        Binding bound = capture();
        T result = operation.run(bound.delegate);
        // Getters remain cached and nonblocking for UI callers. Fetching home happens here on the I/O caller.
        String home = bound.delegate.getHome(), path = bound.delegate.getCurrentPath();
        synchronized (lifecycle) {
            if (current != bound || closed)
                throw new IOException("SSH 连接已切换，此次操作结果未确认；未自动重试，请检查远端文件");
            bound.home = lastHome = home; bound.path = lastPath = path;
        }
        return result;
    }

    @Override public void connect() throws Exception { call(client -> { client.connect(); return null; }); }
    @Override public List<FileItem> listFiles(String path) throws Exception { return call(client -> client.listFiles(path)); }
    @Override public void cd(String path) throws Exception { call(client -> { client.cd(path); return null; }); }
    @Override public void cdUp() throws Exception { call(client -> { client.cdUp(); return null; }); }
    @Override public void downloadFile(String remoteFile, File localTarget) throws Exception { call(client -> { client.downloadFile(remoteFile, localTarget); return null; }); }
    @Override public void uploadFile(File localFile, String remoteTargetName) throws Exception { call(client -> { client.uploadFile(localFile, remoteTargetName); return null; }); }
    @Override public String canonicalFilePath(String path) throws Exception { return call(client -> client.canonicalFilePath(path)); }
    @Override public RemoteFileSnapshot readFileSnapshot(String path) throws Exception { return call(client -> client.readFileSnapshot(path)); }
    @Override public RemoteFileSnapshot saveFileSafely(String path, byte[] content, RemoteFileSnapshot expected) throws Exception {
        return call(client -> client.saveFileSafely(path, content, expected));
    }
    @Override public void delete(String path, boolean directory) throws Exception { call(client -> { client.delete(path, directory); return null; }); }
    @Override public void mkdir(String path) throws Exception { call(client -> { client.mkdir(path); return null; }); }
    @Override public void rename(String source, String destination) throws Exception { call(client -> { client.rename(source, destination); return null; }); }
    @Override public void chmod(int permissions, String path) throws Exception { call(client -> { client.chmod(permissions, path); return null; }); }
    @Override public void createEmptyFile(String path) throws Exception { call(client -> { client.createEmptyFile(path); return null; }); }
    @Override public String executeRemoteCommand(String command) throws Exception { return call(client -> client.executeRemoteCommand(command)); }
    @Override public String resolvePath(String path) throws Exception { return call(client -> client.resolvePath(path)); }
    @Override public boolean usesSession(Session transport) { Binding bound = current; return bound != null && bound.session == transport; }
    @Override public String getHome() { Binding bound = current; return bound == null ? lastHome : bound.home; }
    @Override public String getCurrentPath() { Binding bound = current; return bound == null ? lastPath : bound.path; }
}
