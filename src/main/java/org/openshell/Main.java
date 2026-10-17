package org.openshell;

import com.formdev.flatlaf.FlatDarkLaf;
import org.openshell.ui.MainWindow;
import org.openshell.ui.ThemeUtils;

import javax.swing.*;

public class Main {
    public static void main(String[] args) {
        // Let Swing and FlatLaf use the desktop's text rendering and DPI settings.
        System.setProperty("flatlaf.useWindowDecorations", "true");

        SwingUtilities.invokeLater(() -> {
            try {
                // 初始化现代扁平深色主题 (FlatLaf)
                ThemeUtils.install(true);
            } catch (Exception e) {
                System.err.println("Failed to initialize FlatDarkLaf: " + e.getMessage());
            }

            MainWindow window = new MainWindow();
            window.setVisible(true);
        });
    }
}
