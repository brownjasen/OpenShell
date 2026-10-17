package org.openshell.ssh;

import java.util.List;

/** Linux sampling parser separated from SSH transport so calculations can be checked deterministically. */
final class MonitorSamples {
    record Frame(long cpuTotal, long cpuIdle, long memTotal, long memAvailable, long netRx, long netTx,
                 double diskPercent, double diskUsedGB, double diskTotalGB, String load) {}
    record Metrics(double cpu, double memPercent, long memUsedMB, long memTotalMB,
                   double diskPercent, double diskUsedGB, double diskTotalGB, double rx, double tx, String load) {}
    static Frame parse(List<String> lines, ServerMonitor.Selection selection) {
        long total = 0, idle = 0, mem = 0, available = -1, free = 0, buffers = 0, cached = 0, rx = 0, tx = 0;
        double diskPercent = Double.NaN, diskUsed = 0, diskTotal = 0; String load = ""; boolean nicFound = false; int section = 0;
        try {
            for (String raw : lines) {
                String line = raw.trim(); if (line.isEmpty()) continue;
                if (line.equals("---")) { section++; continue; }
                String[] parts = line.split("\\s+");
                if (section == 0 && line.startsWith("cpu ")) {
                    // guest/guest_nice are already included in user/nice; include steal once.
                    for (int i = 1; i <= 8 && i < parts.length; i++) total += Long.parseLong(parts[i]);
                    idle = Long.parseLong(parts[4]) + (parts.length > 5 ? Long.parseLong(parts[5]) : 0);
                } else if (section == 1 && parts.length >= 2) {
                    long kb = Long.parseLong(parts[1]);
                    switch (parts[0]) {
                        case "MemTotal:" -> mem = kb;
                        case "MemAvailable:" -> available = kb;
                        case "MemFree:" -> free = kb;
                        case "Buffers:" -> buffers = kb;
                        case "Cached:" -> cached = kb;
                    }
                } else if (section == 2 && line.contains(":")) {
                    int colon = line.indexOf(':'); String nic = line.substring(0, colon).trim();
                    if (selection.networkInterface().isEmpty() ? !nic.equals("lo") : nic.equals(selection.networkInterface())) {
                        String[] counters = line.substring(colon + 1).trim().split("\\s+");
                        if (counters.length < 16) throw new IllegalArgumentException("网卡计数不完整");
                        rx += Long.parseLong(counters[0]); tx += Long.parseLong(counters[8]); nicFound = true;
                    }
                } else if (section == 3 && parts.length >= 6) {
                    diskTotal = Long.parseLong(parts[1]) / 1048576.0; diskUsed = Long.parseLong(parts[2]) / 1048576.0;
                    diskPercent = Double.parseDouble(parts[4].replace("%", ""));
                } else if (section == 4 && parts.length >= 3) load = parts[0] + " " + parts[1] + " " + parts[2];
            }
        } catch (IndexOutOfBoundsException | NumberFormatException error) { throw new IllegalArgumentException("格式不正确", error); }
        if (total <= 0 || mem <= 0 || section != 4 || !Double.isFinite(diskPercent) || !nicFound || load.isEmpty())
            throw new IllegalArgumentException(!nicFound ? "所选网卡不存在" : "缺少 CPU、内存或磁盘数据");
        if (available < 0) available = free + buffers + cached;
        return new Frame(total, idle, mem, Math.max(0, Math.min(mem, available)), rx, tx, diskPercent, diskUsed, diskTotal, load);
    }
    static final class Accumulator {
        Frame previous; long previousTime;
        Metrics sample(Frame frame, long now) {
            double cpu = Double.NaN, rx = Double.NaN, tx = Double.NaN;
            if (previous != null && frame.cpuTotal > previous.cpuTotal && frame.cpuIdle >= previous.cpuIdle)
                cpu = Math.max(0, Math.min(100, (1.0 - (double)(frame.cpuIdle - previous.cpuIdle) / (frame.cpuTotal - previous.cpuTotal)) * 100));
            if (previous != null && now > previousTime && frame.netRx >= previous.netRx && frame.netTx >= previous.netTx) {
                double seconds = (now - previousTime) / 1e9;
                rx = (frame.netRx - previous.netRx) / 1024.0 / seconds; tx = (frame.netTx - previous.netTx) / 1024.0 / seconds;
            }
            previous = frame; previousTime = now; long used = frame.memTotal - frame.memAvailable;
            return new Metrics(cpu, used * 100.0 / frame.memTotal, used / 1024, frame.memTotal / 1024,
                    frame.diskPercent, frame.diskUsedGB, frame.diskTotalGB, rx, tx, frame.load);
        }
    }
}
