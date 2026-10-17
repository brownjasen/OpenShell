package org.openshell.ui;

import com.formdev.flatlaf.FlatClientProperties;
import org.openshell.model.HostConfig;
import org.openshell.ssh.SshSession;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public class HostDialog extends JDialog {
    private final JTextField nameField = new JTextField(20);
    private final JTextField groupField = new JTextField(20);
    private final JTextField hostField = new JTextField(20);
    private final JTextField portField = new JTextField("22", 6);
    private final JTextField usernameField = new JTextField("root", 20);
    private final JComboBox<String> authTypeCombo = new JComboBox<>(new String[]{"密码认证", "公钥私钥认证"});
    private final JPasswordField passwordField = new JPasswordField(20);
    private final JTextField keyPathField = new JTextField(15);
    private final JPasswordField keyPassField = new JPasswordField(15);
    private final JTextArea remarksArea = new JTextArea(3, 20);

    private final JComboBox<String> charsetCombo = new JComboBox<>(new String[]{"UTF-8", "GBK", "GB2312", "ISO-8859-1"});
    private boolean saved = false;
    private final HostConfig config;

    public HostDialog(Frame owner, HostConfig existingConfig) {
        super(owner, existingConfig == null ? "新建服务器连接" : "编辑服务器连接", true);
        this.config = existingConfig != null ? existingConfig : new HostConfig();

        setLayout(new BorderLayout());
        setSize(500, 590);
        setLocationRelativeTo(owner);
        setResizable(false);

        // 样式配置
        nameField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "例如: 生产Web集群 / 数据库主库");
        nameField.putClientProperty(FlatClientProperties.STYLE, "arc: 8;");

        groupField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "默认分组");
        groupField.putClientProperty(FlatClientProperties.STYLE, "arc: 8;");

        hostField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "IP 地址或服务器域名");
        hostField.putClientProperty(FlatClientProperties.STYLE, "arc: 8;");

        portField.putClientProperty(FlatClientProperties.STYLE, "arc: 8;");
        usernameField.putClientProperty(FlatClientProperties.STYLE, "arc: 8;");

        passwordField.putClientProperty(FlatClientProperties.STYLE, "arc: 8; showRevealButton: true;");
        keyPathField.putClientProperty(FlatClientProperties.STYLE, "arc: 8;");
        keyPassField.putClientProperty(FlatClientProperties.STYLE, "arc: 8; showRevealButton: true;");

        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBorder(new EmptyBorder(18, 24, 18, 24));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(6, 6, 6, 6);

        int row = 0;
        addFormRow(formPanel, gbc, row++, "连接名称:", nameField);
        addFormRow(formPanel, gbc, row++, "分类分组:", groupField);
        addFormRow(formPanel, gbc, row++, "主机地址:", hostField);
        addFormRow(formPanel, gbc, row++, "端口号:", portField);
        addFormRow(formPanel, gbc, row++, "登录用户名:", usernameField);
        addFormRow(formPanel, gbc, row++, "认证方式:", authTypeCombo);
        addFormRow(formPanel, gbc, row++, "登录密码:", passwordField);

        // 私钥文件选择行
        JPanel keyPanel = new JPanel(new BorderLayout(6, 0));
        JButton browseBtn = new JButton("浏览...");
        browseBtn.putClientProperty(FlatClientProperties.STYLE, "arc: 8;");
        browseBtn.addActionListener(e -> chooseKeyFile());
        keyPanel.add(keyPathField, BorderLayout.CENTER);
        keyPanel.add(browseBtn, BorderLayout.EAST);
        addFormRow(formPanel, gbc, row++, "私钥路径:", keyPanel);
        addFormRow(formPanel, gbc, row++, "私钥密码:", keyPassField);

        addFormRow(formPanel, gbc, row++, "终端编码:", charsetCombo);

        remarksArea.setLineWrap(true);
        remarksArea.putClientProperty(FlatClientProperties.STYLE, "arc: 8;");
        addFormRow(formPanel, gbc, row++, "备注文档:", new JScrollPane(remarksArea));

        add(formPanel, BorderLayout.CENTER);

        // 底部按钮栏
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 14));
        btnPanel.setBackground(ThemeUtils.getHeaderBg());

        JButton testBtn = new JButton("测试连接");
        testBtn.putClientProperty(FlatClientProperties.STYLE, "arc: 8;");
        testBtn.addActionListener(e -> testConnection());

        JButton saveBtn = new JButton("保存连接");
        saveBtn.putClientProperty(FlatClientProperties.STYLE, "arc: 8;");
        saveBtn.setFont(ThemeUtils.uiFont(Font.BOLD, 12));
        saveBtn.addActionListener(e -> onSave());

        JButton cancelBtn = new JButton("取消");
        cancelBtn.putClientProperty(FlatClientProperties.STYLE, "arc: 8;");
        cancelBtn.addActionListener(e -> dispose());

        btnPanel.add(testBtn);
        btnPanel.add(saveBtn);
        btnPanel.add(cancelBtn);
        add(btnPanel, BorderLayout.SOUTH);

        authTypeCombo.addActionListener(e -> updateAuthFields());

        if (existingConfig != null) {
            nameField.setText(existingConfig.getName());
            groupField.setText(existingConfig.getGroup());
            hostField.setText(existingConfig.getHost());
            portField.setText(String.valueOf(existingConfig.getPort()));
            usernameField.setText(existingConfig.getUsername());
            authTypeCombo.setSelectedIndex("KEY".equalsIgnoreCase(existingConfig.getAuthType()) ? 1 : 0);
            passwordField.setText(existingConfig.getPassword() != null ? existingConfig.getPassword() : "");
            keyPathField.setText(existingConfig.getKeyPath() != null ? existingConfig.getKeyPath() : "");
            keyPassField.setText(existingConfig.getKeyPassphrase() != null ? existingConfig.getKeyPassphrase() : "");
            charsetCombo.setSelectedItem(existingConfig.getCharset());
            remarksArea.setText(existingConfig.getRemarks() != null ? existingConfig.getRemarks() : "");
        } else {
            groupField.setText("默认分组");
        }

        updateAuthFields();
    }

    private void addFormRow(JPanel panel, GridBagConstraints gbc, int row, String label, JComponent comp) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        JLabel l = new JLabel(label);
        l.setFont(ThemeUtils.uiFont(Font.PLAIN, 12));
        panel.add(l, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(comp, gbc);
    }

    private void updateAuthFields() {
        boolean isKey = authTypeCombo.getSelectedIndex() == 1;
        passwordField.setEnabled(!isKey);
        keyPathField.setEnabled(isKey);
        keyPassField.setEnabled(isKey);
    }

    private void chooseKeyFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择私钥文件 (如 id_rsa)");
        int res = chooser.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            keyPathField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private HostConfig buildConfigFromInput() {
        config.setName(nameField.getText().trim());
        config.setGroup(groupField.getText().trim());
        config.setHost(hostField.getText().trim());
        try {
            config.setPort(Integer.parseInt(portField.getText().trim()));
        } catch (Exception e) {
            config.setPort(22);
        }
        config.setUsername(usernameField.getText().trim());
        config.setAuthType(authTypeCombo.getSelectedIndex() == 1 ? "KEY" : "PASSWORD");
        config.setPassword(new String(passwordField.getPassword()));
        config.setKeyPath(keyPathField.getText().trim());
        config.setKeyPassphrase(new String(keyPassField.getPassword()));
        config.setRemarks(remarksArea.getText().trim());
        config.setCharset((String) charsetCombo.getSelectedItem());
        return config;
    }

    private void testConnection() {
        HostConfig testCfg = buildConfigFromInput();
        if (testCfg.getHost().isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入主机 IP 或域名", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JDialog loading = new JDialog(this, "正在测试连接...", true);
        loading.setSize(240, 90);
        loading.setLocationRelativeTo(this);
        loading.add(new JLabel("正在建立 SSH 连接，请稍候...", SwingConstants.CENTER));

        new Thread(() -> {
            try {
                SshSession session = new SshSession(testCfg,
                        (host, algorithm, fingerprint) -> HostKeyDialog.confirm(this, host, algorithm, fingerprint));
                try { session.connect(); } finally { session.disconnect(); }
                SwingUtilities.invokeLater(() -> {
                    loading.dispose();
                    JOptionPane.showMessageDialog(this, "连接成功！SSH 认证通过。", "成功", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    loading.dispose();
                    JOptionPane.showMessageDialog(this, "连接失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                });
            }
        }).start();

        loading.setVisible(true);
    }

    private void onSave() {
        if (hostField.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "主机地址不能为空！", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        buildConfigFromInput();
        saved = true;
        dispose();
    }

    public boolean isSaved() {
        return saved;
    }

    public HostConfig getConfig() {
        return config;
    }
}
