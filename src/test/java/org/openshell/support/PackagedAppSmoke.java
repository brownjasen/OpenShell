package org.openshell.support;

import org.openshell.model.HostConfig;
import org.openshell.ssh.SshSession;
import org.openshell.ui.TerminalPanel;
import javax.swing.*;
import java.io.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Run with only the shaded application JAR and test-classes on the classpath. */
public final class PackagedAppSmoke {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            org.openshell.ui.ThemeUtils.install(true);
            SshSession stub = new SshSession(new HostConfig()) {
                @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
                @Override public boolean isConnected() { return false; }
                @Override public void resizePty(int c, int r, int w, int h) {}
            };
            TerminalPanel panel = new TerminalPanel(stub);
            panel.close();
            if (!java.awt.GraphicsEnvironment.isHeadless()) {
                org.openshell.ui.MainWindow window = new org.openshell.ui.MainWindow();
                window.setSize(1366, 768);
                window.getRootPane().setSize(window.getSize());
                layout(window.getRootPane());
                BufferedImage image = new BufferedImage(1366, 768, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics(); window.getRootPane().printAll(graphics); graphics.dispose();
                try {
                    Path output = Path.of("OpenShell/target/verification/home-" + System.getProperty("flatlaf.uiScale", "1") + ".png");
                    Files.createDirectories(output.getParent()); ImageIO.write(image, "png", output.toFile());
                } catch (IOException error) { throw new UncheckedIOException(error); }
                window.dispose();
            }
        });
        if (Class.forName("org.openshell.Main") == null) throw new AssertionError();
        System.out.println("PACKAGED_APP_SMOKE_OK");
    }
    private static void layout(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) if (child instanceof Container nested) layout(nested);
    }
}
