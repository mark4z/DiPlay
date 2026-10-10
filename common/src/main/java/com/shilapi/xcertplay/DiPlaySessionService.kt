package com.shilapi.xcertplay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.host.R

/** Keeps an explicitly started connection alive when another car app is in the foreground. */
class DiPlaySessionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var backendSession = false
    private var cpu: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val maintainLocks = object : Runnable {
        override fun run() {
            if (!backendSession) { releaseLocks(); return }
            try {
                if (cpu?.isHeld != true) {
                    cpu = getSystemService(PowerManager::class.java).newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "DiPlay:relay").apply {
                        setReferenceCounted(false)
                        acquire(10 * 60_000L)
                    }
                } else cpu?.acquire(10 * 60_000L) // bounded timeout renewed only by the live service
                if (wifi?.isHeld != true) {
                    @Suppress("DEPRECATION")
                    val next = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DiPlay:relay")
                    next.setReferenceCounted(false)
                    next.acquire()
                    wifi = next
                }
            } catch (_: RuntimeException) { releaseLocks() }
            main.postDelayed(this, 5 * 60_000L)
        }
    }
    private fun releaseLocks() {
        main.removeCallbacks(maintainLocks)
        cpu?.let { if (it.isHeld) it.release() }; cpu = null
        wifi?.let { if (it.isHeld) it.release() }; wifi = null
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            backendSession = false
            releaseLocks()
            CarPlayBackgroundSession.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val session = CarPlayBackgroundSession.snapshot()
        if (session == null) { releaseLocks(); stopSelf(); return START_NOT_STICKY }
        val backend = session.sink.relayOnly
        backendSession = backend
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "CarPlay connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, CarPlayTaskNavigation.notificationIntent(this, backend), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DiPlaySessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle("DiPlay")
            .setContentText(if (backend) "Backend relay · screen-off supported / 后端转发 · 可熄屏" else "CarPlay connection running")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build()).build()
        try {
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (!backend && Build.VERSION.SDK_INT >= 30 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            // Without it Android stops location updates while another car app (the reversing camera,
            // the car's own map) covers CarPlay, and the iPhone gets no position until DiPlay is back.
            if (AirPlayPersistence.loadLocationReportingEnabled(this) &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(1, notification, types)
        } else startForeground(1, notification)
        } catch (_: RuntimeException) {
            backendSession = false
            releaseLocks()
            CarPlayBackgroundSession.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        main.removeCallbacks(maintainLocks)
        if (backend) maintainLocks.run() else releaseLocks()
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (backendSession) return
        // Preserve existing normal-mode cleanup; relay-only explicitly outlives its UI.
        com.shilapi.xcertplay.hud.BydNavigationOutputs.endNow()
        CarPlayBackgroundSession.stop()
        stopSelf()
    }
    override fun onDestroy() {
        backendSession = false
        releaseLocks()
        CarPlayBackgroundSession.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        const val ACTION_STOP = "com.shihab.diplay.DISCONNECT"
        private const val CHANNEL = "diplay_connection"
    }
}

