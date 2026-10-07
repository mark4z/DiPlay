package com.diplay.networkprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.system.OsConstants;
import android.system.ErrnoException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;

public final class ProbeVpnService extends VpnService {
    public static final String START = "com.diplay.networkprobe.START";
    public static final String STOP = "com.diplay.networkprobe.STOP";
    public static final String MODE_EXTRA = "mode";
    public static final ProbePolicy.StartGate GATE = new ProbePolicy.StartGate();
    public static volatile String status = "Stopped / 已停止";
    public static volatile boolean running;
    public static volatile boolean recoveryRequired;
    public static volatile long endsAt;
    public static volatile int accepted, health;
    public static volatile int sessionPort, sessionMode;
    public static volatile String interfaceReport = "NOT_RUN";
    public static volatile String lastError = "NONE";
    public static volatile String selfCheck = "NOT_RUN";
    private final Handler main = new Handler(Looper.getMainLooper());
    // This reference and all lifecycle/status transitions belong to the main thread.
    private ProbeSession session;
    private String stopReason;
    private boolean destroyed;
    private final Runnable slowStop = () -> {
        if (session != null && session.isCancelled()) {
            status = "Stopping is taking longer than expected / 停止较慢：请在 Android 设置中断开 VPN，必要时强行停止本应用";
            lastError = "CLEANUP_PENDING_" + stopReason;
        }
    };
    private final Runnable expiry = () -> stopProbe("TIME_LIMIT");
    private final Runnable startupTimeout = () -> stopProbe("STARTUP_TIMEOUT");
    private final Runnable foregroundCheck = new Runnable() {
        @Override public void run() {
            if (session == null || session.isCancelled()) return;
            if (!foregroundUnlocked()) stopProbe("BACKGROUND_OR_LOCKED");
            else main.postDelayed(this, 500);
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopProbe("USER_STOP");
            return START_NOT_STICKY;
        }
        if (intent == null || !START.equals(intent.getAction())
                || !GATE.consume(intent.getLongExtra("grant", 0))) {
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        if (recoveryRequired) { stopSelf(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        final int mode = intent.getIntExtra(MODE_EXTRA, 0);
        final int port = ProbePolicy.DEFAULT_PORT;
        if (!ProbePolicy.isTestMode(mode)) {
            lastError = "INVALID_TEST_MODE";
            status = "Stopped / 已停止: INVALID_TEST_MODE";
            stopSelf();
            return START_NOT_STICKY;
        }
        sessionPort = port;
        sessionMode = mode;
        interfaceReport = "PENDING";
        accepted = health = 0;
        lastError = "NONE";
        selfCheck = "PENDING";
        running = true; // Includes starting/stopping. Do not stack workers when OEM calls stall.
        endsAt = SystemClock.elapsedRealtime() + ProbePolicy.DURATION_MS;
        status = "Starting / 正在启动";
        stopReason = null;
        ProbeInterfaces interfaces = new ProbeInterfaces(mode);
        ProbeSession current = new ProbeSession(
                action -> startWorker(action, "health-only-cleanup"),
                () -> startWorker(() -> {
                    ProbeInterfaces.Snapshot afterClose = interfaces.snapshot();
                    main.post(() -> finishStopped(afterClose));
                }, "health-only-final-snapshot"));
        session = current;
        main.postDelayed(expiry, ProbePolicy.DURATION_MS);
        main.postDelayed(startupTimeout, ProbePolicy.STARTUP_TIMEOUT_MS);
        main.post(foregroundCheck);
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("probe", "Network experiment", NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ProbeVpnService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
            PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, ProbeActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, "probe")
                    .setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("DiPlay health experiment: 5 minutes")
                    .setContentText(ProbePolicy.modeName(mode) + " :18080; Stop removes all test interfaces")
                    .setContentIntent(open).setOngoing(true).addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop / 停止", stop).build();
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
            else startForeground(1, notification);
            startWorker(() -> startAndServe(current, port, mode, interfaces), "health-only-io");
        } catch (RuntimeException failure) {
            stopProbe("FOREGROUND_START_FAILED");
            current.workerFinished();
        }
        return START_NOT_STICKY;
    }

    private static void startWorker(Runnable action, String name) {
        Thread worker = new Thread(action, name);
        worker.setDaemon(true);
        worker.start();
    }

    private void startAndServe(ProbeSession current, int port, int mode, ProbeInterfaces interfaces) {
        try {
            if (current.isCancelled()) return;
            reportStage(current, "STARTING_CHECKS");
            if (!foregroundUnlocked()) throw new ProbeFailure("BACKGROUND_OR_LOCKED");
            if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) throw new ProbeFailure("LOCAL_NETWORK_PERMISSION_DENIED");
            if (VpnService.prepare(this) != null) throw new ProbeFailure("CONSENT_REQUIRED");
            rejectConflicts(mode);
            if (current.isCancelled()) return;
            if (!ProbeHandover.establish(current, mode, this::establishInterface, stage -> {
                reportStage(current, stage);
                if (stage.startsWith("AFTER_")) {
                    String snapshot = interfaces.snapshot().text;
                    main.post(() -> {
                        if (session == current && !current.isCancelled()) {
                            String entry = stage + "\n" + snapshot;
                            interfaceReport = "PENDING".equals(interfaceReport) ? entry : interfaceReport + "\n" + entry;
                        }
                    });
                }
            })) return;
            reportStage(current, "STARTING_BIND");
            // Do not assume the old interface is UP. Attempt the same exact primary bind;
            // after-handover diagnostics were captured before a possible bind failure.
            ServerSocket listener = new ServerSocket();
            if (!current.own(listener)) return;
            try {
                if (!ProbeListener.bind(listener, port, current::isCancelled)) return;
            } catch (IOException | SecurityException failure) {
                throw new ProbeFailure(ProbeListener.failureDescription(port, failure, cause -> {
                    if (!(cause instanceof ErrnoException)) return null;
                    int errno = ((ErrnoException) cause).errno;
                    return "errno=" + errno + " (" + OsConstants.errnoName(errno) + ")";
                }));
            }
            main.post(() -> {
                if (session == current && !current.isCancelled()) {
                    main.removeCallbacks(startupTimeout);
                    status = ProbePolicy.modeName(mode) + "; socket listening on port " + port + " / 已监听（不代表外部可达）";
                    lastError = "NONE";
                }
            });
            ProbeSelfCheck check = new ProbeSelfCheck(port);
            Socket localClient = new Socket();
            if (!current.own(localClient)) return;
            if (current.workerStarted()) {
                Runnable checkTimeout = () -> {
                    if (session == current && !current.isCancelled() && "PENDING".equals(selfCheck)) {
                        selfCheck = "TOTAL_TIMEOUT";
                        current.closeAsync(localClient);
                    }
                };
                main.postDelayed(checkTimeout, ProbeSelfCheck.TOTAL_TIMEOUT_MS);
                try {
                    startWorker(() -> {
                        try {
                            String result = check.run(localClient, current::isCancelled);
                            main.post(() -> {
                                if (session == current && !current.isCancelled() && "PENDING".equals(selfCheck)) selfCheck = result;
                            });
                        } finally {
                            main.removeCallbacks(checkTimeout);
                            current.closeOwned(localClient);
                            current.workerFinished();
                        }
                    }, "health-only-self-check");
                } catch (RuntimeException failure) {
                    main.removeCallbacks(checkTimeout);
                    current.workerFinished();
                    throw failure;
                }
            }
            serve(listener, current, check);
        } catch (ProbeFailure failure) { failSession(current, failure.getMessage()); }
        catch (IOException failure) { failSession(current, failure.getMessage() != null && failure.getMessage().endsWith("_ESTABLISH_REJECTED") ? failure.getMessage() : "START_OR_LISTENER_IO_FAILED"); }
        catch (SecurityException failure) { failSession(current, "PERMISSION_DENIED"); }
        catch (Exception failure) { failSession(current, "START_OR_LISTENER_FAILED"); }
        finally { current.workerFinished(); }
    }

    private ParcelFileDescriptor establishInterface(String address) throws Exception {
        if (!foregroundUnlocked()) throw new ProbeFailure("BACKGROUND_OR_LOCKED");
        // Identical bounded configuration for both comparison modes and each interface.
        // Only the app is allowed, with only its own /32 host route. Never route Internet traffic.
        return new Builder().setSession("DiPlay health-only " + address)
                .addAddress(address, 32).addRoute(address, 32)
                .addAllowedApplication(getPackageName())
                .allowBypass().allowFamily(OsConstants.AF_INET6).establish();
    }

    private boolean foregroundUnlocked() {
        PowerManager power = getSystemService(PowerManager.class);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        return ProbeActivity.isForeground && power != null && power.isInteractive()
                && keyguard != null && !keyguard.isKeyguardLocked();
    }

    private void reportStage(ProbeSession current, String stage) {
        main.post(() -> {
            if (session == current && !current.isCancelled()) {
                status = "Starting / 正在启动: " + stage;
                lastError = stage;
            }
        });
    }

    private void rejectConflicts(int mode) throws Exception {
        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        String[] candidates = ProbePolicy.addresses(mode);
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                throw new ProbeFailure("EXISTING_VPN_STOP_IT_FIRST");
            LinkProperties properties = connectivity.getLinkProperties(network);
            if (properties != null) for (RouteInfo route : properties.getRoutes()) {
                for (String candidate : candidates)
                    if (ProbePolicy.overlaps(InetAddress.getByName(candidate).getAddress(), route.getDestination().getAddress().getAddress(), route.getDestination().getPrefixLength()))
                        throw new ProbeFailure("ADDRESS_ROUTE_CONFLICT " + candidate);
            }
        }
        for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces()))
            for (InterfaceAddress address : iface.getInterfaceAddresses())
                for (String candidate : candidates)
                    if (ProbePolicy.overlaps(InetAddress.getByName(candidate).getAddress(), address.getAddress().getAddress(), address.getNetworkPrefixLength()))
                        throw new ProbeFailure("ADDRESS_INTERFACE_CONFLICT " + candidate);
    }

    private void serve(ServerSocket listener, ProbeSession current, ProbeSelfCheck check) throws IOException {
        while (!current.isCancelled()) {
            Socket incoming = listener.accept();
            if (!current.own(incoming)) return;
            try {
                if (current.isCancelled()) return;
                if (SystemClock.elapsedRealtime() >= endsAt) { failSession(current, "TIME_LIMIT"); return; }
                boolean external = !check.isOwnConnection(incoming);
                main.post(() -> { if (session == current && !current.isCancelled() && external) accepted++; });
                incoming.setSoTimeout(2000);
                String request = HealthProtocol.readRequest(incoming.getInputStream());
                if (current.isCancelled()) return;
                if (SystemClock.elapsedRealtime() >= endsAt) { failSession(current, "TIME_LIMIT"); return; }
                incoming.getOutputStream().write(HealthProtocol.response(request));
                incoming.getOutputStream().flush();
                main.post(() -> {
                    if (session == current && !current.isCancelled() && external && HealthProtocol.isHealth(request)) health++;
                });
            } catch (IOException ignored) {
                main.post(() -> {
                    if (session == current && !current.isCancelled()) lastError = "CLIENT_IO_OR_HEADER_LIMIT";
                });
            } finally { current.closeOwned(incoming); }
        }
    }

    private void failSession(ProbeSession current, String reason) {
        main.post(() -> { if (session == current && !current.isCancelled()) stopProbe(reason); });
    }

    private void stopProbe(String reason) {
        GATE.cancel();
        main.removeCallbacks(expiry);
        main.removeCallbacks(startupTimeout);
        main.removeCallbacks(foregroundCheck);
        if (session == null) {
            if (!destroyed) stopSelf();
            return;
        }
        if (session.isCancelled()) return;
        stopReason = reason;
        endsAt = 0;
        lastError = "STOPPING_" + reason;
        if ("PENDING".equals(selfCheck)) selfCheck = "CANCELLED";
        status = "Stopping; closing all test descriptors / 正在停止并关闭全部测试接口与描述符";
        // Signal first, then close on independent background workers. Never block the main looper.
        session.cancel();
        main.postDelayed(slowStop, 2000);
    }

    private void finishStopped(ProbeInterfaces.Snapshot afterClose) {
        // A new start is prohibited until worker completion AND every owned resource close.
        ProbeSession finished = session;
        if (finished == null) return;
        main.removeCallbacks(slowStop);
        session = null;
        running = false;
        recoveryRequired = finished.didCloseFail() || afterClose.visible || afterClose.failed;
        interfaceReport += "\nAFTER_ALL_OWNED_CLOSES\n" + afterClose.text;
        endsAt = 0;
        lastError = finished.didCloseFail() ? "CLEANUP_FAILED_" + stopReason
                : afterClose.failed ? "CLEANUP_OBSERVATION_FAILED_" + stopReason
                : afterClose.visible ? "CLEANUP_INTERFACE_STILL_VISIBLE_" + stopReason : stopReason;
        status = recoveryRequired
                ? "Cleanup needs verification; disconnect VPN and force-stop this app before retrying / 清理需确认：请断开 VPN 并强行停止本应用后重试"
                : "Stopped / 已停止: " + stopReason;
        if (!destroyed) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }
    @Override public void onRevoke() { main.post(() -> stopProbe("SYSTEM_REVOKED")); }
    @Override public void onTaskRemoved(Intent rootIntent) { stopProbe("TASK_REMOVED"); }
    @Override public void onDestroy() {
        destroyed = true;
        stopProbe("SERVICE_DESTROYED");
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    private static final class ProbeFailure extends Exception {
        ProbeFailure(String code) { super(code); }
    }
}
