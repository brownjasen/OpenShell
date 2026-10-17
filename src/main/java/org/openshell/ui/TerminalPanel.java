package org.openshell.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.TerminalCopyPasteHandler;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalAction;
import com.jediterm.terminal.ui.TerminalActionPresentation;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import org.openshell.ssh.SshSession;
import org.openshell.terminal.SshTtyConnector;
import org.openshell.terminal.DirectoryTracker;
import org.openshell.terminal.RemotePathCompletion;
import org.openshell.storage.UiPreferences;
import org.openshell.storage.WorkspaceState;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** Real xterm emulation with a separate local command editor. */
public class TerminalPanel extends JPanel implements ThemeAware {
    public enum InputTarget { TERMINAL, SINGLE_LINE, MULTILINE }
    public record TextState(String text, int dot, int mark) {
        public TextState {
            text = java.util.Objects.requireNonNullElse(text, "");
            dot = Math.max(0, Math.min(dot, text.length()));
            mark = Math.max(0, Math.min(mark, text.length()));
        }
    }
    /** In-memory reconnect state only. Draft text is never written to workspace preferences. */
    public record DraftState(TextState singleLine, TextState multiline, boolean multilineVisible,
                             int sendMode, boolean clearAfterSend, boolean ignoreEmpty,
                             List<String> history, int historyIndex, String historyDraft,
                             InputTarget preferredInput) {
        public DraftState {
            singleLine = java.util.Objects.requireNonNull(singleLine);
            multiline = java.util.Objects.requireNonNull(multiline);
            history = history == null ? List.of() : List.copyOf(history);
            historyIndex = Math.max(-1, Math.min(historyIndex, history.size()));
            historyDraft = java.util.Objects.requireNonNullElse(historyDraft, "");
            preferredInput = java.util.Objects.requireNonNullElse(preferredInput, InputTarget.TERMINAL);
            sendMode = Math.max(0, Math.min(1, sendMode));
        }
    }
    private final SshSession session;
    private final JediTermWidget terminal;
    private final SshTtyConnector connector;
    private final TerminalSettings settings = new TerminalSettings();
    private final JPanel toolbar = new JPanel(new BorderLayout(6, 0));
    private final JPanel commandBar = new JPanel(new BorderLayout(6, 0));
    private final JPanel multilinePanel = new JPanel(new BorderLayout());
    private final JTextField commandInput = new JTextField();
    private final JTextArea commandEditor = new JTextArea(5, 40);
    private final JLabel message = new JLabel(" ");
    private final JCheckBox clearAfterSend = new JCheckBox("发送后清空", true);
    private final JCheckBox ignoreEmpty = new JCheckBox("忽略空行", false);
    private final JComboBox<String> sendMode = new JComboBox<>(new String[]{"Ctrl+Enter 发送", "Enter 发送"});
    private final List<String> history = new ArrayList<>();
    private int historyIndex = -1;
    private String historyDraft = "";
    private boolean navigatingHistory;
    private boolean restoringDraft;
    private boolean sending;
    private boolean disconnected;
    private final List<JButton> sendButtons = new ArrayList<>();
    private final TerminalCopyPasteHandler clipboard;
    private Component preferredInput;
    private final UiPreferences preferences = UiPreferences.current();
    private final String hostKey;
    private boolean closed;
    private volatile String currentDirectory;
    private java.util.function.Consumer<String> directoryChanged = path -> {};
    private final RemotePathCompletion pathCompletion;
    private boolean completingPath;
    private final ExecutorService pathReads = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "OpenShell-PathCompletion"); thread.setDaemon(true); return thread;
    });
    private final ExecutorService writes = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "OpenShell-CommandWriter"); thread.setDaemon(true); return thread;
    });

    public TerminalPanel(SshSession session) { this(session, () -> {}); }
    public TerminalPanel(SshSession session, Runnable onDisconnected) {
        this(session, onDisconnected, () -> Toolkit.getDefaultToolkit().getSystemClipboard());
    }
    TerminalPanel(SshSession session, Runnable onDisconnected, java.util.function.Supplier<Clipboard> clipboardSource) {
        this.session = session;
        clipboard = new TerminalClipboard(clipboardSource, message::setText);
        hostKey = session.getConfig().getId();
        WorkspaceState saved = preferences.snapshot();
        settings.size = saved.fontSize;
        history.addAll(preferences.history(hostKey, ""));
        clearAfterSend.setSelected(saved.clearAfterSend); ignoreEmpty.setSelected(saved.ignoreEmpty);
        sendMode.setSelectedIndex(saved.sendMode);
        setLayout(new BorderLayout());
        pathCompletion = new RemotePathCompletion(session.getRawSession());
        connector = new SshTtyConnector(session, onDisconnected, path -> SwingUtilities.invokeLater(() -> {
            if (closed) return;
            currentDirectory = path; directoryChanged.accept(path);
        }));
        terminal = new JediTermWidget(120, 32, settings) {
            @Override protected StyleState createDefaultStyle() {
                StyleState style = new StyleState();
                style.setDefaultStyle(new TextStyle(settings.getDefaultForeground(), settings.getDefaultBackground()));
                return style;
            }
            @Override protected com.jediterm.terminal.ui.TerminalPanel createTerminalPanel(
                    SettingsProvider provider, StyleState style, TerminalTextBuffer buffer) {
                return new ScalablePanel(provider, buffer, style);
            }
        };
        terminal.setTtyConnector(connector);
        add(terminal, BorderLayout.CENTER);
        preferredInput = terminal.getTerminalPanel();
        for (Component input : List.of(terminal.getTerminalPanel(), commandInput, commandEditor)) {
            input.addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent event) { preferredInput = input; }
            });
        }
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                SwingUtilities.invokeLater(() -> {
                    if (closed || !isShowing()) return;
                    Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                    Container sessionView = (Container) SwingUtilities.getAncestorOfClass(SessionPanel.class, this);
                    if (owner == null || !SwingUtilities.isDescendingFrom(owner, sessionView == null ? this : sessionView))
                        preferredInput.requestFocusInWindow();
                });
            }
        });

        JLabel identity = new JLabel(session.getConfig().getUsername() + "@" + session.getConfig().getHost());
        identity.setFont(ThemeUtils.uiFont(Font.PLAIN, 11));
        toolbar.add(identity, BorderLayout.WEST);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        tools.setOpaque(false);
        tools.add(tool("查找", "查找终端输出 · Ctrl+Shift+F", () -> perform(terminal.getActions(), "查找")));
        tools.add(tool("复制", "复制选中文字 · Ctrl+Shift+C / Ctrl+Insert", () -> perform(terminal.getTerminalPanel().getActions(), "复制")));
        tools.add(tool("粘贴", "粘贴到终端 · Ctrl+Shift+V / Shift+Insert", () -> perform(terminal.getTerminalPanel().getActions(), "粘贴")));
        tools.add(tool("清屏", "清除本地回滚记录 · Ctrl+Shift+L；Shell 清屏使用 Ctrl+L", () -> perform(terminal.getTerminalPanel().getActions(), "清屏")));
        tools.add(button("到底部", () -> terminal.getTerminalPanel().getVerticalScrollModel().setValue(0)));
        tools.add(button("A−", () -> resizeFont(-1)));
        tools.add(button("A+", () -> resizeFont(1)));
        JButton shortcuts = button("快捷键", () -> {});
        shortcuts.addActionListener(event -> showShortcuts(shortcuts));
        tools.add(shortcuts);
        JLabel encoding = new JLabel(session.getConfig().getCharset());
        encoding.setToolTipText("编码使用连接配置；修改后重新连接生效");
        tools.add(encoding);
        toolbar.add(tools, BorderLayout.EAST);
        message.setHorizontalAlignment(SwingConstants.RIGHT);
        message.setBorder(new EmptyBorder(0, 6, 0, 6));
        message.addPropertyChangeListener("text", event -> message.setToolTipText(message.getText()));
        toolbar.add(message, BorderLayout.CENTER);
        add(toolbar, BorderLayout.NORTH);

        commandInput.setFont(ThemeUtils.uiFont(Font.PLAIN, 13));
        commandInput.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "本地命令草稿 · Enter 发送，↑/↓ 历史，F6 回终端");
        commandInput.setToolTipText("本地草稿：Ctrl+Space 补全远程路径，Alt+/ 搜索本地历史。终端内 Tab 补全和 Ctrl+C 由远端 Shell 处理。");
        commandInput.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void edited() { if (!navigatingHistory) { historyIndex = -1; historyDraft = commandInput.getText(); } }
            public void insertUpdate(javax.swing.event.DocumentEvent event) { edited(); }
            public void removeUpdate(javax.swing.event.DocumentEvent event) { edited(); }
            public void changedUpdate(javax.swing.event.DocumentEvent event) { edited(); }
        });
        commandInput.addActionListener(event -> {
            String text = commandInput.getText();
            send(text, () -> { if (commandInput.getText().equals(text)) commandInput.setText(""); });
        });
        bindHistory(KeyEvent.VK_UP, -1);
        bindHistory(KeyEvent.VK_DOWN, 1);
        for (JComponent input : List.of(commandInput, commandEditor)) {
            input.registerKeyboardAction(event -> focusTerminal(), KeyStroke.getKeyStroke("F6"), WHEN_FOCUSED);
            input.registerKeyboardAction(event -> focusTerminal(), KeyStroke.getKeyStroke("ESCAPE"), WHEN_FOCUSED);
        }
        commandInput.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, InputEvent.ALT_DOWN_MASK), "suggest");
        commandInput.getActionMap().put("suggest", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                JPopupMenu choices = new JPopupMenu(); String prefix = commandInput.getText();
                preferences.history(hostKey, prefix).stream().filter(command -> command.startsWith(prefix) && !command.contains("\n"))
                        .distinct().limit(20).forEach(command -> {
                            JMenuItem item = new JMenuItem(command); item.addActionListener(e -> commandInput.setText(command)); choices.add(item);
                        });
                if (choices.getComponentCount() > 0) choices.show(commandInput, 0, commandInput.getHeight());
                else message.setText("没有匹配的本地历史命令");
            }
        });
        commandInput.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK), "remote-path");
        commandInput.getActionMap().put("remote-path", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { completeRemotePath(); }
        });
        JPanel commandTools = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        commandTools.setOpaque(false);
        JButton quick = button("常用命令", () -> {});
        quick.addActionListener(event -> showQuickCommands(quick));
        commandTools.add(quick);
        JButton historyButton = button("历史", () -> {});
        historyButton.addActionListener(event -> showHistory(historyButton));
        commandTools.add(historyButton);
        commandTools.add(tool("补全", "补全草稿中的远程路径 · Ctrl+Space；只填入，不执行", this::completeRemotePath));
        commandBar.add(commandTools, BorderLayout.WEST);
        commandBar.add(commandInput, BorderLayout.CENTER);
        JPanel sendTools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        sendTools.setOpaque(false);
        sendTools.add(sendButton("发送", () -> commandInput.postActionEvent()));
        sendTools.add(button("多行", () -> {
            multilinePanel.setVisible(!multilinePanel.isVisible());
            persist(state -> state.multiline = multilinePanel.isVisible());
            revalidate();
            if (multilinePanel.isVisible()) commandEditor.requestFocusInWindow();
            else commandInput.requestFocusInWindow();
        }));
        commandBar.add(sendTools, BorderLayout.EAST);

        commandEditor.setFont(ThemeUtils.monoFont(Font.PLAIN, 13));
        commandEditor.setBorder(new EmptyBorder(4, 6, 4, 6));
        JPanel editorTools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        editorTools.setOpaque(false);
        editorTools.add(sendMode); editorTools.add(ignoreEmpty); editorTools.add(clearAfterSend);
        editorTools.add(sendButton("发送全部", this::sendEditor));
        editorTools.add(button("清空", () -> commandEditor.setText("")));
        multilinePanel.add(editorTools, BorderLayout.NORTH);
        multilinePanel.add(new JScrollPane(commandEditor), BorderLayout.CENTER);
        multilinePanel.setPreferredSize(new Dimension(0, com.formdev.flatlaf.util.UIScale.scale(130)));
        multilinePanel.setVisible(saved.multiline);
        clearAfterSend.addActionListener(event -> persist(state -> state.clearAfterSend = clearAfterSend.isSelected()));
        ignoreEmpty.addActionListener(event -> persist(state -> state.ignoreEmpty = ignoreEmpty.isSelected()));
        sendMode.addActionListener(event -> persist(state -> state.sendMode = sendMode.getSelectedIndex()));
        commandEditor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "send");
        commandEditor.getActionMap().put("send", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { sendEditor(); }
        });
        commandEditor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "enter");
        commandEditor.getActionMap().put("enter", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                if (sendMode.getSelectedIndex() == 1) sendEditor(); else commandEditor.replaceSelection("\n");
            }
        });
        commandEditor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "newline");
        commandEditor.getActionMap().put("newline", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { commandEditor.replaceSelection("\n"); }
        });
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(multilinePanel, BorderLayout.NORTH);
        bottom.add(commandBar, BorderLayout.CENTER);
        message.setFont(ThemeUtils.uiFont(Font.PLAIN, 10));
        add(bottom, BorderLayout.SOUTH);
        applyTheme();
        terminal.start();
    }

    private void sendEditor() {
        String original = commandEditor.getText();
        String text = ignoreEmpty.isSelected()
                ? original.lines().filter(line -> !line.isBlank()).collect(java.util.stream.Collectors.joining("\n")) : original;
        send(text, () -> {
            if (clearAfterSend.isSelected() && commandEditor.getText().equals(original)) commandEditor.setText("");
        });
    }
    public void executeCommandLine(String text) { send(text, () -> {}); }
    private void send(String text, Runnable onSuccess) {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> send(text, onSuccess)); return; }
        if (text == null || text.isBlank() || closed) return;
        if (!canSend()) { message.setText("连接已断开，草稿已保留；请重新连接后发送"); return; }
        if (sending) return;
        String command = text.replace("\r\n", "\n").replace('\r', '\n').replaceAll("\n+$", "");
        boolean record = preferences.snapshot().recordHistory;
        setSending(true);
        message.setText("发送中…");
        writes.execute(() -> {
            try {
                connector.write(command.replace('\n', '\r') + "\r");
                SwingUtilities.invokeLater(() -> {
                    if (closed) return;
                    setSending(false);
                    message.setText("已发送");
                    if (record) {
                        try { preferences.recordCommand(hostKey, command); refreshHistory(); }
                        catch (RuntimeException error) { message.setText("命令已发送，历史保存失败"); }
                    }
                    onSuccess.run();
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> {
                    if (!closed) { setSending(false); message.setText("发送失败，草稿已保留: " + error.getMessage()); }
                });
            }
        });
    }
    private void bindHistory(int key, int direction) {
        String name = "history" + direction;
        commandInput.getInputMap().put(KeyStroke.getKeyStroke(key, 0), name);
        commandInput.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                if (historyIndex == -1) { if (direction > 0) return; refreshHistory(); historyDraft = commandInput.getText(); historyIndex = history.size(); }
                if (history.isEmpty()) return;
                historyIndex = Math.max(0, Math.min(history.size(), historyIndex + direction));
                navigatingHistory = true;
                try { commandInput.setText(historyIndex == history.size() ? historyDraft : history.get(historyIndex)); }
                finally { navigatingHistory = false; }
            }
        });
    }
    private void refreshHistory() {
        history.clear(); history.addAll(preferences.history(hostKey, "")); historyIndex = -1;
    }
    private void showHistory(JButton owner) {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        JTextField filter = new JTextField();
        filter.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "搜索本机保存的命令历史");
        DefaultListModel<String> items = new DefaultListModel<>();
        JList<String> list = new JList<>(items);
        var renderer = new DefaultListCellRenderer(); renderer.putClientProperty("html.disable", true); list.setCellRenderer(renderer);
        Runnable render = () -> {
            items.clear(); var found = preferences.history(hostKey, filter.getText());
            for (int i = found.size() - 1; i >= 0; i--) items.addElement(found.get(i));
        };
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent event) { render.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent event) { render.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent event) { render.run(); }
        });
        JCheckBox record = new JCheckBox("记录本地命令输入框的历史", preferences.snapshot().recordHistory);
        record.setToolTipText("按主机保存在本机 workspace.json；敏感命令请先关闭记录，已有历史保留");
        JLabel historyStatus = new JLabel("历史以明文保存在本机，每台主机最多 1000 条");
        record.addActionListener(event -> {
            if (!preferences.tryUpdate(state -> state.recordHistory = record.isSelected())) {
                record.setSelected(preferences.snapshot().recordHistory); historyStatus.setText(preferences.lastError());
            } else historyStatus.setText(record.isSelected() ? "已开启历史记录" : "已关闭历史记录；已有历史保留");
        });
        JButton clear = button("清空此主机历史", () -> {
            if (preferences.tryUpdate(state -> state.histories.remove(hostKey))) { refreshHistory(); render.run(); historyStatus.setText("此主机历史已清空"); }
            else historyStatus.setText(preferences.lastError());
        });
        JPanel bottom = new JPanel(new BorderLayout()); bottom.add(record, BorderLayout.CENTER); bottom.add(clear, BorderLayout.EAST);
        bottom.add(historyStatus, BorderLayout.SOUTH);
        panel.add(filter, BorderLayout.NORTH); panel.add(new JScrollPane(list), BorderLayout.CENTER); panel.add(bottom, BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(580, 300)); render.run();
        if (JOptionPane.showConfirmDialog(this, panel, "命令历史 · 选中后填入输入框", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION && list.getSelectedValue() != null) stageCommand(list.getSelectedValue());
    }

    /** EDT only; captures text, selection direction and local command-editor behavior. */
    public DraftState captureDraft() {
        requireDraftEdt();
        InputTarget input = preferredInput == commandInput ? InputTarget.SINGLE_LINE
                : preferredInput == commandEditor ? InputTarget.MULTILINE : InputTarget.TERMINAL;
        return new DraftState(textState(commandInput), textState(commandEditor), multilinePanel.isVisible(),
                sendMode.getSelectedIndex(), clearAfterSend.isSelected(), ignoreEmpty.isSelected(),
                history, historyIndex, historyDraft, input);
    }
    /** EDT only. New input wins; restoration performs no send, focus request or persistence. */
    public boolean restoreDraft(DraftState saved) {
        requireDraftEdt();
        if (closed || saved == null) return false;
        if (!commandInput.getText().isEmpty() || !commandEditor.getText().isEmpty()) {
            message.setText("输入框已有新草稿，未覆盖");
            return false;
        }
        restoringDraft = true;
        boolean previousNavigation = navigatingHistory;
        navigatingHistory = true;
        try {
            restoreText(commandInput, saved.singleLine()); restoreText(commandEditor, saved.multiline());
            multilinePanel.setVisible(saved.multilineVisible());
            sendMode.setSelectedIndex(saved.sendMode());
            clearAfterSend.setSelected(saved.clearAfterSend()); ignoreEmpty.setSelected(saved.ignoreEmpty());
            history.clear(); history.addAll(saved.history()); historyIndex = saved.historyIndex(); historyDraft = saved.historyDraft();
            preferredInput = switch (saved.preferredInput()) {
                case SINGLE_LINE -> commandInput;
                case MULTILINE -> commandEditor;
                case TERMINAL -> terminal.getTerminalPanel();
            };
        } finally {
            navigatingHistory = previousNavigation; restoringDraft = false;
        }
        message.setText(saved.singleLine().text().isEmpty() && saved.multiline().text().isEmpty()
                ? "已恢复输入设置" : "已恢复未发送草稿；请检查后发送");
        revalidate(); repaint();
        return true;
    }
    private static TextState textState(javax.swing.text.JTextComponent input) {
        return new TextState(input.getText(), input.getCaret().getDot(), input.getCaret().getMark());
    }
    private static void restoreText(javax.swing.text.JTextComponent input, TextState state) {
        input.setText(state.text()); input.getCaret().setDot(state.mark()); input.getCaret().moveDot(state.dot());
    }
    private static void requireDraftEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("草稿状态必须在 EDT 上访问");
    }
    public boolean stageCommand(String text) {
        if (closed || text == null || text.isBlank()) return false;
        if (!commandInput.getText().isBlank() || !commandEditor.getText().isBlank()) {
            message.setText("输入框已有未发送内容，请先处理；未替换原稿"); return false;
        }
        String draft = text.replace("\r\n", "\n").replace('\r', '\n').replaceAll("\n+$", "");
        if (draft.contains("\n")) {
            multilinePanel.setVisible(true); commandEditor.setText(draft); commandEditor.requestFocusInWindow(); revalidate();
        } else { commandInput.setText(draft); commandInput.requestFocusInWindow(); }
        message.setText("已填入草稿；确认终端处于 Shell 提示符后按发送");
        return true;
    }
    /** Listener runs on the EDT. The file browser decides whether following is enabled. */
    public void onDirectoryChanged(java.util.function.Consumer<String> listener) {
        directoryChanged = listener == null ? path -> {} : listener;
        if (currentDirectory != null) directoryChanged.accept(currentDirectory);
    }
    public String getCurrentDirectory() { return currentDirectory; }
    public boolean stageDirectoryIntegration() {
        boolean staged = stageCommand(DirectoryTracker.bashIntegrationCommand());
        if (staged) message.setText("已填入 Bash 目录联动脚本；仅当前会话生效，确认在 Shell 提示符后发送");
        return staged;
    }
    private void completeRemotePath() {
        if (closed || completingPath) return;
        if (!canSend()) { message.setText("连接已断开，无法读取远程路径；草稿已保留"); return; }
        String draft = commandInput.getText(); int caret = commandInput.getCaretPosition();
        String directory = currentDirectory;
        completingPath = true;
        message.setText(directory == null ? "正在从 SFTP 主目录补全路径…" : "正在补全远程路径…");
        pathReads.execute(() -> {
            try {
                var candidates = pathCompletion.complete(draft, caret, directory);
                SwingUtilities.invokeLater(() -> {
                    completingPath = false;
                    if (closed) return;
                    if (!commandInput.getText().equals(draft) || commandInput.getCaretPosition() != caret) {
                        message.setText("草稿已变化，未替换内容；请再次补全"); return;
                    }
                    if (candidates.isEmpty()) { message.setText("没有匹配的远程路径"); return; }
                    if (candidates.size() == 1) { applyPathCandidate(candidates.get(0), draft, caret); return; }
                    JPopupMenu choices = new JPopupMenu();
                    for (var candidate : candidates) {
                        JMenuItem item = new JMenuItem(candidate.label());
                        item.putClientProperty("html.disable", true);
                        item.addActionListener(event -> applyPathCandidate(candidate, draft, caret)); choices.add(item);
                    }
                    message.setText("选择路径填入草稿；不会执行（最多显示 100 项）");
                    if (commandInput.isShowing()) choices.show(commandInput, 0, commandInput.getHeight());
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> {
                    completingPath = false;
                    if (!closed) message.setText("路径补全失败，草稿已保留：" + error.getMessage());
                });
            }
        });
    }
    private void applyPathCandidate(RemotePathCompletion.Candidate candidate, String draft, int caret) {
        if (closed || !commandInput.getText().equals(draft) || commandInput.getCaretPosition() != caret) {
            message.setText("草稿已变化，未替换内容；请再次补全"); return;
        }
        commandInput.setText(candidate.draft()); commandInput.setCaretPosition(candidate.caret());
        commandInput.requestFocusInWindow(); message.setText("已补全路径；请检查后发送");
    }
    private void showQuickCommands(JButton owner) {
        JPopupMenu menu = new JPopupMenu();
        String[][] groups = {
                {"系统与硬件", "ls -la", "uname -a && cat /etc/os-release", "uptime", "df -h", "free -h", "lscpu"},
                {"网络与端口", "ss -tulpn", "ip a || ifconfig", "curl -I https://www.google.com"},
                {"进程与服务", "top", "ps -ef", "ps aux --sort=-%mem | head -n 15",
                        "ps aux --sort=-%cpu | head -n 15", "systemctl list-units --type=service --state=running"},
                {"Docker", "docker ps -a", "docker images", "docker stats --no-stream"}
        };
        for (String[] group : groups) {
            JMenu category = new JMenu(group[0]);
            for (int i = 1; i < group.length; i++) {
                String command = group[i];
                JMenuItem item = new JMenuItem(command);
                item.addActionListener(event -> stageCommand(command));
                category.add(item);
            }
            menu.add(category);
        }
        menu.addSeparator();
        for (WorkspaceState.QuickCommand command : preferences.snapshot().quickCommands) {
            JMenuItem item = new JMenuItem(command.name);
            item.addActionListener(event -> stageCommand(command.command)); menu.add(item);
        }
        JMenuItem manage = new JMenuItem("管理自定义命令…");
        manage.addActionListener(event -> manageCommands()); menu.addSeparator(); menu.add(manage);
        menu.show(owner, 0, owner.getHeight());
    }
    private void manageCommands() {
        javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel(new String[]{"名称", "命令（支持多行）"}, 0);
        for (var command : preferences.snapshot().quickCommands) model.addRow(new Object[]{command.name, command.command});
        JTable table = new JTable(model);
        JPanel panel = new JPanel(new BorderLayout()); panel.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel actions = new JPanel();
        actions.add(button("添加", () -> model.addRow(new Object[]{"新命令", ""})));
        actions.add(button("删除选中", () -> { if (table.getSelectedRow() >= 0) model.removeRow(table.getSelectedRow()); }));
        actions.add(button("编辑命令文本", () -> {
            int row = table.getSelectedRow(); if (row < 0) return;
            JTextArea text = new JTextArea(String.valueOf(model.getValueAt(row, 1)), 8, 50);
            if (JOptionPane.showConfirmDialog(this, new JScrollPane(text), "命令文本", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
                model.setValueAt(text.getText(), row, 1);
        }));
        panel.add(actions, BorderLayout.SOUTH); panel.setPreferredSize(new Dimension(600, 320));
        if (JOptionPane.showConfirmDialog(this, panel, "自定义命令 · 选择只填入，不自动执行", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            var commands = new ArrayList<WorkspaceState.QuickCommand>();
            for (int row = 0; row < model.getRowCount(); row++) {
                String name = String.valueOf(model.getValueAt(row, 0)), command = String.valueOf(model.getValueAt(row, 1));
                if (!name.isBlank() && !command.isBlank()) commands.add(new WorkspaceState.QuickCommand(name, command));
            }
            if (!preferences.tryUpdate(state -> state.quickCommands = commands)) message.setText(preferences.lastError());
        }
    }
    private JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        ThemeUtils.toolbarButton(button);
        button.setFont(ThemeUtils.uiFont(Font.PLAIN, 11));
        button.putClientProperty(FlatClientProperties.STYLE, "arc: 4; margin: 1,5,1,5;");
        button.setFocusable(false);
        button.addActionListener(event -> action.run());
        return button;
    }
    private JButton tool(String text, String hint, Runnable action) {
        JButton result = button(text, action); result.setToolTipText(hint); return result;
    }
    private JButton sendButton(String text, Runnable action) {
        JButton result = button(text, action); sendButtons.add(result); return result;
    }
    private void setSending(boolean value) {
        sending = value;
        sendButtons.forEach(button -> button.setEnabled(!value && canSend()));
    }
    private boolean canSend() { return !closed && !disconnected && connector.isConnected(); }
    public void focusTerminal() { if (!closed) terminal.requestFocusInWindow(); }
    private void showShortcuts(JButton owner) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem integration = new JMenuItem("填入当前 Bash 会话的目录联动脚本…");
        integration.setToolTipText("仅填入本地草稿，由你确认发送；不修改服务器启动文件");
        integration.addActionListener(event -> stageDirectoryIntegration()); menu.add(integration); menu.addSeparator();
        for (String hint : List.of("终端内：Ctrl+C 中断；Ctrl+A / E 行首 / 行尾", "Tab 补全、↑/↓ 历史由远端 Shell 处理",
                "Ctrl+Shift+C / Ctrl+Insert 复制选中内容", "Ctrl+Shift+V / Shift+Insert 粘贴",
                "Ctrl+Shift+F 查找；Ctrl+Shift+A 全选输出", "Shift+PageUp / PageDown 翻阅输出；Ctrl+Shift+L 清回滚",
                "F6 切换终端与本地草稿；草稿内 Esc 回终端", "草稿内 Ctrl+C / V 为普通文本复制粘贴；Ctrl+Space 补全远程路径")) {
            JLabel item = new JLabel(hint);
            item.setBorder(new EmptyBorder(4, 10, 4, 10)); menu.add(item);
        }
        menu.show(owner, 0, owner.getHeight());
    }
    private void perform(List<TerminalAction> actions, String name) {
        actions.stream().filter(action -> action.getName().equals(name)).findFirst()
                .filter(action -> action.isEnabled(null)).ifPresent(action -> action.actionPerformed(null));
    }
    private void resizeFont(float delta) {
        settings.size = Math.max(9, Math.min(30, settings.size + delta));
        ((ScalablePanel) terminal.getTerminalPanel()).refreshFont();
        if (!preferences.tryUpdate(state -> state.fontSize = settings.size)) message.setText(preferences.lastError());
    }
    private void persist(java.util.function.Consumer<WorkspaceState> change) {
        if (restoringDraft) return;
        if (!preferences.tryUpdate(change)) message.setText(preferences.lastError());
    }
    public void appendOutput(String text) {
        SwingUtilities.invokeLater(() -> { message.setText(text.strip()); message.setToolTipText(text); });
    }
    public void setDisconnected(String reason) {
        disconnected = true;
        // Keep drafts selectable and copyable after a connection failure.
        sendButtons.forEach(button -> button.setEnabled(false));
        message.setText(reason); message.setForeground(ThemeUtils.getDanger());
    }
    @Override public void applyTheme() {
        setBackground(ThemeUtils.getBg());
        toolbar.setBackground(ThemeUtils.getHeaderBg());
        toolbar.setBorder(new EmptyBorder(2, 6, 2, 6));
        commandBar.setBackground(ThemeUtils.getHeaderBg());
        commandBar.setBorder(new EmptyBorder(3, 6, 3, 6));
        multilinePanel.setBackground(ThemeUtils.getHeaderBg());
        commandInput.setBackground(ThemeUtils.getCardBg()); commandInput.setForeground(ThemeUtils.getTextPrimary());
        commandEditor.setBackground(ThemeUtils.getCardBg()); commandEditor.setForeground(ThemeUtils.getTextPrimary());
        message.setForeground(ThemeUtils.getTextSecondary());
    }
    public void close() {
        if (closed) return;
        closed = true; pathCompletion.close(); pathReads.shutdownNow(); writes.shutdownNow(); terminal.close();
    }
    private static final class TerminalSettings extends DefaultSettingsProvider {
        private float size = 13;
        private static TerminalActionPresentation shortcut(String name, int key) {
            return new TerminalActionPresentation(name, KeyStroke.getKeyStroke(key, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        }
        @Override public TerminalActionPresentation getCopyActionPresentation() {
            return new TerminalActionPresentation("复制", List.of(KeyStroke.getKeyStroke("ctrl shift C"), KeyStroke.getKeyStroke("ctrl INSERT")));
        }
        @Override public TerminalActionPresentation getPasteActionPresentation() {
            return new TerminalActionPresentation("粘贴", List.of(KeyStroke.getKeyStroke("ctrl shift V"), KeyStroke.getKeyStroke("shift INSERT")));
        }
        @Override public TerminalActionPresentation getFindActionPresentation() { return shortcut("查找", KeyEvent.VK_F); }
        @Override public TerminalActionPresentation getSelectAllActionPresentation() { return shortcut("全选输出", KeyEvent.VK_A); }
        @Override public TerminalActionPresentation getClearBufferActionPresentation() { return shortcut("清屏", KeyEvent.VK_L); }
        @Override public TerminalActionPresentation getLineUpActionPresentation() { return new TerminalActionPresentation("向上滚动一行", List.of()); }
        @Override public TerminalActionPresentation getLineDownActionPresentation() { return new TerminalActionPresentation("向下滚动一行", List.of()); }
        @Override public TerminalActionPresentation getPageUpActionPresentation() { return new TerminalActionPresentation("向上翻页", KeyStroke.getKeyStroke("shift PAGE_UP")); }
        @Override public TerminalActionPresentation getPageDownActionPresentation() { return new TerminalActionPresentation("向下翻页", KeyStroke.getKeyStroke("shift PAGE_DOWN")); }
        @Override public TerminalActionPresentation getOpenUrlActionPresentation() { return new TerminalActionPresentation("打开选中的链接", List.of()); }
        @Override public float getTerminalFontSize() { return com.formdev.flatlaf.util.UIScale.scale(size); }
        @Override public Font getTerminalFont() { return ThemeUtils.monoFont(Font.PLAIN, Math.round(size)); }
        @Override public TerminalColor getDefaultForeground() { return TerminalColor.rgb(230, 237, 243); }
        @Override public TerminalColor getDefaultBackground() { return TerminalColor.rgb(16, 19, 23); }
        @Override public boolean audibleBell() { return false; }
        @Override public int getBufferMaxLinesCount() { return 10000; }
    }
    private final class ScalablePanel extends com.jediterm.terminal.ui.TerminalPanel {
        ScalablePanel(SettingsProvider provider, TerminalTextBuffer buffer, StyleState style) { super(provider, buffer, style); }
        @Override protected TerminalCopyPasteHandler createCopyPasteHandler() { return clipboard; }
        @Override protected void setupAntialiasing(Graphics graphics) {
            if (graphics instanceof Graphics2D g2) ThemeUtils.applyTextRendering(g2);
        }
        @Override public void selectAll() {
            super.selectAll();
            // JediTerm 3.76 ends at row count (one past the screen). Keep the
            // inclusive endpoint inside the buffer and omit unused screen rows.
            TerminalTextBuffer buffer = terminal.getTerminalTextBuffer();
            int row = Math.max(0, buffer.getScreenLinesCount() - 1);
            while (row > 0 && buffer.getLine(row).getText().isEmpty()) row--;
            int column = Math.min(buffer.getWidth() - 1, Math.max(0, buffer.getLine(row).getText().length() - 1));
            getSelection().updateEnd(new com.jediterm.core.compatibility.Point(column, row));
            repaint();
        }
        @Override public List<TerminalAction> getActions() {
            List<TerminalAction> actions = new ArrayList<>();
            for (TerminalAction action : super.getActions()) {
                if (action.getName().equals("复制")) {
                    actions.add(new TerminalAction(settings.getCopyActionPresentation(), event -> {
                        if (action.isEnabled(null)) action.actionPerformed(event);
                        else message.setText("请先用鼠标选中终端文字，再复制；Ctrl+C 始终发送中断");
                        return true;
                    }));
                } else if (action.getName().equals("粘贴")) {
                    actions.add(new TerminalAction(settings.getPasteActionPresentation(), event -> {
                        if (!canSend()) message.setText("连接已断开，无法粘贴到终端");
                        else if (action.isEnabled(event)) { action.actionPerformed(event); focusTerminal(); }
                        else message.setText("剪贴板没有可粘贴文本，或暂时无法访问");
                        return true;
                    }));
                } else actions.add(action);
            }
            actions.add(new TerminalAction(new TerminalActionPresentation("编辑本地草稿", KeyStroke.getKeyStroke("F6")), event -> {
                (multilinePanel.isVisible() ? commandEditor : commandInput).requestFocusInWindow(); return true;
            }));
            return actions;
        }
        void refreshFont() { reinitFontAndResize(); }
    }
}
