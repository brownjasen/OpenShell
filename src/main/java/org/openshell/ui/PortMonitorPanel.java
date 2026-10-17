package org.openshell.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.jcraft.jsch.Session;
import org.openshell.ssh.*;
import org.openshell.ssh.PortParser.PortEntry;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.function.IntConsumer;

/** Read-only endpoints, numeric sorting, and visible-tab-only collection. */
public class PortMonitorPanel extends JPanel implements ThemeAware, AutoCloseable {
    private final PortSource source;
    private final PortTableModel model = new PortTableModel();
    private final JTable table = new JTable(model);
    private final TableRowSorter<PortTableModel> sorter = new TableRowSorter<>(model);
    private final JTextField searchField = new JTextField(18);
    private final JButton refreshButton = new JButton("刷新");
    private final JToggleButton autoRefresh = new JToggleButton("自动刷新", true);
    private final JButton processButton = new JButton("查看进程");
    private final JLabel statusLabel = new JLabel(), updatedLabel = new JLabel("尚未更新");
    private final JPanel topBar = new JPanel(new BorderLayout(8, 0)), bottomBar = new JPanel(new BorderLayout());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "OpenShell-Ports"); thread.setDaemon(true); return thread;
    });
    private final Timer timer = new Timer(3000, event -> { if (isShowing()) refreshPorts(); });
    private boolean closed, busy;
    private String lastUpdate, refreshError;
    private IntConsumer processSelection;

    public PortMonitorPanel(Session session) { this(new SshPortSource(session)); }
    PortMonitorPanel(PortSource source) {
        this.source = source; setLayout(new BorderLayout());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); actions.setOpaque(false);
        for (AbstractButton button : new AbstractButton[]{refreshButton, autoRefresh, processButton}) {
            button.setFont(ThemeUtils.uiFont(Font.PLAIN, 11)); button.setFocusable(false);
            button.putClientProperty(FlatClientProperties.STYLE, "arc: 4; margin: 2,8,2,8;"); actions.add(button);
        }
        refreshButton.addActionListener(event -> refreshPorts());
        autoRefresh.addItemListener(event -> { updateAutoRefresh(); updateRefreshTimer(); updateStatus(); });
        processButton.addActionListener(event -> {
            PortEntry selected = selectedPort();
            if (!closed && selected != null && selected.pid() != null && processSelection != null) processSelection.accept(selected.pid());
        });
        processButton.setToolTipText("切换到进程页并定位当前端口对应的 PID；不可见时不会提升权限");
        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "搜索端口 / 地址 / 进程 / PID");
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        searchField.setFont(ThemeUtils.uiFont(Font.PLAIN, 12));
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        });
        topBar.add(actions, BorderLayout.WEST); topBar.add(searchField, BorderLayout.EAST); add(topBar, BorderLayout.NORTH);
        table.setRowSorter(sorter); sorter.setSortsOnUpdates(true); sorter.setMaxSortKeys(1);
        sorter.setSortKeys(List.of(new RowSorter.SortKey(2, SortOrder.ASCENDING)));
        table.setRowHeight(com.formdev.flatlaf.util.UIScale.scale(25)); table.setShowGrid(false);
        table.putClientProperty(FlatClientProperties.STYLE, "showHorizontalLines: true; intercellSpacing: 0,1;");
        table.setFont(ThemeUtils.uiFont(Font.PLAIN, 12)); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(event -> updateProcessButton());
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setFont(ThemeUtils.uiFont(Font.BOLD, 12));
        table.getTableHeader().setToolTipText("点击表头切换升序 / 降序；端口和 PID 按数值排序");
        int[] widths = {75, 220, 80, 100, 80, 350};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(com.formdev.flatlaf.util.UIScale.scale(widths[i]));
        DefaultTableCellRenderer textRenderer = new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(t, value, selected, focus, row, column);
                putClientProperty("html.disable", Boolean.TRUE); setToolTipText(value == null ? "进程不可见（可能需要权限）" : value.toString());
                setText(value == null ? "—" : value.toString()); setBorder(new EmptyBorder(0, 8, 0, 8)); return this;
            }
        };
        table.setDefaultRenderer(String.class, textRenderer);
        table.setDefaultRenderer(Integer.class, new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) { setText(value == null ? "—" : value.toString()); setHorizontalAlignment(SwingConstants.RIGHT); setBorder(new EmptyBorder(0, 8, 0, 8)); }
        });
        JScrollPane scroll = new JScrollPane(table); scroll.setBorder(null); add(scroll, BorderLayout.CENTER);
        statusLabel.setFont(ThemeUtils.uiFont(Font.PLAIN, 11)); updatedLabel.setFont(ThemeUtils.uiFont(Font.PLAIN, 11));
        updatedLabel.setBorder(new EmptyBorder(0, 12, 0, 0));
        updatedLabel.setToolTipText("最近一次成功获取端口数据的本机时间；失败时保留原结果和时间");
        bottomBar.add(statusLabel, BorderLayout.CENTER); bottomBar.add(updatedLabel, BorderLayout.EAST); add(bottomBar, BorderLayout.SOUTH);
        addHierarchyListener(event -> { if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) updateRefreshTimer(); });
        applyTheme(); updateStatus(); updateProcessButton();
    }

    public void setProcessSelectionConsumer(IntConsumer consumer) { processSelection = consumer; updateProcessButton(); }
    private PortEntry selectedPort() { int row = table.getSelectedRow(); return row < 0 ? null : model.rows.get(table.convertRowIndexToModel(row)); }
    private void updateProcessButton() { PortEntry selected = selectedPort(); processButton.setEnabled(!closed && selected != null && selected.pid() != null && processSelection != null); }
    private void updateAutoRefresh() {
        autoRefresh.setText(closed ? "自动刷新：已停止" : autoRefresh.isSelected() ? "自动刷新：开 · 3秒" : "自动刷新：关");
        autoRefresh.setFont(ThemeUtils.uiFont(Font.BOLD, 11));
        autoRefresh.setForeground(!closed && autoRefresh.isSelected() ? ThemeUtils.getSuccess() : ThemeUtils.getTextSecondary());
        String description = closed ? "连接已断开，端口采集已停止" : autoRefresh.isSelected()
                ? "已开启，每 3 秒刷新；隐藏端口页时停止后续采集。点击关闭。" : "已关闭，点击开启。当前采集会完成，也可手动刷新。";
        autoRefresh.setToolTipText(description); autoRefresh.getAccessibleContext().setAccessibleDescription(description);
    }
    private void updateRefreshTimer() { timer.stop(); if (!closed && isShowing() && autoRefresh.isSelected()) { refreshPorts(); timer.start(); } }
    void refreshPorts() {
        if (closed || busy) return;
        busy = true; refreshError = null; refreshButton.setEnabled(false); updateStatus();
        worker.execute(() -> {
            try {
                List<PortEntry> rows = source.load();
                SwingUtilities.invokeLater(() -> { if (!closed) { busy = false; refreshButton.setEnabled(true); replacePorts(rows); } });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> { if (!closed) { busy = false; refreshButton.setEnabled(true); refreshError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); updateStatus(); } });
            }
        });
    }
    void replacePorts(List<PortEntry> rows) {
        PortEntry selected = selectedPort(); model.rows = List.copyOf(rows); model.fireTableDataChanged(); table.clearSelection();
        if (selected != null) for (int i = 0; i < rows.size(); i++) {
            PortEntry row = rows.get(i);
            if (row.protocol().equals(selected.protocol()) && row.address().equals(selected.address()) && row.port() == selected.port() && java.util.Objects.equals(row.pid(), selected.pid())) {
                int view = table.convertRowIndexToView(i); if (view >= 0) table.setRowSelectionInterval(view, view); break;
            }
        }
        lastUpdate = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")); refreshError = null;
        updatedLabel.setText("最近更新 " + lastUpdate); updateStatus(); updateProcessButton();
    }
    private void filter() {
        String query = searchField.getText().strip().toLowerCase(Locale.ROOT);
        sorter.setRowFilter(new RowFilter<>() {
            @Override public boolean include(Entry<? extends PortTableModel, ? extends Integer> entry) {
                PortEntry p = model.rows.get(entry.getIdentifier());
                return query.isEmpty() || (p.protocol() + " " + p.address() + " " + p.port() + " " + p.state() + " " + p.pid() + " " + p.process()).toLowerCase(Locale.ROOT).contains(query);
            }
        }); updateStatus(); updateProcessButton();
    }
    private void updateStatus() {
        statusLabel.setText(closed ? "连接已断开 · 端口监控已停止" : "监听端点 " + table.getRowCount() + " / " + model.getRowCount()
                + " · " + (autoRefresh.isSelected() ? "自动刷新已开启 · 每3秒" : "自动刷新已关闭")
                + " · " + (busy ? "正在刷新…" : refreshError != null ? "刷新失败，保留上次结果: " + refreshError
                : lastUpdate == null ? "打开端口页后开始采集" : "点击表头排序 · 进程不可见时可能需要权限"));
    }
    @Override public void close() {
        if (closed) return; closed = true; timer.stop(); source.close(); worker.shutdownNow();
        refreshButton.setEnabled(false); autoRefresh.setEnabled(false); updateAutoRefresh(); updateProcessButton(); updateStatus();
    }
    @Override public void applyTheme() {
        setBackground(ThemeUtils.getBg());
        topBar.setBackground(ThemeUtils.getHeaderBg()); bottomBar.setBackground(ThemeUtils.getHeaderBg());
        topBar.setBorder(BorderFactory.createCompoundBorder(ThemeUtils.createMatteBorder(0, 0, 1, 0), new EmptyBorder(5, 10, 5, 10)));
        bottomBar.setBorder(BorderFactory.createCompoundBorder(ThemeUtils.createMatteBorder(1, 0, 0, 0), new EmptyBorder(4, 10, 4, 10)));
        table.setBackground(ThemeUtils.getCardBg()); table.setForeground(ThemeUtils.getTextPrimary());
        table.getTableHeader().setBackground(ThemeUtils.getHeaderBg()); table.getTableHeader().setForeground(ThemeUtils.getTextPrimary());
        table.setSelectionBackground(ThemeUtils.getSelectionBg()); table.setSelectionForeground(ThemeUtils.isDark() ? Color.WHITE : ThemeUtils.getTextPrimary());
        searchField.setBackground(ThemeUtils.getCardBg()); searchField.setForeground(ThemeUtils.getTextPrimary());
        statusLabel.setForeground(ThemeUtils.getTextSecondary()); updatedLabel.setForeground(ThemeUtils.getTextSecondary()); updateAutoRefresh(); repaint();
    }
    private static final class PortTableModel extends AbstractTableModel {
        private final String[] names = {"协议", "监听地址", "端口", "状态", "PID", "占用进程"};
        private List<PortEntry> rows = List.of();
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return names.length; }
        public String getColumnName(int col) { return names[col]; }
        public Class<?> getColumnClass(int col) { return col == 2 || col == 4 ? Integer.class : String.class; }
        public Object getValueAt(int row, int col) {
            PortEntry p = rows.get(row);
            return switch (col) { case 0 -> p.protocol(); case 1 -> p.address(); case 2 -> p.port(); case 3 -> p.state(); case 4 -> p.pid(); default -> p.process(); };
        }
        public boolean isCellEditable(int row, int col) { return false; }
    }
}
