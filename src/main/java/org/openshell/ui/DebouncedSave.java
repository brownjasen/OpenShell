package org.openshell.ui;

import javax.swing.Timer;

/** Coalesces resize/drag events; flush before a view is disposed. EDT only. */
final class DebouncedSave {
    private final Timer timer;
    private final Runnable save;
    DebouncedSave(Runnable save) {
        this.save = save;
        timer = new Timer(400, event -> save.run()); timer.setRepeats(false);
    }
    void schedule() { timer.restart(); }
    void flush() { if (timer.isRunning()) { timer.stop(); save.run(); } }
}
