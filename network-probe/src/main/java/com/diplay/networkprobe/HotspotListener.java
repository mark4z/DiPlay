package com.diplay.networkprobe;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.function.BooleanSupplier;

/** Exactly one bind on the selected local address and protocol port; never a wildcard/fallback. */
final class HotspotListener {
    interface Verification { void verify() throws IOException; }
    private HotspotListener() {}

    static boolean bind(ServerSocket listener, InetAddress address, int port, Verification verification,
                        BooleanSupplier cancelled) throws IOException {
        ProbePolicy.requireTestPort(port);
        if (address == null || !HotspotPolicy.isPrivateIpv4(address.getAddress()))
            throw new IOException("NOT_PRIVATE_IPV4");
        if (cancelled.getAsBoolean()) return false;
        verification.verify();
        if (cancelled.getAsBoolean()) return false;
        listener.bind(new InetSocketAddress(address, port), 1);
        if (cancelled.getAsBoolean()) return false;
        verification.verify();
        return !cancelled.getAsBoolean();
    }
}
