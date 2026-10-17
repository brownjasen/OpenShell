package org.openshell.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.jcraft.jsch.Session;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * SSH 端口转发与安全隧道管理器 (FinalShell 核心特色功能)
 * 支持本地端口转发 (-L)、远程端口转发 (-R) ；SOCKS5 动态代理尚未实现
 */
public class PortForwardPanel extends JPanel implements ThemeAware {
    private final Session session;
    private final JTable table;
    private final DefaultTableModel tableModel;
    private final JLabel statusLabel;
    private final JPanel topBar;
    private final JPanel bottomBar;
    private final JLabel tipLabel;
    private final List<Rule> rules = new ArrayList<>();

    public static class Rule {
        String name;
        String type; // "本地转发 (Local -L)", "远程转发 (Remote -R)"
        String bindHost;
        int bindPort;
        String targetHost;
        int targetPort;
        boolean active = false;

        public Rule(String name, String type, String bindHost, int bindPort, String targetHost, int targetPort) {
            this.name = name;
            this.type = type;
            this.bindHost = bindHost == null || bindHost.isEmpty() ? "127.0.0.1" : bindHost;
            this.bindPort = bindPort;
            this.targetHost = targetHost == null || targetHost.isEmpty() ? "127.0.0.1" : targetHost;
            this.targetPort = targetPort;
        }
    }

    public PortForwardPanel(Session session) {
        this.session = session;
        setLayout(new BorderLayout());
        setBackground(ThemeUtils.getBg());

        // 1. 映射规则表格及模型
        String[] columns = {"状态", "规则名称", "转发模式", "监听/绑定端", "目标/远端服务", "说明"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return false;
            }
        };

        table = new JTable(tableModel);
        table.setRowHeight(com.formdev.flatlaf.util.UIScale.scale(28));
        table.setShowGrid(false);
        table.putClientProperty(FlatClientProperties.STYLE, "showHorizontalLines: true; intercellSpacing: 0,1;");
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setFont(ThemeUtils.uiFont(Font.BOLD, 12));
        table.setFont(ThemeUtils.uiFont(Font.PLAIN, 12));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        table.getColumnModel().getColumn(0).setPreferredWidth(80);
        table.getColumnModel().getColumn(1).setPreferredWidth(160);
        table.getColumnModel().getColumn(2).setPreferredWidth(140);
        table.getColumnModel().getColumn(3).setPreferredWidth(160);
        table.getColumnModel().getColumn(4).setPreferredWidth(160);
        table.getColumnModel().getColumn(5).setPreferredWidth(200);

        // 2. 顶部操作工具栏 (FinalShell 风格)
        topBar = new JPanel(new BorderLayout(8, 0));
        topBar.setBackground(ThemeUtils.getHeaderBg());
        topBar.setBorder(BorderFactory.createCompoundBorder(
                ThemeUtils.createMatteBorder(0, 0, 1, 0),
                new EmptyBorder(3, 6, 3, 6)
        ));

        JPanel leftActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        leftActions.setOpaque(false);

        JButton addBtn = createToolButton("新建", "新建端口转发/隧道规则");
        addBtn.setIcon(new UiIcons(UiIcons.Kind.PLUS));
        addBtn.addActionListener(e -> showAddRuleDialog(null));

        JButton editBtn = createToolButton("编辑", "修改选中的端口映射");
        editBtn.setIcon(new UiIcons(UiIcons.Kind.EDIT));
        editBtn.addActionListener(e -> {
            int selected = table.getSelectedRow();
            if (selected >= 0 && selected < rules.size()) {
                showAddRuleDialog(rules.get(selected));
            } else {
                JOptionPane.showMessageDialog(this, "请先在下方表格中选择一条映射规则", "提示", JOptionPane.INFORMATION_MESSAGE);
            }
        });

        JButton startBtn = createToolButton("启动", "建立所选隧道");
        startBtn.setIcon(new UiIcons(UiIcons.Kind.PLAY));
        startBtn.addActionListener(e -> toggleSelectedRule(true));

        JButton stopBtn = createToolButton("停止", "断开所选隧道");
        stopBtn.setIcon(new UiIcons(UiIcons.Kind.STOP));
        stopBtn.addActionListener(e -> toggleSelectedRule(false));

        JButton delBtn = createToolButton("删除", "删除所选映射");
        delBtn.setIcon(new UiIcons(UiIcons.Kind.DELETE));
        delBtn.addActionListener(e -> deleteSelectedRule());

        leftActions.add(addBtn);
        leftActions.add(editBtn);
        leftActions.add(startBtn);
        leftActions.add(stopBtn);
        leftActions.add(Box.createHorizontalStrut(10));
        leftActions.add(delBtn);

        tipLabel = new JLabel("本地 / 远程端口转发");
        tipLabel.setFont(ThemeUtils.uiFont(Font.PLAIN, 11));
        tipLabel.setForeground(ThemeUtils.getTextSecondary());

        topBar.add(leftActions, BorderLayout.WEST);

        add(topBar, BorderLayout.NORTH);

        // 状态列居中彩色渲染
        table.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean isSelected, boolean hasFocus, int r, int c) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(t, v, isSelected, hasFocus, r, c);
                l.setHorizontalAlignment(SwingConstants.CENTER);
                String val = String.valueOf(v);
                if (val.contains("运行中")) {
                    l.setForeground(ThemeUtils.getSuccess());
                    l.setFont(ThemeUtils.uiFont(Font.BOLD, 12));
                } else {
                    l.setForeground(ThemeUtils.getTextMuted());
                    l.setFont(ThemeUtils.uiFont(Font.PLAIN, 12));
                }
                return l;
            }
        });

        for (int column = 1; column < table.getColumnCount(); column++) {
            final boolean address = column == 3 || column == 4;
            table.getColumnModel().getColumn(column).setCellRenderer(new DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focused, int row, int col) {
                    super.getTableCellRendererComponent(t, value, selected, focused, row, col);
                    putClientProperty("html.disable", true);
                    setFont(address ? ThemeUtils.monoFont(Font.PLAIN, 12) : ThemeUtils.uiFont(Font.PLAIN, 12));
                    setToolTipText(String.valueOf(value)); return this;
                }
            });
        }
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(null);
        add(scrollPane, BorderLayout.CENTER);

        // 3. 底部状态栏
        bottomBar = new JPanel(new BorderLayout());
        bottomBar.setBackground(ThemeUtils.getHeaderBg());
        bottomBar.setBorder(BorderFactory.createCompoundBorder(
                ThemeUtils.createMatteBorder(1, 0, 0, 0),
                new EmptyBorder(4, 10, 4, 10)
        ));

        statusLabel = new JLabel("0 个运行中");
        statusLabel.setFont(ThemeUtils.uiFont(Font.PLAIN, 11));
        statusLabel.setForeground(ThemeUtils.getTextSecondary());
        bottomBar.add(statusLabel, BorderLayout.WEST);
        bottomBar.add(tipLabel, BorderLayout.EAST);
        add(bottomBar, BorderLayout.SOUTH);

        // 预设常用推荐示例 (如远程数据库转发)
        rules.add(new Rule("远程 MySQL 穿透", "本地转发 (Local -L)", "127.0.0.1", 33060, "127.0.0.1", 3306));
        rules.add(new Rule("远程 Redis 穿透", "本地转发 (Local -L)", "127.0.0.1", 16379, "127.0.0.1", 6379));
        refreshTable();
        applyTheme();
    }

    private JButton createToolButton(String text, String tip) {
        JButton btn = new JButton(text);
        btn.setFont(ThemeUtils.uiFont(Font.PLAIN, 11));
        btn.putClientProperty(FlatClientProperties.STYLE, "arc: 4; margin: 2,8,2,8;");
        ThemeUtils.toolbarButton(btn);
        btn.setToolTipText(tip);
        return btn;
    }

    private void refreshTable() {
        tableModel.setRowCount(0);
        int activeCount = 0;
        for (Rule r : rules) {
            String status = r.active ? "运行中" : "已停止";
            if (r.active) activeCount++;
            String desc = r.type.contains("Local")
                    ? "将本地 " + r.bindPort + " 转发至远程 " + r.targetHost + ":" + r.targetPort
                    : (r.type.contains("Remote")
                    ? "将远程 " + r.bindPort + " 转发至本地 " + r.targetHost + ":" + r.targetPort
                    : "在本地 " + r.bindPort + " 提供 SOCKS5 代理");

            tableModel.addRow(new Object[]{
                    status,
                    r.name,
                    r.type,
                    r.bindHost + ":" + r.bindPort,
                    r.type.contains("Dynamic") ? "SOCKS5 Proxy" : r.targetHost + ":" + r.targetPort,
                    desc
            });
        }
        statusLabel.setText(activeCount + " 个运行中 · " + rules.size() + " 条规则");
    }

    private void showAddRuleDialog(Rule existingRule) {
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this),
                existingRule == null ? "新建端口转发" : "编辑端口映射", true);
        dialog.setSize(480, 360);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout());

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(16, 20, 16, 20));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 6, 5, 6);

        JTextField nameField = new JTextField(existingRule != null ? existingRule.name : "Web服务映射", 15);
        JComboBox<String> typeCombo = new JComboBox<>(new String[]{
                "本地转发 (Local -L)", "远程转发 (Remote -R)"
        });
        if (existingRule != null) typeCombo.setSelectedItem(existingRule.type);

        JTextField bindHostField = new JTextField(existingRule != null ? existingRule.bindHost : "127.0.0.1", 12);
        JTextField bindPortField = new JTextField(existingRule != null ? String.valueOf(existingRule.bindPort) : "8080", 6);
        JTextField targetHostField = new JTextField(existingRule != null ? existingRule.targetHost : "127.0.0.1", 12);
        JTextField targetPortField = new JTextField(existingRule != null ? String.valueOf(existingRule.targetPort) : "80", 6);

        int row = 0;
        addFormRow(form, gbc, row++, "规则名称:", nameField);
        addFormRow(form, gbc, row++, "转发类型:", typeCombo);

        JPanel bindPanel = new JPanel(new BorderLayout(4, 0));
        bindPanel.add(bindHostField, BorderLayout.CENTER);
        bindPanel.add(new JLabel(":"), BorderLayout.EAST);
        JPanel bindWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        bindWrap.add(bindHostField);
        bindWrap.add(new JLabel(" : "));
        bindWrap.add(bindPortField);
        addFormRow(form, gbc, row++, "监听端 (IP:端口):", bindWrap);

        JPanel targetWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        targetWrap.add(targetHostField);
        targetWrap.add(new JLabel(" : "));
        targetWrap.add(targetPortField);
        addFormRow(form, gbc, row++, "目标端 (IP:端口):", targetWrap);

        typeCombo.addActionListener(e -> {
            boolean isDynamic = typeCombo.getSelectedIndex() == 2;
            targetHostField.setEnabled(!isDynamic);
            targetPortField.setEnabled(!isDynamic);
        });

        dialog.add(form, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        JButton saveBtn = new JButton("保存");
        saveBtn.putClientProperty(FlatClientProperties.STYLE, "arc: 6;");
        saveBtn.addActionListener(e -> {
            try {
                String name = nameField.getText().trim();
                String type = (String) typeCombo.getSelectedItem();
                String bHost = bindHostField.getText().trim();
                int bPort = Integer.parseInt(bindPortField.getText().trim());
                String tHost = targetHostField.getText().trim();
                int tPort = type.contains("Dynamic") ? 0 : Integer.parseInt(targetPortField.getText().trim());

                if (existingRule != null) {
                    if (existingRule.active) stopForwarding(existingRule);
                    existingRule.name = name;
                    existingRule.type = type;
                    existingRule.bindHost = bHost;
                    existingRule.bindPort = bPort;
                    existingRule.targetHost = tHost;
                    existingRule.targetPort = tPort;
                } else {
                    rules.add(new Rule(name, type, bHost, bPort, tHost, tPort));
                }
                refreshTable();
                dialog.dispose();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialog, "输入端口格式有误: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        });

        JButton cancelBtn = new JButton("取消");
        cancelBtn.putClientProperty(FlatClientProperties.STYLE, "arc: 6;");
        cancelBtn.addActionListener(e -> dialog.dispose());

        btnPanel.add(saveBtn);
        btnPanel.add(cancelBtn);
        dialog.add(btnPanel, BorderLayout.SOUTH);

        dialog.setVisible(true);
    }

    private void addFormRow(JPanel p, GridBagConstraints gbc, int row, String label, JComponent comp) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        JLabel l = new JLabel(label);
        l.setFont(ThemeUtils.uiFont(Font.PLAIN, 12));
        p.add(l, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        p.add(comp, gbc);
    }

    private void toggleSelectedRule(boolean start) {
        int idx = table.getSelectedRow();
        if (idx < 0 || idx >= rules.size()) {
            JOptionPane.showMessageDialog(this, "请选择一条规则进行操作", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        Rule r = rules.get(idx);
        if (start && r.active) {
            JOptionPane.showMessageDialog(this, "该隧道已处于启动状态", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!start && !r.active) {
            JOptionPane.showMessageDialog(this, "该隧道已处于停止状态", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        if (start) {
            startForwarding(r);
        } else {
            stopForwarding(r);
        }
        refreshTable();
    }

    private void startForwarding(Rule r) {
        if (session == null || !session.isConnected()) {
            JOptionPane.showMessageDialog(this, "SSH 会话未连接，无法建立隧道", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            if (r.type.contains("Local")) {
                session.setPortForwardingL(r.bindHost, r.bindPort, r.targetHost, r.targetPort);
            } else if (r.type.contains("Remote")) {
                session.setPortForwardingR(r.bindHost, r.bindPort, r.targetHost, r.targetPort);
            } else if (r.type.contains("Dynamic")) {
                throw new UnsupportedOperationException("SOCKS5 动态代理尚未实现");
            }
            r.active = true;
            JOptionPane.showMessageDialog(this, "隧道启动成功！\n" + r.name + " (" + r.bindHost + ":" + r.bindPort + ")", "成功", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "启动隧道失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void stopForwarding(Rule r) {
        if (session != null && session.isConnected()) {
            try {
                if (r.type.contains("Local") || r.type.contains("Dynamic")) {
                    session.delPortForwardingL(r.bindHost, r.bindPort);
                } else if (r.type.contains("Remote")) {
                    session.delPortForwardingR(r.bindPort);
                }
            } catch (Exception ignored) {}
        }
        r.active = false;
    }

    private void deleteSelectedRule() {
        int idx = table.getSelectedRow();
        if (idx >= 0 && idx < rules.size()) {
            Rule r = rules.get(idx);
            if (r.active) stopForwarding(r);
            rules.remove(idx);
            refreshTable();
        }
    }

    public void stopAll() {
        for (Rule r : rules) {
            if (r.active) {
                stopForwarding(r);
            }
        }
    }

    @Override
    public void applyTheme() {
        setBackground(ThemeUtils.getBg());
        if (topBar != null) {
            topBar.setBackground(ThemeUtils.getHeaderBg());
            topBar.setBorder(BorderFactory.createCompoundBorder(
                    ThemeUtils.createMatteBorder(0, 0, 1, 0),
                    new EmptyBorder(3, 6, 3, 6)
            ));
        }
        if (bottomBar != null) {
            bottomBar.setBackground(ThemeUtils.getHeaderBg());
            bottomBar.setBorder(BorderFactory.createCompoundBorder(
                    ThemeUtils.createMatteBorder(1, 0, 0, 0),
                    new EmptyBorder(4, 10, 4, 10)
            ));
        }
        if (tipLabel != null) {
            tipLabel.setForeground(ThemeUtils.getTextSecondary());
        }
        if (statusLabel != null) {
            statusLabel.setForeground(ThemeUtils.getTextSecondary());
        }
        if (table != null) {
            table.setBackground(ThemeUtils.getCardBg());
            table.setForeground(ThemeUtils.getTextPrimary());
            table.getTableHeader().setBackground(ThemeUtils.getHeaderBg());
            table.getTableHeader().setForeground(ThemeUtils.getTextPrimary());
            table.setSelectionBackground(ThemeUtils.getSelectionBg());
            table.setSelectionForeground(ThemeUtils.isDark() ? Color.WHITE : ThemeUtils.getTextPrimary());
        }
        repaint();
    }
}
