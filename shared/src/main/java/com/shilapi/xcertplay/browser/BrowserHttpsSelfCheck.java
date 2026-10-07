package com.shilapi.xcertplay.browser;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.Collections;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SNIHostName;

/** A bounded request to this session's exact local endpoint, never an Internet destination. */
public final class BrowserHttpsSelfCheck {
    public static final int TOTAL_TIMEOUT_MS = 4000;
    static final int CONNECT_TIMEOUT_MS = 1500;
    static final int READ_TIMEOUT_MS = 2000;
    private final int port = BrowserHttpsPolicy.HTTPS_PORT;
    private final String targetAddress = BrowserHttpsPolicy.ADDRESS;
    private final String hostname = BrowserHttpsPolicy.HOSTNAME;
    private final byte[] expected;
    private final TlsWrapper wrapper;
    interface TlsWrapper { SSLSocket wrap(Socket raw, String hostname, int port) throws IOException, BrowserTlsIdentity.Failure; }
    private final String request = "GET /health HTTP/1.1\r\nHost: " + BrowserHttpsPolicy.HOSTNAME
            + ":9999\r\nConnection: close\r\n\r\n";
    private volatile int sourcePort;

    public BrowserHttpsSelfCheck(byte[] expectedResponse) {
        this(expectedResponse, (raw, host, port) -> (SSLSocket) BrowserTlsIdentity.defaultClientFactory()
                .createSocket(raw, host, port, true));
    }

    /** Package-private transport seam for fake-socket tests; production always uses system trust. */
    BrowserHttpsSelfCheck(byte[] expectedResponse, TlsWrapper wrapper) {
        if (expectedResponse == null || expectedResponse.length == 0 || expectedResponse.length > 4096)
            throw new IllegalArgumentException("INVALID_EXPECTED_RESPONSE");
        expected = expectedResponse.clone();
        this.wrapper = java.util.Objects.requireNonNull(wrapper);
    }

    boolean isOwnConnection(Socket incoming) {
        return sourcePort != 0 && incoming.getPort() == sourcePort
                && targetAddress.equals(incoming.getInetAddress().getHostAddress());
    }

    public String run(Socket socket, BooleanSupplier cancelled, Function<Socket, Boolean> own,
               Consumer<Socket> close) {
        boolean connected = false;
        SSLSocket tls = null;
        try {
            if (cancelled.getAsBoolean()) return "CANCELLED";
            InetAddress address = InetAddress.getByName(targetAddress);
            socket.bind(new InetSocketAddress(address, 0));
            sourcePort = socket.getLocalPort();
            if (cancelled.getAsBoolean()) return "CANCELLED";
            socket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
            connected = true;
            if (cancelled.getAsBoolean()) return "CANCELLED";
            if (port == BrowserHttpsPolicy.HTTPS_PORT) {
                // The raw socket is already connected to the numeric local address. This host
                // argument supplies TLS SNI/verification identity; it performs no DNS routing.
                tls = wrapper.wrap(socket, hostname, port);
                if (!own.apply(tls)) return "CANCELLED";
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setServerNames(Collections.singletonList(new SNIHostName(hostname)));
                tls.setSSLParameters(parameters);
                tls.setSoTimeout(READ_TIMEOUT_MS);
                if (cancelled.getAsBoolean()) return "CANCELLED";
                tls.startHandshake();
                socket = tls;
            }
            if (cancelled.getAsBoolean()) return "CANCELLED";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            InputStream input = socket.getInputStream();
            // Match the exact public response from the embedded server.
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
        } catch (BrowserTlsIdentity.Failure failure) {
            return cancelled.getAsBoolean() ? "CANCELLED" : failure.code;
        } catch (SSLException failure) {
            return cancelled.getAsBoolean() ? "CANCELLED" : "TLS_TRUST_HOSTNAME_OR_HANDSHAKE_FAILED";
        } catch (IOException | RuntimeException failure) {
            return cancelled.getAsBoolean() ? "CANCELLED" : "IO_FAILED";
        } finally {
            if (tls != null) close.accept(tls);
        }
    }
}
