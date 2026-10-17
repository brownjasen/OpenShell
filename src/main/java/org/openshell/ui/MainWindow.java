package org.openshell.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import org.openshell.model.HostConfig;
import org.openshell.ssh.SftpClient;
import org.openshell.storage.HostStorage;
import org.openshell.storage.UiPreferences;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/**
 * OpenShell 现代化桌面运维终端主窗口
 * 深度融合 FinalShell 经典双栏/上下分割架构，全面支持深色 (Dark) 与浅色 (Light) 主题引擎
 */
public class MainWindow extends JFrame {
    private final JTabbedPane tabbedPane;
    private final HostTreePanel hostTreePanel;
    private final JSplitPane mainSplitPane;
    private final JToolBar mainToolBar;
    private final JPanel bottomStatusBar;
    private final JLabel statusLeftLabel;
    private final JLabel statusRightLabel;
    private final UiPreferences preferences = UiPreferences.current();
    private final DebouncedSave windowSave = new DebouncedSave(this::saveWindowState);
    private boolean sidebarVisible;
    private int sidebarWidth;
    private boolean isDark = ThemeUtils.isDark();
    private int activeSessionCount = 0;
    private Component welcomePanelRef;

    public MainWindow() {
        var saved = preferences.snapshot();
        ThemeUtils.install(saved.dark);
        isDark = saved.dark; sidebarVisible = saved.sidebarVisible; sidebarWidth = saved.sidebarWidth;
        setTitle("OpenShell · SSH / SFTP");
        setSize(saved.width, saved.height);
        setMinimumSize(new Dimension(800, 480));
        setLocationRelativeTo(null);
        Rectangle desired = new Rectangle(saved.x, saved.y, saved.width, saved.height);
        if (!(saved.x == -1 && saved.y == -1)) {
            for (GraphicsDevice screen : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle bounds = screen.getDefaultConfiguration().getBounds();
                if (bounds.intersects(desired)) {
                    desired.width = Math.min(desired.width, bounds.width); desired.height = Math.min(desired.height, bounds.height);
                    desired.x = Math.max(bounds.x, Math.min(desired.x, bounds.x + bounds.width - desired.width));
                    desired.y = Math.max(bounds.y, Math.min(desired.y, bounds.y + bounds.height - desired.height));
                    setBounds(desired); break;
                }
            }
        }
        if (saved.maximized) setExtendedState(JFrame.MAXIMIZED_BOTH);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) {
                java.util.List<Component> tabs = new java.util.ArrayList<>();
                for (int i = 0; i < tabbedPane.getTabCount(); i++) tabs.add(tabbedPane.getComponentAt(i));
                requestCloseTabs(tabs, () -> { saveWindowState(); windowSave.flush(); dispose(); System.exit(0); });
            }
        });

        // 顶部菜单栏
        setJMenuBar(createMenuBar());

        // 主体区域
        hostTreePanel = new HostTreePanel(this::openSession);
        hostTreePanel.setPreferredSize(new Dimension(sidebarWidth, 0));

        tabbedPane = new JTabbedPane();
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabbedPane.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_TYPE, FlatClientProperties.TABBED_PANE_TAB_TYPE_UNDERLINED);
        tabbedPane.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_HEIGHT, 32);
        tabbedPane.putClientProperty(FlatClientProperties.TABBED_PANE_SHOW_TAB_SEPARATORS, true);
        tabbedPane.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSABLE, false);

        // 默认欢迎工作台首页
        welcomePanelRef = createWelcomePanel();
        tabbedPane.addTab("首页", welcomePanelRef);

        mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, hostTreePanel, tabbedPane);
        mainSplitPane.setDividerLocation(sidebarWidth);
        mainSplitPane.setDividerSize(6);
        mainSplitPane.setContinuousLayout(true);
        mainSplitPane.setBorder(ThemeUtils.createMatteBorder(1, 0, 0, 0));

        if (!sidebarVisible) mainSplitPane.setLeftComponent(null);
        mainToolBar = createMainToolBar(mainSplitPane);

        JPanel centerContainer = new JPanel(new BorderLayout());
        centerContainer.add(mainToolBar, BorderLayout.NORTH);
        centerContainer.add(mainSplitPane, BorderLayout.CENTER);

        add(centerContainer, BorderLayout.CENTER);

        // 底部状态栏 (VS Code / FinalShell 桌面规范)
        bottomStatusBar = new JPanel(new BorderLayout(10, 0));
        bottomStatusBar.setBackground(ThemeUtils.getHeaderBg());
        bottomStatusBar.setBorder(BorderFactory.createCompoundBorder(
                ThemeUtils.createMatteBorder(1, 0, 0, 0),
                new EmptyBorder(4, 12, 4, 12)
        ));

        statusLeftLabel = new JLabel("就绪");
        statusLeftLabel.setFont(ThemeUtils.uiFont(Font.PLAIN, 11));
        statusLeftLabel.setForeground(ThemeUtils.getTextSecondary());

        statusRightLabel = new JLabel("活跃会话: 0 | 字符集: UTF-8");
        statusRightLabel.setFont(ThemeUtils.uiFont(Font.PLAIN, 11));
        statusRightLabel.setForeground(ThemeUtils.getTextSecondary());

        bottomStatusBar.add(statusLeftLabel, BorderLayout.WEST);
        bottomStatusBar.add(statusRightLabel, BorderLayout.EAST);
        add(bottomStatusBar, BorderLayout.SOUTH);
        statusLeftLabel.setText(preferences.lastError() == null ? "就绪 · Ctrl+N 新建连接" : preferences.lastError());
        hostTreePanel.setHostsChanged(this::refreshWelcome);
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { windowSave.schedule(); }
            @Override public void componentMoved(java.awt.event.ComponentEvent e) { windowSave.schedule(); }
        });
        mainSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, event -> {
            if (sidebarVisible && mainSplitPane.getDividerLocation() >= 180) {
                sidebarWidth = mainSplitPane.getDividerLocation(); windowSave.schedule();
            }
        });
        getRootPane().registerKeyboardAction(event -> toggleSidebar(), KeyStroke.getKeyStroke("ctrl shift B"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(event -> {
            Component selected = tabbedPane.getSelectedComponent();
            if (selected instanceof SessionPanel session) closeSessionTab(session);
            else if (selected instanceof RemoteEditorPanel editor) editor.requestClose(() -> tabbedPane.remove(editor));
        }, KeyStroke.getKeyStroke("ctrl shift W"), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private void saveWindowState() {
        boolean maximized = (getExtendedState() & JFrame.MAXIMIZED_BOTH) != 0;
        Rectangle bounds = getBounds();
        if (!preferences.tryUpdate(state -> {
            state.sidebarVisible = sidebarVisible; state.sidebarWidth = sidebarWidth;
            state.dark = isDark; state.maximized = maximized;
            if (!maximized) { state.x = bounds.x; state.y = bounds.y; state.width = bounds.width; state.height = bounds.height; }
        })) statusLeftLabel.setText(preferences.lastError());
    }

    private void toggleSidebar() {
        if (sidebarVisible && mainSplitPane.getDividerLocation() >= 180) sidebarWidth = mainSplitPane.getDividerLocation();
        sidebarVisible = !sidebarVisible;
        mainSplitPane.setLeftComponent(sidebarVisible ? hostTreePanel : null);
        if (sidebarVisible) mainSplitPane.setDividerLocation(sidebarWidth);
        windowSave.schedule();
    }
    private void refreshWelcome() {
        int index = welcomePanelRef == null ? -1 : tabbedPane.indexOfComponent(welcomePanelRef);
        if (index >= 0) { welcomePanelRef = createWelcomePanel(); tabbedPane.setComponentAt(index, welcomePanelRef); }
    }
    private void showWelcome() {
        if (welcomePanelRef == null || tabbedPane.indexOfComponent(welcomePanelRef) < 0) {
            welcomePanelRef = createWelcomePanel(); tabbedPane.insertTab("首页", null, welcomePanelRef, null, 0);
        }
        tabbedPane.setSelectedComponent(welcomePanelRef);
    }

    private JMenuBar createMenuBar() {
        JMenuBar bar = new JMenuBar();

        // 文件 (F)
        JMenu fileMenu = new JMenu("文件 (F)");
        JMenuItem newHost = new JMenuItem("新建服务器配置 (Ctrl+N)");
        newHost.addActionListener(e -> hostTreePanel.showAddHostDialog());
        newHost.setAccelerator(KeyStroke.getKeyStroke("ctrl N"));

        JMenuItem quickConnect = new JMenuItem("快速直连 IP...");
        quickConnect.addActionListener(e -> showQuickConnectDialog());
        quickConnect.setAccelerator(KeyStroke.getKeyStroke("ctrl shift N"));

        JMenuItem exportItem = new JMenuItem("导出主机配置 (JSON)...");
        exportItem.addActionListener(e -> exportHostsToFile());

        JMenuItem importItem = new JMenuItem("导入主机配置 (JSON)...");
        importItem.addActionListener(e -> importHostsFromFile());

        JMenuItem exit = new JMenuItem("退出");
        exit.addActionListener(e -> dispatchEvent(new java.awt.event.WindowEvent(this, java.awt.event.WindowEvent.WINDOW_CLOSING)));

        fileMenu.add(newHost);
        fileMenu.add(quickConnect);
        fileMenu.addSeparator();
        fileMenu.add(exportItem);
        fileMenu.add(importItem);
        fileMenu.addSeparator();
        fileMenu.add(exit);

        // 工具 (T)
        JMenu toolsMenu = new JMenu("工具 (T)");
        JMenuItem portTunnel = new JMenuItem("SSH 端口转发与隧道管理...");
        portTunnel.addActionListener(e -> {
            JOptionPane.showMessageDialog(this, "请在已连接会话的“隧道”页管理本地或远程端口转发。", "端口转发与隧道", JOptionPane.INFORMATION_MESSAGE);
        });
        toolsMenu.add(portTunnel);

        // 视图 (V)
        JMenu viewMenu = new JMenu("视图 (V)");
        JMenuItem toggleTheme = new JMenuItem("切换 深色 / 浅色 主题");
        toggleTheme.addActionListener(e -> toggleTheme());
        viewMenu.add(toggleTheme);

        // 帮助 (H)
        JMenu helpMenu = new JMenu("帮助 (H)");
        JMenuItem about = new JMenuItem("关于 OpenShell");
        about.addActionListener(e -> {
            JOptionPane.showMessageDialog(this,
                    "OpenShell 1.0.0\nSSH / SFTP 桌面工作台\n\n" +
                    "终端、远程文件、在线编辑、资源监控和端口转发。",
                    "关于 OpenShell", JOptionPane.INFORMATION_MESSAGE);
        });
        helpMenu.add(about);

        bar.add(fileMenu);
        bar.add(toolsMenu);
        bar.add(viewMenu);
        bar.add(helpMenu);

        return bar;
    }

    private JToolBar createMainToolBar(JSplitPane splitPane) {
        JToolBar bar = new JToolBar(); bar.setFloatable(false); bar.setRollover(true);
        bar.setBackground(ThemeUtils.getHeaderBg()); bar.setBorder(new EmptyBorder(2, 6, 2, 6));
        JButton home = createBarButton("首页", "快速连接与最近主机"); home.addActionListener(e -> showWelcome()); bar.add(home);
        JButton add = createBarButton("新建连接", "Ctrl+N"); add.setIcon(new UiIcons(UiIcons.Kind.PLUS));
        add.addActionListener(e -> hostTreePanel.showAddHostDialog()); bar.add(add);
        JButton quick = createBarButton("快速连接", "Ctrl+Shift+N"); quick.setIcon(new UiIcons(UiIcons.Kind.CONNECT));
        quick.addActionListener(e -> showQuickConnectDialog()); bar.add(quick); bar.addSeparator();
        JButton hosts = createBarButton("主机", "显示/隐藏主机树 · Ctrl+Shift+B"); hosts.setIcon(new UiIcons(UiIcons.Kind.HOST));
        hosts.addActionListener(e -> toggleSidebar()); bar.add(hosts);
        JButton theme = createBarButton("主题", "切换深浅主题"); theme.addActionListener(e -> toggleTheme()); bar.add(theme);
        bar.add(Box.createHorizontalGlue()); return bar;
    }

    private JButton createBarButton(String text, String tooltip) {
        JButton btn = new JButton(text);
        ThemeUtils.toolbarButton(btn);
        btn.setFont(ThemeUtils.uiFont(Font.PLAIN, 12));
        btn.putClientProperty(FlatClientProperties.STYLE, "arc: 4; margin: 3,8,3,8;");
        btn.setToolTipText(tooltip);
        btn.setFocusable(false);
        return btn;
    }

    public void toggleTheme() {
        try {
            ThemeUtils.install(!isDark);
            isDark = !isDark;
            if (!preferences.tryUpdate(state -> state.dark = isDark)) statusLeftLabel.setText(preferences.lastError());

            // 重新刷新主窗口顶部工具栏和底部状态栏
            mainToolBar.setBackground(ThemeUtils.getHeaderBg());
            mainToolBar.setBorder(BorderFactory.createCompoundBorder(
                    ThemeUtils.createMatteBorder(0, 0, 1, 0),
                    new EmptyBorder(3, 8, 3, 8)
            ));
            mainSplitPane.setBorder(ThemeUtils.createMatteBorder(1, 0, 0, 0));
            bottomStatusBar.setBackground(ThemeUtils.getHeaderBg());
            bottomStatusBar.setBorder(BorderFactory.createCompoundBorder(
                    ThemeUtils.createMatteBorder(1, 0, 0, 0),
                    new EmptyBorder(4, 12, 4, 12)
            ));
            statusLeftLabel.setForeground(ThemeUtils.getTextSecondary());
            statusRightLabel.setForeground(ThemeUtils.getTextSecondary());

            // 重新刷新欢迎页
            if (welcomePanelRef != null && tabbedPane.indexOfComponent(welcomePanelRef) != -1) {
                int welcomeIdx = tabbedPane.indexOfComponent(welcomePanelRef);
                welcomePanelRef = createWelcomePanel();
                tabbedPane.setComponentAt(welcomeIdx, welcomePanelRef);
            }

            // 递归刷新整个组件树
            SwingUtilities.updateComponentTreeUI(this);
            hostTreePanel.applyTheme();

            // 递归通知所有打开的会话/编辑器等标签页应用新主题
            for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                Component c = tabbedPane.getComponentAt(i);
                if (c instanceof ThemeAware) {
                    ((ThemeAware) c).applyTheme();
                }
            }
        } catch (Exception ignored) {}
    }

    public void openSession(HostConfig config) {
        SessionPanel sessionPanel = new SessionPanel(config, this::openEditorTab);

        String tabTitle = " " + config.getName() + " ";
        tabbedPane.addTab(tabTitle, sessionPanel);
        int index = tabbedPane.indexOfComponent(sessionPanel);

        // 自定义带关闭按钮 (x) 的标签头
        JPanel tabHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        tabHeader.setOpaque(false);
        JLabel title = new JLabel(tabTitle);
        title.setFont(ThemeUtils.uiFont(Font.BOLD, 12));

        JButton closeBtn = new JButton("×");
        closeBtn.setFont(ThemeUtils.uiFont(Font.BOLD, 14));
        closeBtn.setMargin(new Insets(0, 4, 0, 4));
        closeBtn.setContentAreaFilled(false);
        closeBtn.setBorderPainted(false);
        closeBtn.setFocusable(false);
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        closeBtn.addActionListener(e -> closeSessionTab(sessionPanel));

        // 标签右键菜单
        JPopupMenu tabMenu = new JPopupMenu();
        JMenuItem dupItem = new JMenuItem("复制会话 (打开新标签)");
        dupItem.addActionListener(e -> openSession(config));

        JMenuItem closeThisItem = new JMenuItem("关闭当前会话");
        closeThisItem.addActionListener(e -> closeSessionTab(sessionPanel));

        JMenuItem closeOtherItem = new JMenuItem("关闭其他所有标签");
        closeOtherItem.addActionListener(e -> closeOtherTabs(sessionPanel));

        tabMenu.add(dupItem);
        tabMenu.addSeparator();
        tabMenu.add(closeThisItem);
        tabMenu.add(closeOtherItem);

        tabHeader.setComponentPopupMenu(tabMenu);
        title.setComponentPopupMenu(tabMenu);

        tabHeader.add(title);
        tabHeader.add(closeBtn);
        tabbedPane.setTabComponentAt(index, tabHeader);
        tabbedPane.setSelectedComponent(sessionPanel);

        sessionPanel.setStateListener(() -> {
            title.setText(sessionPanel.isConnected() ? tabTitle : tabTitle + " · " + sessionPanel.getSessionState().label());
            if (sessionPanel.isConnected()) {
                hostTreePanel.recordConnection(config.getId(), sessionPanel.getHostConfig().getLastConnected());
                if (welcomePanelRef != null) tabbedPane.remove(welcomePanelRef);
            }
            updateStatusBar();
        });
        updateStatusBar();
    }

    private void closeSessionTab(SessionPanel sessionPanel) {
        java.util.List<Component> tabs = new java.util.ArrayList<>();
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            Component tab = tabbedPane.getComponentAt(i);
            if (tab instanceof RemoteEditorPanel editor && sessionPanel.ownsClient(editor.getSftpClient())) tabs.add(tab);
        }
        tabs.add(sessionPanel);
        requestCloseTabs(tabs, () -> {});
    }

    private void closeOtherTabs(Component keepComponent) {
        java.util.List<Component> tabs = new java.util.ArrayList<>();
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            Component tab = tabbedPane.getComponentAt(i);
            if (keepComponent instanceof RemoteEditorPanel editor && tab instanceof SessionPanel session
                    && session.ownsClient(editor.getSftpClient())) continue;
            if (tab != keepComponent) tabs.add(tab);
        }
        requestCloseTabs(tabs, () -> {});
    }

    private void requestCloseTabs(java.util.List<Component> tabs, Runnable onDone) {
        java.util.List<Component> ordered = new java.util.ArrayList<>();
        tabs.stream().filter(tab -> tab instanceof RemoteEditorPanel).forEach(ordered::add);
        tabs.stream().filter(tab -> !(tab instanceof RemoteEditorPanel)).forEach(ordered::add);
        closeNextTab(ordered.iterator(), onDone);
    }

    private void closeNextTab(java.util.Iterator<Component> tabs, Runnable onDone) {
        if (!tabs.hasNext()) { onDone.run(); return; }
        Component tab = tabs.next();
        if (tab instanceof SessionPanel session && !session.confirmStopTransfers()) return;
        Runnable remove = () -> {
            if (tab instanceof SessionPanel session) session.close();
            tabbedPane.remove(tab); updateStatusBar(); closeNextTab(tabs, onDone);
        };
        if (tab instanceof RemoteEditorPanel editor) editor.requestClose(remove); else remove.run();
    }

    public void openEditorTab(SftpClient sftpClient, String remoteFilePath) {
        RemoteEditorPanel editor = new RemoteEditorPanel(sftpClient, remoteFilePath);
        String tabTitle = " " + editor.getFileName() + " ";

        tabbedPane.addTab(tabTitle, editor);
        int index = tabbedPane.indexOfComponent(editor);

        JPanel tabHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        tabHeader.setOpaque(false);
        JLabel title = new JLabel(tabTitle);
        title.setFont(ThemeUtils.uiFont(Font.BOLD, 12));

        JButton closeBtn = new JButton("×");
        closeBtn.setFont(ThemeUtils.uiFont(Font.BOLD, 14));
        closeBtn.setMargin(new Insets(0, 4, 0, 4));
        closeBtn.setContentAreaFilled(false);
        closeBtn.setBorderPainted(false);
        closeBtn.setFocusable(false);
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        closeBtn.addActionListener(e -> editor.requestClose(() -> tabbedPane.remove(editor)));

        JPopupMenu editorTabMenu = new JPopupMenu();
        JMenuItem closeItem = new JMenuItem("关闭当前编辑器");
        closeItem.addActionListener(e -> editor.requestClose(() -> tabbedPane.remove(editor)));

        JMenuItem closeOthers = new JMenuItem("关闭其他所有标签");
        closeOthers.addActionListener(e -> closeOtherTabs(editor));

        editorTabMenu.add(closeItem);
        editorTabMenu.add(closeOthers);

        tabHeader.setComponentPopupMenu(editorTabMenu);
        title.setComponentPopupMenu(editorTabMenu);

        tabHeader.add(title);
        tabHeader.add(closeBtn);
        tabbedPane.setTabComponentAt(index, tabHeader);
        tabbedPane.setSelectedComponent(editor);
    }

    private void exportHostsToFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("openshell_hosts.json"));
        chooser.setDialogTitle("导出主机配置为 JSON 文件");
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                HostStorage.exportHosts(HostStorage.loadHosts(), chooser.getSelectedFile());
                JOptionPane.showMessageDialog(this, "主机配置成功导出至:\n" + chooser.getSelectedFile().getAbsolutePath(), "导出成功", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "导出失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void importHostsFromFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择要导入的 JSON 主机配置文件");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                List<HostConfig> imported = HostStorage.importHosts(chooser.getSelectedFile());
                if (imported != null && !imported.isEmpty()) {
                    List<HostConfig> current = HostStorage.loadHosts();
                    current.addAll(imported);
                    HostStorage.saveHosts(current);
                    hostTreePanel.reloadFromStorage();
                    JOptionPane.showMessageDialog(this, "成功导入 " + imported.size() + " 台服务器配置！", "导入成功", JOptionPane.INFORMATION_MESSAGE);
                }
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "导入失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void updateStatusBar() {
        activeSessionCount = 0;
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if (tabbedPane.getComponentAt(i) instanceof SessionPanel session && session.isConnected()) activeSessionCount++;
        }
        statusRightLabel.setText("已连接会话: " + activeSessionCount);
    }

    private JPanel createWelcomePanel() {
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(ThemeUtils.getBg()); root.setBorder(new EmptyBorder(25, 25, 20, 25));
        JPanel title = new JPanel(new BorderLayout()); title.setOpaque(false);
        JLabel name = new JLabel("OpenShell"); name.setFont(ThemeUtils.uiFont(Font.BOLD, 25));
        title.add(name, BorderLayout.NORTH);
        JLabel hint = new JLabel("连接服务器 · 操作终端 · 管理文件"); hint.setForeground(ThemeUtils.getTextSecondary());
        title.add(hint, BorderLayout.SOUTH); root.add(title, BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout(0, 16)); body.setOpaque(false);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); actions.setOpaque(false);
        JButton add = createDashboardButton("新建连接"); add.addActionListener(e -> hostTreePanel.showAddHostDialog());
        JButton quick = createDashboardButton("快速连接"); quick.addActionListener(e -> showQuickConnectDialog());
        actions.add(add); actions.add(quick); body.add(actions, BorderLayout.NORTH);
        JPanel list = new JPanel(); list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS)); list.setOpaque(false);
        JLabel label = new JLabel("最近连接"); label.setBorder(new EmptyBorder(0, 0, 10, 0)); list.add(label);
        var recent = hostTreePanel.snapshotHosts().stream().filter(host -> host.getLastConnected() > 0)
                .sorted(java.util.Comparator.comparingLong(HostConfig::getLastConnected).reversed()).limit(8).toList();
        if (recent.isEmpty()) {
            JLabel empty = new JLabel("连接成功后，最近使用的主机会显示在这里。"); empty.setForeground(ThemeUtils.getTextSecondary()); list.add(empty);
        }
        for (HostConfig host : recent) {
            RecentConnectionRow row = new RecentConnectionRow(host, this::openSession);
            list.add(row); list.add(Box.createVerticalStrut(5));
        }
        JScrollPane scroll = new JScrollPane(list); scroll.setBorder(null); scroll.setOpaque(false); scroll.getViewport().setOpaque(false);
        body.add(scroll, BorderLayout.CENTER); root.add(body, BorderLayout.CENTER);
        JLabel keys = new JLabel("Ctrl+N 新建连接    Ctrl+Shift+N 快速连接    Ctrl+Shift+W 关闭标签");
        keys.setForeground(ThemeUtils.getTextMuted()); root.add(keys, BorderLayout.SOUTH); return root;
    }

    private JButton createDashboardButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(ThemeUtils.uiFont(Font.PLAIN, 12));
        btn.putClientProperty(FlatClientProperties.STYLE, "arc: 4; margin: 6,12,6,12;");
        btn.setFocusable(false);
        btn.setAlignmentX(Component.LEFT_ALIGNMENT);
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        return btn;
    }

    private void showQuickConnectDialog() {
        String host = JOptionPane.showInputDialog(this, "请输入要连接的主机地址 (如 root@192.168.1.100 或直接 IP):", "快速连接", JOptionPane.PLAIN_MESSAGE);
        if (host != null && !host.trim().isEmpty()) {
            String user = "root";
            String ip = host.trim();
            if (ip.contains("@")) {
                String[] p = ip.split("@", 2);
                user = p[0];
                ip = p[1];
            }
            int port = 22;
            if (ip.contains(":")) {
                String[] p = ip.split(":", 2);
                ip = p[0];
                try { port = Integer.parseInt(p[1]); } catch (Exception ignored) {}
            }

            HostConfig cfg = new HostConfig("快速连接: " + ip, ip, port, user);
            HostDialog dialog = new HostDialog(this, cfg);
            dialog.setVisible(true);
            if (dialog.isSaved()) {
                openSession(dialog.getConfig());
            }
        }
    }
}
