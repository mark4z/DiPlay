package com.shilapi.xcertplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.shilapi.xcertplay.browser.*
import java.io.Closeable
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket

/** Explicit user-started HTTPS session. The OS must never restore this session or its identity. */
class BrowserHttpsVpnService : VpnService() {
    companion object {
        internal const val START = "com.shilapi.xcertplay.browser.HTTPS_START"
        internal const val STOP = "com.shilapi.xcertplay.browser.HTTPS_STOP"
        private const val BACKGROUND_STOP = "com.shilapi.xcertplay.browser.HTTPS_BACKGROUND_STOP"
        private const val CHANNEL = "browser-https"
        private const val NOTIFICATION = 9999
        private data class SavedIdentity(val identity: BrowserTlsIdentity, val revision: Long)
        private val grant = BrowserSessionGrant<SavedIdentity>()
        @Volatile var running = false; private set
        @Volatile var ready = false; private set
        @Volatile var recoveryRequired = false; private set
        @Volatile var status = "Stopped / 已停止"; private set
        @Volatile var selfCheck = "NOT_RUN"; private set
        @Volatile var interfaceReport = "NOT_RUN"; private set
        @Volatile var startupFailure = "NONE"; private set
        /** The same bounded, credential-free state used by the screen and existing export. */
        internal fun diagnosticReport(): String =
            "$status\nStartup failure / 启动错误: $startupFailure\nSelf-check / 自检: $selfCheck\n$interfaceReport"
        internal fun armStart(identity: BrowserTlsIdentity, revision: Long): Long = grant.arm(SavedIdentity(identity, revision))
        internal fun cancelStart() = grant.cancel()
        internal fun hasPendingStart(): Boolean = grant.hasPending()
        fun stop(context: Context, userStop: Boolean = true) {
            cancelStart()
            if (userStop) BrowserHttpsForeground.suppressAutoStart()
            if (running) context.startService(Intent(context, BrowserHttpsVpnService::class.java)
                .setAction(if (userStop) STOP else BACKGROUND_STOP))
        }
        internal fun notifyAutomaticConnection(context: Context) {
            if (!ready || !running || !BrowserHttpsForeground.visible) return
            val stop = PendingIntent.getService(context, 9999,
                Intent(context, BrowserHttpsVpnService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(context, 9999,
                Intent(context, BrowserHttpsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val notice = Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Browser connection allowed / 已允许浏览器连接")
                .setContentText("Automatically allowed by your HTTPS setting / 按你的 HTTPS 设置自动允许")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(false)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop / 停止", stop).build()
            // POST_NOTIFICATIONS denial must not crash or fabricate an approval prompt.
            try { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notice) }
            catch (_: SecurityException) { }
        }
        internal fun worker(name: String, action: () -> Unit) {
            Thread(action, name).apply { isDaemon = true; start() }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var session: BrowserSession? = null
    private var destroyed = false
    private var stopReason = "USER_STOP"
    private val startupTimeout = Runnable { stopSession("STARTUP_TIMEOUT") }
    private val slowStop = Runnable {
        if (session?.isCancelled == true) status = "Cleanup is still pending. Disconnect VPN in Android Settings; force-stop this app if needed. / 清理尚未完成，请断开 VPN，必要时强行停止应用。"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == BACKGROUND_STOP) {
            if (!BrowserHttpsForeground.visible) stopSession("APP_BACKGROUNDED")
            return START_NOT_STICKY
        }
        if (intent?.action == STOP) { BrowserHttpsForeground.suppressAutoStart(); stopSession("USER_STOP"); return START_NOT_STICKY }
        val granted = if (intent?.action == START) grant.consume(intent.getLongExtra("grant", 0)) else null
        val identity = granted?.identity
        if (granted == null || identity == null || granted.revision != BrowserTlsStore(this).revision() || running || recoveryRequired || !BrowserHttpsForeground.visible || BrowserOutput.endpoint != null) {
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        // A fresh accepted attempt must not display the previous attempt's failure.
        selfCheck = "NOT_RUN"
        interfaceReport = "NOT_RUN"
        startupFailure = "NONE"
        // A systemExempted foreground service is eligible only as this user-configured VPN.
        // Do not request unrelated exact-alarm permissions to satisfy the alternate eligibility.
        try {
            if (prepare(this) != null) {
                startupFailure = "PREFLIGHT: VPN_CONSENT_REQUIRED"
                status = "Stopped / 已停止: VPN_CONSENT_REQUIRED"
                stopSelf()
                return START_NOT_STICKY
            }
        } catch (failure: RuntimeException) {
            startupFailure = "PREFLIGHT: VPN_CONSENT_UNAVAILABLE; causes=${BrowserHttpsStartup.causeTypes(failure)}"
            status = "Stopped / 已停止: VPN_CONSENT_UNAVAILABLE"
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        ready = false
        status = "Starting HTTPS / 正在启动 HTTPS"
        selfCheck = "NOT_RUN"
        interfaceReport = "PENDING"
        startupFailure = "NONE"
        val interfaces = BrowserInterfaces(BrowserHttpsPolicy.DUAL)
        lateinit var current: BrowserSession
        current = BrowserSession(
            { action -> worker("browser-https-cleanup") { action.run() } },
            { worker("browser-https-final-check") {
                val afterClose = interfaces.snapshot()
                main.post { finishStopped(current, afterClose) }
            } })
        session = current
        main.postDelayed(startupTimeout, BrowserHttpsPolicy.STARTUP_TIMEOUT_MS)
        try {
            enterForeground()
            worker("browser-https-start") { startSession(current, interfaces, identity, granted.revision) }
        } catch (failure: RuntimeException) {
            startupFailure = "FOREGROUND_START_FAILED; causes=${BrowserHttpsStartup.causeTypes(failure)}"
            stopSession("FOREGROUND_START_FAILED")
            current.workerFinished()
        }
        return START_NOT_STICKY
    }

    private fun enterForeground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Embedded HTTPS viewer", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 9999,
            Intent(this, BrowserHttpsVpnService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 9999,
            Intent(this, BrowserHttpsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("DiPlay embedded HTTPS viewer")
            .setContentText("Foreground only · Stop closes both local VPN interfaces")
            .setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop / 停止", stop).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        else startForeground(NOTIFICATION, notification)
    }

    private fun startSession(current: BrowserSession, interfaces: BrowserInterfaces, identity: BrowserTlsIdentity, revision: Long) {
        var startupStage = BrowserHttpsStartup.Stage.PREFLIGHT
        try {
            if (current.isCancelled) return
            if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED)
                throw StartFailure("LOCAL_NETWORK_PERMISSION_DENIED")
            if (prepare(this) != null) throw StartFailure("VPN_CONSENT_REQUIRED")
            if (!BrowserHttpsForeground.visible) throw StartFailure("APP_NOT_VISIBLE")
            if (revision != BrowserTlsStore(this).revision()) throw StartFailure("SAVED_IDENTITY_CHANGED")
            startupStage = BrowserHttpsStartup.Stage.TLS_VALIDATION
            identity.checkValidity(BrowserHttpsPolicy.HOSTNAME)
            startupStage = BrowserHttpsStartup.Stage.CONFLICT_CHECK
            rejectConflicts()
            if (!BrowserHandover.establish(current, BrowserHttpsPolicy.DUAL, { address -> establishInterface(address) }, { stage ->
                startupStage = if (stage.startsWith("PRIMARY") || stage == "AFTER_PRIMARY")
                    BrowserHttpsStartup.Stage.VPN_PRIMARY else BrowserHttpsStartup.Stage.VPN_COMPATIBILITY
                val snapshot = if (stage.startsWith("AFTER_")) interfaces.snapshot().text else null
                main.post {
                    if (session === current && !current.isCancelled) {
                        status = "Starting HTTPS / 正在启动: $stage"
                        if (snapshot != null) interfaceReport = (if (interfaceReport == "PENDING") "" else "$interfaceReport\n") + "$stage\n$snapshot"
                    }
                }
            })) return
            if (current.isCancelled) return
            BrowserOutput.startSecure(applicationContext, identity, onStage = { startupStage = it }) {
                main.post { if (session === current && !current.isCancelled) stopSession("LISTENER_STOPPED") }
            }
            // Late start completion after Stop still acquires and closes this exact session's server.
            // No replacement session may start until this worker AND all closes finish.
            if (!current.own(Closeable { BrowserOutput.stop() })) return
            if (current.isCancelled) return
            startupStage = BrowserHttpsStartup.Stage.SELF_CHECK
            main.post { if (session === current && !current.isCancelled) selfCheck = "PENDING" }
            val socket = Socket()
            if (!current.own(socket)) return
            val check = BrowserHttpsSelfCheck(BrowserOutput.healthResponseForSelfCheck())
            val timeout = Runnable { current.closeAsync(socket) }
            main.postDelayed(timeout, BrowserHttpsSelfCheck.TOTAL_TIMEOUT_MS.toLong())
            val result = try {
                check.run(socket, { current.isCancelled }, { current.own(it) }, { current.closeAsync(it) })
            } finally {
                main.removeCallbacks(timeout)
                current.closeOwned(socket)
            }
            if (current.isCancelled) return
            if (result != "PASS") throw StartFailure("SELF_CHECK_$result")
            main.post {
                if (session === current && !current.isCancelled) {
                    if (!BrowserOutput.markSecureReady(identity)) { stopSession("LISTENER_STOPPED"); return@post }
                    main.removeCallbacks(startupTimeout)
                    selfCheck = "PASS (system trust + SNI + HTTPS hostname verification)"
                    ready = true
                    status = "HTTPS ready / HTTPS 已就绪\n${BrowserHttpsPolicy.VIEWER_URL}\nLocal self-check passed; Tesla reachability still needs a browser test. / 本机自检通过，仍需车机测试。"
                }
            }
        } catch (failure: BrowserTlsIdentity.Failure) {
            fail(current, failure.code, "${startupStage.name}: ${failure.code}")
        } catch (failure: BrowserHandover.Failure) {
            fail(current, failure.code)
        } catch (failure: StartFailure) {
            fail(current, failure.code, "${startupStage.name}: ${failure.code}")
        } catch (failure: Exception) {
            val code = BrowserHttpsStartup.failureCode(startupStage, failure)
            fail(current, code, "$code; causes=${BrowserHttpsStartup.causeTypes(failure)}")
        } finally { current.workerFinished() }
    }

    private fun establishInterface(address: String): ParcelFileDescriptor? = Builder()
        .setSession("DiPlay HTTPS local $address")
        .addAddress(address, 32).addRoute(address, 32)
        .addAllowedApplication(packageName)
        .allowBypass().allowFamily(OsConstants.AF_INET6).establish()

    private fun rejectConflicts() {
        val candidates = BrowserHttpsPolicy.addresses(BrowserHttpsPolicy.DUAL).map { InetAddress.getByName(it).address }
        val connectivity = getSystemService(ConnectivityManager::class.java)
        for (network in connectivity.allNetworks) {
            if (connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
                throw StartFailure("EXISTING_VPN_STOP_IT_FIRST")
            connectivity.getLinkProperties(network)?.routes?.forEach { route ->
                if (candidates.any { BrowserHttpsPolicy.overlaps(it, route.destination.address.address, route.destination.prefixLength) })
                    throw StartFailure("ADDRESS_ROUTE_CONFLICT")
            }
        }
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
            iface.interfaceAddresses.forEach { address ->
                if (candidates.any { BrowserHttpsPolicy.overlaps(it, address.address.address, address.networkPrefixLength.toInt()) })
                    throw StartFailure("ADDRESS_INTERFACE_CONFLICT")
            }
        }
    }

    private fun fail(current: BrowserSession, code: String, detail: String = code) {
        main.post {
            if (session === current && !current.isCancelled) {
                startupFailure = detail
                stopSession(code)
            }
        }
    }

    private fun stopSession(reason: String) {
        cancelStart()
        main.removeCallbacks(startupTimeout)
        ready = false
        val current = session
        if (current == null) { if (!destroyed) stopSelf(); return }
        if (current.isCancelled) return
        stopReason = reason
        status = "Stopping HTTPS; closing all owned resources / 正在停止并关闭全部资源: $reason"
        if (selfCheck == "PENDING") selfCheck = if (reason.startsWith("SELF_CHECK_")) reason else "CANCELLED"
        current.cancel()
        main.postDelayed(slowStop, 2_000)
    }

    private fun finishStopped(current: BrowserSession, afterClose: BrowserInterfaces.Snapshot) {
        if (session !== current) return
        main.removeCallbacks(slowStop)
        session = null
        running = false
        ready = false
        recoveryRequired = current.didCloseFail() || afterClose.visible || afterClose.failed
        interfaceReport += "\nAFTER_ALL_OWNED_CLOSES\n${afterClose.text}"
        status = if (recoveryRequired) "Cleanup could not be confirmed. Disconnect VPN and force-stop this app before retrying. / 清理未确认，请断开 VPN 并强行停止本应用。\nStopped because / 停止原因: $stopReason"
            else "Stopped / 已停止: $stopReason"
        if (!destroyed) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    override fun onRevoke() { main.post { stopSession("SYSTEM_REVOKED") } }
    override fun onTaskRemoved(rootIntent: Intent?) { stopSession("TASK_REMOVED") }
    override fun onDestroy() {
        destroyed = true
        stopSession("SERVICE_DESTROYED")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    private class StartFailure(val code: String) : Exception(code)
}
