package org.openshell.ui;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HostKeyDialog {
    private HostKeyDialog() {}
    public static boolean confirm(Component owner, String host, String algorithm, String fingerprint) {
        if (GraphicsEnvironment.isHeadless()) return false;
        AtomicBoolean accepted = new AtomicBoolean();
        Runnable show = () -> {
            JTextArea details = new JTextArea("首次连接此服务器，请核对主机指纹：\n\n主机: " + host
                    + "\n算法: " + algorithm + "\n指纹: " + fingerprint + "\n\n确认后将保存到本机 known_hosts。");
            details.setEditable(false); details.setOpaque(false);
            accepted.set(JOptionPane.showConfirmDialog(owner, details, "确认 SSH 主机身份",
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION);
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) show.run(); else SwingUtilities.invokeAndWait(show);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
        return accepted.get();
    }
}
