package org.openshell.ui;

import org.junit.jupiter.api.Test;
import org.openshell.model.HostConfig;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RecentConnectionRowTest {
    @Test void bothThemesShowHoverFeedbackAcrossTheBlankCenterOfTheRow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (boolean dark : new boolean[]{true, false}) {
                    ThemeUtils.install(dark);
                    RecentConnectionRow row = new RecentConnectionRow(new HostConfig("测试服务器", "example.invalid", 22, "user"), host -> {});
                    row.setSize(900, row.getPreferredSize().height);
                    for (boolean hover : new boolean[]{false, true}) {
                        row.getModel().setRollover(hover);
                        var picture = new java.awt.image.BufferedImage(row.getWidth(), row.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                        var graphics = picture.createGraphics(); row.printAll(graphics); graphics.dispose();
                        assertEquals((hover ? ThemeUtils.getElevatedBg() : ThemeUtils.getCardBg()).getRGB(),
                                picture.getRGB(600, row.getHeight() / 2));
                        try {
                            var path = java.nio.file.Path.of("target/verification/welcome-row-" + (dark ? "dark" : "light")
                                    + (hover ? "-hover" : "") + ".png");
                            java.nio.file.Files.createDirectories(path.getParent());
                            javax.imageio.ImageIO.write(picture, "png", path.toFile());
                        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                    }
                }
            } finally { ThemeUtils.install(true); }
        });
    }

    @Test void everyPartOfTheRowConnectsExactlyOnceForTheSameHost() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ThemeUtils.install(true);
            HostConfig host = new HostConfig("测试服务器", "example.invalid", 22, "user");
            AtomicInteger count = new AtomicInteger();
            RecentConnectionRow row = new RecentConnectionRow(host, value -> { assertSame(host, value); count.incrementAndGet(); });
            row.setSize(900, 48); row.doLayout();
            for (int x : new int[]{18, 160, 450, 710, 870}) {
                click(row, x, 24);
                assertEquals(1, count.getAndSet(0), "Each click, including the blank center, opens exactly one connection at " + x);
            }
            click(row, 450, 24, MouseEvent.BUTTON3);
            assertEquals(0, count.get(), "Right click must not start SSH");
            assertEquals(Cursor.HAND_CURSOR, row.getCursor().getType());
        });
    }

    @Test void rowSupportsEnterAndSpaceWithoutASecondFocusableChildButton() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ThemeUtils.install(true);
            AtomicInteger count = new AtomicInteger();
            RecentConnectionRow row = new RecentConnectionRow(new HostConfig(), host -> count.incrementAndGet());
            assertTrue(row.isFocusable());
            for (String stroke : new String[]{"ENTER", "pressed SPACE", "released SPACE"}) {
                Object key = row.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(stroke));
                assertNotNull(key, "Missing keyboard action for " + stroke);
                row.getActionMap().get(key).actionPerformed(new ActionEvent(row, ActionEvent.ACTION_PERFORMED, ""));
            }
            assertEquals(2, count.get());
        });
    }

    private static void click(JComponent target, int x, int y) { click(target, x, y, MouseEvent.BUTTON1); }
    private static void click(JComponent target, int x, int y, int button) {
        int modifiers = button == MouseEvent.BUTTON1 ? InputEvent.BUTTON1_DOWN_MASK : InputEvent.BUTTON3_DOWN_MASK;
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_PRESSED, 1, modifiers, x, y, 1, false, button));
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_RELEASED, 2, 0, x, y, 1, false, button));
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_CLICKED, 3, 0, x, y, 1, false, button));
    }
}
