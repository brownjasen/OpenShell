package org.openshell.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.model.HostConfig;
import org.openshell.storage.HostStorage;
import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class UiRenderingRegressionTest {
    @TempDir Path directory;

    @Test void tunnelCellRendererCanDisplayChineseRuleNamesAndDescriptions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            org.openshell.ui.ThemeUtils.install(true);
            PortForwardPanel panel = new PortForwardPanel(null);
            JTable table = find(panel, JTable.class);
            assertNotNull(table);
            for (int row = 0; row < table.getRowCount(); row++) for (int column : new int[]{1, 2, 5}) {
                JLabel cell = (JLabel) table.prepareRenderer(table.getCellRenderer(row, column), row, column);
                assertEquals(-1, cell.getFont().canDisplayUpTo(cell.getText()),
                        "Tunnel column " + column + " needs a Chinese-capable font: " + cell.getFont());
            }
        });
    }

    @Test void hostRendererUsesTheSameSelectedBackgroundAsTheTreeRow() throws Exception {
        String previous = System.getProperty("openshell.configDir");
        System.setProperty("openshell.configDir", directory.toString());
        try {
            HostStorage.saveHosts(java.util.List.of(new HostConfig("测试主机", "example.invalid", 22, "user")));
            SwingUtilities.invokeAndWait(() -> {
                org.openshell.ui.ThemeUtils.install(true);
                HostTreePanel panel = new HostTreePanel(host -> {});
                JTree tree = find(panel, JTree.class);
                DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) root.getFirstLeaf();
                Component cell = tree.getCellRenderer().getTreeCellRendererComponent(tree, node, true, false, true, 1, true);
                assertEquals(UIManager.getColor("Tree.selectionBackground"), cell.getBackground(),
                        "The selected row must not have a different block behind the host text");
            });
        } finally {
            if (previous == null) System.clearProperty("openshell.configDir"); else System.setProperty("openshell.configDir", previous);
        }
    }

    @Test void bothThemesKeepDefaultSurfacesAndChineseTypographyConsistent() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (boolean dark : new boolean[]{true, false}) {
                    ThemeUtils.install(dark);
                    assertEquals(ThemeUtils.getBg(), new JPanel().getBackground());
                    assertEquals(ThemeUtils.getHeaderBg(), new JTable().getTableHeader().getBackground());
                    assertEquals(-1, new JMenu("文件").getFont().canDisplayUpTo("文件工具视图帮助"));
                    assertEquals(ThemeUtils.getSelectionBg(), UIManager.getColor("Tree.selectionBackground"));
                }
            } finally { ThemeUtils.install(true); }
        });
    }

    @Test void compactNetworkHistoryStillHasVerticalPlotSpace() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ThemeUtils.install(true);
            var chart = new MonitorPanel.NetworkWaveChart(); chart.setSize(200, 28);
            for (int i = 0; i < 32; i++) chart.pushData(10, 0);
            var picture = new java.awt.image.BufferedImage(200, 28, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = picture.createGraphics(); chart.printAll(graphics); graphics.dispose();
            boolean waveAboveAxis = false;
            for (int y = 2; y < 19; y++) for (int x = 130; x < 180; x++)
                if (picture.getRGB(x, y) == ThemeUtils.getAccent().getRGB()) waveAboveAxis = true;
            assertTrue(waveAboveAxis, "A compact history chart must not collapse all samples onto its baseline");
        });
    }

    static <T> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) { T result = find(nested, type); if (result != null) return result; }
        }
        return null;
    }
}
