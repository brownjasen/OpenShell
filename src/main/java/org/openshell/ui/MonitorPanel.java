package org.openshell.ui;

import org.openshell.ssh.ServerMonitor;
import com.formdev.flatlaf.util.UIScale;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayDeque;
import java.util.Deque;

/** Four resource readings with quiet usage tracks; no synthetic samples. */
public class MonitorPanel extends JPanel implements ServerMonitor.MonitorListener, ThemeAware, AutoCloseable {
    private final ResourceTile cpu = new ResourceTile("CPU", true);
    private final ResourceTile memory = new ResourceTile("内存", true);
    private final ResourceTile disk = new ResourceTile("磁盘 /", true);
    private final ResourceTile network = new ResourceTile("网络 ↓ / ↑", false);
    private final JPanel metrics = new JPanel(new GridLayout(1, 4));
    private final NetworkWaveChart netChart = new NetworkWaveChart();
    private final JButton settings = new JButton("⋮");
    private ServerMonitor monitor;
    private volatile boolean monitoringEnabled = true;
    private volatile long uiGeneration;
    private volatile boolean closed;

    public MonitorPanel() {
        setLayout(new BorderLayout(UIScale.scale(12), 0));
        setPreferredSize(new Dimension(UIScale.scale(600), UIScale.scale(50)));
        setMinimumSize(new Dimension(UIScale.scale(320), UIScale.scale(50)));
        metrics.setOpaque(false);
        metrics.add(cpu); metrics.add(memory); metrics.add(disk); metrics.add(network);
        netChart.setPreferredSize(new Dimension(UIScale.scale(180), UIScale.scale(28)));
        netChart.setVisible(false);
        add(metrics, BorderLayout.CENTER);
        JPanel extras = new JPanel(); extras.setOpaque(false); extras.setLayout(new BoxLayout(extras, BoxLayout.X_AXIS));
        extras.add(netChart);
        settings.setToolTipText("监控设置：选择网卡和磁盘挂载点");
        settings.putClientProperty("JButton.buttonType", "toolBarButton"); settings.setFocusable(false);
        settings.setMargin(new Insets(1, 4, 1, 4)); settings.setEnabled(false);
        settings.addActionListener(event -> showSettings()); extras.add(settings);
        add(extras, BorderLayout.EAST);
        cpu.caption.setText("等待监控");
        applyTheme();
    }

    public void bindMonitor(ServerMonitor monitor) {
        if (closed) return;
        this.monitor = monitor; uiGeneration++; settings.setEnabled(monitor != null); updateSelectionCaptions();
    }

    /** The session calls this with its checkbox/tab visibility state; queued older callbacks are discarded. */
    public void setMonitoringEnabled(boolean enabled) {
        if (closed || monitoringEnabled == enabled) return;
        monitoringEnabled = enabled; long generation = ++uiGeneration;
        Runnable refresh = () -> {
            if (generation != uiGeneration) return;
            cpu.caption.setText(enabled ? "等待监控" : "监控已暂停");
            for (ResourceTile tile : new ResourceTile[]{cpu, memory, disk, network}) tile.unavailable(enabled ? "等待新的采样" : "监控隐藏时暂停采集");
            netChart.markUnavailable();
        };
        if (SwingUtilities.isEventDispatchThread()) refresh.run(); else SwingUtilities.invokeLater(refresh);
    }

    @Override public void close() {
        closed = true; monitoringEnabled = false; uiGeneration++; monitor = null;
        settings.setEnabled(false);
    }

    private void updateSelectionCaptions() {
        ServerMonitor.Selection selection = monitor == null ? new ServerMonitor.Selection("", "/") : monitor.getSelection();
        disk.caption.setText("磁盘 " + selection.mountPoint());
        network.caption.setText(selection.networkInterface().isEmpty() ? "网络 ↓ / ↑" : selection.networkInterface() + " ↓ / ↑");
        disk.caption.setToolTipText("磁盘挂载点：" + selection.mountPoint());
        network.caption.setToolTipText(selection.networkInterface().isEmpty() ? "合计非回环网卡；网桥/VPN 可能重复统计，可在监控设置中选择实际网卡" : "当前网卡：" + selection.networkInterface());
    }

    private void showSettings() {
        ServerMonitor target = monitor; if (target == null || closed) return;
        settings.setEnabled(false); settings.setToolTipText("正在查询可用网卡和挂载点…");
        Thread worker = new Thread(() -> {
            ServerMonitor.Resources found = null; String error = null;
            try { found = target.discoverResources(); } catch (Exception failure) { error = failure.getMessage(); }
            ServerMonitor.Resources resources = found; String failure = error;
            SwingUtilities.invokeLater(() -> {
                if (monitor != target || closed) return;
                settings.setEnabled(true); settings.setToolTipText("监控设置：选择网卡和磁盘挂载点");
                ServerMonitor.Selection current = target.getSelection();
                JComboBox<String> nic = new JComboBox<>(); nic.addItem("自动合计（非回环网卡）");
                if (resources != null) resources.networkInterfaces().forEach(nic::addItem);
                if (!current.networkInterface().isEmpty()) { nic.setSelectedItem(current.networkInterface()); if (!current.networkInterface().equals(nic.getSelectedItem())) { nic.addItem(current.networkInterface()); nic.setSelectedItem(current.networkInterface()); } }
                JComboBox<String> mount = new JComboBox<>();
                if (resources != null) resources.mountPoints().forEach(mount::addItem);
                mount.setEditable(true); mount.setSelectedItem(current.mountPoint());
                JPanel form = new JPanel(new GridLayout(0, 2, 8, 8));
                form.add(new JLabel("网络统计网卡")); form.add(nic); form.add(new JLabel("磁盘挂载点")); form.add(mount);
                JPanel content = new JPanel(new BorderLayout(0, 10)); content.add(form, BorderLayout.CENTER);
                content.add(new JLabel(failure == null ? "切换后重新采样；隐藏监控会暂停采集" : "资源列表查询失败，可手动填写挂载点：" + failure), BorderLayout.SOUTH);
                if (JOptionPane.showConfirmDialog(this, content, "监控设置", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                    try {
                        uiGeneration++;
                        target.setSelection(nic.getSelectedIndex() == 0 ? "" : String.valueOf(nic.getSelectedItem()), String.valueOf(mount.getEditor().getItem()));
                        updateSelectionCaptions();
                        for (ResourceTile tile : new ResourceTile[]{cpu, memory, disk, network}) tile.unavailable("等待新的采样");
                        netChart.markUnavailable();
                    } catch (IllegalArgumentException invalid) { JOptionPane.showMessageDialog(this, invalid.getMessage(), "监控设置", JOptionPane.WARNING_MESSAGE); }
                }
            });
        }, "OpenShell-MonitorResources"); worker.setDaemon(true); worker.start();
    }

    @Override public void doLayout() {
        boolean detailed = getWidth() >= UIScale.scale(620);
        for (ResourceTile tile : new ResourceTile[]{cpu, memory, disk}) tile.detail.setVisible(detailed);
        netChart.setVisible(getWidth() >= UIScale.scale(1150));
        super.doLayout();
    }

    @Override public void applyTheme() {
        setBackground(ThemeUtils.getHeaderBg());
        for (ResourceTile tile : new ResourceTile[]{cpu, memory, disk, network}) tile.applyTheme();
        repaint();
    }
    @Override public void onStatus(boolean healthy, String message) {
        long generation = uiGeneration;
        SwingUtilities.invokeLater(() -> {
            if (generation != uiGeneration || !monitoringEnabled || closed) return;
            cpu.caption.setText(healthy ? "CPU" : message.startsWith("等待") ? "等待监控" : "监控不可用");
            setToolTipText(message);
            if (!healthy) {
                for (ResourceTile tile : new ResourceTile[]{cpu, memory, disk, network}) tile.unavailable(message);
                netChart.markUnavailable();
            }
        });
    }
    @Override public void onUpdate(double cpuPercent, double memPercent, long memUsedMB, long memTotalMB,
                                   double diskPercent, double diskUsedGB, double diskTotalGB,
                                   double rxSpeedKB, double txSpeedKB, String loadAvg) {
        long generation = uiGeneration;
        SwingUtilities.invokeLater(() -> {
            if (generation != uiGeneration || !monitoringEnabled || closed) return;
            cpu.caption.setText("CPU");
            cpu.update(cpuPercent, loadAvg == null || loadAvg.isBlank() ? "" : "负载 " + loadAvg.split("\\s+")[0], "CPU 使用率；系统负载 " + loadAvg);
            memory.update(memPercent, String.format("%.1f/%.1fG", memUsedMB / 1024.0, memTotalMB / 1024.0),
                    String.format("内存：%.1f / %.1f GB（%.1f%%）", memUsedMB / 1024.0, memTotalMB / 1024.0, memPercent));
            disk.update(diskPercent, String.format("%.1f/%.1fG", diskUsedGB, diskTotalGB),
                    String.format("%s：%.1f / %.1f GB（%.1f%%）", monitor == null ? "/" : monitor.getSelection().mountPoint(), diskUsedGB, diskTotalGB, diskPercent));
            network.value.setText(compactRate(rxSpeedKB) + " / " + compactRate(txSpeedKB));
            String nic = monitor == null ? "" : monitor.getSelection().networkInterface();
            network.setHint((nic.isEmpty() ? "合计非回环网卡（网桥/VPN 可能重复统计） · " : nic + " · ")
                    + String.format("下载 %.1f KB/s · 上传 %.1f KB/s", rxSpeedKB, txSpeedKB));
            if (Double.isFinite(rxSpeedKB) && Double.isFinite(txSpeedKB)) netChart.pushData(rxSpeedKB, txSpeedKB);
            else netChart.markUnavailable();
            setToolTipText("监控正常 · 每 1.5 秒采样；网络 K/M 表示 KB/s、MB/s");
        });
    }

    private static String compactRate(double kbps) {
        if (!Double.isFinite(kbps)) return "—";
        double value = Math.max(0, kbps);
        return value < 1024 ? String.format("%.0fK", value) : value < 10240
                ? String.format("%.1fM", value / 1024) : String.format("%.0fM", value / 1024);
    }

    private static final class ResourceTile extends JPanel {
        private final JLabel caption, value = new JLabel("—"), detail = new JLabel("");
        private final JProgressBar usage;
        private double percent = Double.NaN;
        ResourceTile(String title, boolean showUsage) {
            super(new BorderLayout(0, UIScale.scale(2))); setOpaque(false);
            caption = new JLabel(title); caption.setFont(ThemeUtils.uiFont(Font.PLAIN, 10));
            value.setFont(ThemeUtils.uiFont(Font.BOLD, 12)); detail.setFont(ThemeUtils.uiFont(Font.PLAIN, 10));
            JPanel reading = new JPanel(); reading.setOpaque(false);
            reading.setLayout(new BoxLayout(reading, BoxLayout.X_AXIS));
            reading.add(value); reading.add(Box.createHorizontalStrut(UIScale.scale(5))); reading.add(detail);
            add(caption, BorderLayout.NORTH); add(reading, BorderLayout.CENTER);
            usage = showUsage ? new JProgressBar(0, 1000) : null;
            JComponent track = usage == null ? new JPanel() : usage;
            track.setOpaque(false); track.setPreferredSize(new Dimension(0, UIScale.scale(3)));
            if (usage != null) { usage.setBorderPainted(false); usage.setStringPainted(false); }
            add(track, BorderLayout.SOUTH);
        }
        void applyTheme() {
            setBorder(BorderFactory.createCompoundBorder(ThemeUtils.createMatteBorder(0, 0, 0, 1),
                    new EmptyBorder(UIScale.scale(4), UIScale.scale(10), UIScale.scale(5), UIScale.scale(10))));
            caption.setForeground(ThemeUtils.getTextSecondary()); value.setForeground(ThemeUtils.getTextPrimary());
            detail.setForeground(ThemeUtils.getTextSecondary());
            if (usage != null) { usage.setBackground(ThemeUtils.getSubtleBorderColor()); updateColor(); }
        }
        void update(double percent, String extra, String hint) {
            this.percent = percent;
            value.setText(Double.isFinite(percent) ? String.format("%.1f%%", percent) : "—");
            detail.setText(extra);
            if (usage != null) usage.setValue(Double.isFinite(percent) ? (int)(Math.max(0, Math.min(100, percent)) * 10) : 0);
            setHint(hint); updateColor();
        }
        private void updateColor() {
            if (usage != null) usage.setForeground(percent >= 90 ? ThemeUtils.getDanger() : percent >= 75 ? ThemeUtils.getWarning() : ThemeUtils.getAccent());
        }
        void setHint(String hint) {
            setToolTipText(hint); caption.setToolTipText(hint); value.setToolTipText(hint); detail.setToolTipText(hint);
        }
        void unavailable(String hint) {
            percent = Double.NaN; value.setText("—"); detail.setText(""); if (usage != null) usage.setValue(0); setHint(hint);
        }
    }

    public static class NetworkWaveChart extends JComponent {
        private final Deque<Double> rxList = new ArrayDeque<>();
        private final Deque<Double> txList = new ArrayDeque<>();
        private static final int MAX_POINTS = 32;
        private double lastRx = Double.NaN;
        private double lastTx = Double.NaN;

        public NetworkWaveChart() {
            setPreferredSize(new Dimension(280, 52));
            for (int i = 0; i < MAX_POINTS; i++) {
                rxList.add(0.0);
                txList.add(0.0);
            }
        }

        public void markUnavailable() {
            lastRx = Double.NaN; lastTx = Double.NaN;
            rxList.clear(); txList.clear();
            for (int i = 0; i < MAX_POINTS; i++) { rxList.add(0.0); txList.add(0.0); }
            repaint();
        }

        public void pushData(double rxKB, double txKB) {
            this.lastRx = rxKB;
            this.lastTx = txKB;
            setToolTipText("下载 " + formatSpeed(rxKB) + " · 上传 " + formatSpeed(txKB));

            rxList.removeFirst();
            rxList.addLast(rxKB);

            txList.removeFirst();
            txList.addLast(txKB);

            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            // 1. 卡片背景底板与微边框
            g2.setColor(ThemeUtils.getCardBg());
            g2.fillRoundRect(0, 1, w, h - 2, 8, 8);
            g2.setColor(ThemeUtils.getBorderColor());
            g2.drawRoundRect(0, 1, w - 1, h - 3, 8, 8);

            // Rates are already shown in the metrics row; use the full height for history.
            int chartTop = UIScale.scale(4);
            int chartBottom = h - UIScale.scale(4);
            int chartHeight = Math.max(1, chartBottom - chartTop);

            // 绘制底线参考轴
            g2.setColor(ThemeUtils.isDark() ? new Color(38, 42, 52) : new Color(230, 235, 242));
            g2.drawLine(10, chartBottom, w - 10, chartBottom);
            g2.drawLine(10, chartTop + chartHeight / 2, w - 10, chartTop + chartHeight / 2);

            // 计算缩放上限
            double maxSpeed = 10.0;
            for (Double d : rxList) maxSpeed = Math.max(maxSpeed, d);
            for (Double d : txList) maxSpeed = Math.max(maxSpeed, d);

            Color rxColor = ThemeUtils.getAccent();
            Color txColor = ThemeUtils.getWarning();
            Color rxFill = new Color(rxColor.getRed(), rxColor.getGreen(), rxColor.getBlue(), 30);
            Color txFill = new Color(txColor.getRed(), txColor.getGreen(), txColor.getBlue(), 25);

            // 绘制波形线与半透明渐变面积
            if (!Double.isNaN(lastRx)) {
                drawWave(g2, rxList, maxSpeed, rxColor, rxFill, w, chartTop, chartBottom, chartHeight);
                drawWave(g2, txList, maxSpeed, txColor, txFill, w, chartTop, chartBottom, chartHeight);
            }

            g2.dispose();
        }

        private void drawWave(Graphics2D g2, Deque<Double> data, double maxVal, Color strokeColor, Color fillColor, int w, int topY, int bottomY, int chartHeight) {
            int size = data.size();
            int[] xPoints = new int[size + 2];
            int[] yPoints = new int[size + 2];

            int i = 0;
            double step = (double) (w - 20) / (size - 1);

            for (Double val : data) {
                int px = (int) Math.round(10 + i * step);
                double normalized = Math.min(1.0, val / maxVal);
                int py = (int) Math.round(bottomY - (normalized * chartHeight));
                xPoints[i] = px;
                yPoints[i] = py;
                i++;
            }

            // 闭合多边形以填充半透明波形面积
            xPoints[size] = (int) Math.round(10 + (size - 1) * step);
            yPoints[size] = bottomY;
            xPoints[size + 1] = 10;
            yPoints[size + 1] = bottomY;

            g2.setColor(fillColor);
            g2.fillPolygon(xPoints, yPoints, size + 2);

            // 绘制平滑波形折线
            g2.setColor(strokeColor);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawPolyline(xPoints, yPoints, size);
        }

        private String formatSpeed(double kbps) {
            if (kbps < 1024) {
                return String.format("%.1f KB/s", kbps);
            } else {
                return String.format("%.2f MB/s", kbps / 1024.0);
            }
        }
    }
}
