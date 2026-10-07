package com.shilapi.xcertplay.browser;

import org.junit.Test;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class BrowserHandoverTest {
    private static final class Fixture {
        final Queue<Runnable> cleanup = new ArrayDeque<>();
        final AtomicInteger finished = new AtomicInteger();
        final BrowserSession session = new BrowserSession(cleanup::add, finished::incrementAndGet);
        final List<String> calls = new ArrayList<>(), stages = new ArrayList<>();
        final AtomicInteger primaryCloses = new AtomicInteger(), compatibilityCloses = new AtomicInteger();
        Closeable establish(String address) {
            calls.add(address);
            return BrowserHttpsPolicy.ADDRESS.equals(address) ? primaryCloses::incrementAndGet : compatibilityCloses::incrementAndGet;
        }
        void stop() {
            session.cancel(); session.workerFinished();
            while (!cleanup.isEmpty()) cleanup.remove().run();
        }
    }

    @Test public void singleAndDualAreSequentialAndRetainOnlyOriginalsUntilStop() throws Exception {
        for (int mode : new int[]{BrowserHttpsPolicy.SINGLE, BrowserHttpsPolicy.DUAL}) {
            Fixture f = new Fixture();
            assertTrue(BrowserHandover.establish(f.session, mode, f::establish, f.stages::add));
            assertEquals(Arrays.asList(BrowserHttpsPolicy.addresses(mode)), f.calls);
            assertEquals(mode == BrowserHttpsPolicy.SINGLE
                    ? Arrays.asList("PRIMARY_ESTABLISHING", "AFTER_PRIMARY")
                    : Arrays.asList("PRIMARY_ESTABLISHING", "AFTER_PRIMARY", "COMPATIBILITY_ESTABLISHING", "AFTER_HANDOVER"), f.stages);
            assertEquals(0, f.primaryCloses.get());
            assertEquals(0, f.compatibilityCloses.get());
            f.stop();
            assertEquals(1, f.primaryCloses.get());
            assertEquals(mode == BrowserHttpsPolicy.SINGLE ? 0 : 1, f.compatibilityCloses.get());
            assertEquals(1, f.finished.get());
        }
    }

    @Test public void cancellationBeforeOrBetweenEstablishNeverAdvances() throws Exception {
        for (String cancelStage : new String[]{"BEFORE", "PRIMARY_ESTABLISHING", "AFTER_PRIMARY", "COMPATIBILITY_ESTABLISHING"}) {
            Fixture f = new Fixture();
            if (cancelStage.equals("BEFORE")) f.session.cancel();
            assertFalse(BrowserHandover.establish(f.session, BrowserHttpsPolicy.DUAL, f::establish, stage -> {
                if (stage.equals(cancelStage)) f.session.cancel();
            }));
            f.stop();
            assertEquals(cancelStage.equals("BEFORE") || cancelStage.equals("PRIMARY_ESTABLISHING") ? 0 : 1, f.calls.size());
            assertEquals(f.calls.size(), f.primaryCloses.get());
            assertEquals(0, f.compatibilityCloses.get());
            assertEquals(1, f.finished.get());
        }
    }

    @Test public void stopDuringSecondEstablishClosesBothIncludingLateDescriptorOnce() throws Exception {
        Fixture f = new Fixture();
        assertFalse(BrowserHandover.establish(f.session, BrowserHttpsPolicy.DUAL, address -> {
            Closeable descriptor = f.establish(address);
            if (BrowserHttpsPolicy.COMPATIBILITY_ADDRESS.equals(address)) f.session.cancel();
            return descriptor;
        }, f.stages::add));
        assertFalse(f.stages.contains("AFTER_HANDOVER"));
        assertEquals(0, f.finished.get());
        f.stop();
        f.session.cancel();
        assertEquals(1, f.primaryCloses.get());
        assertEquals(1, f.compatibilityCloses.get());
        assertEquals(1, f.finished.get());
    }

    @Test public void secondNullOrExceptionStopsWithoutFallbackAndClosesPrimary() {
        for (boolean returnNull : new boolean[]{true, false}) {
            Fixture f = new Fixture();
            Exception failure = assertThrows(Exception.class, () -> {
                try {
                    BrowserHandover.establish(f.session, BrowserHttpsPolicy.DUAL, address -> {
                        if (BrowserHttpsPolicy.ADDRESS.equals(address)) return f.establish(address);
                        f.calls.add(address);
                        if (returnNull) return null;
                        throw new IOException("simulated platform failure");
                    }, f.stages::add);
                } catch (Exception error) {
                    f.session.cancel(); // Same terminal behavior as the service's failure path.
                    throw error;
                } finally { f.session.workerFinished(); }
            });
            assertNotNull(failure);
            while (!f.cleanup.isEmpty()) f.cleanup.remove().run();
            assertEquals(2, f.calls.size());
            assertFalse(f.stages.contains("AFTER_HANDOVER"));
            assertEquals(1, f.primaryCloses.get());
            assertEquals(0, f.compatibilityCloses.get());
            assertEquals(1, f.finished.get());
        }
    }

    @Test public void failedObserverCannotLeaveUnownedPrimary() {
        Fixture f = new Fixture();
        assertThrows(IllegalStateException.class, () -> BrowserHandover.establish(f.session, BrowserHttpsPolicy.DUAL,
                f::establish, stage -> { if (stage.equals("AFTER_PRIMARY")) throw new IllegalStateException("snapshot"); }));
        f.stop();
        assertEquals(1, f.primaryCloses.get());
        assertEquals(0, f.compatibilityCloses.get());
        assertEquals(1, f.finished.get());
    }

    @Test public void bindFailureAfterHandoverClosesBothAndAllSockets() throws Exception {
        Fixture f = new Fixture();
        AtomicInteger socketCloses = new AtomicInteger();
        assertTrue(BrowserHandover.establish(f.session, BrowserHttpsPolicy.DUAL, f::establish, f.stages::add));
        f.session.own(socketCloses::incrementAndGet);
        f.session.own(socketCloses::incrementAndGet);
        f.stop(); // Bind failure, notification Stop, revoke, expiry share this cancellation path.
        assertTrue(f.stages.contains("AFTER_HANDOVER"));
        assertEquals(1, f.primaryCloses.get());
        assertEquals(1, f.compatibilityCloses.get());
        assertEquals(2, socketCloses.get());
        assertEquals(1, f.finished.get());
    }

    @Test public void blockedPrimaryCloseDoesNotBlockSecondDescriptorOrSockets() throws Exception {
        CountDownLatch primaryEntered = new CountDownLatch(1), releasePrimary = new CountDownLatch(1);
        CountDownLatch othersClosed = new CountDownLatch(3), finished = new CountDownLatch(1);
        BrowserSession session = new BrowserSession(task -> {
            Thread thread = new Thread(task); thread.setDaemon(true); thread.start();
        }, finished::countDown);
        assertTrue(BrowserHandover.establish(session, BrowserHttpsPolicy.DUAL, address -> {
            if (BrowserHttpsPolicy.COMPATIBILITY_ADDRESS.equals(address)) return othersClosed::countDown;
            return () -> {
                primaryEntered.countDown();
                try { if (!releasePrimary.await(5, TimeUnit.SECONDS)) throw new IOException("test deadline"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            };
        }, stage -> {}));
        session.own(othersClosed::countDown); session.own(othersClosed::countDown);
        try {
            session.cancel(); session.workerFinished();
            assertTrue(primaryEntered.await(1, TimeUnit.SECONDS));
            assertTrue(othersClosed.await(1, TimeUnit.SECONDS));
            assertEquals(1L, finished.getCount());
        } finally { releasePrimary.countDown(); }
        assertTrue(finished.await(1, TimeUnit.SECONDS));
    }
}
