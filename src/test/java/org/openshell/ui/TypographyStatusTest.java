package org.openshell.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TypographyStatusTest {
    @Test void terminalRenderingKeepsDesktopTextHintsAndOtherGraphicsSettings() {
        var image = new java.awt.image.BufferedImage(200, 60, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.scale(1.5, 1.5);
            var transform = graphics.getTransform();
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ThemeUtils.applyTextRendering(graphics);
            assertEquals(transform, graphics.getTransform());
            assertEquals(RenderingHints.VALUE_ANTIALIAS_ON, graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING));
            Object desktop = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
            Object expected = desktop instanceof Map<?, ?> hints ? hints.get(RenderingHints.KEY_TEXT_ANTIALIASING)
                    : RenderingHints.VALUE_TEXT_ANTIALIAS_ON;
            assertEquals(expected, graphics.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING));
        } finally { graphics.dispose(); }
    }

    @Test void windowsCodeFontHasClearSansGlyphsAndChineseFallback() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ThemeUtils.install(true);
            Font font = ThemeUtils.monoFont(Font.PLAIN, 13);
            if (Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()).contains("Consolas"))
                assertEquals("Consolas", font.getFamily(), "Windows code text should not fall back to thin Courier glyphs");
            assertEquals(-1, font.canDisplayUpTo("English 中文 文件 路径"));
            var image = new java.awt.image.BufferedImage(200, 60, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try {
                var metrics = graphics.getFontMetrics(font);
                assertEquals(metrics.charWidth('i'), metrics.charWidth('W'), "ASCII terminal cells stay monospaced");
            } finally { graphics.dispose(); }
        });
    }

    @Test void resourceStripShowsAllFourMetricsAndUsageBarsInCompactAndWideWidths() throws Exception {
        SwingUtilities.invokeAndWait(() -> ThemeUtils.install(true));
        MonitorPanel panel = new MonitorPanel();
        panel.onUpdate(8.5, 65, 614, 944, 18.2, 11.4, 62.7, 3, 14, "0.03 0.02 0.01");
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(3, descendants(panel, JProgressBar.class).size(), "CPU, memory and disk need compact usage indicators");
            for (int width : new int[]{360, 700, 1200}) {
                panel.setSize(com.formdev.flatlaf.util.UIScale.scale(width), panel.getPreferredSize().height);
                layout(panel);
                for (JLabel label : descendants(panel, JLabel.class)) {
                    if (!label.isVisible() || label.getText().isBlank()) continue;
                    Rectangle bounds = SwingUtilities.convertRectangle(label.getParent(), label.getBounds(), panel);
                    assertTrue(bounds.x >= 0 && bounds.x + bounds.width <= panel.getWidth(), label.getText() + " outside " + width);
                    assertTrue(label.getWidth() >= label.getPreferredSize().width, label.getText() + " clipped at " + width);
                }
            }
        });
        panel.onStatus(false, "采集失败");
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(descendants(panel, JLabel.class).stream().anyMatch(l -> l.getText().contains("不可用")));
            assertTrue(descendants(panel, JProgressBar.class).stream().allMatch(bar -> bar.getValue() == 0));
        });
    }

    private static void layout(Container root) { root.doLayout(); for (Component c : root.getComponents()) if (c instanceof Container nested) layout(nested); }
    private static <T> java.util.List<T> descendants(Container root, Class<T> type) {
        java.util.List<T> values = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) values.add(type.cast(child));
            if (child instanceof Container nested) values.addAll(descendants(nested, type));
        }
        return values;
    }
}
