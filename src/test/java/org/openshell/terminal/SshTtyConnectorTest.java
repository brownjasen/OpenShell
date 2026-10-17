package org.openshell.terminal;

import org.junit.jupiter.api.Test;
import org.openshell.model.HostConfig;
import org.openshell.ssh.SshSession;
import com.jediterm.core.util.TermSize;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class SshTtyConnectorTest {
    @Test void streamedDirectoryEventsDoNotAlterTheTerminalInput() throws Exception {
        String input = "前\u001b]7;file:///home/%E4%B8%AD%E6%96%87\u001b\\后";
        var directories = new java.util.ArrayList<String>();
        var connector = new SshTtyConnector(new StubSession(input), () -> {}, directories::add);
        StringBuilder actual = new StringBuilder(); char[] buffer = new char[1]; int count;
        while ((count = connector.read(buffer, 0, buffer.length)) != -1) actual.append(buffer, 0, count);
        assertEquals(input, actual.toString());
        assertEquals(java.util.List.of("/home/中文"), directories);
    }
    @Test void decodesSplitUtf8AndPreservesControlSequences() throws Exception {
        String input = "中文\u001b[31mRED\u001b[0m";
        StubSession session = new StubSession(input);
        SshTtyConnector connector = new SshTtyConnector(session, () -> {});
        StringBuilder actual = new StringBuilder();
        char[] buffer = new char[3];
        int count;
        while ((count = connector.read(buffer, 0, buffer.length)) != -1) actual.append(buffer, 0, count);
        assertEquals(input, actual.toString());
        connector.write("中文\r");
        assertEquals("中文\r", session.sent.toString(StandardCharsets.UTF_8));
        connector.resize(new TermSize(90, 28));
        assertEquals(90, session.columns);
        assertEquals(28, session.rows);
    }

    static class StubSession extends SshSession {
        final InputStream input;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        int columns, rows;
        StubSession(String text) {
            super(new HostConfig());
            input = new FilterInputStream(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))) {
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    return super.read(bytes, offset, Math.min(length, 1));
                }
            };
        }
        @Override public InputStream getInputStream() { return input; }
        @Override public void sendBytes(byte[] bytes, int offset, int length) { sent.write(bytes, offset, length); }
        @Override public void resizePty(int cols, int rs, int wp, int hp) { columns = cols; rows = rs; }
        @Override public boolean isConnected() { return true; }
    }
}
