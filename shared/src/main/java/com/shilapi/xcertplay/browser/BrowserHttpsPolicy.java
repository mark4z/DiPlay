package com.shilapi.xcertplay.browser;

/** Fixed, local-only endpoints. Production always uses DUAL; SINGLE is a teardown test seam. */
public final class BrowserHttpsPolicy {
    public static final String ADDRESS = "100.99.9.9";
    public static final String COMPATIBILITY_ADDRESS = "192.168.247.2";
    public static final String HOSTNAME = "tesla.mark4z.asia";
    public static final int HTTPS_PORT = 9999;
    public static final int SINGLE = 1, DUAL = 2;
    public static final long STARTUP_TIMEOUT_MS = 15_000L;
    public static final String VIEWER_URL = "https://" + HOSTNAME + ":9999/";
    public static final String HEALTH_URL = VIEWER_URL + "health";
    public static final String WEBSOCKET_URL = "wss://" + HOSTNAME + ":9999/carplay";
    private BrowserHttpsPolicy() { }
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
