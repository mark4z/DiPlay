package com.shilapi.xcertplay.browser;

/** Single-use, process-local ownership handoff. Neither token nor value is persisted. */
public final class BrowserSessionGrant<T> {
    private long sequence, pending;
    private T value;
    public synchronized boolean hasPending() { return pending != 0; }
    public synchronized long arm(T value) {
        if (value == null) throw new IllegalArgumentException("MISSING_IDENTITY");
        this.value = value;
        pending = ++sequence;
        return pending;
    }
    public synchronized T consume(long token) {
        if (token == 0 || token != pending) return null;
        T result = value;
        cancel();
        return result;
    }
    public synchronized void cancel() { pending = 0; value = null; }
}
