package com.diplay.networkprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
    public static final String PORT_EXTRA = "port";
    public static final ProbePolicy.StartGate GATE = new ProbePolicy.StartGate();
    public static volatile String status = "Stopped / 已停止";
    public static volatile boolean running;
    public static volatile boolean recoveryRequired;
    public static volatile long endsAt;
    public static volatile int accepted, health;
    public static volatile int sessionPort;
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
        final int port = intent.getIntExtra(PORT_EXTRA, 0);
        if (!ProbePolicy.isTestPort(port)) {
            lastError = "INVALID_TEST_PORT";
            status = "Stopped / 已停止: INVALID_TEST_PORT";
            stopSelf();
            return START_NOT_STICKY;
        }
        sessionPort = port;
        accepted = health = 0;
        lastError = "NONE";
        selfCheck = "PENDING";
        running = true; // Includes starting/stopping. Do not stack workers when OEM calls stall.
        endsAt = SystemClock.elapsedRealtime() + ProbePolicy.DURATION_MS;
        status = "Starting / 正在启动";
        stopReason = null;
        ProbeSession current = new ProbeSession(
                action -> startWorker(action, "health-only-cleanup"),
                () -> main.post(this::finishStopped));
        session = current;
        main.postDelayed(expiry, ProbePolicy.DURATION_MS);
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("probe", "Network experiment", NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ProbeVpnService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
            PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, ProbeActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, "probe")
                    .setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("DiPlay health experiment: 5 minutes")
                    .setContentText("Port " + port + "; temporary VPN address; tap Stop to remove it")
                    .setContentIntent(open).setOngoing(true).addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop / 停止", stop).build();
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
            else startForeground(1, notification);
            startWorker(() -> startAndServe(current, port), "health-only-io");
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

    private void startAndServe(ProbeSession current, int port) {
        try {
            if (current.isCancelled()) return;
            reportStage(current, "STARTING_CHECKS");
            if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) throw new ProbeFailure("LOCAL_NETWORK_PERMISSION_DENIED");
            if (VpnService.prepare(this) != null) throw new ProbeFailure("CONSENT_REQUIRED");
            rejectConflicts();
            if (current.isCancelled()) return;
            reportStage(current, "STARTING_ESTABLISH");
            // No routes, DNS, gateway, forwarding, TUN reader/writer, or other applications.
            ParcelFileDescriptor tun = new Builder().setSession("DiPlay health-only experiment")
                    .addAddress(ProbePolicy.ADDRESS, 32)
                    .addAllowedApplication(getPackageName())
                    .allowFamily(OsConstants.AF_INET6).establish();
            if (tun == null) throw new ProbeFailure("ESTABLISH_REJECTED");
            if (!current.own(tun)) return;
            if (current.isCancelled()) return;
            reportStage(current, "STARTING_BIND");
            InetAddress address = InetAddress.getByName(ProbePolicy.ADDRESS);
            if (NetworkInterface.getByInetAddress(address) == null) throw new ProbeFailure("ADDRESS_NOT_VISIBLE");
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
                    status = "VPN interface established; socket listening on port " + port + " / 已建接口并监听（不代表外部可达）";
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
        catch (SecurityException failure) { failSession(current, "PERMISSION_DENIED"); }
        catch (Exception failure) { failSession(current, "START_OR_LISTENER_FAILED"); }
        finally { current.workerFinished(); }
    }

    private void reportStage(ProbeSession current, String stage) {
        main.post(() -> {
            if (session == current && !current.isCancelled()) {
                status = "Starting / 正在启动: " + stage;
                lastError = stage;
            }
        });
    }

    private void rejectConflicts() throws Exception {
        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        byte[] candidate = InetAddress.getByName(ProbePolicy.ADDRESS).getAddress();
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                throw new ProbeFailure("EXISTING_VPN_STOP_IT_FIRST");
            LinkProperties properties = connectivity.getLinkProperties(network);
            if (properties != null) for (RouteInfo route : properties.getRoutes()) {
                if (ProbePolicy.overlaps(candidate, route.getDestination().getAddress().getAddress(), route.getDestination().getPrefixLength()))
                    throw new ProbeFailure("ADDRESS_ROUTE_CONFLICT");
            }
        }
        for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces()))
            for (InterfaceAddress address : iface.getInterfaceAddresses())
                if (ProbePolicy.overlaps(candidate, address.getAddress().getAddress(), address.getNetworkPrefixLength()))
                    throw new ProbeFailure("ADDRESS_INTERFACE_CONFLICT");
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
        if (session == null) {
            if (!destroyed) stopSelf();
            return;
        }
        if (session.isCancelled()) return;
        stopReason = reason;
        endsAt = 0;
        lastError = "STOPPING_" + reason;
        if ("PENDING".equals(selfCheck)) selfCheck = "CANCELLED";
        status = "Stopping; removing VPN address / 正在停止并移除地址";
        // Signal first, then close on independent background workers. Never block the main looper.
        session.cancel();
        main.postDelayed(slowStop, 2000);
    }

    private void finishStopped() {
        // A new start is prohibited until worker completion AND every owned resource close.
        ProbeSession finished = session;
        if (finished == null) return;
        main.removeCallbacks(slowStop);
        session = null;
        running = false;
        recoveryRequired = finished.didCloseFail();
        endsAt = 0;
        lastError = finished.didCloseFail() ? "CLEANUP_FAILED_" + stopReason : stopReason;
        status = finished.didCloseFail()
                ? "Cleanup error; disconnect VPN and force-stop this app before retrying / 清理出错：请断开 VPN 并强行停止本应用后重试"
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
