package org.openshell.terminal;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;

/** Explicit draft completion on a separate, bounded SFTP channel; never sends shell commands. */
public final class RemotePathCompletion implements AutoCloseable {
    public record Candidate(String label, String draft, int caret, boolean directory) {}
    private record Token(int start, int end, String text) {}
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "OpenShell-PathDeadline"); thread.setDaemon(true); return thread;
    });
    private final Session session;
    private volatile ChannelSftp active;
    private volatile boolean closed;

    public RemotePathCompletion(Session session) { this.session = session; }

    public List<Candidate> complete(String draft, int caret, String reportedDirectory) throws Exception {
        Token token = token(draft, caret);
        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        synchronized (this) {
            if (closed) { channel.disconnect(); throw new IOException("路径补全已关闭"); }
            if (active != null) { channel.disconnect(); throw new IOException("已有路径补全正在进行"); }
            active = channel;
        }
        ScheduledFuture<?> deadline = DEADLINES.schedule(channel::disconnect, 8, TimeUnit.SECONDS);
        try {
            channel.connect(5000);
            String home = channel.getHome();
            String typed = token.text();
            String expanded = typed.equals("~") ? home + "/" : typed.startsWith("~/") ? home + typed.substring(1) : typed;
            String base = reportedDirectory != null && reportedDirectory.startsWith("/") ? reportedDirectory : home;
            int slash = expanded.lastIndexOf('/');
            String parent = slash < 0 ? "" : expanded.substring(0, slash + 1);
            String prefix = slash < 0 ? expanded : expanded.substring(slash + 1);
            String directory = parent.startsWith("/") ? parent : join(base, parent);
            List<Candidate> found = new ArrayList<>();
            for (Object object : channel.ls(literal(directory))) {
                ChannelSftp.LsEntry item = (ChannelSftp.LsEntry) object;
                String name = item.getFilename();
                if (name.equals(".") || name.equals("..") || !name.startsWith(prefix)
                        || name.codePoints().anyMatch(Character::isISOControl)) continue;
                boolean folder = item.getAttrs().isDir();
                String replacement = quote(parent + name + (folder ? "/" : ""));
                String result = draft.substring(0, token.start()) + replacement + draft.substring(token.end());
                found.add(new Candidate(name + (folder ? "/" : ""), result, token.start() + replacement.length(), folder));
            }
            found.sort(Comparator.comparing(Candidate::directory).reversed().thenComparing(Candidate::label));
            return List.copyOf(found.subList(0, Math.min(100, found.size())));
        } finally {
            deadline.cancel(false); channel.disconnect();
            synchronized (this) { if (active == channel) active = null; }
        }
    }

    private static Token token(String draft, int caret) throws IOException {
        if (caret < 0 || caret > draft.length()) throw new IOException("光标位置无效");
        int start = 0; char quote = 0; boolean escaped = false;
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < caret; i++) {
            char c = draft.charAt(i);
            if (escaped) { token.append(c); escaped = false; continue; }
            if (c == '\\' && quote != '\'') { escaped = true; continue; }
            if ((c == '\'' || c == '"') && (quote == 0 || quote == c)) { quote = quote == 0 ? c : 0; continue; }
            if (quote == 0 && (Character.isWhitespace(c) || ";|&()<>".indexOf(c) >= 0)) {
                start = i + 1; token.setLength(0); continue;
            }
            token.append(c);
        }
        if (escaped || token.indexOf("$") >= 0 || token.indexOf("`") >= 0)
            throw new IOException("请使用普通路径进行补全；不展开变量或命令替换");
        int end = caret;
        // Completion inside a token could erase unreviewed arguments; require the token end.
        if (caret < draft.length() && (quote != 0 || !Character.isWhitespace(draft.charAt(caret))))
            throw new IOException("请把光标移到路径末尾后再补全");
        return new Token(start, end, token.toString());
    }

    private static String join(String base, String child) { return base + (base.endsWith("/") ? "" : "/") + child; }
    private static String literal(String path) { return path.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?"); }
    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    @Override public synchronized void close() { closed = true; if (active != null) active.disconnect(); }
}
