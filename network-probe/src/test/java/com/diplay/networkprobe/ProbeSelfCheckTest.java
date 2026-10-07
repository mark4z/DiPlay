package com.diplay.networkprobe;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class ProbeSelfCheckTest {
    private static class FakeSocket extends Socket {
        int connectTimeout, readTimeout;
        SocketAddress destination, source;
        InputStream input = new ByteArrayInputStream(HealthProtocol.response("GET /health HTTP/1.1\r\n\r\n"));
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean connectTimesOut;
        @Override public void bind(SocketAddress address) { source = address; }
        @Override public int getLocalPort() { return 35001; }
        @Override public void connect(SocketAddress address, int timeout) throws IOException {
            destination = address;
            connectTimeout = timeout;
            if (connectTimesOut) throw new SocketTimeoutException();
        }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public void setSoTimeout(int timeout) { readTimeout = timeout; }
    }

    @Test public void onlyChecksExactLocalEndpointWithFiniteTimeouts() throws Exception {
        for (int port : new int[]{18080}) {
            FakeSocket socket = new FakeSocket();
            ProbeSelfCheck check = new ProbeSelfCheck(port);
            assertEquals("PASS", check.run(socket, () -> false));
            assertEquals(new InetSocketAddress(ProbePolicy.ADDRESS, port), socket.destination);
            assertEquals(new InetSocketAddress(ProbePolicy.ADDRESS, 0), socket.source);
            assertEquals(1500, socket.connectTimeout);
            assertTrue(socket.readTimeout > 0 && socket.readTimeout <= 2000);
            assertTrue(socket.output.toString("US-ASCII").startsWith("GET /health HTTP/1.1\r\nHost: " + ProbePolicy.authority(port) + "\r\n"));
            assertTrue(check.isOwnConnection(new Socket() {
                @Override public int getPort() { return 35001; }
                @Override public InetAddress getInetAddress() { return ((InetSocketAddress) socket.source).getAddress(); }
            }));
        }
    }

    @Test public void cancelledBeforeStartNeverConnects() {
        for (int port : new int[]{18080}) {
            FakeSocket socket = new FakeSocket();
            assertEquals("CANCELLED", new ProbeSelfCheck(port).run(socket, () -> true));
            assertNull(socket.destination);
            assertNull(socket.source);
            assertEquals(0, socket.output.size());
        }
    }

    @Test public void distinguishesConnectTimeoutReadTimeoutAndBadResponse() {
        for (int port : new int[]{18080}) {
            FakeSocket connect = new FakeSocket();
            connect.connectTimesOut = true;
            assertEquals("CONNECT_TIMEOUT", new ProbeSelfCheck(port).run(connect, () -> false));
            FakeSocket read = new FakeSocket();
            read.input = new InputStream() {
                @Override public int read() throws IOException { throw new SocketTimeoutException(); }
            };
            assertEquals("READ_TIMEOUT", new ProbeSelfCheck(port).run(read, () -> false));
            FakeSocket mismatch = new FakeSocket();
            mismatch.input = new ByteArrayInputStream(new byte[]{0});
            assertEquals("RESPONSE_MISMATCH", new ProbeSelfCheck(port).run(mismatch, () -> false));
        }
    }

    @Test public void cancellationWhileReadingCannotReportPassOrIoFailure() {
        for (int port : new int[]{18080}) {
            AtomicBoolean cancelled = new AtomicBoolean();
            FakeSocket socket = new FakeSocket();
            socket.input = new InputStream() {
                @Override public int read() throws IOException {
                    cancelled.set(true);
                    throw new SocketException("closed by cancellation");
                }
            };
            assertEquals("CANCELLED", new ProbeSelfCheck(port).run(socket, cancelled::get));
        }
    }

    @Test public void cancellationAfterSourceBindNeverConnects() {
        for (int port : new int[]{18080}) {
            AtomicBoolean cancelled = new AtomicBoolean();
            FakeSocket socket = new FakeSocket() {
                @Override public void bind(SocketAddress address) {
                    super.bind(address);
                    cancelled.set(true);
                }
            };
            assertEquals("CANCELLED", new ProbeSelfCheck(port).run(socket, cancelled::get));
            assertNull(socket.destination);
            assertEquals(0, socket.output.size());
        }
    }

    @Test public void cancellationAfterConnectNeverWritesHealthRequest() {
        for (int port : new int[]{18080}) {
            AtomicBoolean cancelled = new AtomicBoolean();
            FakeSocket socket = new FakeSocket() {
                @Override public void connect(SocketAddress address, int timeout) throws IOException {
                    super.connect(address, timeout);
                    cancelled.set(true);
                }
            };
            assertEquals("CANCELLED", new ProbeSelfCheck(port).run(socket, cancelled::get));
            assertEquals(0, socket.output.size());
        }
    }
}
