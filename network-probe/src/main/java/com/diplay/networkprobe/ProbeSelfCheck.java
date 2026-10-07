package com.diplay.networkprobe;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** A bounded request to this session's exact local endpoint, never an Internet destination. */
final class ProbeSelfCheck {
    static final int TOTAL_TIMEOUT_MS = 4000;
    static final int CONNECT_TIMEOUT_MS = 1500;
    static final int READ_TIMEOUT_MS = 2000;
    private static final String REQUEST = "GET /health HTTP/1.1\r\nHost: "
            + ProbePolicy.ADDRESS + ":" + ProbePolicy.PORT + "\r\nConnection: close\r\n\r\n";
    private volatile int sourcePort;

    boolean isOwnConnection(Socket incoming) {
        return sourcePort != 0 && incoming.getPort() == sourcePort
                && ProbePolicy.ADDRESS.equals(incoming.getInetAddress().getHostAddress());
    }

    String run(Socket socket, BooleanSupplier cancelled) {
        boolean connected = false;
        try {
            if (cancelled.getAsBoolean()) return "CANCELLED";
            InetAddress address = InetAddress.getByName(ProbePolicy.ADDRESS);
            socket.bind(new InetSocketAddress(address, 0));
            sourcePort = socket.getLocalPort();
            if (cancelled.getAsBoolean()) return "CANCELLED";
            socket.connect(new InetSocketAddress(address, ProbePolicy.PORT), CONNECT_TIMEOUT_MS);
            connected = true;
            if (cancelled.getAsBoolean()) return "CANCELLED";
            socket.getOutputStream().write(REQUEST.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            InputStream input = socket.getInputStream();
            byte[] expected = HealthProtocol.response(REQUEST);
            long deadline = System.nanoTime() + READ_TIMEOUT_MS * 1_000_000L;
            for (byte value : expected) {
                if (cancelled.getAsBoolean()) return "CANCELLED";
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return "READ_TIMEOUT";
                socket.setSoTimeout((int) Math.max(1, remaining / 1_000_000L));
                if (input.read() != (value & 0xff)) return "RESPONSE_MISMATCH";
            }
            return cancelled.getAsBoolean() ? "CANCELLED" : "PASS";
        } catch (SocketTimeoutException failure) {
            return cancelled.getAsBoolean() ? "CANCELLED" : connected ? "READ_TIMEOUT" : "CONNECT_TIMEOUT";
        } catch (IOException | RuntimeException failure) {
            return cancelled.getAsBoolean() ? "CANCELLED" : "IO_FAILED";
        }
    }
}
