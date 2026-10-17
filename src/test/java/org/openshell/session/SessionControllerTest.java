package org.openshell.session;

import org.junit.jupiter.api.Test;
import org.openshell.model.HostConfig;
import org.openshell.ssh.SshSession;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SessionControllerTest {
    @Test void closeDuringConnectionCannotPublishConnected() throws Exception {
        SlowSession connection = new SlowSession();
        AtomicInteger connectedEvents = new AtomicInteger();
        try (SessionController controller = new SessionController(() -> connection, Runnable::run, snapshot -> {
            if (snapshot.state() == SessionState.CONNECTED) connectedEvents.incrementAndGet();
        })) {
            controller.connect();
            assertTrue(connection.started.await(3, TimeUnit.SECONDS));
            controller.close();
            connection.release.countDown();
            assertTrue(connection.finished.await(3, TimeUnit.SECONDS));
            assertEquals(SessionState.CLOSED, controller.getState());
            assertEquals(0, connectedEvents.get());
            assertTrue(connection.disconnected.await(3, TimeUnit.SECONDS));
        }
    }

    @Test void reconnectIgnoresLateSuccessOfPreviousAttempt() throws Exception {
        SlowSession old = new SlowSession();
        SlowSession next = new SlowSession();
        next.release.countDown();
        AtomicInteger count = new AtomicInteger();
        CountDownLatch published = new CountDownLatch(1);
        try (SessionController controller = new SessionController(
                () -> count.getAndIncrement() == 0 ? old : next, Runnable::run, snapshot -> {
                    if (snapshot.state() == SessionState.CONNECTED) published.countDown();
                })) {
            controller.connect();
            assertTrue(old.started.await(3, TimeUnit.SECONDS));
            controller.connect();
            assertTrue(published.await(3, TimeUnit.SECONDS));
            old.release.countDown();
            assertTrue(old.finished.await(3, TimeUnit.SECONDS));
            assertEquals(SessionState.CONNECTED, controller.getState());
            assertSame(next, controller.getSession());
        } finally { old.release.countDown(); }
    }

    static final class SlowSession extends SshSession {
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1),
                finished = new CountDownLatch(1), disconnected = new CountDownLatch(1);
        volatile boolean connected;
        SlowSession() { super(new HostConfig()); }
        @Override public void connect() throws Exception {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            connected = true;
            finished.countDown();
        }
        @Override public boolean isConnected() { return connected; }
        @Override public void disconnect() { connected = false; disconnected.countDown(); }
    }
}
