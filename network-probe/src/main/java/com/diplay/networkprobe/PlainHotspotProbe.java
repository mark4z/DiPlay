package com.diplay.networkprobe;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.system.ErrnoException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

/** Activity-owned foreground-only hotspot HTTP/HTTPS. No service, VPN or network changes. */
final class PlainHotspotProbe {
    static String status = "Stopped / 已停止", lastError = "NONE", selfCheck = "NOT_RUN", sessionUrl = "NOT_RUN";
    static boolean running, recoveryRequired;
    static volatile long endsAt;
    static int accepted, health;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // All lifecycle/state transitions belong to the main looper, including Activity recreation.
    private static ProbeSession session;
    private static Context application;
    private static String stopReason;
    private static final Runnable EXPIRY = () -> stop("TIME_LIMIT");
    private static final Runnable STARTUP_TIMEOUT = () -> stop("STARTUP_TIMEOUT");
    private static final Runnable FOREGROUND_CHECK = new Runnable() {
        @Override public void run() {
            if (session == null || session.isCancelled()) return;
            if (!foregroundUnlocked(application)) stop("BACKGROUND_OR_LOCKED");
            else MAIN.postDelayed(this, 500);
        }
    };
    private static final Runnable SLOW_STOP = () -> {
        if (session != null && session.isCancelled()) {
            status = "Stopping / 停止较慢：必要时请强行停止本应用";
            lastError = "CLEANUP_PENDING_" + stopReason;
        }
    };
    private PlainHotspotProbe() {}

    static void start(Context context, HotspotAddress selected, int port, ProbeTlsIdentity identity) {
        if (running || recoveryRequired || ProbeVpnService.running || ProbeVpnService.recoveryRequired
                || ProbeVpnService.GATE.hasPending()) return;
        if (selected == null || selected.address == null || !HotspotPolicy.isPrivateIpv4(selected.address.getAddress())
                || !ProbePolicy.isTestPort(port) || (port == ProbePolicy.HTTPS_PORT && identity == null)) {
            rejectStart("INVALID_HOTSPOT_START");
            return;
        }
        if (!foregroundUnlocked(context)) {
            rejectStart("BACKGROUND_OR_LOCKED");
            return;
        }
        application = context.getApplicationContext();
        running = true; // Includes startup and all pending cleanup; never stack sessions.
        status = "Starting ordinary hotspot / 正在启动普通热点: " + port;
        sessionUrl = selected.url(port);
        selfCheck = "PENDING";
        lastError = "NONE";
        accepted = health = 0;
        endsAt = SystemClock.elapsedRealtime() + ProbePolicy.DURATION_MS;
        stopReason = null;
        ProbeSession current = new ProbeSession(action -> worker(action, "hotspot-health-cleanup"),
                () -> MAIN.post(PlainHotspotProbe::finishStopped));
        session = current;
        MAIN.postDelayed(EXPIRY, ProbePolicy.DURATION_MS);
        MAIN.postDelayed(STARTUP_TIMEOUT, ProbePolicy.STARTUP_TIMEOUT_MS);
        MAIN.post(FOREGROUND_CHECK);
        Context currentApplication = application;
        try { worker(() -> startAndServe(currentApplication, selected, port, identity, current), "hotspot-health-io"); }
        catch (RuntimeException failure) { stop("WORKER_START_FAILED"); current.workerFinished(); }
    }

    static void worker(Runnable work, String name) {
        Thread thread = new Thread(work, name);
        thread.setDaemon(true);
        thread.start();
    }

    private static void rejectStart(String code) {
        lastError = code;
        status = "Stopped / 已停止: " + code;
    }

    private static boolean foregroundUnlocked(Context context) {
        if (context == null || !ProbeActivity.isForeground) return false;
        try {
            PowerManager power = context.getSystemService(PowerManager.class);
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            return power != null && power.isInteractive() && keyguard != null && !keyguard.isKeyguardLocked();
        } catch (RuntimeException failure) { return false; }
    }

    private static void verifyActive(Context context, HotspotAddress selected) throws IOException {
        if (!foregroundUnlocked(context)) throw new ProbeFailure("BACKGROUND_OR_LOCKED");
        if (SystemClock.elapsedRealtime() >= endsAt) throw new ProbeFailure("TIME_LIMIT");
        selected.verify(context);
    }

    private static void startAndServe(Context context, HotspotAddress selected, int port,
                                      ProbeTlsIdentity identity, ProbeSession current) {
        try {
            if (current.isCancelled()) return;
            verifyActive(context, selected);
            if (Build.VERSION.SDK_INT >= 37 && context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")
                    != PackageManager.PERMISSION_GRANTED) throw new ProbeFailure("LOCAL_NETWORK_PERMISSION_DENIED");
            if (port == ProbePolicy.HTTPS_PORT) identity.checkValidity(ProbePolicy.HOTSPOT_HOSTNAME);
            if (current.isCancelled()) return;
            ServerSocket listener = port == ProbePolicy.HTTPS_PORT ? identity.newServerSocket() : new ServerSocket();
            if (!current.own(listener)) return;
            try {
                if (!HotspotListener.bind(listener, selected.address, port,
                        () -> verifyActive(context, selected), current::isCancelled)) return;
            } catch (HotspotAddress.Failure | ProbeFailure failure) { throw failure; }
            catch (IOException | SecurityException failure) { throw new ProbeFailure(bindFailure(port, failure)); }
            listener.setSoTimeout(500); // Revalidate the exact interface even without incoming clients.
            MAIN.post(() -> {
                if (session == current && !current.isCancelled()) {
                    MAIN.removeCallbacks(STARTUP_TIMEOUT);
                    status = "Ordinary hotspot listening / 普通热点已监听: " + selected.url(port)
                            + " · " + selected.label() + "（不代表外部可达）";
                }
            });
            ProbeSelfCheck check = new ProbeSelfCheck(selected.address, port, ProbePolicy.HOTSPOT_HOSTNAME);
            Socket localClient = new Socket();
            if (!current.own(localClient)) return;
            if (current.workerStarted()) {
                Runnable timeout = () -> {
                    if (session == current && !current.isCancelled() && "PENDING".equals(selfCheck)) {
                        selfCheck = "TOTAL_TIMEOUT";
                        current.closeAsync(localClient);
                    }
                };
                MAIN.postDelayed(timeout, ProbeSelfCheck.TOTAL_TIMEOUT_MS);
                try {
                    worker(() -> {
                        try {
                            String result = check.run(localClient, current::isCancelled, current::own, current::closeAsync);
                            MAIN.post(() -> {
                                if (session == current && !current.isCancelled() && "PENDING".equals(selfCheck)) selfCheck = result;
                            });
                        } finally {
                            MAIN.removeCallbacks(timeout);
                            current.closeAsync(localClient);
                            current.workerFinished();
                        }
                    }, "hotspot-health-self-check");
                } catch (RuntimeException failure) {
                    MAIN.removeCallbacks(timeout);
                    current.workerFinished();
                    throw failure;
                }
            }
            serve(context, selected, port, identity, listener, current, check);
        } catch (ProbeTlsIdentity.Failure failure) { fail(current, failure.code); }
        catch (HotspotAddress.Failure failure) { fail(current, failure.code); }
        catch (ProbeFailure failure) { fail(current, failure.code); }
        catch (SecurityException failure) { fail(current, "PERMISSION_DENIED"); }
        catch (IOException failure) { fail(current, "HOTSPOT_START_OR_LISTENER_IO_FAILED"); }
        catch (Exception failure) { fail(current, "HOTSPOT_START_OR_LISTENER_FAILED"); }
        finally { current.workerFinished(); }
    }

    /** Numeric errno only, never provider exception text, hostnames, requests or certificate data. */
    private static String bindFailure(int port, Throwable failure) {
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (failure instanceof ErrnoException)
                return "HOTSPOT_BIND_FAILED_PORT_" + port + "_ERRNO_" + ((ErrnoException) failure).errno;
        }
        return "HOTSPOT_BIND_FAILED_PORT_" + port;
    }

    private static void serve(Context context, HotspotAddress selected, int port, ProbeTlsIdentity identity,
                              ServerSocket listener, ProbeSession current, ProbeSelfCheck check)
            throws IOException, ProbeTlsIdentity.Failure {
        while (!current.isCancelled()) {
            verifyActive(context, selected);
            if (port == ProbePolicy.HTTPS_PORT) identity.checkValidity(ProbePolicy.HOTSPOT_HOSTNAME);
            if (current.isCancelled()) return;
            Socket incoming;
            try { incoming = listener.accept(); }
            catch (SocketTimeoutException idle) { continue; }
            if (!current.own(incoming)) return;
            try {
                if (current.isCancelled()) return;
                verifyActive(context, selected);
                boolean external = !check.isOwnConnection(incoming);
                MAIN.post(() -> { if (session == current && !current.isCancelled() && external) accepted++; });
                incoming.setSoTimeout(2000);
                if (incoming instanceof SSLSocket) ((SSLSocket) incoming).startHandshake();
                String request = HealthProtocol.readRequest(incoming.getInputStream());
                if (current.isCancelled()) return;
                verifyActive(context, selected);
                if (current.isCancelled()) return;
                incoming.getOutputStream().write(HealthProtocol.response(request));
                incoming.getOutputStream().flush();
                MAIN.post(() -> {
                    if (session == current && !current.isCancelled() && external && HealthProtocol.isHealth(request)) health++;
                });
            } catch (HotspotAddress.Failure | ProbeFailure failure) { throw failure; }
            catch (IOException failure) {
                if (current.isCancelled()) return;
                // Client failures are nonfatal; changed/managed interfaces and lifecycle failures are fatal.
                verifyActive(context, selected);
                String code = failure instanceof SSLException ? "CLIENT_TLS_HANDSHAKE_OR_IO_FAILED" : "CLIENT_IO_OR_HEADER_LIMIT";
                MAIN.post(() -> { if (session == current && !current.isCancelled()) lastError = code; });
            } finally { current.closeOwned(incoming); }
        }
    }

    private static void fail(ProbeSession current, String reason) {
        MAIN.post(() -> { if (session == current && !current.isCancelled()) stop(reason); });
    }

    static void stop(String reason) {
        MAIN.removeCallbacks(EXPIRY);
        MAIN.removeCallbacks(STARTUP_TIMEOUT);
        MAIN.removeCallbacks(FOREGROUND_CHECK);
        if (session == null || session.isCancelled()) return;
        stopReason = reason;
        endsAt = 0;
        lastError = "STOPPING_" + reason;
        if ("PENDING".equals(selfCheck)) selfCheck = "CANCELLED";
        status = "Stopping ordinary hotspot listener / 正在停止普通热点监听";
        session.cancel();
        MAIN.postDelayed(SLOW_STOP, 2000);
    }

    private static void finishStopped() {
        ProbeSession finished = session;
        if (finished == null) return;
        MAIN.removeCallbacks(SLOW_STOP);
        session = null;
        application = null;
        running = false;
        recoveryRequired = finished.didCloseFail();
        endsAt = 0;
        lastError = recoveryRequired ? "CLEANUP_FAILED_" + stopReason : stopReason;
        status = recoveryRequired ? "Cleanup failed / 清理失败：请强行停止本应用后重试" : "Stopped / 已停止: " + stopReason;
    }

    private static final class ProbeFailure extends IOException {
        private static final long serialVersionUID = 1L;
        final String code;
        ProbeFailure(String code) { super(code); this.code = code; }
    }
}
