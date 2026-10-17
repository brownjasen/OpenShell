package org.openshell.ssh;

import com.jcraft.jsch.*;
import org.openshell.model.HostConfig;
import org.openshell.storage.AppPaths;
import java.io.*;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicBoolean;

/** One connection attempt. Reconnect creates a new instance. */
public class SshSession {
    private final HostConfig config;
    private final KnownHostsTrust.Confirmation confirmation;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object(), writeLock = new Object();
    private volatile Session session;
    private volatile ChannelShell shellChannel;
    private volatile InputStream inputStream;
    private volatile OutputStream outputStream;

    public SshSession(HostConfig config) { this(config, (host, algorithm, fingerprint) -> false); }
    public SshSession(HostConfig config, KnownHostsTrust.Confirmation confirmation) {
        this.config = config.copy(); this.confirmation = confirmation;
    }
    public void connect() throws Exception {
        if (isConnected()) return;
        try {
            requireOpen();
            Charset.forName(config.getCharset());
            JSch jsch = new JSch();
            jsch.setHostKeyRepository(new KnownHostsTrust(AppPaths.configDirectory().resolve("known_hosts"),
                    (host, algorithm, fingerprint) -> !closed.get()
                            && confirmation.accept(host, algorithm, fingerprint) && !closed.get()));
            if ("KEY".equalsIgnoreCase(config.getAuthType()) && config.getKeyPath() != null && !config.getKeyPath().isBlank()) {
                if (config.getKeyPassphrase() != null && !config.getKeyPassphrase().isEmpty())
                    jsch.addIdentity(config.getKeyPath(), config.getKeyPassphrase());
                else jsch.addIdentity(config.getKeyPath());
            }
            Session transport = jsch.getSession(config.getUsername(), config.getHost(), config.getPort());
            synchronized (lifecycleLock) { requireOpen(); session = transport; }
            if ("PASSWORD".equalsIgnoreCase(config.getAuthType()) && config.getPassword() != null)
                transport.setPassword(config.getPassword());
            transport.setConfig("StrictHostKeyChecking", "yes");
            transport.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password");
            transport.setServerAliveInterval(15000);
            transport.setServerAliveCountMax(3);
            requireOpen();
            transport.connect(15000);
            requireOpen();
            ChannelShell shell = (ChannelShell) transport.openChannel("shell");
            synchronized (lifecycleLock) { requireOpen(); shellChannel = shell; }
            shell.setPtyType("xterm-256color");
            shell.setPtySize(120, 32, 0, 0);
            inputStream = shell.getInputStream();
            outputStream = shell.getOutputStream();
            shell.connect(10000);
            requireOpen();
        } catch (Exception error) { disconnect(); throw error; }
    }
    private void requireOpen() throws IOException {
        if (closed.get() || Thread.currentThread().isInterrupted()) throw new IOException("连接已取消");
    }
    public void resizePty(int cols, int rows, int wp, int hp) {
        ChannelShell shell = shellChannel;
        if (shell != null && shell.isConnected()) shell.setPtySize(cols, rows, wp, hp);
    }
    public void sendCommand(String command) throws Exception {
        byte[] bytes = command.getBytes(Charset.forName(config.getCharset())); sendBytes(bytes, 0, bytes.length);
    }
    public void sendBytes(byte[] bytes, int offset, int length) throws Exception {
        synchronized (writeLock) {
            if (!isConnected() || outputStream == null) throw new IOException("SSH 会话未连接");
            outputStream.write(bytes, offset, length); outputStream.flush();
        }
    }
    public InputStream getInputStream() { return inputStream; }
    public OutputStream getOutputStream() { return outputStream; }
    public boolean isConnected() {
        Session transport = session; ChannelShell shell = shellChannel;
        return !closed.get() && transport != null && transport.isConnected()
                && shell != null && shell.isConnected() && !shell.isClosed();
    }
    public void closeShell() { ChannelShell shell = shellChannel; if (shell != null) shell.disconnect(); }
    public void disconnect() {
        closed.set(true);
        Session transport; ChannelShell shell;
        synchronized (lifecycleLock) { transport = session; shell = shellChannel; }
        try { if (shell != null) shell.disconnect(); }
        finally { if (transport != null) transport.disconnect(); }
    }
    public Session getRawSession() { return session; }
    public HostConfig getConfig() { return config; }
}
