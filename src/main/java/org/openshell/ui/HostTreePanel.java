package org.openshell.ui;

import com.formdev.flatlaf.FlatClientProperties;
import org.openshell.model.HostConfig;
import org.openshell.model.HostGroups;
import org.openshell.storage.HostStorage;
import org.openshell.storage.UiPreferences;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

public class HostTreePanel extends JPanel implements ThemeAware {
    private final List<HostConfig> hosts = new ArrayList<>();
    private final UiPreferences preferences = UiPreferences.current();
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("主机");
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model) {
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public String getToolTipText(MouseEvent event) {
            TreePath path = getPathForLocation(event.getX(), event.getY());
            if (path == null) return null;
            Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
            if (value instanceof HostConfig host)
                return "连接：" + host.getName() + " · " + host.getUsername() + "@" + host.getHost() + ":" + host.getPort();
            if (value instanceof Group group)
                return group.path.equals("#recent") ? "最近使用的连接（最多 5 台）" : "分组：" + group.path;
            return null;
        }
    };
    private final JTextField search = new JTextField();
    private final Consumer<HostConfig> connect;
    private boolean rebuilding;
    private Runnable hostsChanged = () -> {};
    private record Group(String path, String title) { @Override public String toString() { return title; } }
    private record HostSelection(String id, boolean recent) {}

    public HostTreePanel(Consumer<HostConfig> connect) {
        this.connect = connect;
        try { hosts.addAll(HostStorage.loadHosts()); } catch (RuntimeException error) { showError(error.getMessage()); }
        setLayout(new BorderLayout());
        JPanel heading = new JPanel(new BorderLayout(4, 4));
        heading.setBorder(new EmptyBorder(4, 6, 2, 6));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0)); buttons.setOpaque(false);
        JButton add = small("新建", UiIcons.Kind.PLUS); add.addActionListener(event -> showAddHostDialog());
        JButton group = small("分组", UiIcons.Kind.FOLDER); group.addActionListener(event -> createGroup());
        buttons.add(group); buttons.add(add);
        JLabel section = new JLabel("连接"); section.setForeground(ThemeUtils.getTextSecondary());
        section.setFont(ThemeUtils.uiFont(Font.PLAIN, 11));
        heading.add(section, BorderLayout.WEST); heading.add(buttons, BorderLayout.EAST);
        search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "搜索连接…");
        search.setToolTipText("搜索名称、地址、用户名或分组");
        search.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { reloadTree(search.getText()); }
            public void removeUpdate(DocumentEvent e) { reloadTree(search.getText()); }
            public void changedUpdate(DocumentEvent e) { reloadTree(search.getText()); }
        });
        JPanel top = new JPanel(new BorderLayout(0, 5)); top.add(heading, BorderLayout.NORTH); top.add(search, BorderLayout.SOUTH);
        top.setBorder(new EmptyBorder(0, 5, 5, 5)); add(top, BorderLayout.NORTH);
        tree.setRootVisible(false); tree.setRowHeight(0); tree.setCellRenderer(new Renderer());
        tree.setShowsRootHandles(true);
        tree.setBorder(new EmptyBorder(0, com.formdev.flatlaf.util.UIScale.scale(4), 0, com.formdev.flatlaf.util.UIScale.scale(4)));
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) {
                if (tree.getUI() instanceof javax.swing.plaf.basic.BasicTreeUI ui) {
                    // Refresh width-dependent row measurements, preserving the
                    // renderer, expansion state and selection.
                    ui.setLeftChildIndent(ui.getLeftChildIndent());
                }
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { selectPopup(event); }
            @Override public void mouseReleased(MouseEvent event) { selectPopup(event); }
            private void selectPopup(MouseEvent event) {
                if (event.isPopupTrigger()) {
                    TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                    tree.setSelectionPath(path);
                    popup().show(tree, event.getX(), event.getY());
                }
            }
            @Override public void mouseClicked(MouseEvent event) { if (event.getClickCount() == 2) connectSelected(); }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "connect");
        tree.getActionMap().put("connect", new AbstractAction() { public void actionPerformed(ActionEvent e) { connectSelected(); } });
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            public void treeExpanded(TreeExpansionEvent e) { rememberExpanded(e, false); }
            public void treeCollapsed(TreeExpansionEvent e) { rememberExpanded(e, true); }
        });
        tree.setDropMode(DropMode.ON); tree.setTransferHandler(new TreeTransfer());
        if (!GraphicsEnvironment.isHeadless()) tree.setDragEnabled(true);
        JScrollPane scroll = new JScrollPane(tree); scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null); add(scroll, BorderLayout.CENTER);
        applyTheme();
    }
    private JButton small(String title, UiIcons.Kind icon) {
        JButton button = new JButton(new UiIcons(icon)); button.setToolTipText(title);
        button.getAccessibleContext().setAccessibleName(title); ThemeUtils.toolbarButton(button);
        button.putClientProperty(FlatClientProperties.STYLE, "margin: 1,4,1,4;"); return button;
    }
    private Object selected() {
        return tree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node ? node.getUserObject() : null;
    }
    private String selectedGroup() {
        Object value = selected();
        return value instanceof Group group ? group.path : value instanceof HostConfig host ? host.getGroup() : "";
    }
    private void connectSelected() { if (selected() instanceof HostConfig host) connect.accept(host); }
    private void rememberExpanded(TreeExpansionEvent event, boolean collapsed) {
        if (rebuilding || !search.getText().isBlank()) return;
        Object value = ((DefaultMutableTreeNode) event.getPath().getLastPathComponent()).getUserObject();
        if (value instanceof Group group) {
            if (group.path.equals("#recent")) preferences.tryUpdate(state -> state.recentExpanded = !collapsed);
            else preferences.tryUpdate(state -> {
                state.collapsedGroups.remove(group.path); if (collapsed) state.collapsedGroups.add(group.path);
            });
        }
    }
    public void reloadTree(String keyword) {
        Object selection = selectionIdentity(tree.getLastSelectedPathComponent());
        rebuilding = true;
        try {
            root.removeAllChildren();
            String query = keyword.strip().toLowerCase(Locale.ROOT);
            List<HostConfig> matched = hosts.stream().filter(host -> query.isEmpty()
                    || (host.getName() + " " + host.getHost() + " " + host.getUsername() + " " + host.getGroup())
                    .toLowerCase(Locale.ROOT).contains(query)).toList();
            if (query.isEmpty()) {
                var recent = hosts.stream().filter(host -> host.getLastConnected() > 0)
                        .sorted(Comparator.comparingLong(HostConfig::getLastConnected).reversed()).limit(5).toList();
                if (!recent.isEmpty()) {
                    DefaultMutableTreeNode node = new DefaultMutableTreeNode(new Group("#recent", "最近连接"));
                    recent.forEach(host -> node.add(new DefaultMutableTreeNode(host))); root.add(node);
                }
            }
            List<String> explicit = query.isEmpty() ? preferences.snapshot().groups : List.of();
            Map<String, DefaultMutableTreeNode> nodes = new LinkedHashMap<>();
            for (String path : HostGroups.allPaths(matched, explicit)) {
                int slash = path.lastIndexOf('/'); String parent = slash < 0 ? "" : path.substring(0, slash);
                String label = slash < 0 ? path : path.substring(slash + 1);
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(new Group(path, label));
                nodes.put(path, node); (parent.isEmpty() ? root : nodes.get(parent)).add(node);
            }
            for (HostConfig host : matched) nodes.get(HostGroups.normalize(host.getGroup())).add(new DefaultMutableTreeNode(host));
            model.reload();
            var saved = preferences.snapshot();
            Set<String> collapsed = new HashSet<>(saved.collapsedGroups);
            for (int row = 0; row < tree.getRowCount(); row++) {
                TreePath path = tree.getPathForRow(row);
                Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                if (value instanceof Group group && (group.path.equals("#recent") ? saved.recentExpanded
                        : !query.isEmpty() || !collapsed.contains(group.path))) tree.expandPath(path);
            }
            if (!query.isEmpty() && selection instanceof HostSelection host) selection = new HostSelection(host.id, false);
            var allNodes = root.depthFirstEnumeration();
            while (selection != null && allNodes.hasMoreElements()) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) allNodes.nextElement();
                if (selection.equals(selectionIdentity(node))) { tree.setSelectionPath(new TreePath(node.getPath())); break; }
            }
        } finally { rebuilding = false; }
    }
    private static Object selectionIdentity(Object value) {
        if (!(value instanceof DefaultMutableTreeNode node)) return null;
        if (node.getUserObject() instanceof HostConfig host) {
            boolean recent = node.getParent() instanceof DefaultMutableTreeNode parent
                    && parent.getUserObject() instanceof Group group && group.path.equals("#recent");
            return new HostSelection(host.getId(), recent);
        }
        return node.getUserObject() instanceof Group group ? group : null;
    }
    private JPopupMenu popup() {
        JPopupMenu menu = new JPopupMenu(); Object value = selected();
        if (value instanceof HostConfig host) {
            item(menu, "连接", () -> connect.accept(host));
            item(menu, "编辑连接", () -> editHost(host));
            item(menu, "复制连接", () -> {
                HostConfig copy = host.copy(); copy.setId(UUID.randomUUID().toString()); copy.setName(host.getName() + " (副本)");
                var next = snapshotHosts(); next.add(copy); save(next, preferences.snapshot().groups);
            });
            item(menu, "移动到分组…", () -> {
                Object target = JOptionPane.showInputDialog(this, "选择目标分组（可先新建子分组）", "移动主机",
                        JOptionPane.PLAIN_MESSAGE, null, HostGroups.allPaths(hosts, preferences.snapshot().groups).toArray(), host.getGroup());
                if (target != null) apply(() -> HostGroups.moveHost(hosts, preferences.snapshot().groups, host.getId(), target.toString()));
            });
            menu.addSeparator();
            item(menu, "删除连接", () -> {
                if (JOptionPane.showConfirmDialog(this, "删除连接配置 " + host.getName() + "？", "删除连接", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    var next = snapshotHosts(); next.removeIf(h -> h.getId().equals(host.getId())); save(next, preferences.snapshot().groups);
                }
            });
        }
        if (value instanceof Group group && !group.path.startsWith("#")) {
            item(menu, "新建子分组", this::createGroup);
            item(menu, "重命名 / 移动分组…", () -> {
                String target = JOptionPane.showInputDialog(this, "新的完整分组路径", group.path);
                if (target != null) apply(() -> HostGroups.rename(hosts, preferences.snapshot().groups, group.path, target));
            });
            item(menu, "删除空分组", () -> apply(() -> HostGroups.removeEmpty(hosts, preferences.snapshot().groups, group.path)));
        }
        if (menu.getComponentCount() == 0) item(menu, "新建分组", this::createGroup);
        return menu;
    }
    private void item(JPopupMenu menu, String name, Runnable action) { JMenuItem item = new JMenuItem(name); item.addActionListener(e -> action.run()); menu.add(item); }
    private void createGroup() {
        String base = selectedGroup(); if (base.startsWith("#")) base = "";
        String value = JOptionPane.showInputDialog(this, "分组路径，使用 / 建立子分组", base.isEmpty() ? "新分组" : base + "/新分组");
        if (value == null) return;
        try {
            var groups = new ArrayList<>(preferences.snapshot().groups); groups.add(HostGroups.normalize(value));
            if (!preferences.tryUpdate(state -> state.groups = HostGroups.allPaths(hosts, groups))) showError(preferences.lastError());
            reloadTree(search.getText());
        } catch (IllegalArgumentException error) { showError(error.getMessage()); }
    }
    private interface Prepare { HostGroups.Change run(); }
    private void apply(Prepare prepare) {
        try { var change = prepare.run(); save(change.hosts(), change.groups()); }
        catch (RuntimeException error) { showError(error.getMessage()); }
    }
    private void save(List<HostConfig> next, List<String> groups) {
        try {
            HostStorage.saveHosts(next); hosts.clear(); hosts.addAll(next);
            if (!preferences.tryUpdate(state -> state.groups = new ArrayList<>(groups))) showError(preferences.lastError());
            reloadTree(search.getText()); hostsChanged.run();
        } catch (RuntimeException error) { showError(error.getMessage()); }
    }
    public void showAddHostDialog() {
        HostDialog dialog = new HostDialog((Frame) SwingUtilities.getWindowAncestor(this), null);
        dialog.setVisible(true);
        if (dialog.isSaved()) { var next = snapshotHosts(); next.add(dialog.getConfig()); save(next, preferences.snapshot().groups); }
    }
    private void editHost(HostConfig host) {
        HostDialog dialog = new HostDialog((Frame) SwingUtilities.getWindowAncestor(this), host.copy());
        dialog.setVisible(true);
        if (dialog.isSaved()) {
            var next = snapshotHosts(); next.replaceAll(h -> h.getId().equals(host.getId()) ? dialog.getConfig() : h);
            save(next, preferences.snapshot().groups);
        }
    }
    public List<HostConfig> snapshotHosts() { return new ArrayList<>(hosts.stream().map(HostConfig::copy).toList()); }
    public void setHostsChanged(Runnable action) { hostsChanged = action; }
    public void recordConnection(String id, long time) {
        var next = snapshotHosts();
        boolean exists = next.stream().anyMatch(host -> host.getId().equals(id));
        if (!exists) return; // Quick-connect sessions do not become saved hosts.
        next.stream().filter(host -> host.getId().equals(id)).forEach(host -> host.setLastConnected(time));
        save(next, preferences.snapshot().groups);
    }
    public void reloadFromStorage() {
        try { var next = HostStorage.loadHosts(); hosts.clear(); hosts.addAll(next); reloadTree(search.getText()); hostsChanged.run(); }
        catch (RuntimeException error) { showError(error.getMessage()); }
    }
    private void showError(String message) {
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException(message);
        JOptionPane.showMessageDialog(this, message, "主机管理", JOptionPane.ERROR_MESSAGE);
    }
    @Override public void applyTheme() {
        setBackground(ThemeUtils.getBg()); tree.setBackground(ThemeUtils.getBg());
        if (tree.getUI() instanceof javax.swing.plaf.basic.BasicTreeUI ui) {
            ui.setLeftChildIndent(com.formdev.flatlaf.util.UIScale.scale(6));
            ui.setRightChildIndent(com.formdev.flatlaf.util.UIScale.scale(6));
        }
        reloadTree(search.getText());
    }
    private static final class Renderer extends JPanel implements TreeCellRenderer {
        private final JLabel icon = new JLabel(), name = new JLabel(), address = new JLabel(), count = new JLabel();
        private boolean hostRow;
        private int availableWidth = -1;
        Renderer() {
            name.putClientProperty("html.disable", true); address.putClientProperty("html.disable", true);
            setOpaque(false);
            setLayout(new BorderLayout(com.formdev.flatlaf.util.UIScale.scale(6), 0));
            setBorder(new EmptyBorder(2, 4, 2, 6));
            JPanel text = new JPanel(new BorderLayout());
            text.setOpaque(false); text.add(name, BorderLayout.NORTH); text.add(address, BorderLayout.CENTER);
            add(icon, BorderLayout.WEST); add(text, BorderLayout.CENTER); add(count, BorderLayout.EAST);
            name.setFont(ThemeUtils.uiFont(Font.PLAIN, 12)); address.setFont(ThemeUtils.uiFont(Font.PLAIN, 10));
            count.setFont(ThemeUtils.uiFont(Font.PLAIN, 10));
        }
        @Override public Component getTreeCellRendererComponent(JTree tree, Object node, boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
            Object value = ((DefaultMutableTreeNode) node).getUserObject();
            availableWidth = -1;
            if (tree.getWidth() > 0 && tree.getUI() instanceof javax.swing.plaf.basic.BasicTreeUI ui) {
                int depth = ((DefaultMutableTreeNode) node).getLevel();
                if (tree.isRootVisible() && tree.getShowsRootHandles()) depth++;
                if (!tree.isRootVisible() && !tree.getShowsRootHandles()) depth--;
                int indent = (ui.getLeftChildIndent() + ui.getRightChildIndent()) * depth;
                availableWidth = Math.max(0, tree.getWidth() - tree.getInsets().left - tree.getInsets().right - indent);
            }
            if (value instanceof HostConfig host) {
                hostRow = true; count.setVisible(false);
                name.setText(host.getName()); address.setText(host.getUsername() + "@" + host.getHost() + ":" + host.getPort());
                address.setVisible(true); icon.setIcon(new UiIcons(UiIcons.Kind.HOST));
                name.setFont(ThemeUtils.uiFont(Font.PLAIN, 12));
            } else {
                hostRow = false;
                name.setText(value.toString()); address.setText(""); address.setVisible(false);
                boolean recent = value instanceof Group group && group.path.equals("#recent");
                icon.setIcon(new UiIcons(recent ? UiIcons.Kind.HISTORY : UiIcons.Kind.FOLDER, 14));
                name.setFont(ThemeUtils.uiFont(Font.BOLD, 11));
                int total = 0;
                var descendants = ((DefaultMutableTreeNode) node).depthFirstEnumeration();
                while (descendants.hasMoreElements()) if (((DefaultMutableTreeNode) descendants.nextElement()).getUserObject() instanceof HostConfig) total++;
                count.setText(String.valueOf(total)); count.setVisible(true);
            }
            name.setForeground(hostRow || selected ? ThemeUtils.getTextPrimary() : ThemeUtils.getTextSecondary());
            address.setForeground(ThemeUtils.getTextSecondary()); count.setForeground(ThemeUtils.getTextMuted());
            setBackground(selected ? UIManager.getColor("Tree.selectionBackground") : ThemeUtils.getBg()); return this;
        }
        @Override public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            if (availableWidth >= 0) size.width = availableWidth;
            size.height = Math.max(size.height, com.formdev.flatlaf.util.UIScale.scale(hostRow ? 32 : 22));
            return size;
        }
    }
    private record DragItem(String hostId, String group) {}
    private static final DataFlavor FLAVOR = new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=java.lang.Object", "OpenShell 主机分组");
    private final class TreeTransfer extends TransferHandler {
        @Override public int getSourceActions(JComponent component) { return MOVE; }
        @Override protected Transferable createTransferable(JComponent component) {
            Object value = selected();
            DragItem data = value instanceof HostConfig host ? new DragItem(host.getId(), null)
                    : value instanceof Group group && !group.path.startsWith("#") ? new DragItem(null, group.path) : null;
            if (data == null) return null;
            return new Transferable() {
                public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{FLAVOR}; }
                public boolean isDataFlavorSupported(DataFlavor flavor) { return FLAVOR.equals(flavor); }
                public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException { if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor); return data; }
            };
        }
        @Override public boolean canImport(TransferSupport support) {
            if (!support.isDrop() || !support.isDataFlavorSupported(FLAVOR)) return false;
            TreePath path = ((JTree.DropLocation) support.getDropLocation()).getPath();
            return path != null && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof Group group && !group.path.startsWith("#");
        }
        @Override public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            try {
                DragItem data = (DragItem) support.getTransferable().getTransferData(FLAVOR);
                TreePath path = ((JTree.DropLocation) support.getDropLocation()).getPath();
                Group target = (Group) ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                if (data.hostId != null) apply(() -> HostGroups.moveHost(hosts, preferences.snapshot().groups, data.hostId, target.path));
                else {
                    String leaf = data.group.substring(data.group.lastIndexOf('/') + 1);
                    apply(() -> HostGroups.rename(hosts, preferences.snapshot().groups, data.group, target.path + "/" + leaf));
                }
                return true;
            } catch (Exception error) { showError(error.getMessage()); return false; }
        }
    }
}
