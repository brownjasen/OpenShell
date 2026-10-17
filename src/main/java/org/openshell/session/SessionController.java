package org.openshell.session;

import org.openshell.ssh.SshSession;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Each attempt owns its transport; retired callbacks cannot activate a new view. */
public final class SessionController implements AutoCloseable {
    public record Snapshot(SessionState state, SshSession session, String message) {}
    private final Supplier<SshSession> factory;
    private final Executor callbacks;
    private final Consumer<Snapshot> listener;
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "OpenShell-Session"); thread.setDaemon(true); return thread;
    });
    private final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "OpenShell-SessionHealth"); thread.setDaemon(true); return thread;
    });
    private long generation;
    private SessionState state = SessionState.DISCONNECTED;
    private SshSession current;
    private ScheduledFuture<?> healthCheck;

    public SessionController(Supplier<SshSession> factory, Executor callbacks, Consumer<Snapshot> listener) {
        this.factory = factory; this.callbacks = callbacks; this.listener = listener;
    }
    public void connect() {
        final long attempt;
        final SshSession connection;
        synchronized (this) {
            if (state == SessionState.CLOSED) return;
            boolean reconnect = generation != 0;
            attempt = ++generation;
            if (healthCheck != null) healthCheck.cancel(false);
            if (current != null) { SshSession previous = current; workers.execute(previous::disconnect); }
            connection = factory.get(); current = connection;
            state = reconnect ? SessionState.RECONNECTING : SessionState.CONNECTING;
            publish(attempt, new Snapshot(state, connection, state.label()));
            if (state == SessionState.CLOSED || attempt != generation) { connection.disconnect(); return; }
            workers.execute(() -> {
            try {
                connection.connect();
                synchronized (this) {
                    if (attempt != generation || state == SessionState.CLOSED) {
                        connection.disconnect(); return;
                    }
                    state = SessionState.CONNECTED;
                    publish(attempt, new Snapshot(state, connection, "连接成功"));
                    healthCheck = watcher.scheduleWithFixedDelay(() -> {
                        if (!connection.isConnected()) disconnected(connection, "远端已关闭连接或网络已断开");
                    }, 500, 500, TimeUnit.MILLISECONDS);
                }
            } catch (Exception error) {
                connection.disconnect();
                synchronized (this) {
                    if (attempt != generation || state == SessionState.CLOSED) return;
                    state = SessionState.FAILED;
                    publish(attempt, new Snapshot(state, connection,
                            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
                }
            }
            });
        }
    }
    public synchronized void disconnected(SshSession connection, String reason) {
        if (current != connection || state != SessionState.CONNECTED) return;
        if (healthCheck != null) healthCheck.cancel(false);
        state = SessionState.DISCONNECTED;
        publish(generation, new Snapshot(state, connection, reason));
        workers.execute(connection::disconnect);
    }
    private void publish(long attempt, Snapshot snapshot) {
        callbacks.execute(() -> {
            synchronized (this) { if (attempt != generation || snapshot.state() != state) return; }
            listener.accept(snapshot);
        });
    }
    public synchronized SessionState getState() { return state; }
    public synchronized SshSession getSession() { return current; }
    public synchronized void cancel() {
        if (state != SessionState.CONNECTING && state != SessionState.RECONNECTING) return;
        ++generation;
        if (current != null) workers.execute(current::disconnect);
        state = SessionState.DISCONNECTED;
        publish(generation, new Snapshot(state, current, "连接已取消"));
    }
    @Override public synchronized void close() {
        if (state == SessionState.CLOSED) return;
        ++generation; state = SessionState.CLOSED;
        if (healthCheck != null) healthCheck.cancel(false);
        if (current != null) workers.execute(current::disconnect);
        publish(generation, new Snapshot(state, current, "会话已关闭"));
        workers.shutdown(); watcher.shutdownNow();
    }
}
