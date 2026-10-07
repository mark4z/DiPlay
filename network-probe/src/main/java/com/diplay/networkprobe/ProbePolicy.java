package com.diplay.networkprobe;

/** Fixed experiment endpoints; no user-controlled routes or hosts. */
public final class ProbePolicy {
    public static final String ADDRESS = "100.99.9.9";
    public static final String COMPATIBILITY_ADDRESS = "192.168.247.2";
    public static final String HOSTNAME = "tesla.mark4z.asia";
    public static final String HOTSPOT_HOSTNAME = "test.mark4z.asia";
    public static final int HOTSPOT = 3;
    public static final int DEFAULT_PORT = 18080;
    public static final int HTTPS_PORT = 9999;
    public static final int SINGLE = 1;
    public static final int DUAL = 2;
    public static final long STARTUP_TIMEOUT_MS = 15_000L;
    public static final long DURATION_MS = 5 * 60 * 1000L;
    private ProbePolicy() {}

    public static boolean isTestPort(int port) {
        return port == DEFAULT_PORT || port == HTTPS_PORT;
    }

    public static boolean isTestMode(int mode) { return mode == SINGLE || mode == DUAL; }

    public static boolean isAllowedHostname(String hostname) {
        return HOSTNAME.equals(hostname) || HOTSPOT_HOSTNAME.equals(hostname);
    }

    public static String modeName(int mode) {
        if (mode == HOTSPOT) return "HOTSPOT_NO_VPN";
        if (!isTestMode(mode)) throw new IllegalArgumentException("INVALID_TEST_MODE");
        return mode == SINGLE ? "SINGLE" : "DUAL_HANDOVER";
    }

    public static String[] addresses(int mode) {
        if (!isTestMode(mode)) throw new IllegalArgumentException("INVALID_TEST_MODE");
        return mode == SINGLE ? new String[]{ADDRESS} : new String[]{ADDRESS, COMPATIBILITY_ADDRESS};
    }

    public static int requireTestPort(int port) {
        if (!isTestPort(port)) throw new IllegalArgumentException("INVALID_TEST_PORT");
        return port;
    }

    public static String authority(int port) {
        requireTestPort(port);
        return (port == HTTPS_PORT ? HOSTNAME : ADDRESS) + ":" + port;
    }

    public static String healthUrl(int port) { return (port == HTTPS_PORT ? "https://" : "http://") + authority(port) + "/health"; }

    public static boolean overlaps(byte[] candidate, byte[] network, int prefix) {
        if (candidate.length != 4 || network.length != 4 || prefix < 1 || prefix > 32) return false;
        for (int bit = 0; bit < prefix; bit++) {
            int mask = 1 << (7 - bit % 8);
            if ((candidate[bit / 8] & mask) != (network[bit / 8] & mask)) return false;
        }
        return true;
    }

    /** Memory-only, single-use grant. Process death cannot restore permission to start. */
    public static final class StartGate {
        private long sequence;
        private long pending;
        public synchronized boolean hasPending() { return pending != 0; }
        public synchronized long arm() { pending = ++sequence; return pending; }
        public synchronized boolean consume(long token) {
            if (token == 0 || token != pending) return false;
            pending = 0;
            return true;
        }
        public synchronized void cancel() { pending = 0; }
    }
}
