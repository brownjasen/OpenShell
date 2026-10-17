package org.openshell.transfer;

import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One FIFO queue per SSH session. Every operation owns a separate transfer channel. */
public final class TransferManager implements AutoCloseable {
    public enum Direction { UPLOAD, DOWNLOAD }
    public enum State {
        QUEUED("等待中"), RUNNING("传输中"), WAITING("等待同名处理"), CANCELLING("正在取消"),
        COMMITTING("正在完成"), COMPLETED("已完成"), SKIPPED("已跳过"), CANCELLED("已取消"), FAILED("失败");
        public final String label;
        State(String label) { this.label = label; }
        public boolean terminal() { return ordinal() >= COMPLETED.ordinal(); }
    }
    public enum Choice { REPLACE, KEEP_BOTH, SKIP, CANCEL }
    public record Decision(Choice choice, boolean applyToBatch) {}
    public enum Kind { FILE, DIRECTORY }
    public record Request(Direction direction, Path local, String remote, UUID batch, Kind kind, long expectedSize) {
        public Request(Direction direction, Path local, String remote, UUID batch) { this(direction, local, remote, batch, Kind.FILE, -1); }
        public Request {
            Objects.requireNonNull(direction); Objects.requireNonNull(batch); Objects.requireNonNull(kind);
            local = local.toAbsolutePath().normalize();
            if (remote == null || !remote.startsWith("/") || remote.endsWith("/") || remote.indexOf('\0') >= 0)
                throw new IllegalArgumentException("传输目标必须是远端绝对文件路径");
        }
    }
    public record Snapshot(UUID id, Request request, State state, long done, long total,
                           double bytesPerSecond, String destination, String error) {}
    public interface Transport extends AutoCloseable {
        void execute(Request request, Control control) throws Exception;
        @Override void close();
    }
    public interface Factory { Transport create() throws Exception; }
    public interface ConflictResolver { Decision resolve(Snapshot task) throws Exception; }
    public interface Control {
        void progress(long done, long total) throws InterruptedIOException;
        Choice conflict(String destination) throws Exception;
        void destination(String destination);
        void checkCancelled() throws InterruptedIOException;
        void committing() throws InterruptedIOException;
    }
    public static final class Skipped extends Exception {}
    private static final class Task {
        final UUID id = UUID.randomUUID();
        final Request request;
        State state = State.QUEUED;
        long done, total = -1, started, lastActivity, lastPublished;
        String destination, error = "";
        boolean cancelled, timedOut;
        Transport transport;
        Task(Request request) {
            this.request = request; total = request.expectedSize;
            destination = request.direction == Direction.UPLOAD ? request.remote : request.local.toString();
        }
        Snapshot snapshot() {
            double seconds = started == 0 ? 0 : (System.nanoTime() - started) / 1_000_000_000.0;
            return new Snapshot(id, request, state, done, total,
                    state == State.RUNNING && seconds > 0 ? done / seconds : 0, destination, error);
        }
    }
    private final Factory factory;
    private final ConflictResolver conflicts;
    private final List<Task> tasks = new ArrayList<>();
    private final Map<UUID, Choice> batchChoices = new HashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> daemon(task, "OpenShell-Transfer"));
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "OpenShell-TransferTimeout"));
    private volatile Consumer<List<Snapshot>> listener = rows -> {};
    private boolean closed;
    private final java.util.concurrent.atomic.AtomicBoolean publishQueued = new java.util.concurrent.atomic.AtomicBoolean();

    public TransferManager(Factory factory, ConflictResolver conflicts) {
        this.factory = factory; this.conflicts = conflicts;
        watchdog.scheduleWithFixedDelay(this::checkTimeouts, 1, 1, TimeUnit.SECONDS);
    }
    private static Thread daemon(Runnable task, String name) { Thread t = new Thread(task, name); t.setDaemon(true); return t; }
    public void setListener(Consumer<List<Snapshot>> listener) { this.listener = Objects.requireNonNull(listener); publish(); }
    public synchronized List<Snapshot> snapshots() { return tasks.stream().map(Task::snapshot).toList(); }
    public synchronized Snapshot find(UUID id) { return task(id).snapshot(); }
    private Task task(UUID id) { return tasks.stream().filter(t -> t.id.equals(id)).findFirst().orElseThrow(); }
    public UUID submit(Direction direction, Path local, String remote) { return submit(direction, local, remote, UUID.randomUUID()); }
    public UUID submit(Direction direction, Path local, String remote, UUID batch) {
        return submitAll(List.of(new Request(direction, local, remote, batch))).get(0);
    }
    public List<UUID> submitAll(List<Request> requests) {
        List<Task> added = requests.stream().map(Task::new).toList();
        synchronized (this) {
            if (closed) throw new IllegalStateException("传输队列已停止，请重新连接");
            tasks.addAll(added);
            for (Task task : added) worker.execute(() -> run(task));
        }
        publish(); return added.stream().map(t -> t.id).toList();
    }
    public record Batch(UUID id, int count, int finished, int failed, int pending, long done, long total) {}
    public synchronized List<Batch> batches() {
        Map<UUID, List<Task>> grouped = new LinkedHashMap<>();
        for (Task t : tasks) grouped.computeIfAbsent(t.request.batch, k -> new ArrayList<>()).add(t);
        return grouped.entrySet().stream().map(e -> {
            var rows = e.getValue();
            return new Batch(e.getKey(), rows.size(), (int) rows.stream().filter(t -> t.state.terminal()).count(),
                (int) rows.stream().filter(t -> t.state == State.FAILED).count(),
                (int) rows.stream().filter(t -> !t.state.terminal()).count(), rows.stream().mapToLong(t -> t.done).sum(),
                rows.stream().anyMatch(t -> t.total < 0) ? -1 : rows.stream().mapToLong(t -> t.total).sum());
        }).toList();
    }
    public void cancelBatch(UUID batch) {
        List<UUID> ids;
        synchronized(this) { ids = tasks.stream().filter(t -> t.request.batch.equals(batch)).map(t -> t.id).toList(); }
        ids.forEach(this::cancel);
    }
    public List<UUID> retryFailedBatch(UUID batch) {
        UUID fresh = UUID.randomUUID(); List<Request> requests;
        synchronized(this) { requests = tasks.stream().filter(t -> t.request.batch.equals(batch) && t.state == State.FAILED)
            .map(t -> new Request(t.request.direction, t.request.local, t.request.remote, fresh, t.request.kind, t.request.expectedSize)).toList(); }
        return submitAll(requests);
    }
    public UUID retry(UUID id) {
        Request request;
        synchronized (this) {
            Task t = task(id);
            if (t.state != State.FAILED && t.state != State.CANCELLED) throw new IllegalStateException("只能重试失败或取消的任务");
            request = t.request;
        }
        return submitAll(List.of(new Request(request.direction, request.local, request.remote, UUID.randomUUID(), request.kind, request.expectedSize))).get(0); // Fresh conflict choice.
    }
    public boolean cancel(UUID id) {
        Transport transport;
        synchronized (this) {
            Task t = task(id);
            if (t.state.terminal() || t.state == State.COMMITTING || t.cancelled) return false;
            t.cancelled = true;
            t.state = t.state == State.QUEUED ? State.CANCELLED : State.CANCELLING;
            transport = t.transport;
        }
        if (transport != null) transport.close();
        publish(); return true;
    }
    public synchronized boolean isClosed() { return closed; }
    public synchronized long pendingCount() { return tasks.stream().filter(t -> !t.state.terminal()).count(); }
    public void clearFinished() {
        synchronized (this) {
            tasks.removeIf(t -> t.state.terminal());
            Set<UUID> batches = new HashSet<>(); tasks.forEach(t -> batches.add(t.request.batch));
            batchChoices.keySet().retainAll(batches);
        }
        publish();
    }
    private void run(Task task) {
        synchronized (this) {
            if (closed || task.cancelled) return;
            task.state = State.RUNNING; task.started = task.lastActivity = System.nanoTime();
        }
        publish();
        try {
            Transport transport = factory.create();
            synchronized (this) { task.transport = transport; }
            try (transport) {
                control(task).checkCancelled(); transport.execute(task.request, control(task));
                synchronized (this) {
                    if (task.cancelled && task.state != State.COMMITTING) task.state = State.CANCELLED;
                    else { task.state = State.COMPLETED; if (task.total >= 0) task.done = task.total; }
                }
            }
        } catch (Exception error) {
            synchronized (this) {
                boolean confirming = task.state == State.COMMITTING;
                task.state = task.cancelled ? State.CANCELLED : error instanceof Skipped ? State.SKIPPED : State.FAILED;
                task.error = error instanceof Skipped ? "" : task.timedOut ? "传输超过 60 秒没有响应；" + message(error) : message(error);
                if (confirming) task.error = "完成结果未确认，请检查目标文件；" + task.error;
            }
        } finally {
            synchronized (this) {
                task.transport = null;
                if (closed && tasks.stream().allMatch(t -> t.state.terminal())) watchdog.shutdown();
            }
            publish();
        }
    }
    private Control control(Task task) {
        return new Control() {
            public void checkCancelled() throws InterruptedIOException {
                synchronized (TransferManager.this) {
                    if (task.cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("传输已取消");
                    if (task.timedOut) throw new InterruptedIOException("传输无响应");
                }
            }
            public void progress(long done, long total) throws InterruptedIOException {
                checkCancelled(); boolean update;
                synchronized (TransferManager.this) {
                    boolean firstData = task.done == 0 && done > 0;
                    task.done = done; task.total = total; task.lastActivity = System.nanoTime();
                    update = firstData || task.lastActivity - task.lastPublished >= 100_000_000L;
                    if (update) task.lastPublished = task.lastActivity;
                }
                if (update) publish();
            }
            public Choice conflict(String destination) throws Exception {
                checkCancelled(); Choice cached;
                long waitingStarted = System.nanoTime();
                synchronized (TransferManager.this) {
                    task.destination = destination; cached = batchChoices.get(task.request.batch);
                    task.state = State.WAITING;
                }
                publish(); Decision decision = cached == null ? conflicts.resolve(find(task.id)) : new Decision(cached, false);
                synchronized (TransferManager.this) {
                    task.state = State.RUNNING; task.lastActivity = System.nanoTime();
                    task.started += task.lastActivity - waitingStarted;
                    if (decision != null && decision.applyToBatch && decision.choice != Choice.CANCEL)
                        batchChoices.put(task.request.batch, decision.choice);
                }
                publish(); checkCancelled();
                if (decision == null || decision.choice == Choice.CANCEL) {
                    synchronized (TransferManager.this) { task.cancelled = true; }
                    throw new InterruptedIOException("用户取消传输");
                }
                return decision.choice;
            }
            public void destination(String destination) { synchronized (TransferManager.this) { task.destination = destination; } publish(); }
            public void committing() throws InterruptedIOException {
                synchronized (TransferManager.this) { checkCancelled(); task.state = State.COMMITTING; task.lastActivity = System.nanoTime(); }
                publish();
            }
        };
    }
    private void checkTimeouts() {
        List<Transport> stale = new ArrayList<>();
        synchronized (this) {
            long now = System.nanoTime();
            for (Task t : tasks) if ((t.state == State.RUNNING || t.state == State.COMMITTING)
                    && !t.timedOut && now - t.lastActivity > TimeUnit.SECONDS.toNanos(60)) {
                t.timedOut = true; if (t.transport != null) stale.add(t.transport);
            }
        }
        stale.forEach(Transport::close);
    }
    private void publish() {
        boolean large; synchronized(this) { large = tasks.size() > 200; }
        if (!large) { listener.accept(snapshots()); return; }
        if (publishQueued.compareAndSet(false, true)) CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(() -> {
            publishQueued.set(false); listener.accept(snapshots());
        });
    }
    private static String message(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    @Override public void close() {
        List<UUID> cancel;
        synchronized (this) {
            if (closed) return; closed = true;
            cancel = tasks.stream().filter(t -> !t.state.terminal()).map(t -> t.id).toList();
        }
        cancel.forEach(this::cancel);
        worker.shutdown(); // Allow an already-committing rename to report its real result.
        synchronized (this) { if (tasks.stream().allMatch(t -> t.state.terminal())) watchdog.shutdown(); }
        publish();
    }
}
