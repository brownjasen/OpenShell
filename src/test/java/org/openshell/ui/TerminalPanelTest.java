package org.openshell.ui;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.ArrayTerminalDataStream;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.emulator.JediEmulator;
import com.jediterm.terminal.ui.JediTermWidget;
import org.junit.jupiter.api.Test;
import org.openshell.model.HostConfig;
import org.openshell.ssh.SshSession;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class TerminalPanelTest {
    @Test void constructsRendersAndEmulatesCursorColorsAndAlternateScreen() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            org.openshell.ui.ThemeUtils.install(true);
            TerminalPanel panel = new TerminalPanel(new IdleSession());
            try {
                panel.setSize(1000, 440); layout(panel);
                JediTermWidget widget = findWidget(panel);
                assertNotNull(widget);
                var buffer = widget.getTerminalTextBuffer();
                String frames = "\u001b[2J\u001b[Habc\rX\u001b[2;1H\u001b[31mRED\u001b[0m";
                feed(widget, frames);
                assertEquals('X', buffer.getCharAt(0, 0));
                assertEquals('b', buffer.getCharAt(1, 0));
                assertEquals(TerminalColor.index(1), buffer.getStyleAt(0, 1).getForeground());
                feed(widget, "\u001b[?1049h\u001b[Hfull-screen");
                assertTrue(buffer.isUsingAlternateBuffer());
                feed(widget, "\u001b[?1049l");
                assertFalse(buffer.isUsingAlternateBuffer());
                assertEquals('X', buffer.getCharAt(0, 0));
                BufferedImage image = new BufferedImage(1000, 440, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics(); panel.printAll(graphics); graphics.dispose();
                assertEquals(widget.getTerminalPanel().getBackground().getRGB(), image.getRGB(500, 100),
                        "Cleared terminal cells must use the configured background");
                try {
                    Path output = Path.of("target/verification/terminal-component.png");
                    Files.createDirectories(output.getParent()); ImageIO.write(image, "png", output.toFile());
                } catch (IOException error) { throw new UncheckedIOException(error); }
            } finally { panel.close(); }
        });
    }
    private static void feed(JediTermWidget widget, String data) {
        JediEmulator emulator = new JediEmulator(new ArrayTerminalDataStream(data.toCharArray()), widget.getTerminal());
        try { while (emulator.hasNext()) emulator.next(); }
        catch (IOException error) { throw new UncheckedIOException(error); }
    }
    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }
    private static JediTermWidget findWidget(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JediTermWidget widget) return widget;
            if (child instanceof Container nested) { var result = findWidget(nested); if (result != null) return result; }
        }
        return null;
    }
    private static final class IdleSession extends SshSession {
        private final PipedInputStream input = new PipedInputStream();
        private final PipedOutputStream hold;
        IdleSession() { super(new HostConfig()); try { hold = new PipedOutputStream(input); } catch (IOException e) { throw new UncheckedIOException(e); } }
        @Override public InputStream getInputStream() { return input; }
        @Override public boolean isConnected() { return true; }
        @Override public void resizePty(int cols, int rows, int width, int height) {}
        @Override public void closeShell() { try { hold.close(); } catch (IOException ignored) {} }
    }
}
