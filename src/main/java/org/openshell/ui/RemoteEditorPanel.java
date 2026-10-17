package org.openshell.ui;

import com.formdev.flatlaf.FlatClientProperties;
import org.openshell.ssh.SftpClient;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 远程文本与代码在线编辑器 (FinalShell 核心特色)
 * 具备自适应主题配色、语法字体、一键保存并自动上传回服务器
 */
public class RemoteEditorPanel extends JPanel implements ThemeAware {
    private final SftpClient sftpClient;
    private final String remoteFilePath;
    private final String fileName;
    private final JTextArea editorArea;
    private final JLabel statusLabel;
    private final JPanel topBar;
    private final JPanel bottomBar;
    private final JLabel fileTag;
    private File tempLocalFile;
    private boolean isSaving;
    private boolean loaded, loading, modified, closed;
    private String savedText = "";
    private JButton saveBtn, reloadBtn, saveAsBtn;
    private SftpClient.RemoteFileSnapshot remoteVersion;
    private final EditorDialogs dialogs;

    enum ConflictChoice { COMPARE, RELOAD, SAVE_AS, OVERWRITE, CANCEL }
    interface EditorDialogs {
        ConflictChoice conflict(Component parent, String path, boolean deleted);
        boolean discard(Component parent);
        String saveAs(Component parent, String path);
        void compare(Component parent, String local, String remote);
    }
    private static final EditorDialogs SWING_DIALOGS = new EditorDialogs() {
        public ConflictChoice conflict(Component parent, String path, boolean deleted) {
            Object[] options = {"比较内容", "重新载入", "另存为副本", deleted ? "确认重新创建" : "覆盖远端", "取消"};
            int selected = JOptionPane.showOptionDialog(parent,
                    (deleted ? "远端文件已被删除。" : "远端文件已被其他程序修改。") + "\n本地修改已保留：" + path,
                    "保存冲突", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[4]);
            return selected >= 0 && selected < 4 ? ConflictChoice.values()[selected] : ConflictChoice.CANCEL;
        }
        public boolean discard(Component parent) {
            return JOptionPane.showConfirmDialog(parent, "重新载入会放弃未保存的修改，是否继续？", "重新载入",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
        }
        public String saveAs(Component parent, String path) {
            return JOptionPane.showInputDialog(parent, "远端副本完整路径（原编辑文件保持不变）：", path + ".copy");
        }
        public void compare(Component parent, String local, String remote) {
            JPanel comparison = new JPanel(new GridLayout(1, 2, 8, 0));
            for (String[] side : new String[][]{{"本地修改（保留）", local}, {"当前远端", remote}}) {
                JTextArea text = new JTextArea(side[1]); text.setEditable(false); text.setFont(ThemeUtils.monoFont(Font.PLAIN, 12));
                JPanel pane = new JPanel(new BorderLayout(0, 4)); pane.add(new JLabel(side[0]), BorderLayout.NORTH);
                pane.add(new JScrollPane(text), BorderLayout.CENTER); comparison.add(pane);
            }
            comparison.setPreferredSize(new Dimension(860, 440));
            JOptionPane.showMessageDialog(parent, comparison, "比较本地与远端内容", JOptionPane.PLAIN_MESSAGE);
        }
    };

    public RemoteEditorPanel(SftpClient sftpClient, String remoteFilePath) {
        this(sftpClient, remoteFilePath, SWING_DIALOGS);
    }

    RemoteEditorPanel(SftpClient sftpClient, String remoteFilePath, EditorDialogs dialogs) {
        this.sftpClient = sftpClient;
        this.dialogs = dialogs;
        this.remoteFilePath = remoteFilePath;
        this.fileName = new File(remoteFilePath).getName();

        setLayout(new BorderLayout());
        setBackground(ThemeUtils.getBg());

        // 1. 顶部操作栏
        topBar = new JPanel(new BorderLayout(8, 0));
        topBar.setBackground(ThemeUtils.getHeaderBg());
        topBar.setBorder(BorderFactory.createCompoundBorder(
                ThemeUtils.createMatteBorder(0, 0, 1, 0),
                new EmptyBorder(6, 12, 6, 12)
        ));

        fileTag = new JLabel("" + remoteFilePath);
        fileTag.setFont(ThemeUtils.uiFont(Font.BOLD, 12));
        fileTag.setForeground(ThemeUtils.getAccent());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);

        saveBtn = new JButton("保存并上传 (Ctrl+S)");
        saveBtn.setFont(ThemeUtils.uiFont(Font.BOLD, 12));
        saveBtn.setForeground(ThemeUtils.getAccent());
        saveBtn.putClientProperty(FlatClientProperties.STYLE, "arc: 4;");
        saveBtn.addActionListener(e -> saveRemote());

        reloadBtn = new JButton("↻ 重新载入");
        reloadBtn.putClientProperty(FlatClientProperties.STYLE, "arc: 4;");
        reloadBtn.addActionListener(e -> {
            if (!modified || dialogs.discard(this)) loadContentAsync();
        });

        saveAsBtn = new JButton("另存为副本");
        saveAsBtn.addActionListener(e -> saveAsCopy());

        actions.add(saveBtn);
        actions.add(saveAsBtn);
        actions.add(reloadBtn);

        topBar.add(fileTag, BorderLayout.WEST);
        topBar.add(actions, BorderLayout.EAST);
        add(topBar, BorderLayout.NORTH);

        // 2. 中间代码编辑区
        editorArea = new JTextArea();
        editorArea.setBackground(ThemeUtils.getCardBg());
        editorArea.setForeground(ThemeUtils.getTextPrimary());
        editorArea.setCaretColor(ThemeUtils.getAccent());
        editorArea.setFont(ThemeUtils.monoFont(Font.PLAIN, 14));
        editorArea.setTabSize(4);
        editorArea.setBorder(new EmptyBorder(8, 12, 8, 12));

        // 注册 Ctrl+S 快捷键保存
        KeyStroke saveKey = KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK);
        editorArea.getInputMap().put(saveKey, "saveFile");
        editorArea.getActionMap().put("saveFile", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                saveRemote();
            }
        });

        JScrollPane scrollPane = new JScrollPane(editorArea);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);

        // 3. 底部状态栏
        bottomBar = new JPanel(new BorderLayout());
        bottomBar.setBackground(ThemeUtils.getHeaderBg());
        bottomBar.setBorder(BorderFactory.createCompoundBorder(
                ThemeUtils.createMatteBorder(1, 0, 0, 0),
                new EmptyBorder(4, 12, 4, 12)
        ));

        statusLabel = new JLabel("正在从服务器拉取文件内容...");
        statusLabel.setFont(ThemeUtils.uiFont(Font.PLAIN, 11));
        statusLabel.setForeground(ThemeUtils.getTextSecondary());
        bottomBar.add(statusLabel, BorderLayout.WEST);
        add(bottomBar, BorderLayout.SOUTH);

        editorArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent event) { updateModified(); }
            public void removeUpdate(javax.swing.event.DocumentEvent event) { updateModified(); }
            public void changedUpdate(javax.swing.event.DocumentEvent event) { updateModified(); }
        });
        loadContentAsync();
    }

    private void updateModified() {
        if (loading) return;
        modified = loaded && !editorArea.getText().equals(savedText);
        fileTag.setText((modified ? "* " : "") + remoteFilePath);
    }

    private void loadContentAsync() {
        if (closed || isSaving || loading) return;
        loading = true;
        setActionsEnabled(false); editorArea.setEditable(false);
        statusLabel.setText("正在拉取远程文件: " + remoteFilePath + " ...");
        Thread worker = new Thread(() -> {
            try {
                SftpClient.RemoteFileSnapshot version = sftpClient.readFileSnapshot(remoteFilePath);
                if (version == null) throw new java.io.FileNotFoundException("远端文件不存在");
                String content = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(version.content())).toString();
                File downloaded = File.createTempFile("openshell_edit_", ".txt");
                downloaded.deleteOnExit();
                Files.write(downloaded.toPath(), version.content());
                SwingUtilities.invokeLater(() -> {
                    if (closed) return;
                    tempLocalFile = downloaded;
                    remoteVersion = version;
                    editorArea.setText(content); editorArea.setCaretPosition(0);
                    savedText = content; loading = false; loaded = true;
                    updateModified();
                    editorArea.setEditable(true); setActionsEnabled(true);
                    statusLabel.setText(String.format("就绪 | %.2f KB | UTF-8 | %s", downloaded.length() / 1024.0, remoteFilePath));
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> {
                    if (closed) return;
                    loading = false; editorArea.setEditable(loaded); setActionsEnabled(true);
                    statusLabel.setText("拉取文件失败: " + error.getMessage());
                });
            }
        }, "OpenShell-EditorLoad");
        worker.setDaemon(true); worker.start();
    }

    private void saveRemote() { saveRemote(() -> {}); }
    private void saveRemote(Runnable onSuccess) {
        saveSnapshot(remoteFilePath, remoteVersion, false, onSuccess);
    }

    private void setActionsEnabled(boolean enabled) {
        saveBtn.setEnabled(enabled && loaded); saveAsBtn.setEnabled(enabled && loaded); reloadBtn.setEnabled(enabled);
    }

    private void saveAsCopy() {
        if (!loaded || isSaving || loading || closed) return;
        String destination = dialogs.saveAs(this, remoteFilePath);
        if (destination == null || destination.isBlank()) return;
        destination = destination.trim();
        if (!destination.startsWith("/") || destination.equals(remoteFilePath)) {
            statusLabel.setText("请输入不同于原文件的远端绝对路径"); return;
        }
        saveSnapshot(destination, null, true, () -> {});
    }

    private void saveSnapshot(String destination, SftpClient.RemoteFileSnapshot expected, boolean copy, Runnable onSuccess) {
        if (closed || isSaving || loading || !loaded || tempLocalFile == null) return;
        isSaving = true; setActionsEnabled(false);
        String snapshot = editorArea.getText();
        File local = tempLocalFile;
        statusLabel.setText("正在保存并上传…");
        Thread worker = new Thread(() -> {
            try {
                Files.writeString(local.toPath(), snapshot, StandardCharsets.UTF_8);
                String resolvedDestination = destination;
                if (copy) {
                    resolvedDestination = sftpClient.canonicalFilePath(destination);
                    String original = sftpClient.canonicalFilePath(remoteFilePath);
                    if (resolvedDestination.equals(original))
                        throw new java.io.IOException("另存为路径实际指向原文件，未保存；请选择其他副本路径");
                }
                SftpClient.RemoteFileSnapshot published = sftpClient.saveFileSafely(resolvedDestination, snapshot.getBytes(StandardCharsets.UTF_8), expected);
                SwingUtilities.invokeLater(() -> {
                    if (closed) return;
                    isSaving = false;
                    if (!copy) { savedText = snapshot; remoteVersion = published; }
                    updateModified(); setActionsEnabled(true);
                    statusLabel.setText(copy ? "已另存为 " + destination + "；原文件编辑状态保持不变"
                            : modified ? "已保存上次提交的内容；当前仍有新修改" : "已安全保存 · " + java.time.LocalTime.now().withNano(0));
                    onSuccess.run();
                });
            } catch (SftpClient.RemoteFileConflictException conflict) {
                SwingUtilities.invokeLater(() -> {
                    if (closed) return;
                    isSaving = false; setActionsEnabled(true);
                    statusLabel.setText("远端发生变化，未覆盖；本地修改已保留");
                    resolveConflict(destination, conflict.current(), copy, onSuccess);
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> {
                    if (closed) return;
                    isSaving = false; setActionsEnabled(true);
                    statusLabel.setText("保存失败，本地副本保留在 " + local.getAbsolutePath() + " · " + error.getMessage());
                });
            }
        }, "OpenShell-EditorSave");
        worker.setDaemon(true); worker.start();
    }

    private void resolveConflict(String destination, SftpClient.RemoteFileSnapshot current, boolean copy, Runnable onSuccess) {
        ConflictChoice choice;
        do {
            choice = dialogs.conflict(this, destination, current == null);
            if (choice == ConflictChoice.COMPARE)
                dialogs.compare(this, editorArea.getText(), current == null ? "（远端文件已删除）" : new String(current.content(), StandardCharsets.UTF_8));
        } while (choice == ConflictChoice.COMPARE && !closed);
        if (choice == ConflictChoice.OVERWRITE) saveSnapshot(destination, current, copy, onSuccess);
        else if (choice == ConflictChoice.SAVE_AS) saveAsCopy();
        else if (choice == ConflictChoice.RELOAD && !copy && (!modified || dialogs.discard(this))) loadContentAsync();
    }

    public void requestClose(Runnable onClosed) {
        if (isSaving) { statusLabel.setText("正在保存，请等待完成后关闭"); return; }
        if (modified) {
            int choice = JOptionPane.showOptionDialog(this, "文件有未保存的修改：" + remoteFilePath, "关闭文件",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                    new String[]{"保存并关闭", "放弃修改", "取消"}, "取消");
            if (choice == 0) { saveRemote(() -> { if (!modified) { closed = true; onClosed.run(); } }); return; }
            if (choice != 1) return;
        }
        closed = true; onClosed.run();
    }

    public SftpClient getSftpClient() { return sftpClient; }

    public String getFileName() {
        return fileName;
    }

    @Override
    public void applyTheme() {
        setBackground(ThemeUtils.getBg());
        if (topBar != null) {
            topBar.setBackground(ThemeUtils.getHeaderBg());
            topBar.setBorder(BorderFactory.createCompoundBorder(
                    ThemeUtils.createMatteBorder(0, 0, 1, 0),
                    new EmptyBorder(6, 12, 6, 12)
            ));
        }
        if (bottomBar != null) {
            bottomBar.setBackground(ThemeUtils.getHeaderBg());
            bottomBar.setBorder(BorderFactory.createCompoundBorder(
                    ThemeUtils.createMatteBorder(1, 0, 0, 0),
                    new EmptyBorder(4, 12, 4, 12)
            ));
        }
        if (fileTag != null) {
            fileTag.setForeground(ThemeUtils.getAccent());
        }
        if (editorArea != null) {
            editorArea.setBackground(ThemeUtils.getCardBg());
            editorArea.setForeground(ThemeUtils.getTextPrimary());
            editorArea.setCaretColor(ThemeUtils.getAccent());
        }
        if (statusLabel != null) {
            statusLabel.setForeground(ThemeUtils.getTextSecondary());
        }
        repaint();
    }
}
