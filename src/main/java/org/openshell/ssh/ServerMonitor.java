package org.openshell.ssh;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.Session;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Each restart owns its channel and baselines; obsolete workers cannot publish into a newer run. */
public class ServerMonitor {
    private final Session session;
    private Run active;
    private long generation;
    private Selection selection = new Selection("", "/");
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "OpenShell-MonitorTimeout"); thread.setDaemon(true); return thread;
    });
    public interface MonitorListener {
        default void onStatus(boolean healthy, String message) {}
        void onUpdate(double cpuPercent, double memPercent, long memUsedMB, long memTotalMB,
                      double diskPercent, double diskUsedGB, double diskTotalGB,
                      double rxSpeedKB, double txSpeedKB, String loadAvg);
    }
    public record Selection(String networkInterface, String mountPoint) {
        public Selection {
            networkInterface = networkInterface == null ? "" : networkInterface.trim();
            mountPoint = mountPoint == null || mountPoint.isBlank() ? "/" : mountPoint.trim();
            if (!mountPoint.startsWith("/") || mountPoint.indexOf('\n') >= 0 || mountPoint.indexOf('\r') >= 0
                    || mountPoint.indexOf('\0') >= 0) throw new IllegalArgumentException("挂载点须为远端绝对路径");
            if (networkInterface.length() > 64 || networkInterface.indexOf(':') >= 0
                    || networkInterface.chars().anyMatch(Character::isWhitespace) || networkInterface.indexOf('\0') >= 0)
                throw new IllegalArgumentException("网卡名称不合法");
        }
    }
    public record Resources(List<String> networkInterfaces, List<String> mountPoints) {
        public Resources { networkInterfaces = List.copyOf(networkInterfaces); mountPoints = List.copyOf(mountPoints); }
    }
    private static final class Run {
        final long generation; final MonitorListener listener; final Selection selection;
        volatile ChannelExec channel; Thread thread; volatile long lastRead = System.nanoTime();
        Run(long generation, MonitorListener listener, Selection selection) { this.generation = generation; this.listener = listener; this.selection = selection; }
    }
    public ServerMonitor(Session session) { this.session = session; }
    public synchronized boolean isRunning() { return active != null; }
    public synchronized Selection getSelection() { return selection; }
    public synchronized void setSelection(String networkInterface, String mountPoint) {
        Selection changed = new Selection(networkInterface, mountPoint);
        if (selection.equals(changed)) return;
        selection = changed;
        if (active != null) { MonitorListener listener = active.listener; stop(); start(listener); }
    }
    public synchronized void start(MonitorListener listener) {
        if (active != null) return;
        Objects.requireNonNull(listener);
        Run run = new Run(++generation, listener, selection); active = run;
        listener.onStatus(false, "等待监控数据");
        run.thread = new Thread(() -> monitor(run), "OpenShell-Monitor-" + run.generation);
        run.thread.setDaemon(true); run.thread.start();
    }
    public synchronized void stop() {
        Run old = active; active = null; generation++;
        if (old != null) { if (old.channel != null) old.channel.disconnect(); if (old.thread != null) old.thread.interrupt(); }
    }
    private synchronized boolean current(Run run) { return active == run && generation == run.generation; }
    private synchronized void status(Run run, String message) { if (current(run)) run.listener.onStatus(false, message); }
    private synchronized void publish(Run run, MonitorSamples.Metrics sample) {
        if (current(run)) run.listener.onUpdate(sample.cpu(), sample.memPercent(), sample.memUsedMB(), sample.memTotalMB(),
                sample.diskPercent(), sample.diskUsedGB(), sample.diskTotalGB(), sample.rx(), sample.tx(), sample.load());
    }
    static String command(Selection selection) {
        return "LC_ALL=C; export LC_ALL; while true; do head -n 1 /proc/stat; printf '\\n---\\n'; "
                + "grep -E '^(MemTotal|MemAvailable|MemFree|Buffers|Cached):' /proc/meminfo; printf '\\n---\\n'; "
                + "cat /proc/net/dev; printf '\\n---\\n'; df -P -k -- " + quote(selection.mountPoint())
                + " 2>/dev/null | tail -n 1; printf '\\n---\\n'; cat /proc/loadavg; printf '\\n===\\n'; sleep 1.5; done";
    }
    private static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
    private void monitor(Run run) {
        while (current(run) && session != null && session.isConnected()) {
            ChannelExec channel = null; ScheduledFuture<?> guard = null;
            try {
                channel = (ChannelExec) session.openChannel("exec");
                synchronized (this) { if (!current(run)) { channel.disconnect(); return; } run.channel = channel; }
                channel.setCommand(command(run.selection)); channel.setInputStream(null); channel.setErrStream(OutputStream.nullOutputStream());
                BufferedReader reader = new BufferedReader(new InputStreamReader(channel.getInputStream(), StandardCharsets.UTF_8));
                channel.connect(5000); run.lastRead = System.nanoTime(); ChannelExec owned = channel;
                guard = WATCHDOG.scheduleAtFixedRate(() -> {
                    if (System.nanoTime() - run.lastRead > TimeUnit.SECONDS.toNanos(12)) owned.disconnect();
                }, 3, 3, TimeUnit.SECONDS);
                MonitorSamples.Accumulator accumulator = new MonitorSamples.Accumulator(); List<String> lines = new ArrayList<>();
                String line; int bytes = 0;
                while (current(run) && (line = reader.readLine()) != null) {
                    run.lastRead = System.nanoTime();
                    if (line.trim().equals("===")) {
                        try { publish(run, accumulator.sample(MonitorSamples.parse(lines, run.selection), System.nanoTime())); }
                        catch (IllegalArgumentException error) { status(run, "监控输出不完整：" + error.getMessage()); accumulator = new MonitorSamples.Accumulator(); }
                        lines.clear(); bytes = 0;
                    } else {
                        bytes += line.length(); if (bytes > 256 * 1024 || lines.size() >= 4096) throw new IOException("监控输出超过限制"); lines.add(line);
                    }
                }
                status(run, "监控中断，等待重试");
            } catch (Exception error) { status(run, "监控不可用，等待重试"); }
            finally {
                if (guard != null) guard.cancel(false); if (channel != null) channel.disconnect();
                synchronized (this) { if (run.channel == channel) run.channel = null; }
            }
            if (current(run)) {
                try { Thread.sleep(2000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        }
        synchronized (this) { if (current(run)) active = null; }
    }
    /** Bounded discovery used by the settings dialog, never by the metric hot path. */
    public Resources discoverResources() throws Exception {
        if (session == null || !session.isConnected()) throw new IOException("SSH 连接已断开");
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        ScheduledFuture<?> guard = WATCHDOG.schedule(channel::disconnect, 8, TimeUnit.SECONDS);
        try {
            channel.setCommand("LC_ALL=C; export LC_ALL; cat /proc/net/dev; printf '\\n---\\n'; df -P -k");
            channel.setInputStream(null); channel.setErrStream(OutputStream.nullOutputStream());
            InputStream input = channel.getInputStream(); channel.connect(4000);
            byte[] bytes = input.readNBytes(256 * 1024 + 1);
            if (bytes.length > 256 * 1024) throw new IOException("监控资源列表过大");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!channel.isClosed() && System.nanoTime() < deadline) Thread.sleep(10);
            if (channel.getExitStatus() != 0) throw new IOException("无法读取网卡和挂载点列表");
            Set<String> interfaces = new LinkedHashSet<>(), mounts = new LinkedHashSet<>(); mounts.add("/"); boolean disk = false;
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
                if (line.trim().equals("---")) { disk = true; continue; }
                if (!disk && line.contains(":")) interfaces.add(line.substring(0, line.indexOf(':')).trim());
                if (disk) { String[] fields = line.trim().split("\\s+", 6); if (fields.length == 6 && fields[5].startsWith("/")) mounts.add(fields[5]); }
            }
            return new Resources(new ArrayList<>(interfaces), new ArrayList<>(mounts));
        } finally { guard.cancel(false); channel.disconnect(); }
    }
}
