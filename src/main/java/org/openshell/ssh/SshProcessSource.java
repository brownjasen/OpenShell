package org.openshell.ssh;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.Session;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Short, bounded exec channels; never writes to the interactive terminal. */
public final class SshProcessSource implements ProcessSource {
    static final String SNAPSHOT_COMMAND = """
            LC_ALL=C.UTF-8
            export LC_ALL
            hz=$(getconf CLK_TCK) || exit 1
            read -r uptime rest < /proc/uptime || exit 1
            printf 'OSPROC1 %s %s %s\\n' "$$" "$hz" "$uptime"
            ps -e -ww -o pid=,ppid=,user:64=,rss=,etimes=,pmem=,args= || exit 1
            printf 'OSSTAT\\n'
            for path in /proc/[0-9]*/stat; do
                { IFS= read -r stat < "$path"; } 2>/dev/null && printf '%s\\n' "$stat"
            done
            printf 'OSEND\\n'
            """;
    private final Session session;
    private final long timeoutMillis;
    private volatile ChannelExec active;
    private volatile boolean closed;

    public SshProcessSource(Session session) { this(session, 8000); }
    SshProcessSource(Session session, long timeoutMillis) { this.session = session; this.timeoutMillis = timeoutMillis; }

    @Override public List<LinuxProcessSampler.ProcessInfo> load() throws Exception {
        LinuxProcessSampler sampler = new LinuxProcessSampler();
        sampler.accept(execute(SNAPSHOT_COMMAND));
        Thread.sleep(1000);
        return sampler.accept(execute(SNAPSHOT_COMMAND));
    }

    @Override public void kill(int pid) throws Exception {
        if (pid <= 0) throw new IllegalArgumentException("无效的 PID");
        execute("kill -9 " + pid);
    }

    String execute(String command) throws Exception {
        if (closed) throw new IOException("进程监控已关闭");
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        active = channel;
        try {
            if (closed) throw new IOException("进程监控已关闭");
            channel.setCommand(command);
            channel.setInputStream(null);
            InputStream input = channel.getInputStream(), errors = channel.getErrStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream(), diagnostics = new ByteArrayOutputStream();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            channel.connect((int) Math.min(5000, timeoutMillis));
            while (true) {
                if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("采集已取消");
                drain(input, output, 16 * 1024 * 1024);
                drain(errors, diagnostics, 65536);
                if (channel.isClosed() && input.available() == 0 && errors.available() == 0) break;
                if (System.nanoTime() >= deadline) throw new IOException("读取进程超时");
                Thread.sleep(20);
            }
            if (channel.getExitStatus() != 0) {
                String detail = diagnostics.toString(StandardCharsets.UTF_8).strip();
                throw new IOException("远程命令退出码 " + channel.getExitStatus() + (detail.isEmpty() ? "" : ": " + detail));
            }
            return output.toString(StandardCharsets.UTF_8);
        } finally {
            channel.disconnect();
            if (active == channel) active = null;
        }
    }

    private static void drain(InputStream input, ByteArrayOutputStream output, int limit) throws IOException {
        byte[] bytes = new byte[8192];
        while (input.available() > 0) {
            int count = input.read(bytes, 0, Math.min(bytes.length, input.available()));
            if (count < 0) break;
            if (output.size() + count > limit) throw new IOException("进程数据超过读取上限");
            output.write(bytes, 0, count);
        }
    }

    @Override public void close() {
        closed = true;
        ChannelExec channel = active;
        if (channel != null) channel.disconnect();
    }
}
