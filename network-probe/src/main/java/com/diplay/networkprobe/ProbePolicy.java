package com.diplay.networkprobe;

/** Fixed experiment configuration: no user-controlled routes, hosts or destinations. */
public final class ProbePolicy {
    public static final String ADDRESS = "100.96.23.17";
    public static final int PORT = 18080;
    public static final long DURATION_MS = 5 * 60 * 1000L;
    private ProbePolicy() {}

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
        public synchronized long arm() { pending = ++sequence; return pending; }
        public synchronized boolean consume(long token) {
            if (token == 0 || token != pending) return false;
            pending = 0;
            return true;
        }
        public synchronized void cancel() { pending = 0; }
    }
}
