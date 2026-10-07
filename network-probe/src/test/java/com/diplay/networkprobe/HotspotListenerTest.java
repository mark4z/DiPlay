package com.diplay.networkprobe;

import org.junit.Test;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class HotspotListenerTest {
    static class Listener extends ServerSocket {
        SocketAddress bound;
        int binds;
        Listener() throws IOException { super(); }
        @Override public void bind(SocketAddress address, int backlog) throws IOException { bound = address; binds++; }
    }

    @Test public void bindsExactlySelectedPrivateAddressAndPortWithTwoVerifications() throws Exception {
        InetAddress ip = InetAddress.getByName("10.18.0.8");
        for (int port : new int[]{9999, 18080}) {
            AtomicInteger checks = new AtomicInteger();
            try (Listener listener = new Listener()) {
                assertTrue(HotspotListener.bind(listener, ip, port, checks::incrementAndGet, () -> false));
                assertEquals(new InetSocketAddress(ip, port), listener.bound);
                assertEquals(1, listener.binds);
                assertEquals(2, checks.get());
            }
        }
    }

    @Test public void unavailableLocalAddressNeverBinds() throws Exception {
        try (Listener listener = new Listener()) {
            assertThrows(IOException.class, () -> HotspotListener.bind(listener, InetAddress.getByName("10.18.0.8"),
                    9999, () -> { throw new IOException("changed"); }, () -> false));
            assertEquals(0, listener.binds);
        }
    }

    @Test public void cancellationBeforeOrDuringVerificationNeverBinds() throws Exception {
        for (boolean initiallyCancelled : new boolean[]{true, false}) {
            AtomicBoolean cancelled = new AtomicBoolean(initiallyCancelled);
            try (Listener listener = new Listener()) {
                assertFalse(HotspotListener.bind(listener, InetAddress.getByName("10.18.0.8"), 9999,
                        () -> cancelled.set(true), cancelled::get));
                assertEquals(0, listener.binds);
            }
        }
    }

    @Test public void cancellationDuringBindNeverReportsReady() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger checks = new AtomicInteger();
        try (Listener listener = new Listener() {
            @Override public void bind(SocketAddress address, int backlog) throws IOException {
                super.bind(address, backlog);
                cancelled.set(true);
            }
        }) {
            assertFalse(HotspotListener.bind(listener, InetAddress.getByName("10.18.0.8"), 9999,
                    checks::incrementAndGet, cancelled::get));
            assertEquals(1, listener.binds);
            assertEquals(1, checks.get());
        }
    }

    @Test public void changedAddressImmediatelyAfterBindFailsRatherThanListening() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        try (Listener listener = new Listener()) {
            assertThrows(IOException.class, () -> HotspotListener.bind(listener, InetAddress.getByName("10.18.0.8"),
                    9999, () -> { if (checks.incrementAndGet() == 2) throw new IOException("changed"); }, () -> false));
            assertEquals(1, listener.binds);
            assertEquals(2, checks.get());
        }
    }

    @Test public void cancellationDuringSecondVerificationNeverReportsReady() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean();
        try (Listener listener = new Listener()) {
            assertFalse(HotspotListener.bind(listener, InetAddress.getByName("10.18.0.8"), 9999,
                    () -> { if (checks.incrementAndGet() == 2) cancelled.set(true); }, cancelled::get));
            assertEquals(1, listener.binds);
            assertEquals(2, checks.get());
        }
    }

    @Test public void rejectsWildcardSharedSpacePublicAndWrongPortsWithoutBinding() throws Exception {
        for (String ip : new String[]{"0.0.0.0", "100.96.23.17", "8.8.8.8", "::1"}) {
            try (Listener listener = new Listener()) {
                assertThrows(IOException.class, () -> HotspotListener.bind(listener, InetAddress.getByName(ip), 9999, () -> {}, () -> false));
                assertEquals(0, listener.binds);
            }
        }
        for (int port : new int[]{0, 80, 443, 65536}) {
            try (Listener listener = new Listener()) {
                assertThrows(IllegalArgumentException.class, () -> HotspotListener.bind(listener, InetAddress.getByName("10.18.0.8"), port, () -> {}, () -> false));
                assertEquals(0, listener.binds);
            }
        }
    }

    @Test public void noFallbackAfterPermissionFailure() throws Exception {
        try (Listener listener = new Listener() {
            @Override public void bind(SocketAddress address, int backlog) throws IOException {
                binds++;
                throw new SocketException("EACCES");
            }
        }) {
            assertThrows(SocketException.class, () -> HotspotListener.bind(listener, InetAddress.getByName("10.18.0.8"), 9999, () -> {}, () -> false));
            assertEquals(1, listener.binds);
        }
    }
}
