package com.diplay.networkprobe;

import org.junit.Test;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ProbeSessionTest {
    private static final class QueuedCleanup implements Executor {
        final Queue<Runnable> queue = new ArrayDeque<>();
        @Override public void execute(Runnable task) { queue.add(task); }
        void drain() { while (!queue.isEmpty()) queue.remove().run(); }
    }

    @Test public void cancelDoesNotCloseOnCallerAndCompletesOnlyAfterAllResources() {
        QueuedCleanup cleanup = new QueuedCleanup();
        AtomicInteger closes = new AtomicInteger(), finished = new AtomicInteger();
        ProbeSession session = new ProbeSession(cleanup, finished::incrementAndGet);
        assertTrue(session.own(closes::incrementAndGet));
        assertTrue(session.own(closes::incrementAndGet));
        session.cancel();
        session.cancel();
        assertTrue(session.isCancelled());
        assertEquals(0, closes.get());
        session.workerFinished();
        assertEquals(0, finished.get());
        cleanup.drain();
        assertEquals(2, closes.get());
        assertEquals(1, finished.get());
        session.cancel();
        session.workerFinished();
        assertEquals(1, finished.get());
    }

    @Test public void cancellationDuringEstablishClosesLateDescriptorAndPreventsRestart() {
        QueuedCleanup cleanup = new QueuedCleanup();
        AtomicInteger closes = new AtomicInteger(), finished = new AtomicInteger();
        ProbeSession session = new ProbeSession(cleanup, finished::incrementAndGet);
        session.cancel(); // Simulates Stop while the OEM establish call is still running.
        assertEquals(0, finished.get());
        assertFalse(session.own(closes::incrementAndGet));
        cleanup.drain();
        assertEquals(1, closes.get());
        assertEquals(0, finished.get());
        session.workerFinished();
        assertEquals(1, finished.get());
    }

    @Test public void workerCloseAndCancelCannotDoubleCloseAResource() {
        QueuedCleanup cleanup = new QueuedCleanup();
        AtomicInteger closes = new AtomicInteger(), finished = new AtomicInteger();
        ProbeSession session = new ProbeSession(cleanup, finished::incrementAndGet);
        Closeable socket = closes::incrementAndGet;
        session.own(socket);
        session.closeOwned(socket);
        session.cancel();
        session.closeOwned(socket);
        session.workerFinished();
        cleanup.drain();
        assertEquals(1, closes.get());
        assertEquals(1, finished.get());
    }

    @Test public void cancelOwnsSocketEvenWhenWorkerFinallyRunsBeforeCleanup() {
        QueuedCleanup cleanup = new QueuedCleanup();
        AtomicInteger closes = new AtomicInteger(), finished = new AtomicInteger();
        ProbeSession session = new ProbeSession(cleanup, finished::incrementAndGet);
        Closeable socket = closes::incrementAndGet;
        session.own(socket);
        session.cancel();
        session.closeOwned(socket);
        session.workerFinished();
        assertEquals(0, closes.get());
        cleanup.drain();
        assertEquals(1, closes.get());
        assertEquals(1, finished.get());
    }

    @Test public void blockedSocketCloseDoesNotBlockCancelOrVpnDescriptorClose() throws Exception {
        CountDownLatch closeEntered = new CountDownLatch(1), releaseClose = new CountDownLatch(1);
        CountDownLatch descriptorClosed = new CountDownLatch(1), finished = new CountDownLatch(1);
        ProbeSession session = new ProbeSession(task -> {
            Thread closer = new Thread(task);
            closer.setDaemon(true);
            closer.start();
        }, finished::countDown);
        session.own(() -> {
            closeEntered.countDown();
            try {
                if (!releaseClose.await(5, TimeUnit.SECONDS)) throw new IOException("test deadline");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException(failure);
            }
        });
        session.own(descriptorClosed::countDown);
        try {
            session.cancel();
            session.workerFinished();
            assertTrue(closeEntered.await(1, TimeUnit.SECONDS));
            assertTrue(descriptorClosed.await(1, TimeUnit.SECONDS));
            assertEquals(1L, finished.getCount());
            // These lock-taking operations must remain available during blocked I/O.
            assertTrue(session.isCancelled());
            session.cancel();
        } finally { releaseClose.countDown(); }
        assertTrue(finished.await(1, TimeUnit.SECONDS));
    }

    @Test public void failedCloseIsReportedWithoutSkippingRemainingCleanup() {
        QueuedCleanup cleanup = new QueuedCleanup();
        AtomicInteger closes = new AtomicInteger(), finished = new AtomicInteger();
        ProbeSession session = new ProbeSession(cleanup, finished::incrementAndGet);
        session.own(() -> { throw new IOException("simulated close failure"); });
        session.own(closes::incrementAndGet);
        session.cancel();
        session.workerFinished();
        cleanup.drain();
        assertTrue(session.didCloseFail());
        assertEquals(1, closes.get());
        assertEquals(1, finished.get());
    }
    @Test public void selfCheckWorkerMustFinishBeforeSessionTerminates() {
        QueuedCleanup cleanup = new QueuedCleanup();
        AtomicInteger finished = new AtomicInteger();
        ProbeSession session = new ProbeSession(cleanup, finished::incrementAndGet);
        assertTrue(session.workerStarted());
        session.cancel();
        assertFalse(session.workerStarted());
        session.workerFinished();
        assertEquals(0, finished.get());
        session.workerFinished();
        assertEquals(1, finished.get());
    }

    @Test public void selfCheckWatchdogClosesSocketOffCallerAndCancelDoesNotDoubleClose() {
        QueuedCleanup cleanup = new QueuedCleanup();
        AtomicInteger closes = new AtomicInteger(), finished = new AtomicInteger();
        ProbeSession session = new ProbeSession(cleanup, finished::incrementAndGet);
        Closeable socket = closes::incrementAndGet;
        session.own(socket);
        session.closeAsync(socket);
        assertEquals(0, closes.get());
        session.cancel();
        session.workerFinished();
        cleanup.drain();
        assertEquals(1, closes.get());
        assertEquals(1, finished.get());
    }

}
