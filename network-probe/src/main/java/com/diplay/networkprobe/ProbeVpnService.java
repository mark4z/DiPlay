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
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;

public final class ProbeVpnService extends VpnService {
    public static final String START = "com.diplay.networkprobe.START";
    public static final String STOP = "com.diplay.networkprobe.STOP";
    public static final ProbePolicy.StartGate GATE = new ProbePolicy.StartGate();
    public static volatile String status = "Stopped / 已停止";
    public static volatile boolean running;
    public static volatile long endsAt;
    public static volatile int accepted, health;
    public static volatile String lastError = "NONE";
    private final Handler main = new Handler(Looper.getMainLooper());
    private ParcelFileDescriptor tun;
    private ServerSocket server;
    private Socket client;
    private long generation;
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
        if (running) return START_NOT_STICKY;
        accepted = health = 0;
        lastError = "NONE";
        try {
            if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) throw new ProbeFailure("LOCAL_NETWORK_PERMISSION_DENIED");
            if (VpnService.prepare(this) != null) throw new ProbeFailure("CONSENT_REQUIRED");
            rejectConflicts();
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("probe", "Network experiment", NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ProbeVpnService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
            PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, ProbeActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, "probe")
                    .setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("DiPlay health experiment: 5 minutes")
                    .setContentText("Temporary VPN address; tap Stop to remove it")
                    .setContentIntent(open).setOngoing(true).addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop / 停止", stop).build();
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
            else startForeground(1, notification);
            // No routes, DNS, gateway, forwarding, TUN reader/writer, or other applications.
            tun = new Builder().setSession("DiPlay health-only experiment")
                    .addAddress(ProbePolicy.ADDRESS, 32)
                    .addAllowedApplication(getPackageName())
                    .allowFamily(OsConstants.AF_INET6).establish();
            if (tun == null) throw new ProbeFailure("ESTABLISH_REJECTED");
            InetAddress address = InetAddress.getByName(ProbePolicy.ADDRESS);
            if (NetworkInterface.getByInetAddress(address) == null) throw new ProbeFailure("ADDRESS_NOT_VISIBLE");
            server = new ServerSocket();
            server.bind(new InetSocketAddress(address, ProbePolicy.PORT), 1);
            running = true;
            endsAt = SystemClock.elapsedRealtime() + ProbePolicy.DURATION_MS;
            status = "VPN interface established; socket listening / 已建接口并监听（不代表外部可达）";
            long current = ++generation;
            ServerSocket listener = server;
            new Thread(() -> serve(listener, current), "health-only-listener").start();
            main.postDelayed(expiry, ProbePolicy.DURATION_MS);
        } catch (ProbeFailure failure) { stopProbe(failure.getMessage()); }
        catch (java.net.BindException failure) { stopProbe("BIND_FAILED"); }
        catch (SecurityException failure) { stopProbe("PERMISSION_DENIED"); }
        catch (Exception failure) { stopProbe("START_FAILED"); }
        return START_NOT_STICKY;
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

    private void serve(ServerSocket listener, long current) {
        try {
            while (!listener.isClosed()) {
                Socket incoming = listener.accept();
                synchronized (this) {
                    if (generation != current || !running) { incoming.close(); return; }
                    if (SystemClock.elapsedRealtime() >= endsAt) {
                        incoming.close();
                        expireSession(current);
                        return;
                    }
                    client = incoming;
                    accepted++;
                }
                try (Socket socket = incoming) {
                    socket.setSoTimeout(2000);
                    String request = HealthProtocol.readRequest(socket.getInputStream());
                    if (SystemClock.elapsedRealtime() >= endsAt) { expireSession(current); return; }
                    socket.getOutputStream().write(HealthProtocol.response(request));
                    socket.getOutputStream().flush();
                    synchronized (this) {
                        if (generation == current && HealthProtocol.isHealth(request)) health++;
                    }
                } catch (IOException ignored) {
                    synchronized (this) { if (generation == current && running) lastError = "CLIENT_IO_OR_HEADER_LIMIT"; }
                } finally { synchronized (this) { if (client == incoming) client = null; } }
            }
        } catch (IOException ignored) {
            main.post(() -> { if (generation == current && running) stopProbe("LISTENER_FAILED"); });
        }
    }

    private void expireSession(long current) {
        main.post(() -> { if (generation == current && running) stopProbe("TIME_LIMIT"); });
    }

    private synchronized void dispose() {
        generation++;
        running = false;
        endsAt = 0;
        GATE.cancel();
        main.removeCallbacks(expiry);
        try { if (client != null) client.close(); } catch (IOException ignored) { }
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        try { if (tun != null) tun.close(); } catch (IOException ignored) { }
        client = null; server = null; tun = null;
    }
    private void stopProbe(String reason) {
        dispose();
        lastError = reason;
        status = "Stopped / 已停止: " + reason;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    @Override public void onRevoke() { main.post(() -> stopProbe("SYSTEM_REVOKED")); }
    @Override public void onTaskRemoved(Intent rootIntent) { stopProbe("TASK_REMOVED"); }
    @Override public void onDestroy() {
        dispose();
        if (!status.startsWith("Stopped")) status = "Stopped / 已停止: SERVICE_DESTROYED";
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    private static final class ProbeFailure extends Exception {
        ProbeFailure(String code) { super(code); }
    }
}
