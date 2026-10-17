package org.openshell.ui;

import org.junit.jupiter.api.Test;
import java.awt.datatransfer.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class TerminalClipboardTest {
    @Test void busyClipboardReportsFailureAndCanBeRetried() {
        AtomicReference<String> status = new AtomicReference<>();
        Clipboard system = new Clipboard("isolated") {
            boolean busy = true;
            @Override public synchronized void setContents(Transferable text, ClipboardOwner owner) {
                if (busy) { busy = false; throw new IllegalStateException("busy"); }
                super.setContents(text, owner);
            }
        };
        TerminalClipboard clipboard = new TerminalClipboard(() -> system, status::set);
        clipboard.setContents("中文", false);
        assertTrue(status.get().contains("重试"));
        assertNull(clipboard.getContents(false));
        clipboard.setContents("中文", false);
        assertEquals("中文", clipboard.getContents(false));
        assertTrue(status.get().contains("已复制"));
    }

    @Test void failedClipboardReadDoesNotThrowOrSupplyTextToTerminal() {
        AtomicReference<String> status = new AtomicReference<>();
        TerminalClipboard clipboard = new TerminalClipboard(() -> { throw new IllegalStateException("busy"); }, status::set);
        assertNull(clipboard.getContents(false));
        assertTrue(status.get().contains("暂不可用"));
    }
}
