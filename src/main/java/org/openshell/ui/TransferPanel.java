package org.openshell.ui;

import com.jcraft.jsch.Session;
import org.openshell.transfer.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.*;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.CardLayout;
import java.awt.GridBagLayout;
import java.awt.Dimension;
import java.awt.Color;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.openshell.transfer.TransferManager.*;

/** Visible per-session transfers; network work stays off the Swing event thread. */
public final class TransferPanel extends JPanel implements ThemeAware, AutoCloseable {
    private final TransferManager manager;
    private final Model model = new Model();
    private final JTable table = new JTable(model) {
        @Override public String getToolTipText(MouseEvent event) {
            int row = rowAtPoint(event.getPoint()), column = columnAtPoint(event.getPoint());
            return row < 0 || column < 0 ? null : String.valueOf(getValueAt(row, column));
        }
    };
    private final JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
    private final JLabel status = new JLabel("暂无传输任务");
    private final JButton cancel = new JButton("取消选中"), retry = new JButton("从头重试"), clear = new JButton("清理记录"), details = new JButton("详情");
    private final JButton cancelBatch = new JButton("取消本批"), retryBatch = new JButton("重试失败项");
    private final JLabel batchSummary = new JLabel(" ");
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards), emptyState = new JPanel(new GridBagLayout());
    private final JLabel emptyTitle = new JLabel("还没有传输任务");
    private final JLabel emptyHelp = new JLabel("上传本地文件，或到文件页选择远程文件下载");
    private final JLabel emptyDetail = new JLabel("开始后，可在这里查看进度、取消或重试");
    private final JLabel actionHint = new JLabel("选中任务可查看详情、取消或重试");
    private final JLabel uploadTarget = new JLabel("上传到：等待文件页就绪") {
        @Override public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            return new Dimension(Math.min(size.width, com.formdev.flatlaf.util.UIScale.scale(560)), size.height);
        }
    };
    private Runnable showFiles, startUpload;
    private String uploadDirectory;
    private final Action uploadEntry = new AbstractAction("上传文件") {
        @Override public void actionPerformed(java.awt.event.ActionEvent event) {
            if (isEnabled() && startUpload != null) startUpload.run();
        }
    };
    private final Action filesEntry = new AbstractAction("去文件页") {
        @Override public void actionPerformed(java.awt.event.ActionEvent event) {
            if (isEnabled() && showFiles != null) showFiles.run();
        }
    };
    private final JButton emptyUpload = new JButton(uploadEntry);
    private final AtomicBoolean updateQueued = new AtomicBoolean();
    private Consumer<List<Snapshot>> changed = rows -> {};
    private volatile JDialog conflictDialog;

    public TransferPanel(Session session) { manager = new TransferManager(() -> new SftpTransfer(session), this::resolveConflict); initialize(); }
    TransferPanel(TransferManager manager) { this.manager = manager; initialize(); }
    private void initialize() {
        setLayout(new BorderLayout());
        for (Action entry : new Action[]{uploadEntry, filesEntry}) {
            JButton button = new JButton(entry); ThemeUtils.toolbarButton(button); toolbar.add(button);
        }
        for (JButton button : new JButton[]{cancel, retry, cancelBatch, retryBatch, clear, details}) {
            ThemeUtils.toolbarButton(button); toolbar.add(button);
        }
        actionHint.setFont(ThemeUtils.uiFont(Font.PLAIN, 11)); toolbar.add(actionHint);
        retry.setToolTipText("仅对失败或取消的任务可用；从头传输，并重新检查同名文件");
        clear.setToolTipText("仅移除已结束的任务记录，不删除文件，也不影响正在传输的任务");
        details.setToolTipText("选中任务后查看完整来源、目标和错误原因");
        cancel.addActionListener(event -> { Snapshot row = selected(); if (row != null) manager.cancel(row.id()); });
        retry.addActionListener(event -> { Snapshot row = selected(); if (row != null) manager.retry(row.id()); });
        cancelBatch.addActionListener(event -> { Snapshot row = selected(); if (row != null) manager.cancelBatch(row.request().batch()); });
        retryBatch.addActionListener(event -> { Snapshot row = selected(); if (row != null) manager.retryFailedBatch(row.request().batch()); });
        cancelBatch.setToolTipText("取消所选任务所属批次的全部等待和传输任务");
        retryBatch.setToolTipText("仅重新提交所选批次的失败项，已成功文件不会重新传输");
        clear.addActionListener(event -> manager.clearFinished());
        details.addActionListener(event -> {
            Snapshot row = selected();
            if (row != null) {
                JTextArea text = new JTextArea("来源: " + (row.request().direction() == Direction.UPLOAD ? row.request().local() : row.request().remote())
                        + "\n目标: " + row.destination() + "\n状态: " + row.state().label + "\n" + row.error(), 8, 65);
                text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
                JOptionPane.showMessageDialog(this, new JScrollPane(text), "传输详情", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        JPanel heading = new JPanel(new BorderLayout()); heading.add(toolbar, BorderLayout.NORTH);
        batchSummary.setBorder(new EmptyBorder(2, 8, 3, 8)); heading.add(batchSummary, BorderLayout.SOUTH); add(heading, BorderLayout.NORTH);
        table.setFont(ThemeUtils.uiFont(Font.PLAIN, 12));
        table.setRowHeight(com.formdev.flatlaf.util.UIScale.scale(26)); table.setShowGrid(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.setDefaultRenderer(String.class, new DefaultTableCellRenderer() {
            @Override public java.awt.Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focused, int row, int column) {
                super.getTableCellRendererComponent(t, value, selected, focused, row, column);
                putClientProperty("html.disable", true); setText(value == null ? "" : value.toString()); return this;
            }
        });
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setFont(ThemeUtils.uiFont(Font.BOLD, 12));
        int[] widths = {55, 160, 125, 120, 90, 100, 290, 240};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(com.formdev.flatlaf.util.UIScale.scale(widths[i]));
        table.getColumnModel().getColumn(2).setCellRenderer((t, value, selected, focused, row, column) -> {
            Snapshot task = model.rows.get(t.convertRowIndexToModel(row));
            JProgressBar bar = new JProgressBar(0, 100);
            int percent = task.state() == State.COMPLETED ? 100 : task.total() > 0 ? (int) Math.min(100, task.done() * 100.0 / task.total()) : 0;
            bar.setValue(percent); bar.setStringPainted(true); bar.setString(percent + "%");
            bar.setBorder(new EmptyBorder(6, 5, 6, 5));
            bar.setBackground(selected ? ThemeUtils.getSelectionBg() : ThemeUtils.getCardBg());
            return bar;
        });
        table.getSelectionModel().addListSelectionListener(event -> updateButtons());
        JScrollPane scroll = new JScrollPane(table); scroll.setBorder(null);
        content.add(scroll, "tasks"); content.add(buildEmptyState(), "empty");
        content.setMinimumSize(new Dimension(0, 0)); add(content, BorderLayout.CENTER);
        status.setFont(ThemeUtils.uiFont(Font.PLAIN, 11)); status.setBorder(new EmptyBorder(4, 8, 4, 8)); add(status, BorderLayout.SOUTH);
        manager.setListener(this::queueUpdate); applyTheme(); updateButtons();
    }
    private JPanel buildEmptyState() {
        JPanel stack = new JPanel(); stack.setOpaque(false); stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        emptyTitle.setFont(ThemeUtils.uiFont(Font.BOLD, 15));
        emptyHelp.setFont(ThemeUtils.uiFont(Font.PLAIN, 12)); emptyDetail.setFont(ThemeUtils.uiFont(Font.PLAIN, 11));
        uploadTarget.setFont(ThemeUtils.uiFont(Font.PLAIN, 11)); uploadTarget.putClientProperty("html.disable", true);
        for (JLabel label : new JLabel[]{emptyTitle, emptyHelp, emptyDetail, uploadTarget}) label.setAlignmentX(CENTER_ALIGNMENT);
        stack.add(emptyTitle); stack.add(Box.createVerticalStrut(com.formdev.flatlaf.util.UIScale.scale(6)));
        stack.add(emptyHelp); stack.add(Box.createVerticalStrut(com.formdev.flatlaf.util.UIScale.scale(3))); stack.add(emptyDetail);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 7)); actions.setOpaque(false);
        emptyUpload.setFont(ThemeUtils.uiFont(Font.BOLD, 12));
        JButton files = new JButton(filesEntry); files.setFont(ThemeUtils.uiFont(Font.PLAIN, 12));
        actions.add(emptyUpload); actions.add(files); stack.add(actions); stack.add(uploadTarget);
        emptyState.add(stack); return emptyState;
    }
    public void setFileActions(Runnable showFiles, Runnable startUpload) {
        this.showFiles = showFiles; this.startUpload = startUpload; updateButtons();
    }
    public void setUploadDirectory(String directory) {
        uploadDirectory = directory;
        uploadTarget.setText("上传到：" + directory); uploadTarget.setToolTipText(directory);
        updateButtons();
    }
    public TransferManager manager() { return manager; }
    public void onChanged(Consumer<List<Snapshot>> changed) { this.changed = changed; }
    public UUID submit(Direction direction, Path local, String remote, UUID batch) { return manager.submit(direction, local, remote, batch); }
    private void queueUpdate(List<Snapshot> rows) {
        if (updateQueued.compareAndSet(false, true)) SwingUtilities.invokeLater(() -> {
            updateQueued.set(false); updateRows(manager.snapshots());
        });
    }
    void updateRows(List<Snapshot> rows) {
        Snapshot before = selected(); model.rows = List.copyOf(rows); model.fireTableDataChanged(); table.clearSelection();
        if (before != null) for (int i = 0; i < rows.size(); i++) if (rows.get(i).id().equals(before.id())) {
            int view = table.convertRowIndexToView(i); if (view >= 0) table.setRowSelectionInterval(view, view); break;
        }
        long waiting = rows.stream().filter(r -> r.state() == State.QUEUED).count();
        long active = rows.stream().filter(r -> !r.state().terminal() && r.state() != State.QUEUED).count();
        long done = rows.stream().filter(r -> r.state() == State.COMPLETED).count();
        long failed = rows.stream().filter(r -> r.state() == State.FAILED).count();
        long cancelled = rows.stream().filter(r -> r.state() == State.CANCELLED || r.state() == State.SKIPPED).count();
        status.setText((manager.isClosed() ? "队列已停止 · " : "") + "等待 " + waiting + " · 进行中 " + active
                + " · 完成 " + done + " · 失败 " + failed + " · 取消/跳过 " + cancelled);
        if (rows.isEmpty()) status.setText(manager.isClosed() ? "队列已停止，请重新连接后传输" : "上传和下载任务会显示在这里");
        updateButtons(); changed.accept(rows);
    }
    private Snapshot selected() { int row = table.getSelectedRow(); return row < 0 ? null : model.rows.get(table.convertRowIndexToModel(row)); }
    private void updateButtons() {
        boolean empty = model.rows.isEmpty();
        cards.show(content, empty ? "empty" : "tasks"); toolbar.setVisible(!empty);
        emptyTitle.setText(manager.isClosed() ? "连接已断开" : "还没有传输任务");
        emptyHelp.setText(manager.isClosed() ? "重新连接后，可继续上传和下载文件" : "上传本地文件，或到文件页选择远程文件下载");
        emptyDetail.setText(manager.isClosed() ? "文件页仍可查看上次的目录内容" : "开始后，可在这里查看进度、取消或重试");
        uploadEntry.setEnabled(!manager.isClosed() && startUpload != null && uploadDirectory != null);
        filesEntry.setEnabled(showFiles != null);
        uploadEntry.putValue(Action.SHORT_DESCRIPTION, manager.isClosed() ? "连接已断开，请先重新连接"
                : uploadDirectory == null ? "请先在文件页打开一个目录" : "选择本地文件，上传到 " + uploadDirectory);
        filesEntry.putValue(Action.SHORT_DESCRIPTION, "打开文件页，选中远程文件后点击下载");
        Snapshot row = selected();
        var batches = manager.batches();
        var batch = row == null ? (batches.isEmpty() ? null : batches.get(batches.size()-1)) : batches.stream().filter(b -> b.id().equals(row.request().batch())).findFirst().orElse(null);
        cancelBatch.setEnabled(row != null && batch != null && batch.pending() > 0);
        retryBatch.setEnabled(row != null && !manager.isClosed() && batch != null && batch.failed() > 0);
        batchSummary.setVisible(!empty);
        batchSummary.setText(batch == null ? "选择任务查看所属批次的总进度" : (row == null ? "最近批次 " : "本批 ") + batch.finished() + " / " + batch.count() + " 项已结束 · "
                + size(batch.done()) + " / " + size(batch.total()) + " · 失败 " + batch.failed()
                + (batch.total() > 0 ? " · " + Math.min(100, batch.done() * 100 / batch.total()) + "%" : ""));
        cancel.setEnabled(row != null && !row.state().terminal() && row.state() != State.COMMITTING && row.state() != State.CANCELLING);
        retry.setEnabled(!manager.isClosed() && row != null && (row.state() == State.FAILED || row.state() == State.CANCELLED));
        details.setEnabled(row != null); clear.setEnabled(model.rows.stream().anyMatch(r -> r.state().terminal()));
        cancel.setToolTipText(row == null ? "先选中等待或传输中的任务" : row.state() == State.COMMITTING
                ? "正在完成文件替换，此时不能取消" : "停止选中的任务，原目标不会被半截传输内容覆盖");
        actionHint.setText(row == null ? "选中任务可查看详情、取消或重试" : switch (row.state()) {
            case QUEUED -> "等待按顺序传输，可取消";
            case RUNNING -> "正在传输，文件页可继续浏览";
            case WAITING -> "请完成同名文件处理";
            case CANCELLING -> "正在取消并清理临时文件";
            case COMMITTING -> "正在保存完整文件，请稍候";
            case COMPLETED -> "传输完成，可查看详情或清理记录";
            case SKIPPED -> "已跳过该文件，原目标未改变";
            case FAILED, CANCELLED -> "可从头重试，会重新检查同名文件";
        });
    }
    private Decision resolveConflict(Snapshot row) throws Exception {
        AtomicReference<Decision> result = new AtomicReference<>(new Decision(Choice.CANCEL, false));
        SwingUtilities.invokeAndWait(() -> {
            if (manager.isClosed() || manager.snapshots().stream().noneMatch(r -> r.id().equals(row.id()) && r.state() == State.WAITING)) return;
            JCheckBox apply = new JCheckBox("对本批次的同名文件采用相同处理");
            JTextArea text = new JTextArea("目标文件已存在：\n" + row.destination(), 3, 55);
            text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true); text.setOpaque(false);
            JPanel content = new JPanel(new BorderLayout(0, 8)); content.add(text, BorderLayout.CENTER); content.add(apply, BorderLayout.SOUTH);
            String[] options = {"覆盖", "保留两者", "跳过", "取消任务"};
            JOptionPane pane = new JOptionPane(content, JOptionPane.QUESTION_MESSAGE, JOptionPane.DEFAULT_OPTION, null, options, "跳过");
            JDialog dialog = pane.createDialog(this, "同名文件处理"); conflictDialog = dialog;
            try {
                dialog.setVisible(true); Object choice = pane.getValue();
                Choice selected = "覆盖".equals(choice) ? Choice.REPLACE : "保留两者".equals(choice) ? Choice.KEEP_BOTH
                        : "跳过".equals(choice) ? Choice.SKIP : Choice.CANCEL;
                result.set(new Decision(selected, apply.isSelected()));
            } finally { conflictDialog = null; dialog.dispose(); }
        });
        return result.get();
    }
    public boolean confirmStopPending() {
        long pending = manager.pendingCount();
        return pending == 0 || JOptionPane.showConfirmDialog(this, "仍有 " + pending + " 项传输未结束。\n继续会停止未完成传输并关闭通道。\n若任务处于“正在完成”，关闭后请核对最终目标文件。",
                "停止此会话的传输？", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }
    @Override public void close() {
        manager.close(); JDialog dialog = conflictDialog; if (dialog != null) dialog.dispose();
    }
    @Override public void applyTheme() {
        setBackground(ThemeUtils.getBg()); toolbar.setBackground(ThemeUtils.getHeaderBg());
        content.setBackground(ThemeUtils.getCardBg()); emptyState.setBackground(ThemeUtils.getCardBg());
        emptyTitle.setForeground(ThemeUtils.getTextPrimary()); emptyHelp.setForeground(ThemeUtils.getTextSecondary());
        emptyDetail.setForeground(ThemeUtils.getTextMuted()); uploadTarget.setForeground(ThemeUtils.getTextSecondary());
        actionHint.setForeground(ThemeUtils.getTextSecondary());
        emptyUpload.setBackground(ThemeUtils.getAccent()); emptyUpload.setForeground(Color.WHITE);
        table.setBackground(ThemeUtils.getCardBg()); table.setForeground(ThemeUtils.getTextPrimary());
        table.getTableHeader().setBackground(ThemeUtils.getHeaderBg()); table.getTableHeader().setForeground(ThemeUtils.getTextPrimary());
        table.setSelectionBackground(ThemeUtils.getSelectionBg()); table.setSelectionForeground(ThemeUtils.getTextPrimary());
        status.setForeground(ThemeUtils.getTextSecondary()); repaint();
    }
    private static String size(long bytes) {
        if (bytes < 0) return "—";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
    }
    private static final class Model extends AbstractTableModel {
        List<Snapshot> rows = List.of();
        String[] names = {"方向", "文件", "进度", "已传 / 总量", "速度", "状态", "目标", "说明"};
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return names.length; }
        public String getColumnName(int column) { return names[column]; }
        public Object getValueAt(int row, int column) {
            Snapshot t = rows.get(row);
            return switch (column) {
                case 0 -> (t.request().direction() == Direction.UPLOAD ? "上传" : "下载") + (t.request().kind() == Kind.DIRECTORY ? "目录" : "");
                case 1 -> t.request().direction() == Direction.UPLOAD ? t.request().local().getFileName().toString()
                        : t.request().remote().substring(t.request().remote().lastIndexOf('/') + 1);
                case 2 -> t.state() == State.COMPLETED ? 100.0 : t.total() > 0 ? Math.min(100.0, t.done() * 100.0 / t.total()) : 0.0;
                case 3 -> size(t.done()) + " / " + size(t.total());
                case 4 -> t.state() == State.RUNNING ? size((long) t.bytesPerSecond()) + "/s" : "—";
                case 5 -> t.state().label; case 6 -> t.destination(); case 7 -> t.error();
                default -> throw new IndexOutOfBoundsException();
            };
        }
        public Class<?> getColumnClass(int column) { return column == 2 ? Double.class : String.class; }
    }
}
