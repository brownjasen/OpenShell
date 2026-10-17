package org.openshell.ui;

import com.jediterm.terminal.TerminalCopyPasteHandler;
import java.awt.datatransfer.*;
import java.util.function.*;

/** Lazy access: constructing a terminal never reads or changes the clipboard. */
final class TerminalClipboard implements TerminalCopyPasteHandler {
    private final Supplier<Clipboard> clipboard;
    private final Consumer<String> status;

    TerminalClipboard(Supplier<Clipboard> clipboard, Consumer<String> status) {
        this.clipboard = clipboard;
        this.status = status;
    }

    @Override public void setContents(String text, boolean systemSelection) {
        try {
            clipboard.get().setContents(new StringSelection(text), null);
            status.accept("已复制选中的终端文本");
        } catch (IllegalStateException | SecurityException | java.awt.HeadlessException error) {
            status.accept("剪贴板暂不可用，请重试复制");
        }
    }

    @Override public String getContents(boolean systemSelection) {
        try {
            Clipboard source = clipboard.get();
            if (!source.isDataFlavorAvailable(DataFlavor.stringFlavor)) return null;
            String text = (String) source.getData(DataFlavor.stringFlavor);
            // Normalize before JediTerm converts LF to terminal CR, including on Windows.
            return text.replace("\r\n", "\n").replace('\r', '\n');
        } catch (Exception error) {
            status.accept("剪贴板暂不可用或没有可粘贴文本");
            return null;
        }
    }
}
