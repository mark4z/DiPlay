package com.diplay.networkprobe;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
public class HealthProtocolTest {
    private String response(String request) { return new String(HealthProtocol.response(request), StandardCharsets.UTF_8); }
    @Test public void getAndHeadExactPathOnly() {
        assertTrue(response("GET /health HTTP/1.1\r\n\r\n").endsWith(HealthProtocol.BODY));
        assertTrue(response("HEAD /health HTTP/1.1\r\n\r\n").endsWith("\r\n\r\n"));
        for (String request : new String[]{"POST /health HTTP/1.1\r\n", "GET / HTTP/1.1\r\n", "GET /health?x=1 HTTP/1.1\r\n", "GET /health HTTP/2\r\n", "GET http://example.com/health HTTP/1.1\r\n"}) {
            assertFalse(HealthProtocol.isHealth(request));
            assertTrue(response(request).startsWith("HTTP/1.1 404"));
        }
    }
    @Test public void headersAreBoundedAndBodiesNeverRead() throws Exception {
        String header = "GET /health HTTP/1.1\r\nHost: example\r\n\r\n";
        ByteArrayInputStream stream = new ByteArrayInputStream((header + "secret body").getBytes(StandardCharsets.US_ASCII));
        assertEquals(header, HealthProtocol.readRequest(stream));
        assertEquals('s', stream.read());
        try { HealthProtocol.readRequest(new ByteArrayInputStream(new byte[4097])); fail(); } catch (IOException expected) { }
        try { HealthProtocol.readRequest(new ByteArrayInputStream("GET /health".getBytes(StandardCharsets.US_ASCII))); fail(); } catch (IOException expected) { }
    }
}
