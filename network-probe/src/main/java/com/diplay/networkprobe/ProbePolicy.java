package com.diplay.networkprobe;

/** Fixed address and two explicit test ports; no user-controlled routes or hosts. */
public final class ProbePolicy {
    public static final String ADDRESS = "100.96.23.17";
    public static final int DEFAULT_PORT = 18080;
    public static final int HTTP_PORT = 80;
    public static final long DURATION_MS = 5 * 60 * 1000L;
    private ProbePolicy() {}

    public static boolean isTestPort(int port) {
        return port == DEFAULT_PORT || port == HTTP_PORT;
    }

    public static int requireTestPort(int port) {
        if (!isTestPort(port)) throw new IllegalArgumentException("INVALID_TEST_PORT");
        return port;
    }

    public static String authority(int port) {
        requireTestPort(port);
        return ADDRESS + (port == HTTP_PORT ? "" : ":" + port);
    }

    public static String healthUrl(int port) { return "http://" + authority(port) + "/health"; }

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
