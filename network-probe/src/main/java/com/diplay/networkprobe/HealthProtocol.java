package com.diplay.networkprobe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** No request logs, body parsing, reflection, identifiers, cookies or application commands. */
public final class HealthProtocol {
    private HealthProtocol() {}
    public static final int MAX_HEADER_BYTES = 4096;
    public static final String BODY = "diplay-network-probe health ok version=1\n";

    public static String readRequest(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + 2_000_000_000L;
        int state = 0;
        while (bytes.size() < MAX_HEADER_BYTES) {
            if (System.nanoTime() > deadline) throw new IOException("HEADER_TIMEOUT");
            int value = input.read();
            if (value < 0) throw new IOException("INCOMPLETE_HEADER");
            bytes.write(value);
            state = (state == 0 || state == 2) && value == '\r' ? state + 1
                    : (state == 1 || state == 3) && value == '\n' ? state + 1 : 0;
            if (state == 4) return bytes.toString(StandardCharsets.US_ASCII.name());
        }
        throw new IOException("HEADER_LIMIT");
    }
    public static boolean isHealth(String request) {
        return request.startsWith("GET /health HTTP/1.1\r\n")
                || request.startsWith("HEAD /health HTTP/1.1\r\n")
                || request.startsWith("GET /health HTTP/1.0\r\n")
                || request.startsWith("HEAD /health HTTP/1.0\r\n");
    }
    public static byte[] response(String request) {
        boolean ok = isHealth(request);
        String body = ok ? BODY : "not found\n";
        String header = "HTTP/1.1 " + (ok ? "200 OK" : "404 Not Found")
                + "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n\r\n";
        return (header + (request.startsWith("HEAD ") ? "" : body)).getBytes(StandardCharsets.UTF_8);
    }
}
