package org.openshell.ssh;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MonitorSamplesTest {
    static List<String> frame(long ethRx, long ethTx, long vpnRx, long vpnTx) {
        return List.of("cpu 100 0 100 700 0 0 0 100 20 0", "---", "MemTotal: 1024000 kB", "MemAvailable: 0 kB",
                "MemFree: 100 kB", "Buffers: 100 kB", "Cached: 100 kB", "---",
                "lo: 50000 1 0 0 0 0 0 0 50000 1 0 0 0 0 0 0",
                "eth0:" + ethRx + " 1 0 0 0 0 0 0 " + ethTx + " 1 0 0 0 0 0 0",
                "vpn0: " + vpnRx + " 1 0 0 0 0 0 0 " + vpnTx + " 1 0 0 0 0 0 0", "---",
                "/dev/root 10485760 5242880 5242880 50% /", "---", "0.10 0.20 0.30 1/20 123");
    }
    @Test void selectsExactInterfaceParsesNoSpaceCountersAndIncludesStealWithoutDoubleCountingGuests() {
        var selected = MonitorSamples.parse(frame(1024, 2048, 5000, 6000), new ServerMonitor.Selection("eth0", "/"));
        assertEquals(1024, selected.netRx()); assertEquals(2048, selected.netTx());
        assertEquals(1000, selected.cpuTotal()); assertEquals(700, selected.cpuIdle());
        assertEquals(0, selected.memAvailable(), "MemAvailable=0 is meaningful and must not use MemFree fallback");
        var aggregate = MonitorSamples.parse(frame(1024, 2048, 5000, 6000), new ServerMonitor.Selection("", "/"));
        assertEquals(6024, aggregate.netRx()); assertEquals(8048, aggregate.netTx());
        assertThrows(IllegalArgumentException.class, () -> MonitorSamples.parse(frame(1, 1, 1, 1), new ServerMonitor.Selection("missing", "/")));
    }
    @Test void ratesNeedBaselineAndResetCountersNeverAppearAsTraffic() {
        var calculator = new MonitorSamples.Accumulator();
        var first = calculator.sample(MonitorSamples.parse(frame(1000, 2000, 0, 0), new ServerMonitor.Selection("eth0", "/")), 1_000_000_000L);
        assertTrue(Double.isNaN(first.rx())); assertTrue(Double.isNaN(first.cpu()));
        var next = calculator.sample(MonitorSamples.parse(frame(3048, 6096, 0, 0), new ServerMonitor.Selection("eth0", "/")), 3_000_000_000L);
        assertEquals(1, next.rx(), .0001); assertEquals(2, next.tx(), .0001); assertEquals(100, next.memPercent());
        var reset = calculator.sample(MonitorSamples.parse(frame(1, 1, 0, 0), new ServerMonitor.Selection("eth0", "/")), 4_000_000_000L);
        assertTrue(Double.isNaN(reset.rx()));
    }
    @Test void selectedMountIsShellQuotedAndRejectsMultilinePaths() {
        String command = ServerMonitor.command(new ServerMonitor.Selection("eth0", "/mnt/my 'disk;$(touch nope)"));
        assertTrue(command.contains("df -P -k -- '/mnt/my '\"'\"'disk;$(touch nope)'"));
        assertThrows(IllegalArgumentException.class, () -> new ServerMonitor.Selection("", "/tmp\nwhoami"));
    }
}
