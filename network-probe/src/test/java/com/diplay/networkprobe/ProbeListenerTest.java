package com.diplay.networkprobe;

import org.junit.Test;
import java.io.IOException;
import java.net.*;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ProbeListenerTest {
    private static class FakeListener extends ServerSocket {
        int calls, backlog, closes;
        SocketAddress bound;
        IOException failure;
        Runnable onBind = () -> {};
        FakeListener() throws IOException { super(); }
        @Override public void bind(SocketAddress address, int queue) throws IOException {
            calls++;
            bound = address;
            backlog = queue;
            onBind.run();
            if (failure != null) throw failure;
        }
        @Override public void close() { closes++; }
    }

    @Test public void bindsOnlyTheSelectedFixedEndpointOnce() throws Exception {
        for (int port : new int[]{80, 18080}) {
            FakeListener listener = new FakeListener();
            assertTrue(ProbeListener.bind(listener, port, () -> false));
            assertEquals(new InetSocketAddress(ProbePolicy.ADDRESS, port), listener.bound);
            assertEquals(1, listener.calls);
            assertEquals(1, listener.backlog);
        }
    }

    @Test public void invalidPortAndCancelledStartNeverBind() throws Exception {
        FakeListener invalid = new FakeListener();
        assertThrows(IllegalArgumentException.class, () -> ProbeListener.bind(invalid, 8080, () -> false));
        assertEquals(0, invalid.calls);
        for (int port : new int[]{80, 18080}) {
            FakeListener listener = new FakeListener();
            assertFalse(ProbeListener.bind(listener, port, () -> true));
            assertEquals(0, listener.calls);
        }
    }

    @Test public void bindFailuresKeepOriginalExceptionAndNeverFallback() throws Exception {
        for (int port : new int[]{80, 18080}) {
            for (String message : new String[]{"EACCES", "EPERM", "EADDRINUSE", "EADDRNOTAVAIL"}) {
                FakeListener listener = new FakeListener();
                listener.failure = new BindException(message);
                IOException thrown = assertThrows(IOException.class, () -> ProbeListener.bind(listener, port, () -> false));
                assertSame(listener.failure, thrown);
                assertEquals(1, listener.calls);
                assertEquals(port, ((InetSocketAddress) listener.bound).getPort());
            }
        }
    }

    @Test public void platformSecurityDenialNeverFallsBack() throws Exception {
        for (int port : new int[]{80, 18080}) {
            FakeListener listener = new FakeListener();
            SecurityException denial = new SecurityException("platform denied bind");
            listener.onBind = () -> { throw denial; };
            assertSame(denial, assertThrows(SecurityException.class,
                    () -> ProbeListener.bind(listener, port, () -> false)));
            assertEquals(1, listener.calls);
            assertEquals(port, ((InetSocketAddress) listener.bound).getPort());
        }
    }

    @Test public void cancellationDuringBindClosesListenerAndDescriptorBeforeFinish() throws Exception {
        for (int port : new int[]{80, 18080}) {
            Queue<Runnable> cleanup = new ArrayDeque<>();
            AtomicInteger descriptorCloses = new AtomicInteger(), finished = new AtomicInteger();
            ProbeSession session = new ProbeSession(cleanup::add, finished::incrementAndGet);
            FakeListener listener = new FakeListener();
            assertTrue(session.own(descriptorCloses::incrementAndGet));
            assertTrue(session.own(listener));
            listener.onBind = session::cancel;
            assertFalse(ProbeListener.bind(listener, port, session::isCancelled));
            session.workerFinished();
            assertEquals(0, finished.get());
            while (!cleanup.isEmpty()) cleanup.remove().run();
            assertEquals(1, listener.closes);
            assertEquals(1, descriptorCloses.get());
            assertEquals(1, finished.get());
        }
    }

    @Test public void failedBindStillAllowsAllOwnedResourcesToClose() throws Exception {
        for (int port : new int[]{80, 18080}) {
            Queue<Runnable> cleanup = new ArrayDeque<>();
            AtomicInteger descriptorCloses = new AtomicInteger(), finished = new AtomicInteger();
            ProbeSession session = new ProbeSession(cleanup::add, finished::incrementAndGet);
            FakeListener listener = new FakeListener();
            listener.failure = new BindException("permission denied");
            session.own(descriptorCloses::incrementAndGet);
            session.own(listener);
            assertThrows(IOException.class, () -> ProbeListener.bind(listener, port, session::isCancelled));
            session.cancel();
            session.workerFinished();
            while (!cleanup.isEmpty()) cleanup.remove().run();
            assertEquals(1, listener.closes);
            assertEquals(1, descriptorCloses.get());
            assertEquals(1, finished.get());
        }
    }

    @Test public void bindDiagnosticsReportActualNestedErrnoWithoutGuessing() {
        IOException os = new IOException("bind failed: Permission denied");
        BindException wrapper = new BindException("bind failed");
        wrapper.initCause(os);
        String result = ProbeListener.failureDescription(80, wrapper, cause -> cause == os ? "errno=13 (EACCES)" : null);
        assertTrue(result.startsWith("BIND_FAILED port=80; errno=13 (EACCES);"));
        assertTrue(result.contains("Permission denied"));
        String unavailable = ProbeListener.failureDescription(18080, new SecurityException("denied\nby platform"), cause -> null);
        assertTrue(unavailable.contains("errno=UNAVAILABLE"));
        assertTrue(unavailable.contains("SecurityException: denied by platform"));
        assertFalse(unavailable.contains("EACCES"));
    }

    @Test public void diagnosticTraversalAndMessageLengthAreBounded() {
        IOException first = new IOException("x".repeat(500));
        IOException second = new IOException("cycle");
        first.initCause(second);
        second.initCause(first);
        AtomicInteger visited = new AtomicInteger();
        String result = ProbeListener.failureDescription(80, first, cause -> { visited.incrementAndGet(); return null; });
        assertEquals(16, visited.get());
        assertTrue(result.length() < 300);
    }
}
