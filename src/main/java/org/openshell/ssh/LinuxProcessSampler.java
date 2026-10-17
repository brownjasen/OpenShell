package org.openshell.ssh;

import java.io.IOException;
import java.util.*;

/** Linux /proc tick deltas. A fully occupied CPU core is 100%, as in top's default mode. */
public final class LinuxProcessSampler {
    public record ProcessInfo(int pid, String user, Double cpuPercent, double memoryPercent,
                              long rssKiB, long elapsedSeconds, String command, long startTicks) {}
    private record Counter(long ticks, long start) {}
    private record Entry(int pid, int parent, String user, long rss, long elapsed, double memory, String command) {}
    private Map<Integer, Counter> previous = Map.of();
    private double previousUptime;

    public List<ProcessInfo> accept(String output) throws IOException {
        try {
            String[] lines = output.strip().split("\\R");
            String[] header = lines[0].split("\\s+");
            if (header.length != 4 || !header[0].equals("OSPROC1") || !lines[lines.length - 1].equals("OSEND"))
                throw new IllegalArgumentException("不完整的进程采样");
            int collector = Integer.parseInt(header[1]);
            long hz = Long.parseLong(header[2]);
            double uptime = Double.parseDouble(header[3]);
            if (collector <= 0 || hz <= 0 || !Double.isFinite(uptime) || uptime < 0)
                throw new IllegalArgumentException("无效的采样时钟");
            List<Entry> entries = new ArrayList<>();
            Map<Integer, Counter> counters = new HashMap<>();
            boolean stats = false;
            for (int i = 1; i < lines.length - 1; i++) {
                String line = lines[i].trim();
                if (line.equals("OSSTAT")) { stats = true; continue; }
                if (line.isEmpty()) continue;
                if (!stats) {
                    String[] p = line.split("\\s+", 7);
                    if (p.length != 7) throw new IllegalArgumentException("无效的 ps 输出");
                    entries.add(new Entry(Integer.parseInt(p[0]), Integer.parseInt(p[1]), p[2],
                            Long.parseLong(p[3]), Long.parseLong(p[4]), Double.parseDouble(p[5]), p[6]));
                } else {
                    // comm is parenthesized but may itself contain spaces or ')'.
                    int end = line.lastIndexOf(") ");
                    if (end < 0) continue; // A process can exit while it is being read.
                    String[] p = line.substring(end + 2).split("\\s+");
                    if (p.length < 20) continue;
                    try {
                        int pid = Integer.parseInt(line.substring(0, line.indexOf(' ')));
                        counters.put(pid, new Counter(Long.parseLong(p[11]) + Long.parseLong(p[12]), Long.parseLong(p[19])));
                    } catch (NumberFormatException ignored) { /* disappearing/unreadable process */ }
                }
            }
            if (!stats) throw new IllegalArgumentException("缺少 /proc 采样");
            Set<Integer> excluded = new HashSet<>(Set.of(collector));
            boolean changed;
            do {
                changed = false;
                for (Entry entry : entries)
                    if (excluded.contains(entry.parent)) changed |= excluded.add(entry.pid);
            } while (changed);
            double seconds = uptime - previousUptime;
            List<ProcessInfo> result = new ArrayList<>();
            for (Entry entry : entries) {
                Counter now = counters.get(entry.pid), before = previous.get(entry.pid);
                if (excluded.contains(entry.pid) || now == null) continue;
                Double cpu = null;
                if (before != null && before.start == now.start && now.ticks >= before.ticks && seconds > 0)
                    cpu = (now.ticks - before.ticks) * 100.0 / hz / seconds;
                result.add(new ProcessInfo(entry.pid, entry.user, cpu, entry.memory, entry.rss,
                        entry.elapsed, entry.command, now.start));
            }
            previous = counters;
            previousUptime = uptime;
            return List.copyOf(result);
        } catch (RuntimeException error) {
            throw new IOException("无法解析 Linux 进程数据: " + error.getMessage(), error);
        }
    }
}
