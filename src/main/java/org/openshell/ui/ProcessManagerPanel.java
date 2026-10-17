package org.openshell.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.jcraft.jsch.Session;
import org.openshell.ssh.*;
import org.openshell.ssh.LinuxProcessSampler.ProcessInfo;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Interval CPU sampling, numeric sorting, and visible-tab-only refresh. */
public class ProcessManagerPanel extends JPanel implements ThemeAware, AutoCloseable {
    private final ProcessSource source;
    private final ProcessTableModel model = new ProcessTableModel();
    private final JTable table = new JTable(model) {
        @Override public String getToolTipText(MouseEvent event) {
            int row = rowAtPoint(event.getPoint()), column = columnAtPoint(event.getPoint());
            if (row < 0 || column < 0) return null;
            Object value = getValueAt(row, column);
            return value == null ? "等待下一次 CPU 样本" : value.toString();
        }
    };
    private final TableRowSorter<ProcessTableModel> sorter = new TableRowSorter<>(model);
    private final JTextField searchField = new JTextField(20);
    private final JLabel statusLabel = new JLabel("打开进程页后开始采样");
    private final JLabel updatedLabel = new JLabel("尚未更新");
    private final JPanel topBar = new JPanel(new BorderLayout(8, 0));
    private final JPanel bottomBar = new JPanel(new BorderLayout());
    private final JButton refreshButton = new JButton("刷新");
    private final JButton killButton = new JButton("强制结束");
    private final JToggleButton autoRefresh = new JToggleButton("自动刷新", true);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "OpenShell-Processes"); thread.setDaemon(true); return thread;
    });
    private final Timer timer = new Timer(3000, event -> { if (isShowing()) refreshProcesses(); });
    private boolean busy, closed;
    private Integer requestedPid;
    private String lastUpdate, refreshError;

    public ProcessManagerPanel(Session session) { this(new SshProcessSource(session)); }

    ProcessManagerPanel(ProcessSource source) {
        this.source = source;
        setLayout(new BorderLayout());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.setOpaque(false);
        killButton.setForeground(ThemeUtils.getDanger());
        killButton.setEnabled(false);
        for (AbstractButton button : new AbstractButton[]{refreshButton, autoRefresh, killButton}) {
            button.setFont(ThemeUtils.uiFont(Font.PLAIN, 11));
            button.putClientProperty(FlatClientProperties.STYLE, "arc: 4; margin: 2,8,2,8;");
            button.setFocusable(false); actions.add(button);
        }
        refreshButton.addActionListener(event -> refreshProcesses());
        autoRefresh.addItemListener(event -> { updateAutoRefreshControl(); updateRefreshTimer(); updateStatus(); });
        killButton.addActionListener(event -> killSelectedProcess());
        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "搜索进程 / PID / 用户");
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        searchField.setFont(ThemeUtils.uiFont(Font.PLAIN, 12));
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        });
        topBar.add(actions, BorderLayout.WEST); topBar.add(searchField, BorderLayout.EAST);
        add(topBar, BorderLayout.NORTH);

        table.setRowSorter(sorter);
        sorter.setSortsOnUpdates(true);
        sorter.setMaxSortKeys(1);
        sorter.setSortKeys(List.of(new RowSorter.SortKey(2, SortOrder.DESCENDING)));
        table.setRowHeight(com.formdev.flatlaf.util.UIScale.scale(25));
        table.setShowGrid(false);
        table.putClientProperty(FlatClientProperties.STYLE, "showHorizontalLines: true; intercellSpacing: 0,1;");
        table.setFont(ThemeUtils.uiFont(Font.PLAIN, 12));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(event -> updateKillButton());
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setFont(ThemeUtils.uiFont(Font.BOLD, 12));
        table.getTableHeader().setToolTipText("点击表头切换升序 / 降序；CPU 按采样间隔计算，单核满载为 100%，多核进程可超过 100%");
        int[] widths = {75, 115, 85, 85, 100, 120, 500};
        for (int i = 0; i < widths.length; i++)
            table.getColumnModel().getColumn(i).setPreferredWidth(com.formdev.flatlaf.util.UIScale.scale(widths[i]));
        DefaultTableCellRenderer numberRenderer = new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) {
                setBorder(new EmptyBorder(0, 8, 0, 8));
                setHorizontalAlignment(SwingConstants.RIGHT);
                setText(value == null ? "—" : value instanceof Double d ? String.format(Locale.ROOT, "%.1f", d) : value.toString());
            }
        };
        table.setDefaultRenderer(Double.class, numberRenderer);
        table.setDefaultRenderer(Integer.class, numberRenderer);
        table.getColumnModel().getColumn(4).setCellRenderer(new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) {
                setBorder(new EmptyBorder(0, 8, 0, 8));
                setHorizontalAlignment(SwingConstants.RIGHT);
                setText(String.format(Locale.ROOT, "%.1f MiB", ((Long) value) / 1024.0));
            }
        });
        table.getColumnModel().getColumn(5).setCellRenderer(new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) {
                setBorder(new EmptyBorder(0, 8, 0, 8));
                long seconds = (Long) value;
                setText((seconds >= 86400 ? seconds / 86400 + "天 " : "")
                        + String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600 % 24, seconds / 60 % 60, seconds % 60));
            }
        });
        JPopupMenu menu = new JPopupMenu();
        JMenuItem killItem = new JMenuItem("强制终止此进程 (kill -9)");
        killItem.addActionListener(event -> killSelectedProcess()); menu.add(killItem);
        table.addMouseListener(new MouseAdapter() {
            private void selectPopup(MouseEvent event) {
                if (event.isPopupTrigger()) {
                    int row = table.rowAtPoint(event.getPoint());
                    if (row >= 0) table.setRowSelectionInterval(row, row); else table.clearSelection();
                }
            }
            @Override public void mousePressed(MouseEvent e) { selectPopup(e); }
            @Override public void mouseReleased(MouseEvent e) { selectPopup(e); }
        });
        table.setComponentPopupMenu(menu);
        JScrollPane scrollPane = new JScrollPane(table); scrollPane.setBorder(null);
        add(scrollPane, BorderLayout.CENTER);
        statusLabel.setFont(ThemeUtils.uiFont(Font.PLAIN, 11));
        updatedLabel.setFont(ThemeUtils.uiFont(Font.PLAIN, 11));
        updatedLabel.setBorder(new EmptyBorder(0, 12, 0, 0));
        updatedLabel.setToolTipText("最近一次成功获取进程数据的本机时间；刷新失败时保留上次时间");
        statusLabel.setToolTipText("CPU 为最近两次采样的占用；— 表示新进程尚无可比较样本；内存大小为 RSS 常驻内存");
        bottomBar.add(statusLabel, BorderLayout.CENTER);
        bottomBar.add(updatedLabel, BorderLayout.EAST); add(bottomBar, BorderLayout.SOUTH);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) updateRefreshTimer();
        });
        applyTheme();
        updateStatus();
    }

    private void updateAutoRefreshControl() {
        boolean enabled = autoRefresh.isSelected();
        autoRefresh.setText(closed ? "自动刷新：已停止" : enabled ? "自动刷新：开 · 3秒" : "自动刷新：关");
        autoRefresh.setFont(ThemeUtils.uiFont(Font.BOLD, 11));
        autoRefresh.setForeground(!closed && enabled ? ThemeUtils.getSuccess() : ThemeUtils.getTextSecondary());
        String description = closed ? "连接已断开，自动刷新已停止"
                : enabled ? "已开启，每 3 秒刷新；点击关闭。隐藏进程页时停止后续采样。"
                : "已关闭，点击开启每 3 秒刷新。正在进行的采样会完成，也可手动刷新。";
        autoRefresh.setToolTipText(description);
        autoRefresh.getAccessibleContext().setAccessibleDescription(description);
    }

    private void updateRefreshTimer() {
        timer.stop();
        if (!closed && isShowing() && autoRefresh.isSelected()) { refreshProcesses(); timer.start(); }
    }

    void refreshProcesses() {
        if (closed || busy) return;
        busy = true; refreshButton.setEnabled(false); updateKillButton();
        refreshError = null; updateStatus();
        worker.execute(() -> {
            try {
                List<ProcessInfo> rows = source.load();
                SwingUtilities.invokeLater(() -> {
                    if (closed) return;
                    busy = false; refreshButton.setEnabled(true); replaceProcesses(rows);
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> {
                    if (closed) return;
                    busy = false; refreshButton.setEnabled(true); updateKillButton();
                    refreshError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                    updateStatus();
                });
            }
        });
    }

    void replaceProcesses(List<ProcessInfo> rows) {
        lastUpdate = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        refreshError = null;
        updatedLabel.setText("最近更新 " + lastUpdate);
        ProcessInfo selected = selectedProcess();
        model.rows = List.copyOf(rows);
        model.fireTableDataChanged();
        table.clearSelection();
        if (selected != null) {
            for (int i = 0; i < rows.size(); i++) {
                ProcessInfo row = rows.get(i);
                if (row.pid() == selected.pid() && row.startTicks() == selected.startTicks()) {
                    int view = table.convertRowIndexToView(i);
                    if (view >= 0) table.setRowSelectionInterval(view, view);
                    break;
                }
            }
        }
        updateStatus();
        selectRequestedPid();
        updateKillButton();
    }

    public void showPid(int pid) {
        if (closed || pid <= 0) return;
        searchField.setText(Integer.toString(pid)); requestedPid = pid;
        sorter.setRowFilter(new RowFilter<>() { @Override public boolean include(Entry<? extends ProcessTableModel, ? extends Integer> e) { return ((Number) e.getValue(0)).intValue() == pid; } });
        selectRequestedPid(); refreshProcesses();
    }
    private void selectRequestedPid() {
        if (requestedPid == null) return;
        for (int i = 0; i < model.rows.size(); i++) if (model.rows.get(i).pid() == requestedPid) {
            int view = table.convertRowIndexToView(i);
            if (view >= 0) { table.setRowSelectionInterval(view, view); table.scrollRectToVisible(table.getCellRect(view, 0, true)); }
            requestedPid = null; return;
        }
        statusLabel.setText(statusLabel.getText() + " · PID " + requestedPid + " 未在本次结果中（可能已退出或无权限）");
    }

    private void updateKillButton() { killButton.setEnabled(!closed && !busy && table.getSelectedRow() >= 0); }

    private ProcessInfo selectedProcess() {
        int view = table.getSelectedRow();
        return view < 0 ? null : model.rows.get(table.convertRowIndexToModel(view));
    }

    private void filter() {
        requestedPid = null;
        String query = searchField.getText().strip().toLowerCase(Locale.ROOT);
        sorter.setRowFilter(new RowFilter<>() {
            @Override public boolean include(Entry<? extends ProcessTableModel, ? extends Integer> entry) {
                ProcessInfo p = model.rows.get(entry.getIdentifier());
                return query.isEmpty() || (p.pid() + " " + p.user() + " " + p.command()).toLowerCase(Locale.ROOT).contains(query);
            }
        });
        updateStatus();
    }

    private void updateStatus() {
        if (closed) { statusLabel.setText("连接已断开 · 进程监控已停止"); return; }
        statusLabel.setText("进程 " + table.getRowCount() + " / " + model.getRowCount()
                + " · " + (autoRefresh.isSelected() ? "自动刷新已开启 · 每3秒" : "自动刷新已关闭")
                + " · " + (busy ? "正在刷新…" : refreshError != null ? "刷新失败，保留上次结果: " + refreshError
                : lastUpdate == null ? "等待采样" : "CPU 单核 100% · 点击表头排序"));
    }

    private void killSelectedProcess() {
        if (closed || busy) return;
        ProcessInfo process = selectedProcess();
        if (process == null) {
            JOptionPane.showMessageDialog(this, "请先选择要终止的进程", "提示", JOptionPane.INFORMATION_MESSAGE); return;
        }
        timer.stop(); // Modal dialogs run a nested event loop; do not start a competing refresh there.
        int confirmation = JOptionPane.showConfirmDialog(this, "确定强制终止该进程？\nPID: " + process.pid() + "\n" + process.command(),
                "确认终止 (kill -9)", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (closed) return;
        if (confirmation != JOptionPane.YES_OPTION) { updateRefreshTimer(); return; }
        busy = true; refreshButton.setEnabled(false); updateKillButton();
        updateRefreshTimer();
        worker.execute(() -> {
            Exception failure = null;
            try { source.kill(process.pid()); } catch (Exception error) { failure = error; }
            Exception result = failure;
            SwingUtilities.invokeLater(() -> {
                if (closed) return;
                busy = false; refreshButton.setEnabled(true); updateKillButton();
                if (result == null) refreshProcesses();
                else JOptionPane.showMessageDialog(this, "终止失败: " + result.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            });
        });
    }

    @Override public void close() {
        if (closed) return;
        closed = true; timer.stop(); source.close(); worker.shutdownNow();
        refreshButton.setEnabled(false); autoRefresh.setEnabled(false); updateKillButton();
        updateAutoRefreshControl(); updateStatus();
    }

    @Override public void applyTheme() {
        setBackground(ThemeUtils.getBg());
        for (JPanel bar : new JPanel[]{topBar, bottomBar}) bar.setBackground(ThemeUtils.getHeaderBg());
        topBar.setBorder(BorderFactory.createCompoundBorder(ThemeUtils.createMatteBorder(0, 0, 1, 0), new EmptyBorder(4, 8, 4, 8)));
        bottomBar.setBorder(BorderFactory.createCompoundBorder(ThemeUtils.createMatteBorder(1, 0, 0, 0), new EmptyBorder(4, 10, 4, 10)));
        table.setBackground(ThemeUtils.getCardBg()); table.setForeground(ThemeUtils.getTextPrimary());
        table.getTableHeader().setBackground(ThemeUtils.getHeaderBg()); table.getTableHeader().setForeground(ThemeUtils.getTextPrimary());
        table.setSelectionBackground(ThemeUtils.getSelectionBg());
        table.setSelectionForeground(ThemeUtils.isDark() ? Color.WHITE : ThemeUtils.getTextPrimary());
        searchField.setBackground(ThemeUtils.getCardBg()); searchField.setForeground(ThemeUtils.getTextPrimary());
        statusLabel.setForeground(ThemeUtils.getTextSecondary());
        updatedLabel.setForeground(ThemeUtils.getTextSecondary());
        updateAutoRefreshControl(); repaint();
    }

    private static final class ProcessTableModel extends AbstractTableModel {
        private final String[] columns = {"PID", "用户", "CPU %", "内存 %", "内存 (RSS)", "运行时长", "进程指令 / 名称"};
        private final Class<?>[] types = {Integer.class, String.class, Double.class, Double.class, Long.class, Long.class, String.class};
        private List<ProcessInfo> rows = List.of();
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return columns.length; }
        public String getColumnName(int column) { return columns[column]; }
        public Class<?> getColumnClass(int column) { return types[column]; }
        public Object getValueAt(int row, int column) {
            ProcessInfo p = rows.get(row);
            return switch (column) {
                case 0 -> p.pid(); case 1 -> p.user(); case 2 -> p.cpuPercent(); case 3 -> p.memoryPercent();
                case 4 -> p.rssKiB(); case 5 -> p.elapsedSeconds(); case 6 -> p.command();
                default -> throw new IndexOutOfBoundsException();
            };
        }
    }
}
