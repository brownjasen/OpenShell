package org.openshell.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class MonitorPauseTest {
    @Test void redundantVisibilityUpdatesKeepCurrentReadingsAndCloseRejectsQueuedSamples() throws Exception {
        AtomicReference<MonitorPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panel.set(new MonitorPanel()));
        panel.get().onUpdate(12, 50, 500, 1000, 50, 5, 10, 2, 3, "1 2 3");
        SwingUtilities.invokeAndWait(() -> {
            panel.get().setMonitoringEnabled(true);
            assertTrue(hasLabel(panel.get(), "12.0%"), "Same visibility state must not blank every sample");
            panel.get().onUpdate(88, 50, 500, 1000, 50, 5, 10, 2, 3, "1 2 3");
            panel.get().close();
        });
        SwingUtilities.invokeAndWait(() -> assertFalse(hasLabel(panel.get(), "88.0%")));
    }

    @Test void hiddenPauseDiscardsAlreadyQueuedSamplesAndResumeWaitsForFreshData() throws Exception {
        AtomicReference<MonitorPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            panel.set(new MonitorPanel());
            panel.get().onUpdate(99, 90, 900, 1000, 50, 5, 10, 2, 3, "1 2 3");
            panel.get().setMonitoringEnabled(false);
        });
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(hasLabel(panel.get(), "监控已暂停")); assertFalse(hasLabel(panel.get(), "99.0%"));
            panel.get().onUpdate(88, 50, 500, 1000, 50, 5, 10, 2, 3, "1 2 3");
            panel.get().setMonitoringEnabled(true);
        });
        SwingUtilities.invokeAndWait(() -> { assertTrue(hasLabel(panel.get(), "等待监控")); assertFalse(hasLabel(panel.get(), "88.0%")); });
        panel.get().onUpdate(12, 50, 500, 1000, 50, 5, 10, 2, 3, "1 2 3");
        SwingUtilities.invokeAndWait(() -> assertTrue(hasLabel(panel.get(), "12.0%")));
    }
    private static boolean hasLabel(Container root, String value) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label && value.equals(label.getText())) return true;
            if (child instanceof Container nested && hasLabel(nested, value)) return true;
        }
        return false;
    }
}
