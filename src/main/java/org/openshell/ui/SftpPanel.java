package org.openshell.ui;

import com.formdev.flatlaf.FlatClientProperties;
import org.openshell.ssh.SftpClient;
import org.openshell.ssh.SshSession;
import org.openshell.storage.UiPreferences;
import org.openshell.transfer.*;
import java.nio.file.Path;
import static org.openshell.transfer.TransferManager.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Remote directory tree and file table. Shell commands are staged for explicit sending. */
public class SftpPanel extends JPanel implements ThemeAware {
    private final SshSession sshSession;
    private final UiPreferences preferences = UiPreferences.current();
    private JSplitPane directorySplit;
    private boolean restoringPreferences = true;
    private final DebouncedSave viewSave = new DebouncedSave(this::saveView);
    private final SftpClient sftpClient;
    private volatile String currentDirectory = "/";
    private volatile boolean closed;
    private boolean directoryReady;
    private final java.util.concurrent.atomic.AtomicLong directoryRequest = new java.util.concurrent.atomic.AtomicLong();
    private final JTextField pathField;
    private final JTextField filterField;
    private final JCheckBox showHiddenCheckBox;
    private final JTable fileTable;
    private final DefaultTableModel tableModel;
    private final TableRowSorter<DefaultTableModel> tableSorter;
    private final JLabel statusLabel;
    private final JLabel statusRightLabel;
    private final BiConsumer<SftpClient, String> openEditorConsumer;
    private final java.util.function.Function<String, Boolean> commandSender;
    private TransferPanel transfers;
    private Runnable showTransfers = () -> {};
    private final JButton transferButton = new JButton("传输");
    private final JCheckBoxMenuItem followTerminal = new JCheckBoxMenuItem("跟随终端目录（需 Shell 集成）");
    private final JButton cancelPreparation = new JButton("取消整理");
    private Runnable stageIntegration = () -> {};
    private String terminalDirectory;
    private final java.util.concurrent.ExecutorService preparation = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "OpenShell-TransferPlan"); t.setDaemon(true); return t; });
    private volatile TreeTransferPlanner activePlanner;
    private volatile java.util.concurrent.Future<?> preparationTask;
    private final java.util.concurrent.atomic.AtomicLong preparationGeneration = new java.util.concurrent.atomic.AtomicLong();
    private final Set<UUID> completedTransfers = new HashSet<>();

    // UI 容器引用 (用于动态主题无缝切换与布局保持)
    private final JPanel topContainer;
    private final JPanel leftTreePanel;
    private final JPanel treeHeader;
    private final JLabel treeTitle;
    private final JPanel rightTablePanel;
    private final JPanel bottomBar;

    // 左侧目录树核心组件
    private final JTree dirTree;
    private final DefaultTreeModel treeModel;
    private final DefaultMutableTreeNode treeRootNode;
    private boolean isTreeSelecting = false;

    // 历史路径栈
    private final Deque<String> backHistory = new ArrayDeque<>();
    private final Deque<String> forwardHistory = new ArrayDeque<>();
    private boolean isNavigatingHistory = false;
    private JButton backBtn;
    private JButton forwardBtn;

    private final Set<String> directoryNames = new HashSet<>();
    private List<SftpClient.FileItem> currentItems = new ArrayList<>();
    private final List<SftpClient.FileItem> displayedItems = new ArrayList<>();

    public SftpPanel(SshSession sshSession, BiConsumer<SftpClient, String> openEditorConsumer) {
        this(sshSession, openEditorConsumer, null);
    }

    public SftpPanel(SshSession sshSession, BiConsumer<SftpClient, String> openEditorConsumer, java.util.function.Function<String, Boolean> commandSender) {
        this.sshSession = sshSession;
        this.sftpClient = new SftpClient(sshSession.getRawSession());
        this.openEditorConsumer = openEditorConsumer;
        this.commandSender = commandSender;
        setLayout(new BorderLayout());
        statusLabel = new JLabel("正在连接 SFTP 子系统...");
        statusLabel.setFont(ThemeUtils.uiFont(Font.PLAIN, 11));
        statusLabel.setForeground(ThemeUtils.getTextSecondary());

        statusRightLabel = new JLabel("SFTP 协议: v3 | 字符编码: UTF-8");
        statusRightLabel.setFont(ThemeUtils.uiFont(Font.PLAIN, 11));
        statusRightLabel.setForeground(ThemeUtils.getTextMuted());

        // 1. 初始化右侧文件列表与表格模型
        String[] columns = {"名称", "类型/大小", "权限", "最后修改时间"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override public Class<?> getColumnClass(int column) { return column == 1 ? Long.class : String.class; }
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        fileTable = new JTable(tableModel);
        fileTable.setRowHeight(com.formdev.flatlaf.util.UIScale.scale(26));
        fileTable.setShowGrid(false);
        fileTable.putClientProperty(FlatClientProperties.STYLE, "showHorizontalLines: true; intercellSpacing: 0,1;");
        fileTable.getTableHeader().setReorderingAllowed(false);
        fileTable.getTableHeader().setFont(ThemeUtils.uiFont(Font.BOLD, 12));
        fileTable.setFont(ThemeUtils.uiFont(Font.PLAIN, 12));
        fileTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        fileTable.getColumnModel().getColumn(0).setPreferredWidth(320);
        fileTable.getColumnModel().getColumn(1).setPreferredWidth(90);
        fileTable.getColumnModel().getColumn(2).setPreferredWidth(90);
        fileTable.getColumnModel().getColumn(3).setPreferredWidth(150);

        fileTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateSelectionStatus();
            }
        });

        tableSorter = new TableRowSorter<>(tableModel);
        tableSorter.setComparator(0, (o1, o2) -> {
            String s1 = o1.toString();
            String s2 = o2.toString();
            boolean isDir1 = directoryNames.contains(s1);
            boolean isDir2 = directoryNames.contains(s2);
            if (isDir1 && !isDir2) return -1;
            if (!isDir1 && isDir2) return 1;
            return s1.compareToIgnoreCase(s2);
        });
        tableSorter.setComparator(1, (left, right) -> Long.compare(((Number) left).longValue(), ((Number) right).longValue()));
        fileTable.getColumnModel().getColumn(0).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focused, row, column);
                putClientProperty("html.disable", true);
                setIcon(new UiIcons(directoryNames.contains(String.valueOf(value)) ? UiIcons.Kind.FOLDER : UiIcons.Kind.FILE));
                setText(String.valueOf(value)); return this;
            }
        });
        fileTable.getColumnModel().getColumn(1).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focused, row, column);
                int modelRow = table.convertRowIndexToModel(row);
                setText(modelRow < displayedItems.size() ? displayedItems.get(modelRow).getFormattedSize() : "");
                setHorizontalAlignment(SwingConstants.RIGHT); return this;
            }
        });
        fileTable.setRowSorter(tableSorter);

        // 2. 顶部综合工具栏 (FinalShell 经典单行桌面工具栏规范)
        topContainer = new JPanel(new BorderLayout(8, 0));
        topContainer.setBackground(ThemeUtils.getHeaderBg());
        topContainer.setBorder(BorderFactory.createCompoundBorder(
                ThemeUtils.createMatteBorder(0, 0, 1, 0),
                new EmptyBorder(3, 6, 3, 6)
        ));

        // 左侧：路径历史与文件基础操作
        JPanel leftBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        leftBox.setOpaque(false);

        backBtn = createToolbarButton("◀", "返回上一路径 (Alt+Left)");
        backBtn.setEnabled(false);
        backBtn.addActionListener(e -> navigateHistory(true));

        forwardBtn = createToolbarButton("▶", "前进到下一路径 (Alt+Right)");
        forwardBtn.setEnabled(false);
        forwardBtn.addActionListener(e -> navigateHistory(false));

        JButton upBtn = createToolbarButton("▲", "返回上级目录 (cd ..)");
        upBtn.addActionListener(e -> {
            if (sftpClient != null) {
                String cur = currentDirectory;
                if (cur != null && !cur.equals("/")) {
                    int lastSlash = cur.lastIndexOf('/');
                    String parent = lastSlash <= 0 ? "/" : cur.substring(0, lastSlash);
                    loadDirectoryAsync(parent);
                }
            }
        });

        JButton refreshBtn = createToolbarButton("↻", "刷新当前目录 (F5)");
        refreshBtn.addActionListener(e -> {
            if (sftpClient != null) loadDirectoryAsync(currentDirectory);
        });

        JButton homeBtn = createToolbarButton("主目录", "转到当前用户家目录");
        homeBtn.addActionListener(e -> loadDirectoryAsync("~"));

        JButton uploadBtn = createToolbarButton("上传", "上传本地文件到当前远程目录");
        uploadBtn.addActionListener(e -> showUploadDialog());

        JButton downloadBtn = createToolbarButton("下载", "下载选中的远程文件到本地电脑");
        downloadBtn.addActionListener(e -> downloadSelectedFile());

        JButton newMenuBtn = createToolbarButton("新建 ▾", "新建空白文件或子目录");
        JPopupMenu newMenu = new JPopupMenu();
        JMenuItem mNewFile = new JMenuItem("新建空白文件");
        mNewFile.addActionListener(e -> createNewFile());
        JMenuItem mNewDir = new JMenuItem("新建子目录");
        mNewDir.addActionListener(e -> createNewDirectory());
        newMenu.add(mNewFile);
        newMenu.add(mNewDir);
        newMenuBtn.addActionListener(e -> newMenu.show(newMenuBtn, 0, newMenuBtn.getHeight()));

        JButton moreMenuBtn = createToolbarButton("操作 ▾", "更多文件管理操作");
        JPopupMenu moreMenu = new JPopupMenu();
        JMenuItem mRename = new JMenuItem("重命名 (F2)");
        mRename.addActionListener(e -> renameSelected());
        JMenuItem mChmod = new JMenuItem("修改权限 (chmod)");
        mChmod.addActionListener(e -> chmodSelected());
        JMenuItem mCompress = new JMenuItem("打包压缩 (.tar.gz)");
        mCompress.addActionListener(e -> compressSelected());
        JMenuItem mDecompress = new JMenuItem("解压压缩包");
        mDecompress.addActionListener(e -> decompressSelected());
        JMenuItem mCopyPath = new JMenuItem("复制绝对路径");
        mCopyPath.addActionListener(e -> copySelectedPath());
        moreMenu.add(mRename);
        moreMenu.add(mChmod);
        moreMenu.addSeparator();
        moreMenu.add(mCompress);
        moreMenu.add(mDecompress);
        moreMenu.addSeparator();
        moreMenu.add(mCopyPath);
        moreMenuBtn.addActionListener(e -> moreMenu.show(moreMenuBtn, 0, moreMenuBtn.getHeight()));

        cancelPreparation.setVisible(false); ThemeUtils.toolbarButton(cancelPreparation);
        cancelPreparation.addActionListener(e -> cancelPreparation());
        leftBox.add(uploadBtn);
        leftBox.add(downloadBtn);
        ThemeUtils.toolbarButton(transferButton);
        transferButton.setToolTipText("查看传输队列、进度、取消与重试");
        transferButton.addActionListener(event -> showTransfers.run());
        leftBox.add(transferButton);
        leftBox.add(newMenuBtn);
        leftBox.add(moreMenuBtn); leftBox.add(cancelPreparation);
        JButton bookmarks = createToolbarButton("书签 ▾", "收藏当前目录，或跳转已收藏的目录");
        bookmarks.addActionListener(e -> showBookmarks(bookmarks));
        leftBox.add(bookmarks);
        followTerminal.setSelected(preferences.snapshot().view(sshSession.getConfig().getId()).followTerminal);
        followTerminal.addActionListener(e -> {
            viewSave.schedule();
            if (followTerminal.isSelected()) {
                if (terminalDirectory != null) loadDirectoryAsync(terminalDirectory);
                else statusLabel.setText("等待终端目录事件；在操作菜单中启用当前 Bash 会话集成");
            }
        });
        moreMenu.addSeparator(); moreMenu.add(followTerminal);
        JMenuItem integration = new JMenuItem("启用当前 Bash 会话目录集成…");
        integration.addActionListener(e -> stageIntegration.run()); moreMenu.add(integration);

        // 中间：地址栏 + 前往 + 终端同步
        JPanel centerBox = new JPanel(new BorderLayout(4, 0));
        centerBox.setOpaque(false);

        pathField = new JTextField("/");
        pathField.setFont(ThemeUtils.monoFont(Font.PLAIN, 12));
        pathField.putClientProperty(FlatClientProperties.STYLE, "arc: 4;");
        pathField.addActionListener(e -> {
            String target = pathField.getText().trim();
            if (!target.isEmpty()) loadDirectoryAsync(target);
        });

        JPanel centerEast = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        centerEast.setOpaque(false);

        JButton goBtn = createToolbarButton("前往 ⏎", "转到输入的远程路径");
        goBtn.addActionListener(e -> {
            String target = pathField.getText().trim();
            if (!target.isEmpty()) loadDirectoryAsync(target);
        });

        JButton syncTerminalBtn = createToolbarButton("填入 cd", "在命令输入框生成 cd 草稿，确认后再发送");
        syncTerminalBtn.setForeground(ThemeUtils.getAccent());
        syncTerminalBtn.setFont(ThemeUtils.uiFont(Font.BOLD, 11));
        syncTerminalBtn.addActionListener(e -> {
            if (commandSender != null && sftpClient != null) {
                String current = currentDirectory;
                boolean staged = commandSender.apply("cd -- " + shellQuote(current));
                statusLabel.setText(staged ? "已填入 cd 草稿，请确认 Shell 就绪后发送" : "终端有未发送草稿，未替换");
            }
        });

        centerEast.add(goBtn);
        centerEast.add(syncTerminalBtn);

        centerBox.add(pathField, BorderLayout.CENTER);
        centerBox.add(centerEast, BorderLayout.EAST);

        // 右侧：过滤与隐藏文件切换
        JPanel rightBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rightBox.setOpaque(false);

        filterField = new JTextField(9);
        filterField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "过滤文件");
        filterField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        filterField.putClientProperty(FlatClientProperties.STYLE, "arc: 4;");
        filterField.setFont(ThemeUtils.uiFont(Font.PLAIN, 11));
        filterField.addCaretListener(e -> filterFiles(filterField.getText().trim()));

        showHiddenCheckBox = new JCheckBox("隐藏");
        showHiddenCheckBox.setToolTipText("显示以点(.)开头的隐藏文件");
        showHiddenCheckBox.setFont(ThemeUtils.uiFont(Font.PLAIN, 11));
        showHiddenCheckBox.setOpaque(false);
        showHiddenCheckBox.setSelected(preferences.snapshot().view(sshSession.getConfig().getId()).showHidden);
        showHiddenCheckBox.addActionListener(e -> { filterFiles(filterField.getText().trim()); viewSave.schedule(); });

        rightBox.add(filterField);
        rightBox.add(showHiddenCheckBox);

        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0)); navigation.setOpaque(false);
        for (JButton button : new JButton[]{backBtn, forwardBtn, upBtn, refreshBtn, homeBtn}) navigation.add(button);
        JMenuItem deleteAction = new JMenuItem("删除选中项"); deleteAction.setIcon(new UiIcons(UiIcons.Kind.DELETE));
        deleteAction.addActionListener(event -> deleteSelected()); moreMenu.addSeparator(); moreMenu.add(deleteAction);
        JPanel pathRow = new JPanel(new BorderLayout(4, 0)); pathRow.setOpaque(false);
        pathRow.add(navigation, BorderLayout.WEST); pathRow.add(centerBox, BorderLayout.CENTER);
        JPanel operations = new JPanel(new BorderLayout()); operations.setOpaque(false);
        operations.add(leftBox, BorderLayout.WEST); operations.add(rightBox, BorderLayout.EAST);
        topContainer.add(pathRow, BorderLayout.NORTH); topContainer.add(operations, BorderLayout.SOUTH);
        add(topContainer, BorderLayout.NORTH);

        // 3. 构建左侧远程目录树 (FinalShell 核心特色：JTree 懒加载树)
        DirNode rootDirNode = new DirNode("/", "/");
        treeRootNode = new DefaultMutableTreeNode(rootDirNode);
        // 添加预设常用根目录节点
        String[] standardDirs = {"root", "home", "etc", "var", "usr", "opt", "tmp", "www"};
        for (String d : standardDirs) {
            DirNode sub = new DirNode(d, "/" + d);
            DefaultMutableTreeNode subNode = new DefaultMutableTreeNode(sub);
            subNode.add(new DefaultMutableTreeNode("...")); // 占位符供懒加载展开
            treeRootNode.add(subNode);
        }

        treeModel = new DefaultTreeModel(treeRootNode);
        dirTree = new JTree(treeModel);
        dirTree.setRootVisible(true);
        dirTree.setShowsRootHandles(true);
        dirTree.setRowHeight(com.formdev.flatlaf.util.UIScale.scale(24));
        dirTree.setFont(ThemeUtils.uiFont(Font.PLAIN, 12));
        dirTree.setCellRenderer(new DirTreeRenderer());

        // 监听展开事件：动态懒加载子目录
        dirTree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) event.getPath().getLastPathComponent();
                if (node.getChildCount() == 1) {
                    DefaultMutableTreeNode firstChild = (DefaultMutableTreeNode) node.getFirstChild();
                    if ("...".equals(firstChild.getUserObject())) {
                        DirNode dn = (DirNode) node.getUserObject();
                        loadTreeSubDirsAsync(node, dn.fullPath);
                    }
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {}
        });

        // 监听点击目录树节点：联动右侧表格跳转
        dirTree.addTreeSelectionListener(e -> {
            if (isTreeSelecting) return;
            TreePath path = dirTree.getSelectionPath();
            if (path != null) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                if (node.getUserObject() instanceof DirNode) {
                    DirNode dn = (DirNode) node.getUserObject();
                    if (sftpClient != null && !dn.fullPath.equals(currentDirectory)) {
                        loadDirectoryAsync(dn.fullPath);
                    }
                }
            }
        });

        // 左侧树容器 (带标题栏)
        leftTreePanel = new JPanel(new BorderLayout());
        leftTreePanel.setBackground(ThemeUtils.getCardBg());
        treeHeader = new JPanel(new BorderLayout());
        treeHeader.setBackground(ThemeUtils.getHeaderBg());
        treeHeader.setBorder(BorderFactory.createCompoundBorder(
                ThemeUtils.createMatteBorder(0, 0, 1, 0),
                new EmptyBorder(5, 8, 5, 8)
        ));
        treeTitle = new JLabel("远程目录", new UiIcons(UiIcons.Kind.FOLDER), SwingConstants.LEFT);
        treeTitle.setFont(ThemeUtils.uiFont(Font.BOLD, 11));
        treeTitle.setForeground(ThemeUtils.getTextPrimary());
        treeHeader.add(treeTitle, BorderLayout.WEST);

        leftTreePanel.add(treeHeader, BorderLayout.NORTH);
        JScrollPane treeScroll = new JScrollPane(dirTree);
        treeScroll.setBorder(null);
        leftTreePanel.add(treeScroll, BorderLayout.CENTER);

        // 4. 右侧表格容器
        rightTablePanel = new JPanel(new BorderLayout());
        rightTablePanel.setBackground(ThemeUtils.getCardBg());
        JScrollPane tableScroll = new JScrollPane(fileTable);
        tableScroll.setBorder(null);
        rightTablePanel.add(tableScroll, BorderLayout.CENTER);

        // 5. 左右横向分割面板 (FinalShell 双栏架构)
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftTreePanel, rightTablePanel);
        directorySplit = splitPane;
        splitPane.setDividerLocation(preferences.snapshot().view(sshSession.getConfig().getId()).directoryTreeWidth);
        splitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, event -> {
            if (!restoringPreferences && splitPane.getWidth() > 0) viewSave.schedule();
        });
        splitPane.setDividerSize(5);
        splitPane.setContinuousLayout(true);
        splitPane.setBorder(null);
        add(splitPane, BorderLayout.CENTER);

        // 6. 底部状态栏
        bottomBar = new JPanel(new BorderLayout());
        bottomBar.setBackground(ThemeUtils.getHeaderBg());
        bottomBar.setBorder(BorderFactory.createCompoundBorder(
                ThemeUtils.createMatteBorder(1, 0, 0, 0),
                new EmptyBorder(4, 10, 4, 10)
        ));

        bottomBar.add(statusLabel, BorderLayout.WEST);
        bottomBar.add(statusRightLabel, BorderLayout.EAST);
        add(bottomBar, BorderLayout.SOUTH);

        // 7. 双击与右键菜单事件
        fileTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int selected = fileTable.getSelectedRow();
                    if (selected != -1) {
                        int modelRow = fileTable.convertRowIndexToModel(selected);
                        if (modelRow >= 0 && modelRow < displayedItems.size()) {
                            SftpClient.FileItem item = displayedItems.get(modelRow);
                            if (item.isDirectory()) {
                                String nextPath = buildPath(currentDirectory, item.getName());
                                loadDirectoryAsync(nextPath);
                            } else {
                                if (openEditorConsumer != null) {
                                    String remoteFilePath = buildPath(currentDirectory, item.getName());
                                    openEditorConsumer.accept(sftpClient, remoteFilePath);
                                }
                            }
                        }
                    }
                }
            }
        });

        setupContextMenu();
        fileTable.setTransferHandler(new TransferHandler() {
            @Override public boolean canImport(TransferSupport support) { return !closed && directoryReady && transfers != null && !transfers.manager().isClosed() && support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor); }
            @Override public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    @SuppressWarnings("unchecked") List<File> files = (List<File>) support.getTransferable().getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                    String captured = currentDirectory;
                    if (JOptionPane.showConfirmDialog(SftpPanel.this, "将 " + files.size() + " 项文件/文件夹上传到：\n" + captured + "\n已有目录会合并，同名文件会另行询问。", "确认拖入上传", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return false;
                    enqueueUploads(files, captured); return true;
                } catch (Exception error) { statusLabel.setText("无法读取拖入文件: " + error.getMessage()); return false; }
            }
        });
        registerKeyboardAction(e -> deleteSelected(), KeyStroke.getKeyStroke("DELETE"), WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        fileTable.addMouseListener(new MouseAdapter() {
            void select(MouseEvent e) { if (e.isPopupTrigger()) { int row = fileTable.rowAtPoint(e.getPoint()); if (row >= 0 && !fileTable.isRowSelected(row)) fileTable.setRowSelectionInterval(row, row); } }
            @Override public void mousePressed(MouseEvent e) { select(e); }
            @Override public void mouseReleased(MouseEvent e) { select(e); }
        });

        // 异步初始化连接
        var columnWidths = preferences.snapshot().view(sshSession.getConfig().getId()).columns;
        if (columnWidths.size() == fileTable.getColumnCount()) {
            for (int i = 0; i < columnWidths.size(); i++) fileTable.getColumnModel().getColumn(i).setPreferredWidth(columnWidths.get(i));
        }
        fileTable.getColumnModel().addColumnModelListener(new javax.swing.event.TableColumnModelListener() {
            public void columnAdded(javax.swing.event.TableColumnModelEvent e) {}
            public void columnRemoved(javax.swing.event.TableColumnModelEvent e) {}
            public void columnMoved(javax.swing.event.TableColumnModelEvent e) {}
            public void columnSelectionChanged(javax.swing.event.ListSelectionEvent e) {}
            public void columnMarginChanged(javax.swing.event.ChangeEvent e) { if (!restoringPreferences && fileTable.getWidth() > 0) viewSave.schedule(); }
        });
        registerKeyboardAction(event -> loadDirectoryAsync(currentDirectory), KeyStroke.getKeyStroke("F5"), WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        registerKeyboardAction(event -> navigateHistory(true), KeyStroke.getKeyStroke("alt LEFT"), WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        registerKeyboardAction(event -> navigateHistory(false), KeyStroke.getKeyStroke("alt RIGHT"), WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        registerKeyboardAction(event -> renameSelected(), KeyStroke.getKeyStroke("F2"), WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        registerKeyboardAction(event -> { pathField.requestFocusInWindow(); pathField.selectAll(); }, KeyStroke.getKeyStroke("ctrl L"), WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        restoringPreferences = false;
        initSftpAsync();
    }

    public static class DirNode {
        public String name;
        public String fullPath;

        public DirNode(String name, String fullPath) {
            this.name = name;
            this.fullPath = fullPath;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static class DirTreeRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean exp, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, exp, leaf, row, hasFocus);
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            Object uo = node.getUserObject();
            if (uo instanceof DirNode) {
                DirNode dn = (DirNode) uo;
                setText(dn.name.equals("/") ? "/ (根目录)" : dn.name);
                setIcon(exp ? UIManager.getIcon("Tree.openIcon") : UIManager.getIcon("Tree.closedIcon"));
            } else {
                setText(String.valueOf(uo));
            }
            return this;
        }
    }

    private void loadTreeSubDirsAsync(DefaultMutableTreeNode node, String remotePath) {
        new Thread(() -> {
            try {
                List<SftpClient.FileItem> items = sftpClient.listFiles(remotePath);
                SwingUtilities.invokeLater(() -> {
                    if (closed) return;
                    node.removeAllChildren();
                    for (SftpClient.FileItem it : items) {
                        if (it.isDirectory()) {
                            String subPath = buildPath(remotePath, it.getName());
                            DefaultMutableTreeNode child = new DefaultMutableTreeNode(new DirNode(it.getName(), subPath));
                            child.add(new DefaultMutableTreeNode("...")); // 占位符
                            node.add(child);
                        }
                    }
                    treeModel.nodeStructureChanged(node);
                });
            } catch (Exception ignored) {}
        }).start();
    }

    private JButton createToolbarButton(String text, String tip) {
        JButton btn = new JButton(text);
        btn.setFont(ThemeUtils.uiFont(Font.PLAIN, 12));
        btn.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        btn.putClientProperty(FlatClientProperties.STYLE, "arc: 4; margin: 2,5,2,5;");
        btn.setFocusable(false);
        btn.setToolTipText(tip);
        return btn;
    }

    private void initSftpAsync() {
        long initialRequest = directoryRequest.get();
        new Thread(() -> {
            try {
                sftpClient.connect();
                if (closed) { sftpClient.disconnect(); return; }
                String home = sftpClient.getHome();
                SwingUtilities.invokeLater(() -> {
                    if (!closed && directoryRequest.get() == initialRequest) loadDirectoryAsync(home);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> { if (!closed) statusLabel.setText("SFTP 初始化失败: " + e.getMessage()); });
            }
        }).start();
    }

    public void loadDirectoryAsync(String path) {
        if (closed) return;
        long request = directoryRequest.incrementAndGet();
        SwingUtilities.invokeLater(() -> {
            if (!closed && request == directoryRequest.get()) statusLabel.setText("正在读取目录: " + path + " ...");
        });
        new Thread(() -> {
            try {
                String actualPath = sftpClient.resolvePath(path);
                List<SftpClient.FileItem> items = sftpClient.listFiles(actualPath);

                SwingUtilities.invokeLater(() -> {
                    if (closed || request != directoryRequest.get()) return;
                    currentDirectory = actualPath;
                    directoryReady = true;
                    if (transfers != null) transfers.setUploadDirectory(actualPath);
                    if (!isNavigatingHistory) {
                        String old = pathField.getText();
                        if (!old.equals(actualPath)) {
                            backHistory.push(old);
                            forwardHistory.clear();
                        }
                    }
                    isNavigatingHistory = false;
                    backBtn.setEnabled(!backHistory.isEmpty());
                    forwardBtn.setEnabled(!forwardHistory.isEmpty());

                    pathField.setText(actualPath);
                    currentItems = items;
                    filterFiles(filterField.getText().trim());

                    long dirCount = items.stream().filter(SftpClient.FileItem::isDirectory).count();
                    long fileCount = items.size() - dirCount;
                    statusLabel.setText(String.format("共 %d 项 (%d 个文件夹, %d 个文件)", items.size(), dirCount, fileCount));
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    if (!closed && request == directoryRequest.get()) statusLabel.setText("加载目录失败: " + e.getMessage());
                });
            }
        }).start();
    }

    private void navigateHistory(boolean isBack) {
        if (isBack && !backHistory.isEmpty()) {
            forwardHistory.push(pathField.getText());
            String prev = backHistory.pop();
            isNavigatingHistory = true;
            loadDirectoryAsync(prev);
        } else if (!isBack && !forwardHistory.isEmpty()) {
            backHistory.push(pathField.getText());
            String next = forwardHistory.pop();
            isNavigatingHistory = true;
            loadDirectoryAsync(next);
        }
    }

    private void filterFiles(String query) {
        tableModel.setRowCount(0);
        displayedItems.clear(); directoryNames.clear();
        currentItems.stream().filter(SftpClient.FileItem::isDirectory).forEach(item -> directoryNames.add(item.getName()));
        boolean showHidden = showHiddenCheckBox.isSelected();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        for (SftpClient.FileItem item : currentItems) {
            if (!showHidden && item.getName().startsWith(".")) {
                continue;
            }
            if (!query.isEmpty() && !item.getName().toLowerCase().contains(query.toLowerCase())) {
                continue;
            }

            displayedItems.add(item);
            tableModel.addRow(new Object[]{
                    item.getName(),
                    item.getSize(),
                    item.getPermissions(),
                    sdf.format(new Date(item.getMtime()))
            });
        }
        updateSelectionStatus();
    }

    private void updateSelectionStatus() {
        int selectedRow = fileTable.getSelectedRow();
        if (fileTable.getSelectedRowCount() > 1) { statusRightLabel.setText("已选择 " + fileTable.getSelectedRowCount() + " 项 · Ctrl/Shift 多选"); return; }
        if (selectedRow == -1) {
            statusRightLabel.setText("SFTP 协议: v3 | 字符编码: UTF-8");
        } else {
            int modelRow = fileTable.convertRowIndexToModel(selectedRow);
            if (modelRow >= 0 && modelRow < displayedItems.size()) {
                SftpClient.FileItem item = displayedItems.get(modelRow);
                statusRightLabel.setText(String.format("已选择: %s | 大小: %s | 权限: %s",
                        item.getName(), item.getFormattedSize(), item.getPermissions()));
            }
        }
    }

    private void setupContextMenu() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem openItem = new JMenuItem("打开 / 编辑 (Enter)");
        openItem.addActionListener(e -> {
            if (fileTable.getSelectedRowCount() != 1) { statusLabel.setText("请选择一项打开"); return; }
            int row = fileTable.getSelectedRow();
            if (row != -1) {
                int modelRow = fileTable.convertRowIndexToModel(row);
                SftpClient.FileItem item = displayedItems.get(modelRow);
                if (item.isDirectory()) {
                    loadDirectoryAsync(buildPath(currentDirectory, item.getName()));
                } else if (openEditorConsumer != null) {
                    openEditorConsumer.accept(sftpClient, buildPath(currentDirectory, item.getName()));
                }
            }
        });

        JMenuItem downloadItem = new JMenuItem("下载到本地电脑...");
        downloadItem.addActionListener(e -> downloadSelectedFile());

        JMenuItem uploadItem = new JMenuItem("上传文件到此处...");
        uploadItem.addActionListener(e -> showUploadDialog());

        JMenuItem copyItem = new JMenuItem("复制远程绝对路径");
        copyItem.addActionListener(e -> copySelectedPath());

        JMenuItem renameItem = new JMenuItem("重命名 (F2)");
        renameItem.addActionListener(e -> renameSelected());

        JMenuItem chmodItem = new JMenuItem("修改文件权限 (chmod)");
        chmodItem.addActionListener(e -> chmodSelected());

        JMenuItem compressItem = new JMenuItem("打包压缩 (tar.gz)");
        compressItem.addActionListener(e -> compressSelected());

        JMenuItem decompressItem = new JMenuItem("解压压缩包");
        decompressItem.addActionListener(e -> decompressSelected());

        JMenuItem deleteItem = new JMenuItem("删除文件/目录 (Del)");
        deleteItem.addActionListener(e -> deleteSelected());

        fileTable.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "openSelected");
        fileTable.getActionMap().put("openSelected", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { openItem.doClick(); } });
        menu.add(openItem);
        menu.addSeparator();
        menu.add(downloadItem);
        menu.add(uploadItem);
        menu.addSeparator();
        menu.add(copyItem);
        menu.add(renameItem);
        menu.add(chmodItem);
        menu.add(compressItem);
        menu.add(decompressItem);
        menu.addSeparator();
        menu.add(deleteItem);

        fileTable.setComponentPopupMenu(menu);
    }

    public void showUploadDialog() {
        if (!transferAvailable()) return;
        if (!directoryReady) { statusLabel.setText("请等待目录加载完成后再上传"); return; }
        final String operationDirectory = currentDirectory;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("上传文件或文件夹到 " + operationDirectory);
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File[] files = chooser.getSelectedFiles();
            enqueueUploads(Arrays.asList(files), operationDirectory);
        }
    }

    List<SftpClient.FileItem> selectedItems() {
        return Arrays.stream(fileTable.getSelectedRows()).map(fileTable::convertRowIndexToModel)
            .filter(i -> i >= 0 && i < displayedItems.size()).mapToObj(displayedItems::get).toList();
    }
    private void downloadSelectedFile() {
        if (!transferAvailable()) return;
        String captured = currentDirectory; var selected = selectedItems();
        if (selected.isEmpty()) { statusLabel.setText("请选择要下载的文件或文件夹（支持 Ctrl/Shift 多选）"); return; }
        JFileChooser chooser = new JFileChooser();
        if (selected.size() == 1 && !selected.get(0).isDirectory()) {
            chooser.setSelectedFile(new File(selected.get(0).getName())); chooser.setDialogTitle("保存远程文件到本地");
            if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                transfers.submit(Direction.DOWNLOAD, chooser.getSelectedFile().toPath(), buildPath(captured, selected.get(0).getName()), UUID.randomUUID()); showTransfers.run();
            }
        } else {
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY); chooser.setDialogTitle("选择下载目标目录（保留文件夹结构）");
            if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                var paths = selected.stream().map(it -> buildPath(captured, it.getName())).toList(); Path target = chooser.getSelectedFile().toPath();
                startPreparation(planner -> planner.download(paths, target, UUID.randomUUID(), () -> closed || Thread.currentThread().isInterrupted()));
            }
        }
    }
    @FunctionalInterface private interface Plan { List<Request> build(TreeTransferPlanner planner) throws Exception; }
    private void startPreparation(Plan plan) {
        if (!transferAvailable()) return;
        if (preparationTask != null && !preparationTask.isDone()) { statusLabel.setText("正在整理上一批目录，可先取消整理"); return; }
        long generation = preparationGeneration.incrementAndGet(); cancelPreparation.setVisible(true); statusLabel.setText("正在整理目录与总大小，尚未传输…");
        preparationTask = preparation.submit(() -> {
            try (var planner = new TreeTransferPlanner(sshSession.getRawSession())) {
                activePlanner = planner; var requests = plan.build(planner);
                SwingUtilities.invokeLater(() -> {
                    if (closed || generation != preparationGeneration.get()) return;
                    cancelPreparation.setVisible(false);
                    if (!transferAvailable()) return;
                    transfers.manager().submitAll(requests); statusLabel.setText("已加入传输队列：" + requests.size() + " 项（目录与文件）"); showTransfers.run();
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> { if (!closed && generation == preparationGeneration.get()) { cancelPreparation.setVisible(false); statusLabel.setText("整理失败，未加入队列: " + error.getMessage()); } });
            } finally { activePlanner = null; }
        });
    }
    private void cancelPreparation() {
        preparationGeneration.incrementAndGet(); var planner = activePlanner; if (planner != null) planner.close();
        var task = preparationTask; if (task != null) task.cancel(true);
        cancelPreparation.setVisible(false); statusLabel.setText("目录整理已取消，未加入传输队列");
    }
    public void setDirectoryIntegrationAction(Runnable action) { stageIntegration = action; }
    public void onTerminalDirectory(String path) {
        if (closed || path == null || !path.startsWith("/")) return;
        terminalDirectory = path;
        if (followTerminal.isSelected() && !path.equals(currentDirectory)) loadDirectoryAsync(path);
    }
    private void showBookmarks(JButton anchor) {
        JPopupMenu menu = new JPopupMenu(); var saved = preferences.snapshot().view(sshSession.getConfig().getId()).directoryBookmarks;
        JMenuItem add = new JMenuItem("收藏当前目录"); add.setEnabled(directoryReady);
        add.addActionListener(e -> { String path = currentDirectory; preferences.tryUpdate(state -> { var marks = state.view(sshSession.getConfig().getId()).directoryBookmarks; if (!marks.contains(path)) marks.add(path); }); }); menu.add(add);
        JMenuItem remove = new JMenuItem("移除当前目录书签"); remove.setEnabled(saved.contains(currentDirectory));
        remove.addActionListener(e -> preferences.tryUpdate(state -> state.view(sshSession.getConfig().getId()).directoryBookmarks.remove(currentDirectory))); menu.add(remove); menu.addSeparator();
        for (String path : saved) { JMenuItem item = new JMenuItem(path); item.putClientProperty("html.disable", true); item.addActionListener(e -> loadDirectoryAsync(path)); menu.add(item); }
        if (saved.isEmpty()) { JMenuItem empty = new JMenuItem("暂无目录书签"); empty.setEnabled(false); menu.add(empty); }
        menu.show(anchor, 0, anchor.getHeight());
    }

    public void setTransfers(TransferPanel transfers, Runnable showTransfers) {
        this.transfers = transfers; this.showTransfers = showTransfers;
        if (directoryReady) transfers.setUploadDirectory(currentDirectory);
    }

    void enqueueUploads(List<File> files, String operationDirectory) {
        if (files.isEmpty()) return;
        List<Path> captured = files.stream().map(File::toPath).toList();
        startPreparation(planner -> TreeTransferPlanner.upload(captured, operationDirectory, UUID.randomUUID(), () -> closed || Thread.currentThread().isInterrupted()));
    }

    private boolean transferAvailable() {
        if (closed || transfers == null || transfers.manager().isClosed()) {
            statusLabel.setText("传输队列已停止，请重新连接"); return false;
        }
        return true;
    }

    public void onTransfersChanged(List<org.openshell.transfer.TransferManager.Snapshot> rows) {
        if (closed) return;
        long pending = rows.stream().filter(row -> !row.state().terminal()).count();
        transferButton.setText(pending == 0 ? "传输" : "传输 (" + pending + ")");
        for (var row : rows) if (row.state() == org.openshell.transfer.TransferManager.State.COMPLETED
                && completedTransfers.add(row.id()) && row.request().direction() == org.openshell.transfer.TransferManager.Direction.UPLOAD) {
            String destination = row.destination();
            String parent = destination.substring(0, destination.lastIndexOf('/') + 1);
            refreshIfCurrent(parent.length() > 1 ? parent.substring(0, parent.length() - 1) : "/");
        }
        Set<UUID> retained = new HashSet<>(); rows.forEach(row -> retained.add(row.id())); completedTransfers.retainAll(retained);
    }

    private void createNewFile() {
        if (closed) return;
        final String operationDirectory = currentDirectory;
        String name = JOptionPane.showInputDialog(this, "请输入新文件名:", "新建文件", JOptionPane.PLAIN_MESSAGE);
        if (name != null && !name.trim().isEmpty()) {
            new Thread(() -> {
                try {
                    sftpClient.createEmptyFile(buildPath(operationDirectory, name.trim()));
                    refreshIfCurrent(operationDirectory);
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "新建失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE));
                }
            }).start();
        }
    }

    private void createNewDirectory() {
        if (closed) return;
        final String operationDirectory = currentDirectory;
        String name = JOptionPane.showInputDialog(this, "请输入新文件夹名称:", "新建目录", JOptionPane.PLAIN_MESSAGE);
        if (name != null && !name.trim().isEmpty()) {
            new Thread(() -> {
                try {
                    sftpClient.mkdir(buildPath(operationDirectory, name.trim()));
                    refreshIfCurrent(operationDirectory);
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "创建失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE));
                }
            }).start();
        }
    }

    private void renameSelected() {
        if (closed) return;
        if (fileTable.getSelectedRowCount() != 1) { statusLabel.setText("此操作请选择一项；批量操作支持下载与删除"); return; }
        final String operationDirectory = currentDirectory;
        int row = fileTable.getSelectedRow();
        if (row == -1) return;
        int modelRow = fileTable.convertRowIndexToModel(row);
        SftpClient.FileItem item = displayedItems.get(modelRow);

        String newName = JOptionPane.showInputDialog(this, "重命名 " + item.getName() + " 为:", item.getName());
        if (newName != null && !newName.trim().isEmpty() && !newName.equals(item.getName())) {
            new Thread(() -> {
                try {
                    sftpClient.rename(buildPath(operationDirectory, item.getName()), buildPath(operationDirectory, newName.trim()));
                    refreshIfCurrent(operationDirectory);
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "重命名失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE));
                }
            }).start();
        }
    }

    private void deleteSelected() {
        if (closed) return;
        final String capturedDirectory = currentDirectory; var items = selectedItems(); if (items.isEmpty()) return;
        List<String> paths = items.stream().map(it -> buildPath(capturedDirectory, it.getName())).toList();
        JTextArea names = new JTextArea("永久删除以下 " + paths.size() + " 项？\n目录仅在为空时删除，不递归删除内容。\n\n" + String.join("\n", paths), Math.min(14, paths.size() + 4), 58);
        names.setEditable(false); names.setCaretPosition(0);
        if (JOptionPane.showConfirmDialog(this, new JScrollPane(names), "确认批量删除", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        Thread worker = new Thread(() -> {
            try {
                var result = BatchFileOperations.delete(sshSession.getRawSession(), paths);
                SwingUtilities.invokeLater(() -> {
                    if (closed) return;
                    refreshIfCurrent(capturedDirectory);
                    statusLabel.setText("删除完成 " + result.deleted().size() + " 项，失败 " + result.failures().size() + " 项");
                    if (!result.failures().isEmpty()) { JTextArea errors = new JTextArea(result.failures().entrySet().stream().map(e -> e.getKey() + "：" + e.getValue()).collect(java.util.stream.Collectors.joining("\n")), 12, 65); errors.setEditable(false); JOptionPane.showMessageDialog(this, new JScrollPane(errors), "部分项目未删除", JOptionPane.WARNING_MESSAGE); }
                });
            } catch (Exception error) { SwingUtilities.invokeLater(() -> { if (!closed) statusLabel.setText("删除失败: " + error.getMessage()); }); }
        }, "OpenShell-BatchDelete"); worker.setDaemon(true); worker.start();
    }

    private void chmodSelected() {
        if (closed) return;
        if (fileTable.getSelectedRowCount() != 1) { statusLabel.setText("此操作请选择一项；批量操作支持下载与删除"); return; }
        final String operationDirectory = currentDirectory;
        int row = fileTable.getSelectedRow();
        if (row == -1) return;
        int modelRow = fileTable.convertRowIndexToModel(row);
        SftpClient.FileItem item = displayedItems.get(modelRow);

        String permStr = JOptionPane.showInputDialog(this, "请输入八进制权限值 (例如 755 或 644):", "755");
        if (permStr != null && !permStr.trim().isEmpty()) {
            try {
                int p = Integer.parseInt(permStr.trim(), 8);
                new Thread(() -> {
                    try {
                        sftpClient.chmod(p, buildPath(operationDirectory, item.getName()));
                        refreshIfCurrent(operationDirectory);
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "修改权限失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE));
                    }
                }).start();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "权限值格式不正确", "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void compressSelected() {
        if (closed) return;
        if (fileTable.getSelectedRowCount() != 1) { statusLabel.setText("此操作请选择一项；批量操作支持下载与删除"); return; }
        int row = fileTable.getSelectedRow();
        if (row == -1) {
            JOptionPane.showMessageDialog(this, "请先选择要打包压缩的文件或目录", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int modelRow = fileTable.convertRowIndexToModel(row);
        SftpClient.FileItem item = displayedItems.get(modelRow);
        String archiveName = item.getName() + ".tar.gz";

        if (commandSender != null && sftpClient != null) {
            String cur = currentDirectory;
            String cmd = "cd -- " + shellQuote(cur) + " && tar -czvf " + shellQuote(archiveName) + " -- " + shellQuote(item.getName()) + "\n";
            boolean staged = commandSender.apply(cmd);
            statusLabel.setText(staged ? "压缩命令已填入终端输入框，请检查后发送" : "终端已有草稿，未替换");
        }
    }

    private void decompressSelected() {
        if (closed) return;
        if (fileTable.getSelectedRowCount() != 1) { statusLabel.setText("此操作请选择一项；批量操作支持下载与删除"); return; }
        int row = fileTable.getSelectedRow();
        if (row == -1) {
            JOptionPane.showMessageDialog(this, "请选择要解压的压缩包文件", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int modelRow = fileTable.convertRowIndexToModel(row);
        SftpClient.FileItem item = displayedItems.get(modelRow);
        String name = item.getName();

        if (commandSender != null && sftpClient != null) {
            String cur = currentDirectory;
            String cmd;
            if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
                cmd = "cd -- " + shellQuote(cur) + " && tar -xzvf " + shellQuote(name) + "\n";
            } else if (name.endsWith(".tar.bz2")) {
                cmd = "cd -- " + shellQuote(cur) + " && tar -xjvf " + shellQuote(name) + "\n";
            } else if (name.endsWith(".zip")) {
                cmd = "cd -- " + shellQuote(cur) + " && unzip -o " + shellQuote("./" + name) + "\n";
            } else {
                cmd = "cd -- " + shellQuote(cur) + " && tar -xvf " + shellQuote(name) + "\n";
            }
            boolean staged = commandSender.apply(cmd);
            statusLabel.setText(staged ? "解压命令已填入终端输入框，请检查后发送" : "终端已有草稿，未替换");
        }
    }

    private void copySelectedPath() {
        int row = fileTable.getSelectedRow();
        if (row != -1) {
            int modelRow = fileTable.convertRowIndexToModel(row);
            SftpClient.FileItem item = displayedItems.get(modelRow);
            String full = buildPath(currentDirectory, item.getName());
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(full), null);
            JOptionPane.showMessageDialog(this, "已复制路径到剪贴板:\n" + full, "复制成功", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private String buildPath(String parent, String name) {
        if ("/".equals(parent)) return "/" + name;
        return parent + "/" + name;
    }

    @Override
    public void applyTheme() {
        setBackground(ThemeUtils.getBg());
        if (topContainer != null) {
            topContainer.setBackground(ThemeUtils.getHeaderBg());
            topContainer.setBorder(BorderFactory.createCompoundBorder(
                    ThemeUtils.createMatteBorder(0, 0, 1, 0),
                    new EmptyBorder(3, 6, 3, 6)
            ));
        }
        if (leftTreePanel != null) {
            leftTreePanel.setBackground(ThemeUtils.getCardBg());
        }
        if (treeHeader != null) {
            treeHeader.setBackground(ThemeUtils.getHeaderBg());
            treeHeader.setBorder(BorderFactory.createCompoundBorder(
                    ThemeUtils.createMatteBorder(0, 0, 1, 0),
                    new EmptyBorder(5, 8, 5, 8)
            ));
        }
        if (treeTitle != null) {
            treeTitle.setForeground(ThemeUtils.getTextPrimary());
        }
        if (dirTree != null) {
            dirTree.setBackground(ThemeUtils.getCardBg());
            dirTree.setForeground(ThemeUtils.getTextPrimary());
        }
        if (rightTablePanel != null) {
            rightTablePanel.setBackground(ThemeUtils.getCardBg());
        }
        if (fileTable != null) {
            fileTable.setBackground(ThemeUtils.getCardBg());
            fileTable.setForeground(ThemeUtils.getTextPrimary());
            fileTable.getTableHeader().setBackground(ThemeUtils.getHeaderBg());
            fileTable.getTableHeader().setForeground(ThemeUtils.getTextPrimary());
            fileTable.setSelectionBackground(ThemeUtils.getSelectionBg());
            fileTable.setSelectionForeground(ThemeUtils.isDark() ? Color.WHITE : ThemeUtils.getTextPrimary());
        }
        if (bottomBar != null) {
            bottomBar.setBackground(ThemeUtils.getHeaderBg());
            bottomBar.setBorder(BorderFactory.createCompoundBorder(
                    ThemeUtils.createMatteBorder(1, 0, 0, 0),
                    new EmptyBorder(4, 10, 4, 10)
            ));
        }
        if (statusLabel != null) {
            statusLabel.setForeground(ThemeUtils.getTextSecondary());
        }
        if (statusRightLabel != null) {
            statusRightLabel.setForeground(ThemeUtils.getTextMuted());
        }
        if (pathField != null) {
            pathField.setBackground(ThemeUtils.getCardBg());
            pathField.setForeground(ThemeUtils.getTextPrimary());
        }
        if (filterField != null) {
            filterField.setBackground(ThemeUtils.getCardBg());
            filterField.setForeground(ThemeUtils.getTextPrimary());
        }
        if (showHiddenCheckBox != null) {
            showHiddenCheckBox.setForeground(ThemeUtils.getTextPrimary());
        }
        repaint();
    }

    private void refreshIfCurrent(String operationDirectory) {
        SwingUtilities.invokeLater(() -> {
            if (!closed && operationDirectory.equals(currentDirectory)) loadDirectoryAsync(operationDirectory);
        });
    }

    private void saveView() {
        var widths = new ArrayList<Integer>();
        for (int i = 0; i < fileTable.getColumnCount(); i++) widths.add(fileTable.getColumnModel().getColumn(i).getWidth());
        if (!preferences.tryUpdate(state -> {
            var view = state.view(sshSession.getConfig().getId());
            view.columns = widths; view.showHidden = showHiddenCheckBox.isSelected(); view.followTerminal = followTerminal.isSelected();
            if (directorySplit != null && directorySplit.getDividerLocation() > 0) view.directoryTreeWidth = directorySplit.getDividerLocation();
        })) statusLabel.setText(preferences.lastError());
    }

    @Override public Dimension getMinimumSize() {
        if (topContainer == null || fileTable == null || bottomBar == null) return super.getMinimumSize();
        return new Dimension(0, topContainer.getPreferredSize().height + fileTable.getTableHeader().getPreferredSize().height
                + bottomBar.getPreferredSize().height + fileTable.getRowHeight() * 2);
    }

    public void onDisconnected() {
        close(); directoryReady = false; statusLabel.setText("连接已断开，目录为上次快照；重新连接后继续操作");
    }

    public void close() {
        viewSave.flush();
        closed = true; cancelPreparation(); preparation.shutdownNow();
        directoryRequest.incrementAndGet();
        SftpClient client = sftpClient;
        if (client != null) client.disconnect();
    }
}
