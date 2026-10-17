package org.openshell.ui;

import org.openshell.model.HostConfig;
import org.openshell.storage.HostStorage;
import org.openshell.ssh.SshSession;
import org.openshell.support.LocalSshServer;
import javax.swing.*;
import javax.swing.tree.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real components and isolated loopback SSH; no saved user hosts are loaded. */
public final class WorkbenchAppearancePreview {
    public static void main(String[] args) throws Exception {
        System.setProperty("flatlaf.useWindowDecorations", "true");
        try { preview(); }
        catch (Throwable failure) {
            java.io.StringWriter trace = new java.io.StringWriter(); failure.printStackTrace(new java.io.PrintWriter(trace));
            Files.writeString(Path.of(".work2/ui-preview-error.log"), trace.toString());
            System.out.println(trace); throw failure;
        }
    }
    private static void preview() throws Exception {
        Path output = Path.of("OpenShell/target/verification").toAbsolutePath(); Files.createDirectories(output);
        Path isolated = Files.createTempDirectory(output, "appearance-");
        System.setProperty("openshell.configDir", isolated.resolve("config").toAbsolutePath().toString());
        Path remote = Files.createDirectory(isolated.resolve("remote"));
        Files.createDirectory(remote.resolve("config")); Files.createDirectory(remote.resolve("logs"));
        Files.writeString(remote.resolve("部署说明.txt"), "隔离测试文件");
        Files.writeString(remote.resolve("app.conf"), "port=8080");
        AtomicReference<MainWindow> frame = new AtomicReference<>();
        try (LocalSshServer server = new LocalSshServer(remote)) {
            HostConfig host = server.host(); host.setName("开发服务器"); host.setGroup("开发环境");
            HostConfig backup = new HostConfig("备份服务器", "example.invalid", 22, "backup"); backup.setGroup("开发环境");
            HostStorage.saveHosts(java.util.List.of(host, backup));
            // Trust only this generated test server key, within the isolated configuration.
            SshSession primed = new SshSession(host, (h, a, f) -> true); primed.connect(); primed.disconnect();
            SwingUtilities.invokeAndWait(() -> {
                ThemeUtils.install(true);
                System.out.println("UI_FONT=" + UIManager.getFont("defaultFont"));
                MainWindow window = new MainWindow(); frame.set(window);
                window.setSize(Integer.getInteger("openshell.previewWidth", 1366), Integer.getInteger("openshell.previewHeight", 768));
                window.addNotify(); window.validate(); window.openSession(host);
            });
            long deadline = System.currentTimeMillis() + 10000;
            while (true) {
                AtomicReference<Boolean> ready = new AtomicReference<>(false);
                SwingUtilities.invokeAndWait(() -> {
                    SftpPanel files = UiRenderingRegressionTest.find(frame.get(), SftpPanel.class);
                    JTable table = files == null ? null : UiRenderingRegressionTest.find(files, JTable.class);
                    ready.set(table != null && table.getRowCount() >= 4);
                });
                if (ready.get()) break;
                if (System.currentTimeMillis() >= deadline) throw new AssertionError("Preview session did not connect");
                Thread.sleep(25);
            }
            server.shell.send("\u001b[2J\u001b[H隔离测试 SSH 服务 · UTF-8 中文显示\r\n\r\n"
                    + "test@dev:~$ ls -l\r\nconfig/   logs/   app.conf   部署说明.txt\r\n\r\ntest@dev:~$ ");
            Path upload = isolated.resolve("上传示例.txt"); Files.writeString(upload, "隔离传输预览，非用户服务器数据");
            AtomicReference<TransferPanel> transferView = new AtomicReference<>();
            AtomicReference<java.util.UUID> transferId = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                transferView.set(UiRenderingRegressionTest.find(frame.get(), TransferPanel.class));
                JTabbedPane toolTabs = (JTabbedPane) transferView.get().getParent();
                toolTabs.setSelectedComponent(transferView.get());
                frame.get().setTitle("OpenShell · 隔离预览 / 空白传输页"); prepare(frame.get());
                String guideScale = System.getProperty("flatlaf.uiScale", "1");
                write(frame.get().getRootPane(), output.resolve("workbench-transfers-empty-" + guideScale + ".png"));
                frame.get().toggleTheme(); prepare(frame.get());
                write(frame.get().getRootPane(), output.resolve("workbench-transfers-empty-light-" + guideScale + ".png"));
                frame.get().toggleTheme(); toolTabs.setSelectedIndex(toolTabs.indexOfTab("文件"));
                transferId.set(transferView.get().submit(org.openshell.transfer.TransferManager.Direction.UPLOAD,
                        upload, "/上传示例.txt", java.util.UUID.randomUUID()));
            });
            long transferDeadline = System.currentTimeMillis() + 8000;
            while (!transferView.get().manager().find(transferId.get()).state().terminal()) {
                if (System.currentTimeMillis() > transferDeadline) throw new AssertionError("Packaged SFTP transfer timed out");
                Thread.sleep(20);
            }
            if (transferView.get().manager().find(transferId.get()).state() != org.openshell.transfer.TransferManager.State.COMPLETED)
                throw new AssertionError(transferView.get().manager().find(transferId.get()).error());
            if (!Files.readString(remote.resolve("上传示例.txt")).equals(Files.readString(upload))) throw new AssertionError("Packaged transfer content mismatch");
            long listingDeadline = System.currentTimeMillis() + 5000;
            while (true) {
                java.util.concurrent.atomic.AtomicBoolean listed = new java.util.concurrent.atomic.AtomicBoolean();
                SwingUtilities.invokeAndWait(() -> listed.set(UiRenderingRegressionTest.find(UiRenderingRegressionTest.find(frame.get(), SftpPanel.class), JTable.class).getRowCount() >= 5));
                if (listed.get()) break;
                if (System.currentTimeMillis() > listingDeadline) throw new AssertionError("Uploaded file did not appear in browser");
                Thread.sleep(20);
            }
            boolean sampleMetrics = Boolean.getBoolean("openshell.previewSampleMetrics");
            if (sampleMetrics) SwingUtilities.invokeAndWait(() -> {
                frame.get().setTitle("OpenShell · 隔离预览 / 示例指标");
                UiRenderingRegressionTest.find(frame.get(), MonitorPanel.class).setMonitoringEnabled(true);
                UiRenderingRegressionTest.find(frame.get(), MonitorPanel.class)
                        .onUpdate(8.5, 65, 614, 944, 18.2, 11.4, 62.7, 3, 14, "0.03 0.02 0.01");
            });
            String suffix = System.getProperty("flatlaf.uiScale", "1") +
                    (System.getProperty("openshell.previewWidth") == null ? "" : "-" + System.getProperty("openshell.previewWidth"))
                    + (sampleMetrics ? "-sample" : "");
            SwingUtilities.invokeAndWait(() -> {
                MainWindow window = frame.get(); attachHeaders(window);
                JTree tree = UiRenderingRegressionTest.find(window, JTree.class);
                DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
                var nodes = root.depthFirstEnumeration();
                while (nodes.hasMoreElements()) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
                    if (node.getUserObject() instanceof HostConfig value && value.getId().equals(host.getId())
                            && node.getParent().toString().equals("开发环境")) {
                        TreePath path = new TreePath(node.getPath()); tree.setSelectionPath(path); tree.scrollPathToVisible(path); break;
                    }
                }
                prepare(window);
            });
            SwingUtilities.invokeAndWait(() -> {
                MainWindow window = frame.get(); prepare(window);
                write(window.getRootPane(), output.resolve("workbench-dark-" + suffix + ".png"));
                write(UiRenderingRegressionTest.find(window, HostTreePanel.class), output.resolve("host-groups-" + suffix + ".png"));
                write(UiRenderingRegressionTest.find(window, TerminalPanel.class), output.resolve("terminal-typography-" + suffix + ".png"));
                write((JComponent) UiRenderingRegressionTest.find(window, MonitorPanel.class).getParent(), output.resolve("resource-header-" + suffix + ".png"));
                SessionPanel session = UiRenderingRegressionTest.find(window, SessionPanel.class);
                JTabbedPane tools = UiRenderingRegressionTest.find(session, JTabbedPane.class);
                tools.setSelectedComponent(transferView.get()); prepare(window);
                write(window.getRootPane(), output.resolve("workbench-transfers-" + suffix + ".png"));
                tools.setSelectedIndex(tools.indexOfTab("隧道")); prepare(window);
                PortForwardPanel forwarding = UiRenderingRegressionTest.find(session, PortForwardPanel.class);
                JTable table = UiRenderingRegressionTest.find(forwarding, JTable.class); table.setRowSelectionInterval(0, 0);
                write(window.getRootPane(), output.resolve("workbench-tunnels-" + suffix + ".png"));
                write(forwarding, output.resolve("tunnels-detail-" + suffix + ".png"));
                window.toggleTheme(); prepare(window);
                write(window.getRootPane(), output.resolve("workbench-light-" + suffix + ".png"));
            });
        } finally {
            if (frame.get() != null) SwingUtilities.invokeAndWait(() -> {
                SessionPanel session = UiRenderingRegressionTest.find(frame.get(), SessionPanel.class);
                if (session != null) session.close(); frame.get().dispose();
            });
        }
        System.out.println("WORKBENCH_APPEARANCE_PREVIEW_OK");
    }
    private static void attachHeaders(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTable table) {
                JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
                if (scroll != null) scroll.setColumnHeaderView(table.getTableHeader());
            }
            if (child instanceof Container nested) attachHeaders(nested);
        }
    }
    private static void prepare(MainWindow window) { window.getRootPane().setSize(window.getSize()); layout(window.getRootPane()); }
    private static void layout(Container root) {
        root.doLayout(); for (Component child : root.getComponents()) if (child instanceof Container nested) layout(nested);
    }
    private static void write(JComponent panel, Path file) {
        BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); panel.printAll(graphics); graphics.dispose();
        try { ImageIO.write(image, "png", file.toFile()); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
}
