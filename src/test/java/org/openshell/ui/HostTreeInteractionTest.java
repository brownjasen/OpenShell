package org.openshell.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.model.HostConfig;
import org.openshell.storage.*;
import javax.swing.*;
import javax.swing.tree.*;
import java.nio.file.*;
import java.awt.*;
import java.awt.event.*;
import static org.junit.jupiter.api.Assertions.*;

class HostTreeInteractionTest {
    @TempDir Path directory;

    @Test void recentConnectionsStartCollapsedWhileNormalGroupsRemainExpanded() throws Exception {
        isolated(() -> {
            HostTreePanel panel = new HostTreePanel(host -> {});
            JTree tree = UiRenderingRegressionTest.find(panel, JTree.class);
            var root = (DefaultMutableTreeNode) tree.getModel().getRoot();
            TreePath recent = new TreePath(((DefaultMutableTreeNode) root.getChildAt(0)).getPath());
            TreePath group = new TreePath(((DefaultMutableTreeNode) root.getChildAt(1)).getPath());
            assertTrue(tree.getShowsRootHandles(), "Top-level groups need visible expand/collapse controls");
            assertFalse(tree.isExpanded(recent), "Do not show the same connection twice by default");
            assertTrue(tree.isExpanded(group));
            assertEquals(3, tree.getRowCount());
        });
    }

    @Test void expansionChoicesAndSelectedHostSurviveRecordingAndReload() throws Exception {
        isolated(() -> {
            HostTreePanel panel = new HostTreePanel(host -> {});
            JTree tree = UiRenderingRegressionTest.find(panel, JTree.class);
            var root = (DefaultMutableTreeNode) tree.getModel().getRoot();
            var recentNode = (DefaultMutableTreeNode) root.getChildAt(0);
            var groupNode = (DefaultMutableTreeNode) root.getChildAt(1);
            var hostNode = (DefaultMutableTreeNode) groupNode.getChildAt(0);
            HostConfig host = (HostConfig) hostNode.getUserObject();
            tree.expandPath(new TreePath(recentNode.getPath()));
            tree.setSelectionPath(new TreePath(hostNode.getPath()));
            panel.recordConnection(host.getId(), 45678);
            assertNotNull(tree.getSelectionPath(), "Saving a recent connection must preserve selection");
            var selected = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            assertEquals(host.getId(), ((HostConfig) selected.getUserObject()).getId());
            assertEquals("默认分组", selected.getParent().toString(), "Selection should stay in the real group");
            tree.collapsePath(new TreePath(((DefaultMutableTreeNode) selected.getParent()).getPath()));
            HostTreePanel restored = new HostTreePanel(h -> {});
            JTree restoredTree = UiRenderingRegressionTest.find(restored, JTree.class);
            var restoredRoot = (DefaultMutableTreeNode) restoredTree.getModel().getRoot();
            assertTrue(restoredTree.isExpanded(new TreePath(((DefaultMutableTreeNode) restoredRoot.getChildAt(0)).getPath())));
            assertFalse(restoredTree.isExpanded(new TreePath(((DefaultMutableTreeNode) restoredRoot.getChildAt(1)).getPath())));
        });
    }

    @Test void searchingDoesNotChangeSavedGroupExpansion() throws Exception {
        isolated(() -> {
            HostTreePanel panel = new HostTreePanel(host -> {});
            JTree tree = UiRenderingRegressionTest.find(panel, JTree.class);
            var root = (DefaultMutableTreeNode) tree.getModel().getRoot();
            tree.collapsePath(new TreePath(((DefaultMutableTreeNode) root.getChildAt(1)).getPath()));
            panel.reloadTree("example.invalid");
            assertEquals(2, tree.getRowCount(), "Search only shows the matching host in its real group");
            panel.reloadTree("");
            root = (DefaultMutableTreeNode) tree.getModel().getRoot();
            assertFalse(tree.isExpanded(new TreePath(((DefaultMutableTreeNode) root.getChildAt(1)).getPath())));
        });
    }

    @Test void narrowSidebarKeepsRowsInsideTheViewportAndProvidesTheFullAddress() throws Exception {
        isolated(() -> {
            var hosts = HostStorage.loadHosts();
            hosts.get(0).setName("用于检查窄侧栏省略显示的很长主机名称");
            HostStorage.saveHosts(hosts);
            HostTreePanel panel = new HostTreePanel(host -> {});
            panel.setSize(com.formdev.flatlaf.util.UIScale.scale(220), 400); layout(panel);
            JTree tree = UiRenderingRegressionTest.find(panel, JTree.class);
            tree.dispatchEvent(new ComponentEvent(tree, ComponentEvent.COMPONENT_RESIZED)); layout(panel);
            Rectangle host = tree.getRowBounds(tree.getRowCount() - 1);
            assertTrue(host.x + host.width <= tree.getWidth() - tree.getInsets().right,
                    "Connection rows should fit the sidebar and ellipsize long text");
            MouseEvent hover = new MouseEvent(tree, MouseEvent.MOUSE_MOVED, 1, 0, host.x + 4, host.y + 4, 0, false);
            assertNotNull(tree.getToolTipText(hover));
            assertTrue(tree.getToolTipText(hover).contains("user@example.invalid:22"));
        });
    }

    private static void layout(Container root) {
        root.doLayout(); for (Component child : root.getComponents()) if (child instanceof Container nested) layout(nested);
    }

    private void isolated(Runnable check) throws Exception {
        String previous = System.getProperty("openshell.configDir");
        System.setProperty("openshell.configDir", directory.toString());
        try {
            HostConfig host = new HostConfig("示例主机", "example.invalid", 22, "user");
            host.setLastConnected(12345);
            HostStorage.saveHosts(java.util.List.of(host));
            SwingUtilities.invokeAndWait(() -> { ThemeUtils.install(true); check.run(); });
        } finally {
            if (previous == null) System.clearProperty("openshell.configDir"); else System.setProperty("openshell.configDir", previous);
        }
    }
}
