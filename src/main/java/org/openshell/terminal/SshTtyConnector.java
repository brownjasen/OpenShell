package org.openshell.terminal;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.TtyConnector;
import org.openshell.ssh.SshSession;
import java.io.*;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Decoder state survives packet boundaries; all terminal escape sequences are preserved. */
public final class SshTtyConnector implements TtyConnector {
    private final SshSession session;
    private final Reader reader;
    private final Charset charset;
    private final Runnable onDisconnected;
    private final AtomicBoolean ended = new AtomicBoolean();
    private final DirectoryTracker directoryTracker;
    public SshTtyConnector(SshSession session, Runnable onDisconnected) {
        this(session, onDisconnected, path -> {});
    }
    public SshTtyConnector(SshSession session, Runnable onDisconnected, Consumer<String> onDirectoryChanged) {
        this.session = session;
        this.charset = Charset.forName(session.getConfig().getCharset());
        this.reader = new InputStreamReader(session.getInputStream(), charset);
        this.onDisconnected = onDisconnected;
        directoryTracker = new DirectoryTracker(onDirectoryChanged);
    }
    @Override public int read(char[] buffer, int offset, int length) throws IOException {
        try {
            int count = reader.read(buffer, offset, length);
            if (count > 0) directoryTracker.accept(buffer, offset, count);
            if (count == -1) finished();
            return count;
        } catch (IOException error) { finished(); throw error; }
    }
    @Override public void write(byte[] bytes) throws IOException {
        try { session.sendBytes(bytes, 0, bytes.length); }
        catch (Exception error) { throw new IOException("SSH 写入失败", error); }
    }
    @Override public void write(String text) throws IOException { write(text.getBytes(charset)); }
    @Override public boolean isConnected() { return !ended.get() && session.isConnected(); }
    @Override public boolean ready() throws IOException { return reader.ready(); }
    @Override public String getName() { return session.getConfig().getName(); }
    @Override public void resize(TermSize size) {
        session.resizePty(Math.max(2, size.getColumns()), Math.max(1, size.getRows()), 0, 0);
    }
    @Override public int waitFor() throws InterruptedException {
        while (isConnected()) Thread.sleep(100);
        return 0;
    }
    @Override public void close() { ended.set(true); session.closeShell(); }
    private void finished() { if (ended.compareAndSet(false, true)) onDisconnected.run(); }
}
