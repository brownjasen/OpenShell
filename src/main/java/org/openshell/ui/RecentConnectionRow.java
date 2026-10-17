package org.openshell.ui;

import org.openshell.model.HostConfig;
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import javax.swing.*;
import java.awt.*;
import java.util.Map;
import java.util.function.Consumer;

/** A recent host entry on the welcome page. */
final class RecentConnectionRow extends JButton {
    RecentConnectionRow(HostConfig host, Consumer<HostConfig> connect) {
        super(host.getName() + "  ·  " + host.getUsername() + "@" + host.getHost(), new UiIcons(UiIcons.Kind.HOST));
        putClientProperty("html.disable", true);
        putClientProperty(FlatClientProperties.STYLE, Map.of(
                "arc", 4, "borderWidth", 0, "focusWidth", 1,
                "margin", new Insets(8, 10, 8, 68),
                "background", ThemeUtils.getCardBg(),
                "hoverBackground", ThemeUtils.getElevatedBg(),
                "pressedBackground", ThemeUtils.getSelectionBg()));
        setFont(ThemeUtils.uiFont(Font.PLAIN, 12));
        setForeground(ThemeUtils.getTextPrimary());
        setHorizontalAlignment(SwingConstants.LEFT);
        setIconTextGap(UIScale.scale(6));
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText("连接：" + host.getUsername() + "@" + host.getHost() + ":" + host.getPort() + " · " + host.getGroup());
        getAccessibleContext().setAccessibleName("连接 " + host.getName() + "，" + host.getUsername() + "@" + host.getHost());
        setMaximumSize(new Dimension(Integer.MAX_VALUE, UIScale.scale(44)));
        setAlignmentX(Component.LEFT_ALIGNMENT);
        addActionListener(event -> connect.accept(host));
        registerKeyboardAction(event -> doClick(), KeyStroke.getKeyStroke("ENTER"), WHEN_FOCUSED);
    }

    @Override public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize(); size.height = UIScale.scale(44); return size;
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        // Paint a cue inside the same button. There is no nested control to
        // intercept clicks in the icon, text, blank body or trailing action.
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            ThemeUtils.applyTextRendering(g);
            g.setFont(ThemeUtils.uiFont(Font.PLAIN, 11));
            g.setColor(getModel().isRollover() ? ThemeUtils.getAccent() : ThemeUtils.getTextSecondary());
            var metrics = g.getFontMetrics();
            g.drawString("连接", getWidth() - UIScale.scale(14) - metrics.stringWidth("连接"),
                    (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
        } finally { g.dispose(); }
    }
}
