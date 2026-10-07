package com.diplay.networkprobe;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Resource ownership only. Never performs I/O or calls another object while holding its lock. */
final class ProbeSession {
    private final Executor cleanup;
    private final Runnable terminated;
    private final List<Closeable> owned = new ArrayList<>();
    private boolean cancelled, notified, closeFailed;
    private int closing;
    private int workers = 1; // The startup/listener worker exists before cancellation is possible.

    ProbeSession(Executor cleanup, Runnable terminated) {
        this.cleanup = cleanup;
        this.terminated = terminated;
    }

    synchronized boolean isCancelled() { return cancelled; }
    synchronized boolean didCloseFail() { return closeFailed; }

    /** A resource returned by a late establish/bind is still closed after cancellation. */
    boolean own(Closeable resource) {
        synchronized (this) {
            if (!cancelled) {
                owned.add(resource);
                return true;
            }
            closing++;
        }
        cleanup.execute(() -> closeTracked(resource));
        return false;
    }

    /** Called only by the I/O worker. Cancellation may already have detached this resource. */
    void closeOwned(Closeable resource) {
        synchronized (this) {
            if (!owned.remove(resource)) return;
            closing++;
        }
        closeTracked(resource);
    }

    void closeAsync(Closeable resource) {
        synchronized (this) {
            if (!owned.remove(resource)) return;
            closing++;
        }
        cleanup.execute(() -> closeTracked(resource));
    }

    /** Fast cancellation; every resource has an independent closer, so one cannot block another. */
    void cancel() {
        List<Closeable> detached;
        synchronized (this) {
            cancelled = true;
            detached = new ArrayList<>(owned);
            owned.clear();
            closing += detached.size();
        }
        for (Closeable resource : detached) cleanup.execute(() -> closeTracked(resource));
        notifyIfTerminated();
    }

    synchronized boolean workerStarted() {
        if (cancelled) return false;
        workers++;
        return true;
    }

    void workerFinished() {
        synchronized (this) { if (workers > 0) workers--; }
        notifyIfTerminated();
    }

    private void closeTracked(Closeable resource) {
        try { resource.close(); }
        catch (IOException | RuntimeException failure) {
            synchronized (this) { closeFailed = true; }
        } finally {
            synchronized (this) { closing--; }
            notifyIfTerminated();
        }
    }

    private void notifyIfTerminated() {
        synchronized (this) {
            if (!cancelled || workers != 0 || closing != 0 || notified) return;
            notified = true;
        }
        terminated.run();
    }
}
