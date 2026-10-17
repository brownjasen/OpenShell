package org.openshell.transfer;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.openshell.transfer.TransferManager.*;

class TransferManagerTest {
    @Test void queueRunsOneTransferAtATimeAndQueuedCancellationDoesNotStartAnotherChannel() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger opened = new AtomicInteger();
        try (var queue = new TransferManager(() -> new Transport() {
            public void execute(Request request, Control control) throws Exception {
                opened.incrementAndGet(); started.countDown(); release.await(); control.checkCancelled(); control.committing();
            }
            public void close() { release.countDown(); }
        }, snapshot -> new Decision(Choice.SKIP, false))) {
            UUID first = queue.submit(Direction.UPLOAD, Path.of("first"), "/first");
            assertTrue(started.await(2, TimeUnit.SECONDS));
            UUID second = queue.submit(Direction.UPLOAD, Path.of("second"), "/second");
            assertEquals(State.QUEUED, queue.find(second).state());
            assertTrue(queue.cancel(second)); assertTrue(queue.cancel(first));
            await(queue, first, State.CANCELLED); assertEquals(State.CANCELLED, queue.find(second).state());
            assertEquals(1, opened.get());
            UUID retry = queue.retry(first); await(queue, retry, State.COMPLETED);
            assertEquals(2, opened.get()); assertNotEquals(first, retry);
            assertTrue(queue.find(retry).request().local().isAbsolute());
        }
    }

    @Test void committingCannotBeReportedAsCancelledAndClosingRejectsFurtherTasks() throws Exception {
        CountDownLatch committing = new CountDownLatch(1), release = new CountDownLatch(1);
        var queue = new TransferManager(() -> new Transport() {
            public void execute(Request r, Control c) throws Exception { c.committing(); committing.countDown(); release.await(); }
            public void close() { release.countDown(); }
        }, snapshot -> new Decision(Choice.SKIP, false));
        try {
            UUID id = queue.submit(Direction.DOWNLOAD, Path.of("result"), "/result");
            assertTrue(committing.await(2, TimeUnit.SECONDS));
            assertFalse(queue.cancel(id)); release.countDown(); await(queue, id, State.COMPLETED);
        } finally { release.countDown(); queue.close(); }
        assertThrows(IllegalStateException.class, () -> queue.submit(Direction.UPLOAD, Path.of("x"), "/x"));
    }

    @Test void retryFailureIsFreshAndDoesNotReuseAnEarlierOverwriteDecision() throws Exception {
        AtomicInteger attempts = new AtomicInteger(), questions = new AtomicInteger();
        try (var queue = new TransferManager(() -> new Transport() {
            public void execute(Request r, Control c) throws Exception {
                c.conflict("destination"); c.progress(3, 10);
                if (attempts.getAndIncrement() == 0) throw new java.io.IOException("permission denied");
                c.committing();
            }
            public void close() {}
        }, snapshot -> { questions.incrementAndGet(); return new Decision(Choice.REPLACE, true); })) {
            UUID id = queue.submit(Direction.UPLOAD, Path.of("x"), "/x"); await(queue, id, State.FAILED);
            assertTrue(queue.find(id).error().contains("permission denied"));
            UUID retry = queue.retry(id); await(queue, retry, State.COMPLETED);
            assertEquals(2, questions.get());
        }
    }

    static void await(TransferManager queue, UUID id, State expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!queue.find(id).state().terminal() && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(expected, queue.find(id).state(), queue.find(id).error());
    }

    @Test void aBatchChoiceIsSharedButCancelChoiceCancelsOnlyTheCurrentTask() throws Exception {
        AtomicInteger questions = new AtomicInteger();
        try (var queue = new TransferManager(() -> new Transport() {
            public void execute(Request request, Control c) throws Exception { if (c.conflict(request.remote()) == Choice.SKIP) throw new Skipped(); }
            public void close() {}
        }, row -> { questions.incrementAndGet(); return new Decision(Choice.SKIP, true); })) {
            UUID batch = UUID.randomUUID();
            UUID first = queue.submit(Direction.UPLOAD, Path.of("a"), "/a", batch);
            UUID second = queue.submit(Direction.UPLOAD, Path.of("b"), "/b", batch);
            await(queue, first, State.SKIPPED); await(queue, second, State.SKIPPED);
            assertEquals(1, questions.get());
        }
        try (var queue = new TransferManager(() -> new Transport() {
            public void execute(Request r, Control c) throws Exception { c.conflict(r.remote()); }
            public void close() {}
        }, row -> new Decision(Choice.CANCEL, false))) {
            UUID id = queue.submit(Direction.DOWNLOAD, Path.of("local"), "/remote"); await(queue, id, State.CANCELLED);
        }
    }
}
