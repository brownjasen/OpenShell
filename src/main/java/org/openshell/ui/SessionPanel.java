package org.openshell.ui;

import com.formdev.flatlaf.FlatClientProperties;
import org.openshell.model.HostConfig;
import org.openshell.session.SessionController;
import org.openshell.session.SessionState;
import org.openshell.storage.UiPreferences;
import org.openshell.ssh.ServerMonitor;
import org.openshell.ssh.ReconnectingSftpClient;
import org.openshell.ssh.SftpClient;
import org.openshell.ssh.SshSession;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.BiConsumer;

/**
 * 综合会话控制台 (FinalShell 核心经典布局)
 * 1. 顶部：硬件实时探针仪表盘 (CPU / 内存 / 磁盘 / 动态网络吞吐)
 * 2. 中部：FinalShell 旗舰级综合工作台 (上终端+命令编辑器，下双栏 SFTP 文件树与表格)
 * 3. 多功能拓展标签：全屏终端、全屏SFTP、进程管理、端口监听、SSH 端口转发与安全隧道
 */
public class SessionPanel extends JPanel implements ThemeAware {
    private final HostConfig hostConfig;
    private final BiConsumer<SftpClient, String> editorConsumer;
    private final ReconnectingSftpClient editorClient = new ReconnectingSftpClient();
    private TerminalPanel.DraftState pendingDraft;
    private SshSession sshSession;
    private ServerMonitor monitor;
    private MonitorPanel monitorPanel;
    private TerminalPanel terminalPanel;
    private ProcessManagerPanel processManagerPanel;
    private PortMonitorPanel portMonitorPanel;
    private PortForwardPanel portForwardPanel;
    private SftpPanel sftpPanel;
    private TransferPanel transferPanel;
    private JSplitPane workbenchSplitPane;
    private final JLabel statusLabel;
    private JTabbedPane toolTabs;
    private final SessionController controller;
    private final JButton retryButton = new JButton("重连");
    private final JButton cancelButton = new JButton("取消连接");
    private final JPanel connectionBar;
    private final JComboBox<String> layoutMode = new JComboBox<>(new String[]{"终端 + 文件", "专注终端", "专注文件"});
    private final UiPreferences preferences = UiPreferences.current();
    private final JCheckBox showMonitor = new JCheckBox("监控");
    private double terminalRatio;
    private boolean restoringLayout;
    private final DebouncedSave viewSave = new DebouncedSave(this::saveView);
    private Runnable stateListener = () -> {};

    public SessionPanel(HostConfig hostConfig, BiConsumer<SftpClient, String> editorConsumer) {
        this(hostConfig, editorConsumer, null);
    }

    SessionPanel(HostConfig hostConfig, BiConsumer<SftpClient, String> editorConsumer,
                 java.util.function.Supplier<SshSession> connectionFactory) {
        this.hostConfig = hostConfig.copy();
        var saved = preferences.snapshot().view(hostConfig.getId());
        terminalRatio = saved.terminalRatio;
        layoutMode.setSelectedIndex(saved.layoutMode);
        showMonitor.setSelected(saved.monitorVisible); showMonitor.setOpaque(false);
        this.editorConsumer = editorConsumer;
        setLayout(new BorderLayout());
        setBackground(ThemeUtils.getBg());

        statusLabel = new JLabel("正在连接", SwingConstants.LEFT);
        statusLabel.setToolTipText(hostConfig.getHost() + ":" + hostConfig.getPort());
        statusLabel.setPreferredSize(new Dimension(com.formdev.flatlaf.util.UIScale.scale(66), 0));
        statusLabel.setFont(ThemeUtils.uiFont(Font.PLAIN, 11));
        statusLabel.setHorizontalAlignment(SwingConstants.LEFT);
        statusLabel.setForeground(ThemeUtils.getTextSecondary());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.setOpaque(false);
        layoutMode.setFont(ThemeUtils.uiFont(Font.PLAIN, 11));
        layoutMode.addActionListener(event -> { applyLayoutMode(); viewSave.schedule(); });
        showMonitor.addActionListener(event -> {
            if (monitorPanel != null) monitorPanel.setVisible(showMonitor.isSelected());
            updateMonitoring(); viewSave.schedule(); revalidate();
        });
        actions.add(showMonitor); actions.add(layoutMode); actions.add(retryButton); actions.add(cancelButton);
        connectionBar = new SessionHeader(statusLabel, actions);
        add(connectionBar, BorderLayout.NORTH);
        controller = new SessionController(
                connectionFactory != null ? connectionFactory : () -> new SshSession(this.hostConfig,
                        (host, algorithm, fingerprint) -> HostKeyDialog.confirm(this, host, algorithm, fingerprint)),
                SwingUtilities::invokeLater, this::onSessionState);
        retryButton.addActionListener(event -> reconnect());
        ThemeUtils.toolbarButton(retryButton); ThemeUtils.toolbarButton(cancelButton);
        retryButton.setToolTipText("重新连接此会话");
        connectionBar.setBackground(ThemeUtils.getHeaderBg());
        connectionBar.setBorder(BorderFactory.createCompoundBorder(ThemeUtils.createMatteBorder(0, 0, 1, 0),
                new EmptyBorder(2, 8, 2, 4)));
        cancelButton.addActionListener(event -> controller.cancel());
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0) updateMonitoring();
        });
        controller.connect();
    }

    private void onSessionState(SessionController.Snapshot snapshot) {
        statusLabel.setText(snapshot.state() == SessionState.CONNECTED ? "已连接" : snapshot.state().label());
        statusLabel.setToolTipText(hostConfig.getHost() + ":" + hostConfig.getPort() + " · " + snapshot.message());
        statusLabel.setForeground(snapshot.state() == SessionState.CONNECTED ? ThemeUtils.getSuccess()
                : snapshot.state() == SessionState.FAILED ? ThemeUtils.getDanger() : ThemeUtils.getTextSecondary());
        boolean pending = snapshot.state() == SessionState.CONNECTING || snapshot.state() == SessionState.RECONNECTING;
        cancelButton.setVisible(pending);
        retryButton.setVisible(!pending && snapshot.state() != SessionState.CLOSED);
        layoutMode.setEnabled(snapshot.state() == SessionState.CONNECTED);
        if (snapshot.state() == SessionState.CONNECTED) {
            sshSession = snapshot.session();
            monitor = new ServerMonitor(sshSession.getRawSession());
            hostConfig.setLastConnected(System.currentTimeMillis());
            buildWorkbench();
        } else if (snapshot.state() == SessionState.DISCONNECTED || snapshot.state() == SessionState.FAILED) {
            editorClient.suspend();
            if (monitor != null) monitor.stop();
            if (transferPanel != null) transferPanel.close();
            if (processManagerPanel != null) processManagerPanel.close();
            if (portMonitorPanel != null) portMonitorPanel.close();
            if (sftpPanel != null) sftpPanel.onDisconnected();
            if (monitorPanel != null) { monitorPanel.bindMonitor(null); monitorPanel.onStatus(false, "连接已断开 · " + snapshot.message()); }
            if (terminalPanel != null) terminalPanel.setDisconnected(snapshot.message());
        }
        stateListener.run();
    }

    private void buildWorkbench() {
        removeAll();
        monitorPanel = new MonitorPanel();
        monitorPanel.setVisible(showMonitor.isSelected());
        monitorPanel.bindMonitor(monitor);
        updateMonitoring();
        connectionBar.add(monitorPanel, BorderLayout.CENTER);
        add(connectionBar, BorderLayout.NORTH);

        SshSession activeSession = sshSession;
        editorClient.bind(activeSession.getRawSession());
        terminalPanel = new TerminalPanel(activeSession,
                () -> controller.disconnected(activeSession, "终端连接已结束或中断"));
        if (pendingDraft != null && terminalPanel.restoreDraft(pendingDraft)) pendingDraft = null;
        terminalPanel.setMinimumSize(new Dimension(0, 120));
        sftpPanel = new SftpPanel(activeSession,
                editorConsumer == null ? null : (client, path) -> editorConsumer.accept(editorClient, path),
                terminalPanel::stageCommand);
        transferPanel = new TransferPanel(activeSession.getRawSession());
        processManagerPanel = new ProcessManagerPanel(activeSession.getRawSession());
        portMonitorPanel = new PortMonitorPanel(activeSession.getRawSession());
        portForwardPanel = new PortForwardPanel(activeSession.getRawSession());
        toolTabs = new JTabbedPane();
        toolTabs.putClientProperty(FlatClientProperties.TABBED_PANE_SHOW_TAB_SEPARATORS, true);
        toolTabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_HEIGHT, 26);
        toolTabs.setBorder(ThemeUtils.createMatteBorder(1, 0, 0, 0));
        toolTabs.addTab("文件", sftpPanel);
        toolTabs.addTab("传输", transferPanel);
        toolTabs.addTab("进程", processManagerPanel);
        toolTabs.addTab("端口", portMonitorPanel);
        toolTabs.addTab("隧道", portForwardPanel);
        TransferPanel currentTransfers = transferPanel;
        SftpPanel currentFiles = sftpPanel;
        JTabbedPane currentTabs = toolTabs;
        TerminalPanel currentTerminal = terminalPanel;
        currentTerminal.onDirectoryChanged(currentFiles::onTerminalDirectory);
        currentFiles.setDirectoryIntegrationAction(currentTerminal::stageDirectoryIntegration);
        ProcessManagerPanel currentProcesses = processManagerPanel;
        portMonitorPanel.setProcessSelectionConsumer(pid -> { currentTabs.setSelectedComponent(currentProcesses); currentProcesses.showPid(pid); });
        currentFiles.setTransfers(currentTransfers, () -> currentTabs.setSelectedComponent(currentTransfers));
        currentTransfers.setFileActions(() -> currentTabs.setSelectedComponent(currentFiles), currentFiles::showUploadDialog);
        currentTransfers.onChanged(rows -> {
            if (transferPanel != currentTransfers) return;
            currentFiles.onTransfersChanged(rows);
            long pendingTransfers = rows.stream().filter(row -> !row.state().terminal()).count();
            currentTabs.setTitleAt(currentTabs.indexOfComponent(currentTransfers), pendingTransfers == 0 ? "传输" : "传输 (" + pendingTransfers + ")");
        });
        workbenchSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, terminalPanel, toolTabs);
        workbenchSplitPane.setResizeWeight(0.64);
        workbenchSplitPane.setDividerSize(5);
        workbenchSplitPane.putClientProperty(FlatClientProperties.STYLE,
                "style: plain; background: $ToolBar.background");
        workbenchSplitPane.setContinuousLayout(true);
        workbenchSplitPane.setBorder(null);
        add(workbenchSplitPane, BorderLayout.CENTER);
        JSplitPane split = workbenchSplitPane;
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) { restoreDivider(); }
        });
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, event -> {
            if (!restoringLayout && layoutMode.getSelectedIndex() == 0 && split.getHeight() > 200) {
                terminalRatio = Math.max(0.2, Math.min(0.85, split.getDividerLocation() / (double) (split.getHeight() - split.getDividerSize())));
                viewSave.schedule();
            }
        });
        split.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && split.isShowing()) {
                SwingUtilities.invokeLater(() -> {
                    if (workbenchSplitPane == split && layoutMode.getSelectedIndex() == 0) {
                        restoreDivider();
                    }
                });
            }
        });
        applyLayoutMode();
        revalidate(); repaint();
    }

    private void updateMonitoring() {
        if (monitor == null || monitorPanel == null) return;
        boolean active = isConnected() && isShowing() && showMonitor.isSelected();
        monitorPanel.setMonitoringEnabled(active);
        if (active) monitor.start(monitorPanel); else monitor.stop();
    }

    private void applyLayoutMode() {
        if (workbenchSplitPane == null || terminalPanel == null || toolTabs == null) return;
        restoringLayout = true;
        int mode = layoutMode.getSelectedIndex();
        workbenchSplitPane.setTopComponent(mode == 2 ? null : terminalPanel);
        workbenchSplitPane.setBottomComponent(mode == 1 ? null : toolTabs);
        workbenchSplitPane.setDividerSize(mode == 0 ? 5 : 0);
        restoringLayout = false;
        restoreDivider();
        revalidate(); repaint();
    }

    private void restoreDivider() {
        if (workbenchSplitPane == null || layoutMode.getSelectedIndex() != 0) return;
        int available = workbenchSplitPane.getHeight() - workbenchSplitPane.getDividerSize();
        if (available <= 0) return;
        int terminalMinimum = terminalPanel.getMinimumSize().height;
        int toolsMinimum = toolTabs.getMinimumSize().height;
        int desired = (int) Math.round(available * terminalRatio);
        // Preserve the saved ratio while temporarily making room for file controls and rows.
        int toolsSpace = Math.min(toolsMinimum, Math.max(0, available - 100));
        int terminalSpace = Math.min(terminalMinimum, available - toolsSpace);
        desired = Math.max(terminalSpace, Math.min(desired, available - toolsSpace));
        restoringLayout = true;
        workbenchSplitPane.setDividerLocation(desired);
        restoringLayout = false;
    }

    private void saveView() {
        if (!preferences.tryUpdate(state -> {
            var view = state.view(hostConfig.getId());
            view.layoutMode = layoutMode.getSelectedIndex(); view.terminalRatio = terminalRatio;
            view.monitorVisible = showMonitor.isSelected();
        })) statusLabel.setText(preferences.lastError());
    }

    public void reconnect() {
        if (!confirmStopTransfers()) return;
        if (terminalPanel != null) pendingDraft = terminalPanel.captureDraft();
        viewSave.flush();
        cleanupViews();
        removeAll();
        add(connectionBar, BorderLayout.NORTH);
        revalidate(); repaint();
        controller.connect();
    }

    private void cleanupViews() {
        editorClient.suspend();
        if (transferPanel != null) { transferPanel.close(); transferPanel = null; }
        if (processManagerPanel != null) { processManagerPanel.close(); processManagerPanel = null; }
        if (portMonitorPanel != null) { portMonitorPanel.close(); portMonitorPanel = null; }
        if (monitor != null) { monitor.stop(); monitor = null; }
        if (monitorPanel != null) { monitorPanel.close(); connectionBar.remove(monitorPanel); monitorPanel = null; }
        if (terminalPanel != null) { terminalPanel.close(); terminalPanel = null; }
        if (sftpPanel != null) { sftpPanel.close(); sftpPanel = null; }
        workbenchSplitPane = null;
        PortForwardPanel oldForwarding = portForwardPanel;
        portForwardPanel = null;
        if (oldForwarding != null) {
            Thread cleanup = new Thread(oldForwarding::stopAll, "OpenShell-TunnelCleanup");
            cleanup.setDaemon(true); cleanup.start();
        }
    }

    public void close() {
        viewSave.flush();
        pendingDraft = null;
        editorClient.close();
        controller.close();
        cleanupViews();
    }

    public boolean confirmStopTransfers() { return transferPanel == null || transferPanel.confirmStopPending(); }

    public boolean ownsClient(SftpClient client) {
        return client != null && (client == editorClient
                || sshSession != null && client.usesSession(sshSession.getRawSession()));
    }

    public boolean isConnected() { return controller.getState() == SessionState.CONNECTED; }
    public SessionState getSessionState() { return controller.getState(); }
    public void setStateListener(Runnable listener) { stateListener = listener; }

    public HostConfig getHostConfig() {
        return hostConfig;
    }

    /** Keep all readings on narrow windows by moving controls above the strip. */
    private final class SessionHeader extends JPanel {
        private final JComponent state, controls;
        private final JButton compactActions = new JButton("选项");
        SessionHeader(JComponent state, JComponent controls) {
            super(null); this.state = state; this.controls = controls;
            ThemeUtils.toolbarButton(compactActions);
            compactActions.setToolTipText("监控、布局与重新连接");
            compactActions.addActionListener(event -> {
                JPopupMenu menu = new JPopupMenu();
                JCheckBoxMenuItem monitor = new JCheckBoxMenuItem("显示系统监控", showMonitor.isSelected());
                monitor.addActionListener(e -> showMonitor.doClick()); menu.add(monitor);
                JMenu layout = new JMenu("工作区布局");
                for (int i = 0; i < layoutMode.getItemCount(); i++) {
                    int mode = i;
                    JRadioButtonMenuItem item = new JRadioButtonMenuItem(layoutMode.getItemAt(i), layoutMode.getSelectedIndex() == i);
                    item.setEnabled(layoutMode.isEnabled());
                    item.addActionListener(e -> layoutMode.setSelectedIndex(mode)); layout.add(item);
                }
                menu.add(layout); menu.addSeparator();
                JMenuItem retry = new JMenuItem(cancelButton.isVisible() ? "取消连接" : "重新连接");
                retry.addActionListener(e -> { if (cancelButton.isVisible()) cancelButton.doClick(); else retryButton.doClick(); });
                menu.add(retry); menu.show(compactActions, 0, compactActions.getHeight());
            });
            compactActions.setVisible(false);
            add(state); add(controls); add(compactActions);
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override public void componentResized(java.awt.event.ComponentEvent event) { revalidate(); }
            });
        }
        private MonitorPanel metrics() {
            for (Component c : getComponents()) if (c instanceof MonitorPanel monitor && c.isVisible()) return monitor;
            return null;
        }
        private boolean stacked(MonitorPanel monitor) {
            Insets insets = getInsets();
            return monitor != null && getWidth() > 0 && getWidth() - insets.left - insets.right
                    < state.getPreferredSize().width + actions(monitor).getPreferredSize().width
                    + monitor.getMinimumSize().width + com.formdev.flatlaf.util.UIScale.scale(16);
        }
        private JComponent actions(MonitorPanel monitor) {
            Insets insets = getInsets();
            boolean compact = monitor != null && getWidth() > 0 && getWidth() - insets.left - insets.right
                    < state.getPreferredSize().width + controls.getPreferredSize().width
                    + monitor.getMinimumSize().width + com.formdev.flatlaf.util.UIScale.scale(16);
            return compact ? compactActions : controls;
        }
        @Override public Dimension getPreferredSize() {
            MonitorPanel monitor = metrics(); Insets insets = getInsets();
            int controlHeight = Math.max(state.getPreferredSize().height, actions(monitor).getPreferredSize().height);
            int height = monitor == null ? controlHeight : stacked(monitor) ? controlHeight + monitor.getPreferredSize().height
                    : Math.max(controlHeight, monitor.getPreferredSize().height);
            return new Dimension(state.getPreferredSize().width + controls.getPreferredSize().width
                    + (monitor == null ? 0 : monitor.getPreferredSize().width) + insets.left + insets.right,
                    height + insets.top + insets.bottom);
        }
        @Override public void doLayout() {
            MonitorPanel monitor = metrics(); Insets insets = getInsets();
            int width = Math.max(0, getWidth() - insets.left - insets.right);
            int height = Math.max(0, getHeight() - insets.top - insets.bottom);
            JComponent activeActions = actions(monitor);
            compactActions.setVisible(activeActions == compactActions); controls.setVisible(activeActions == controls);
            int stateWidth = state.getPreferredSize().width, controlWidth = activeActions.getPreferredSize().width;
            boolean stack = stacked(monitor);
            int controlHeight = stack ? activeActions.getPreferredSize().height : height;
            state.setBounds(insets.left, insets.top, stateWidth, controlHeight);
            int actionHeight = activeActions.getPreferredSize().height;
            activeActions.setBounds(insets.left + width - controlWidth,
                    insets.top + (stack ? 0 : Math.max(0, (height - actionHeight) / 2)), controlWidth, actionHeight);
            if (monitor != null) {
                int gap = com.formdev.flatlaf.util.UIScale.scale(8);
                if (stack) monitor.setBounds(insets.left, insets.top + controlHeight, width, height - controlHeight);
                else monitor.setBounds(insets.left + stateWidth + gap, insets.top, Math.max(0, width - stateWidth - controlWidth - 2 * gap), height);
            }
        }
    }

    @Override
    public void applyTheme() {
        setBackground(ThemeUtils.getBg());
        connectionBar.setBackground(ThemeUtils.getHeaderBg());
        if (toolTabs != null) toolTabs.setBorder(ThemeUtils.createMatteBorder(1, 0, 0, 0));
        connectionBar.setBorder(BorderFactory.createCompoundBorder(ThemeUtils.createMatteBorder(0, 0, 1, 0),
                new EmptyBorder(2, 8, 2, 4)));
        statusLabel.setForeground(isConnected() ? ThemeUtils.getSuccess()
                : controller.getState() == SessionState.FAILED ? ThemeUtils.getDanger() : ThemeUtils.getTextSecondary());
        if (monitorPanel != null) {
            monitorPanel.applyTheme();
        }
        if (terminalPanel != null) {
            terminalPanel.applyTheme();
        }
        if (sftpPanel != null) {
            sftpPanel.applyTheme();
        }
        if (transferPanel != null) transferPanel.applyTheme();
        if (processManagerPanel != null) {
            processManagerPanel.applyTheme();
        }
        if (portMonitorPanel != null) {
            portMonitorPanel.applyTheme();
        }
        if (portForwardPanel != null) {
            portForwardPanel.applyTheme();
        }
        repaint();
    }
}
