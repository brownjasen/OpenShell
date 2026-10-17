package org.openshell.ui;

import javax.swing.*;
import java.awt.*;

/** Small scalable vector icons, independent of emoji fonts and platform glyphs. */
public final class UiIcons implements Icon {
    public enum Kind { HOST, FOLDER, FILE, PLUS, CONNECT, SEARCH, EDIT, DELETE, PLAY, STOP, REFRESH, HISTORY }
    private final Kind kind;
    private final int size;
    public UiIcons(Kind kind) { this(kind, 16); }
    public UiIcons(Kind kind, int size) { this.kind = kind; this.size = size; }
    @Override public int getIconWidth() { return com.formdev.flatlaf.util.UIScale.scale(size); }
    @Override public int getIconHeight() { return com.formdev.flatlaf.util.UIScale.scale(size); }
    @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.translate(x, y);
        float scale = com.formdev.flatlaf.util.UIScale.getUserScaleFactor() * size / 16f; g.scale(scale, scale);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(component != null && !component.isEnabled() ? ThemeUtils.getTextMuted() : ThemeUtils.getTextSecondary());
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (kind) {
            case HOST -> { g.drawRoundRect(2, 2, 12, 9, 2, 2); g.drawLine(8, 11, 8, 14); g.drawLine(4, 14, 12, 14); }
            case FOLDER -> g.drawPolygon(new int[]{1, 1, 6, 8, 14, 14, 1}, new int[]{4, 2, 2, 4, 4, 13, 13}, 7);
            case FILE -> { g.drawRect(3, 1, 10, 14); g.drawLine(6, 6, 10, 6); g.drawLine(6, 9, 10, 9); }
            case PLUS -> { g.drawLine(8, 3, 8, 13); g.drawLine(3, 8, 13, 8); }
            case CONNECT -> { g.drawLine(2, 8, 13, 8); g.drawLine(9, 4, 13, 8); g.drawLine(9, 12, 13, 8); }
            case SEARCH -> { g.drawOval(2, 2, 8, 8); g.drawLine(10, 10, 14, 14); }
            case EDIT -> { g.drawLine(3, 13, 12, 3); g.drawLine(12, 3, 14, 5); g.drawLine(3, 13, 7, 12); }
            case DELETE -> { g.drawRect(4, 5, 8, 9); g.drawLine(2, 4, 14, 4); g.drawLine(6, 2, 10, 2); }
            case PLAY -> g.fillPolygon(new int[]{5, 5, 13}, new int[]{2, 14, 8}, 3);
            case STOP -> g.fillRect(4, 4, 8, 8);
            case REFRESH -> { g.drawArc(2, 2, 12, 12, 35, 285); g.drawLine(14, 2, 14, 7); g.drawLine(10, 7, 14, 7); }
            case HISTORY -> { g.drawOval(2, 2, 12, 12); g.drawLine(8, 4, 8, 8); g.drawLine(8, 8, 11, 9); }
        }
        g.dispose();
    }
}
