package com.shilapi.xcertplay.browser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Fixed, local-only endpoints. Production always uses DUAL; SINGLE is a teardown test seam. */
public final class BrowserHttpsPolicy {
    public static final String ADDRESS = "100.99.9.9";
    public static final String COMPATIBILITY_ADDRESS = "192.168.247.2";
    public static final String DEFAULT_HOSTNAME = "tesla.mark4z.asia";
    public static final String CONFIG_RESOURCE = "/diplay-browser-https.properties";
    public static final String HOSTNAME = loadHostname();
    public static final int HTTPS_PORT = 9999;
    public static final int SINGLE = 1, DUAL = 2;
    public static final long STARTUP_TIMEOUT_MS = 15_000L;
    public static final String VIEWER_URL = "https://" + HOSTNAME + ":9999/";
    public static final String HEALTH_URL = VIEWER_URL + "health";
    public static final String WEBSOCKET_URL = "wss://" + HOSTNAME + ":9999/carplay";
    private BrowserHttpsPolicy() { }

    // No Android or generated Java dependency. Standalone javac TLS validators
    // must put a matching hostname resource on their classpath for custom domains;
    // the explicit hostname argument alone cannot override this immutable policy.
    private static String loadHostname() {
        try (InputStream input = BrowserHttpsPolicy.class.getResourceAsStream(CONFIG_RESOURCE)) {
            return readHostname(input);
        } catch (IOException failure) {
            throw new IllegalStateException("HTTPS_CONFIG_UNAVAILABLE");
        }
    }

    static String readHostname(InputStream input) throws IOException {
        if (input == null) return DEFAULT_HOSTNAME;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int value; (value = input.read()) != -1;) {
            if (bytes.size() >= 264 || value > 127) throw new IOException("HTTPS_CONFIG_INVALID");
            bytes.write(value);
        }
        String text = new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
        if (!text.startsWith("hostname=") || !text.endsWith("\n")) throw new IOException("HTTPS_CONFIG_INVALID");
        String hostname = text.substring(9, text.length() - 1);
        try {
            if (!hostname.equals(normalizeHostname(hostname))) throw new IllegalArgumentException();
        } catch (IllegalArgumentException failure) {
            throw new IOException("HTTPS_CONFIG_INVALID");
        }
        return hostname;
    }

    public static String normalizeHostname(String value) {
        if (value == null) throw new IllegalArgumentException("HTTPS_HOSTNAME_INVALID");
        // Check before Unicode case folding can turn a non-ASCII character into ASCII.
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) throw new IllegalArgumentException("HTTPS_HOSTNAME_INVALID");
        }
        String hostname = value.trim().toLowerCase(Locale.ROOT);
        String[] labels = hostname.split("\\.", -1);
        if (hostname.length() > 253 || labels.length < 2 || labels[labels.length - 1].matches("[0-9]+")) {
            throw new IllegalArgumentException("HTTPS_HOSTNAME_INVALID");
        }
        for (String label : labels) {
            if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
                throw new IllegalArgumentException("HTTPS_HOSTNAME_INVALID");
            }
        }
        return hostname;
    }
    public static String[] addresses(int mode) {
        if (mode != SINGLE && mode != DUAL) throw new IllegalArgumentException("INVALID_MODE");
        return mode == SINGLE ? new String[]{ADDRESS} : new String[]{ADDRESS, COMPATIBILITY_ADDRESS};
    }
    public static boolean overlaps(byte[] candidate, byte[] network, int prefix) {
        if (candidate.length != 4 || network.length != 4 || prefix < 1 || prefix > 32) return false;
        for (int bit = 0; bit < prefix; bit++) {
            int mask = 1 << (7 - bit % 8);
            if ((candidate[bit / 8] & mask) != (network[bit / 8] & mask)) return false;
        }
        return true;
    }
}
