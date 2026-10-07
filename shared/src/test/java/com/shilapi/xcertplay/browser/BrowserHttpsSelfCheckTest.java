package com.shilapi.xcertplay.browser;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.*;
import static org.junit.Assert.*;

public class BrowserHttpsSelfCheckTest {
    private static final byte[] RESPONSE = "HTTP/1.1 200 OK\r\n\r\nsynthetic-health\n".getBytes(StandardCharsets.US_ASCII);
    private static class RawSocket extends Socket {
        SocketAddress destination, source;
        int connectTimeout;
        boolean timeout;
        @Override public void bind(SocketAddress address) { source = address; }
        @Override public int getLocalPort() { return 35001; }
        @Override public void connect(SocketAddress address, int timeoutMs) throws IOException {
            destination = address; connectTimeout = timeoutMs;
            if (timeout) throw new SocketTimeoutException();
        }
    }
    private static class TlsSocket extends SSLSocket {
        InputStream input = new ByteArrayInputStream(RESPONSE);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        SSLParameters parameters = new SSLParameters();
        int timeout, handshakes;
        boolean failHandshake;
        @Override public SSLParameters getSSLParameters() { return parameters; }
        @Override public void setSSLParameters(SSLParameters value) { parameters = value; }
        @Override public void setSoTimeout(int value) { timeout = value; }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public void startHandshake() throws IOException { handshakes++; if (failHandshake) throw new SSLHandshakeException("synthetic failure"); }
        @Override public String[] getSupportedCipherSuites() { return new String[0]; }
        @Override public String[] getEnabledCipherSuites() { return new String[0]; }
        @Override public void setEnabledCipherSuites(String[] suites) { }
        @Override public String[] getSupportedProtocols() { return new String[0]; }
        @Override public String[] getEnabledProtocols() { return new String[0]; }
        @Override public void setEnabledProtocols(String[] protocols) { }
        @Override public SSLSession getSession() { return null; }
        @Override public void addHandshakeCompletedListener(HandshakeCompletedListener listener) { }
        @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) { }
        @Override public void setUseClientMode(boolean mode) { }
        @Override public boolean getUseClientMode() { return true; }
        @Override public void setNeedClientAuth(boolean need) { }
        @Override public boolean getNeedClientAuth() { return false; }
        @Override public void setWantClientAuth(boolean want) { }
        @Override public boolean getWantClientAuth() { return false; }
        @Override public void setEnableSessionCreation(boolean flag) { }
        @Override public boolean getEnableSessionCreation() { return true; }
    }
    @Test public void exactLocalDestinationNamedSniEndpointVerificationAndOwnedTlsBeforeHandshake() throws Exception {
        RawSocket raw = new RawSocket();
        TlsSocket tls = new TlsSocket();
        List<Socket> owned = new ArrayList<>(), closed = new ArrayList<>();
        BrowserHttpsSelfCheck check = new BrowserHttpsSelfCheck(RESPONSE, (socket, host, port) -> {
            assertSame(raw, socket);
            assertEquals("tesla.mark4z.asia", host);
            assertEquals(9999, port);
            return tls;
        });
        String result = check.run(raw, () -> false, socket -> { assertEquals(0, tls.handshakes); owned.add(socket); return true; }, closed::add);
        assertEquals("PASS", result);
        assertEquals(new InetSocketAddress("100.99.9.9", 9999), raw.destination);
        assertEquals(new InetSocketAddress("100.99.9.9", 0), raw.source);
        assertEquals(1500, raw.connectTimeout);
        assertEquals("HTTPS", tls.parameters.getEndpointIdentificationAlgorithm());
        assertEquals("tesla.mark4z.asia", ((SNIHostName) tls.parameters.getServerNames().get(0)).getAsciiName());
        assertEquals(1, tls.handshakes);
        assertTrue(tls.timeout > 0 && tls.timeout <= 2000);
        assertTrue(tls.output.toString("US-ASCII").contains("Host: tesla.mark4z.asia:9999\r\n"));
        assertSame(tls, owned.get(0)); assertSame(tls, closed.get(0));
    }
    @Test public void cancellationBeforeBindAndAfterBindNeverConnects() {
        RawSocket raw = new RawSocket();
        BrowserHttpsSelfCheck check = new BrowserHttpsSelfCheck(RESPONSE);
        assertEquals("CANCELLED", check.run(raw, () -> true, socket -> false, socket -> {}));
        assertNull(raw.source); assertNull(raw.destination);
        AtomicBoolean cancelled = new AtomicBoolean();
        RawSocket duringBind = new RawSocket() {
            @Override public void bind(SocketAddress address) { super.bind(address); cancelled.set(true); }
        };
        assertEquals("CANCELLED", check.run(duringBind, cancelled::get, socket -> false, socket -> {}));
        assertNull(duringBind.destination);
    }
    @Test public void cancellationAfterTlsCreationOwnsAndClosesWithoutHandshakeOrRequest() {
        TlsSocket tls = new TlsSocket();
        List<Socket> closed = new ArrayList<>();
        BrowserHttpsSelfCheck check = new BrowserHttpsSelfCheck(RESPONSE, (socket, host, port) -> tls);
        assertEquals("CANCELLED", check.run(new RawSocket(), () -> false, socket -> false, closed::add));
        assertEquals(0, tls.handshakes); assertEquals(0, tls.output.size()); assertSame(tls, closed.get(0));
    }
    @Test public void timeoutHandshakeRejectionAndResponseMismatchCannotPass() {
        RawSocket raw = new RawSocket(); raw.timeout = true;
        assertEquals("CONNECT_TIMEOUT", new BrowserHttpsSelfCheck(RESPONSE).run(raw, () -> false, socket -> true, socket -> {}));
        TlsSocket tls = new TlsSocket(); tls.failHandshake = true;
        BrowserHttpsSelfCheck check = new BrowserHttpsSelfCheck(RESPONSE, (socket, host, port) -> tls);
        assertEquals("TLS_TRUST_HOSTNAME_OR_HANDSHAKE_FAILED", check.run(new RawSocket(), () -> false, socket -> true, socket -> {}));
        assertEquals(0, tls.output.size());
        tls.failHandshake = false;
        tls.input = new ByteArrayInputStream(new byte[]{0});
        assertEquals("RESPONSE_MISMATCH", check.run(new RawSocket(), () -> false, socket -> true, socket -> {}));
        tls.input = new InputStream() { @Override public int read() throws IOException { throw new SocketTimeoutException(); } };
        assertEquals("READ_TIMEOUT", check.run(new RawSocket(), () -> false, socket -> true, socket -> {}));
    }
    @Test public void cancellationDuringResponseCannotReportSuccess() {
        AtomicBoolean cancelled = new AtomicBoolean();
        TlsSocket tls = new TlsSocket();
        tls.input = new InputStream() { @Override public int read() throws IOException { cancelled.set(true); throw new SocketException("closed"); } };
        BrowserHttpsSelfCheck check = new BrowserHttpsSelfCheck(RESPONSE, (socket, host, port) -> tls);
        assertEquals("CANCELLED", check.run(new RawSocket(), cancelled::get, socket -> true, socket -> {}));
    }
    @Test public void expectedResponseIsBoundedAndCopied() {
        assertThrows(IllegalArgumentException.class, () -> new BrowserHttpsSelfCheck(null));
        assertThrows(IllegalArgumentException.class, () -> new BrowserHttpsSelfCheck(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new BrowserHttpsSelfCheck(new byte[4097]));
        byte[] expected = RESPONSE.clone();
        TlsSocket tls = new TlsSocket();
        BrowserHttpsSelfCheck check = new BrowserHttpsSelfCheck(expected, (socket, host, port) -> tls);
        expected[0] = 0;
        assertEquals("PASS", check.run(new RawSocket(), () -> false, socket -> true, socket -> {}));
    }
}
