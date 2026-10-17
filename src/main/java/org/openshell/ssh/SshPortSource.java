package org.openshell.ssh;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.Session;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** A bounded, read-only exec request, independent from the interactive terminal. */
public final class SshPortSource implements PortSource {
    static final String COMMAND = """
            LC_ALL=C; export LC_ALL
            if command -v ss >/dev/null 2>&1; then
              printf 'OSPORTS SS\\n'
              ss -H -tulnp
            elif command -v netstat >/dev/null 2>&1; then
              printf 'OSPORTS NETSTAT\\n'
              netstat -tulnp
            else
              printf 'ss / netstat unavailable\\n' >&2; exit 127
            fi
            """;
    private final Session session;
    private final long timeoutMillis;
    private volatile ChannelExec active;
    private volatile boolean closed;
    public SshPortSource(Session session) { this(session, 8000); }
    SshPortSource(Session session, long timeoutMillis) { this.session = session; this.timeoutMillis = timeoutMillis; }

    @Override public List<PortParser.PortEntry> load() throws Exception {
        if (closed) throw new IOException("端口监控已关闭");
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        active = channel;
        try {
            if (closed) throw new IOException("端口监控已关闭");
            channel.setCommand(COMMAND); channel.setInputStream(null);
            InputStream input = channel.getInputStream(), errors = channel.getErrStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream(), diagnostics = new ByteArrayOutputStream();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            channel.connect((int) Math.min(5000, timeoutMillis));
            while (true) {
                if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("端口采集已取消");
                drain(input, output, 4 * 1024 * 1024); drain(errors, diagnostics, 65536);
                if (channel.isClosed() && input.available() == 0 && errors.available() == 0) break;
                if (System.nanoTime() >= deadline) throw new IOException("读取端口超时（" + timeoutMillis / 1000.0 + " 秒）");
                Thread.sleep(20);
            }
            if (channel.getExitStatus() != 0) {
                String detail = diagnostics.toString(StandardCharsets.UTF_8).strip();
                throw new IOException("远程命令退出码 " + channel.getExitStatus() + (detail.isEmpty() ? "" : ": " + detail));
            }
            String text = output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
            if (text.startsWith("OSPORTS SS\n")) return PortParser.parseSs(text.substring("OSPORTS SS\n".length()));
            if (text.startsWith("OSPORTS NETSTAT\n")) return PortParser.parseNetstat(text.substring("OSPORTS NETSTAT\n".length()));
            throw new IOException("无法识别端口采集结果（服务器需要 ss 或 netstat）");
        } finally { channel.disconnect(); if (active == channel) active = null; }
    }

    private static void drain(InputStream input, ByteArrayOutputStream output, int limit) throws IOException {
        byte[] bytes = new byte[8192];
        while (input.available() > 0) {
            int count = input.read(bytes, 0, Math.min(bytes.length, input.available()));
            if (count < 0) break;
            if (output.size() + count > limit) throw new IOException("端口数据超过读取上限");
            output.write(bytes, 0, count);
        }
    }

    @Override public void close() { closed = true; ChannelExec channel = active; if (channel != null) channel.disconnect(); }
}
