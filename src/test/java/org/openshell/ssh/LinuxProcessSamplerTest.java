package org.openshell.ssh;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class LinuxProcessSamplerTest {
    static String snapshot(double uptime, long ticks, long start) {
        return "OSPROC1 900 100 " + uptime + "\n"
                + "42 1 azureuser 20480 90000 2.5 /opt/中文 app --test\n"
                + "900 1 azureuser 1000 0 0.1 sh -c collector\n"
                + "901 900 azureuser 1000 0 0.1 ps -e\n"
                + "902 901 azureuser 1000 0 0.1 collector-child\n"
                + "77 1 root 1024 50 0.1 /opt/ps-agent\n"
                + "OSSTAT\n" + stat(42, ticks, start) + stat(900, 99, 500)
                + stat(901, 99, 500) + stat(902, 99, 500) + stat(77, 0, 500) + "OSEND\n";
    }

    private static String stat(int pid, long ticks, long start) {
        // comm may contain spaces and closing parentheses; utime=14, stime=15, starttime=22.
        return pid + " (worker ) name) S 1 1 1 0 0 0 0 0 0 0 " + ticks
                + " 0 0 0 20 0 1 0 " + start + " 0 0\n";
    }

    @Test void measuresRecentCpuAndExcludesOnlyCollectorAndItsDescendants() throws Exception {
        var sampler = new LinuxProcessSampler();
        var first = sampler.accept(snapshot(1000, 1000, 100));
        assertEquals(2, first.size());
        assertNull(first.get(0).cpuPercent());
        var next = sampler.accept(snapshot(1002, 1050, 100));
        assertEquals(25.0, next.get(0).cpuPercent(), 0.001);
        assertEquals("azureuser", next.get(0).user());
        assertEquals("/opt/中文 app --test", next.get(0).command());
        assertEquals(20480, next.get(0).rssKiB());
        assertEquals(90000, next.get(0).elapsedSeconds());
        assertEquals(77, next.get(1).pid(), "Do not hide applications just because their names contain ps");
    }

    @Test void pidReuseAndCounterResetRequireAnotherSampleAndMulticoreCanExceed100() throws Exception {
        var sampler = new LinuxProcessSampler();
        sampler.accept(snapshot(1000, 1000, 100));
        assertNull(sampler.accept(snapshot(1002, 1, 200)).get(0).cpuPercent());
        assertEquals(200.0, sampler.accept(snapshot(1004, 401, 200)).get(0).cpuPercent());
        assertNull(sampler.accept(snapshot(1006, 10, 200)).get(0).cpuPercent());
    }

    @Test void rejectsIncompleteOrUnsupportedOutputInsteadOfShowingAnEmptySuccess() throws Exception {
        var sampler = new LinuxProcessSampler();
        assertThrows(IOException.class, () -> sampler.accept("ps: bad option\n"));
        assertThrows(IOException.class, () -> sampler.accept(snapshot(1000, 0, 100).replace("OSEND\n", "")));
        assertThrows(IOException.class, () -> sampler.accept(snapshot(1000, 0, 100).replace("900 100 1000", "900 0 1000")));
        assertNull(sampler.accept(snapshot(1000, 100, 100)).get(0).cpuPercent());
    }

    @Test void includesProcessesBeyondTheOldCpuTop150Cutoff() throws Exception {
        StringBuilder entries = new StringBuilder("OSPROC1 900 100 1000\n"), stats = new StringBuilder("OSSTAT\n");
        for (int pid = 1; pid <= 220; pid++) {
            entries.append(pid).append(" 0 root 1024 100 0.1 memory-worker\n");
            stats.append(stat(pid, 0, 10));
        }
        assertEquals(220, new LinuxProcessSampler().accept(entries.append(stats).append("OSEND\n").toString()).size());
    }
}
