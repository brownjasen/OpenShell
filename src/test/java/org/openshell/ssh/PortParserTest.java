package org.openshell.ssh;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PortParserTest {
    @Test void parsesSsAddressesStatesAndProcessNamesWithoutLosingIpv6() {
        var rows = PortParser.parseSs("""
                Netid State Recv-Q Send-Q Local Address:Port Peer Address:Port Process
                udp UNCONN 0 0 127.0.0.53%lo:53 0.0.0.0:* users:(("systemd-resolve",pid=450,fd=13))
                tcp LISTEN 0 128 [::]:443 [::]:* users:(("web worker",pid=92,fd=5),("nginx",pid=91,fd=5))
                tcp LISTEN 0 128 *:22 *:*
                tcp LISTEN 0 128 [fe80::1%eth0]:8080 [::]:*
                """);
        assertEquals(4, rows.size());
        assertEquals("127.0.0.53%lo", rows.get(0).address());
        assertEquals(53, rows.get(0).port());
        assertEquals("::", rows.get(1).address());
        assertEquals(92, rows.get(1).pid());
        assertTrue(rows.get(1).process().contains("web worker"));
        assertNull(rows.get(2).pid());
        assertEquals("fe80::1%eth0", rows.get(3).address());
    }

    @Test void netstatHasItsOwnColumnsAndUdpDoesNotHaveAStateColumn() {
        var rows = PortParser.parseNetstat("""
                Active Internet connections (only servers)
                Proto Recv-Q Send-Q Local Address Foreign Address State PID/Program name
                tcp 0 0 0.0.0.0:100 0.0.0.0:* LISTEN 921/sshd
                tcp6 0 0 :::9 :::* LISTEN -
                udp 0 0 127.0.0.1:80 0.0.0.0:* 76/dns worker
                udp6 0 0 :::53 :::* -
                """);
        assertEquals(4, rows.size());
        assertEquals(100, rows.get(0).port());
        assertEquals(921, rows.get(0).pid());
        assertEquals("::", rows.get(1).address());
        assertEquals("UNCONN", rows.get(2).state());
        assertEquals("dns worker", rows.get(2).process());
        assertNull(rows.get(3).pid());
    }

    @Test void ignoresMalformedRowsAndNeverTreatsAHeaderAsAnEndpoint() {
        assertTrue(PortParser.parseSs("tcp LISTEN 0 1 bad-address x\ntcp LISTEN 0 1 *:99999 x").isEmpty());
        assertTrue(PortParser.parseNetstat("Proto Recv-Q Send-Q Local Address Foreign Address State PID/Program name").isEmpty());
    }
}
