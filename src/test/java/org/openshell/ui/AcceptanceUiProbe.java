package org.openshell.ui;

import javax.swing.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only observation helpers; never reads user settings or operates a native window. */
final class AcceptanceUiProbe {
    private AcceptanceUiProbe() {}
    static <T> T edt(Callable<T> task) throws Exception {
        CompletableFuture<T> done = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            try { done.complete(task.call()); } catch (Throwable error) { done.completeExceptionally(error); }
        });
        try { return done.get(35, TimeUnit.SECONDS); }
        catch (ExecutionException error) {
            if (error.getCause() instanceof AssertionError failure) throw failure;
            if (error.getCause() instanceof Exception failure) throw failure;
            throw error;
        }
    }
    static void await(Callable<Boolean> condition, long seconds, String reason) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.call() && System.nanoTime() < deadline) Thread.sleep(25);
        assertTrue(condition.call(), reason);
    }
    static <T> T field(Object owner, String name, Class<T> type) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(owner)); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    static void report(String filename, String text) throws Exception {
        Path target = Path.of("target", "verification", filename); Files.createDirectories(target.getParent());
        Files.writeString(target, text); System.out.println(text);
    }
    static long heap() { Runtime runtime = Runtime.getRuntime(); return runtime.totalMemory() - runtime.freeMemory(); }
    static long appThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive)
                .filter(thread -> thread.getName().startsWith("OpenShell-")).count();
    }
    static String appThreadNames() {
        return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive)
                .map(Thread::getName).filter(name -> name.startsWith("OpenShell-")).sorted()
                .collect(java.util.stream.Collectors.joining(", "));
    }
    static long millis(long nanos) { return TimeUnit.NANOSECONDS.toMillis(nanos); }
    static final class Heartbeat implements AutoCloseable {
        final AtomicLong maximumLag = new AtomicLong(), samples = new AtomicLong();
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Acceptance-EDTHeartbeat"); thread.setDaemon(true); return thread;
        });
        Heartbeat() {
            timer.scheduleAtFixedRate(() -> {
                long queued = System.nanoTime();
                SwingUtilities.invokeLater(() -> {
                    maximumLag.accumulateAndGet(System.nanoTime() - queued, Math::max); samples.incrementAndGet();
                });
            }, 0, 40, TimeUnit.MILLISECONDS);
        }
        void drain() throws Exception { edt(() -> null); }
        @Override public void close() { timer.shutdownNow(); }
    }
}
