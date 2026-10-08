package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.widget.*
import com.shilapi.xcertplay.browser.*
import java.io.Closeable
import java.io.IOException

/** Shared by the home screen and the optional detail page; no secret enters an Intent or saved state. */
internal class BrowserHttpsControls(private val activity: Activity) {
    private val applicationContext get() = activity.applicationContext
    private val store = BrowserTlsStore(applicationContext)
    private val preferences = BrowserHttpsPreferences(applicationContext)
    private var settings = preferences.load()
    private var storageTicket: BrowserTlsStore.PendingImport? = null
    private var loading = false
    private var loaded = false
    private var loadedRevision = -1L
    private var waitingForReady = false
    private var loadGeneration = 0
    private var autoStartEligible = false
    private var updatingSwitches = false
    private var afterAutomaticStart: (() -> Unit)? = null
    val hasSystemInteraction: Boolean get() = selecting || systemPrompt
    private lateinit var serviceSwitch: Switch
    private lateinit var autoStart: Switch
    private lateinit var autoAllow: Switch
    private var disclosure: AlertDialog? = null
    private val main = Handler(Looper.getMainLooper())
    private var resumed = false
    private var destroyed = false
    private var selecting = false
    private var pending = false
    private var systemPrompt = false
    private var prepareAfterResume = false
    private var beginAfterResume = false
    private var identity: BrowserTlsIdentity? = null
    private var importTask: BrowserSession? = null
    private var candidate: BrowserTlsIdentity? = null
    private var importStatus = "NOT_IMPORTED / 尚未导入"
    private lateinit var state: TextView
    private lateinit var importButton: Button
    private lateinit var cancelButton: Button
    private lateinit var forgetButton: Button
    private lateinit var startButton: Button
    private lateinit var parked: CheckBox
    private val approvalUi by lazy { BrowserApprovalUi(activity) }
    private var approvalRegistered = false
    private val importTimeout = Runnable { cancelImport("IMPORT_TIMEOUT") }
    private val grantTimeout = Runnable {
        if (!BrowserHttpsVpnService.running) {
            BrowserHttpsVpnService.cancelStart()
            pending = false
            waitingForReady = false
            finishAutomaticStart()
            importStatus = "START_NOT_ACCEPTED / 启动未成功，可重试"
            refreshControls()
        }
    }
    private val refresh = object : Runnable {
        override fun run() {
            if (!resumed || destroyed) return
            if (waitingForReady && (BrowserHttpsVpnService.ready ||
                    (!BrowserHttpsVpnService.running && !BrowserHttpsVpnService.hasPendingStart()))) {
                waitingForReady = false; pending = false
                main.removeCallbacks(grantTimeout)
                finishAutomaticStart()
            }
            val mayApprove = BrowserHttpsVpnService.ready
            if (mayApprove && !approvalRegistered) { approvalUi.resume(); approvalRegistered = true }
            if (!mayApprove && approvalRegistered) { approvalUi.pause(); approvalRegistered = false }
            refreshControls()
            maybeAutoStart()
            if (loaded && !loading && !pending && (!BrowserHttpsVpnService.running || BrowserHttpsVpnService.ready)) finishAutomaticStart()
            main.postDelayed(this, 500)
        }
    }

    fun createView(): View {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            background = GradientDrawable().apply { setColor(Color.rgb(24, 32, 43)); cornerRadius = 16f }
        }
        fun label(text: String) = TextView(activity).also { it.text = text; it.setTextColor(Color.WHITE); column.addView(it) }
        fun button(text: String, action: () -> Unit) = Button(activity).also {
            it.text = text; it.setTextColor(Color.WHITE)
            it.backgroundTintList = ColorStateList.valueOf(Color.rgb(45, 60, 77))
            it.setOnClickListener { action() }; column.addView(it)
        }
        label("HTTPS browser / HTTPS 浏览器")
        label("${BrowserHttpsPolicy.VIEWER_URL}\n在连接 CarPlay 前完成本机证书导入。仅限停车和可信局域网。 / Configure before connecting CarPlay; parked, trusted LAN only.")
        importButton = button("Import local certificate ZIP / 本机导入证书 ZIP", ::chooseZip)
        cancelButton = button("Cancel import / 取消导入") { cancelImport("IMPORT_CANCELLED") }
        parked = CheckBox(activity).apply {
            text = "I am parked and trust this network / 已停车并信任此网络"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, _ -> refreshControls() }
            column.addView(this)
        }
        serviceSwitch = Switch(activity).apply {
            text = "HTTPS service / HTTPS 服务"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, enabled ->
                if (!updatingSwitches) { if (enabled) prepareStart() else stop(); refreshControls() }
            }
            column.addView(this)
        }
        // Keep the explicit button as an accessible retry path after a denied system prompt.
        startButton = button("Start HTTPS viewer / 启动 HTTPS 播放页", ::prepareStart)
        autoStart = Switch(activity).apply {
            text = "Start HTTPS when I open DiPlay / 打开 DiPlay 时自动启动 HTTPS"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, enabled ->
                if (!updatingSwitches) changeOption(startOnOpen = true, enabled = enabled)
            }
            column.addView(this)
        }
        autoAllow = Switch(activity).apply {
            text = "Automatically allow browser connections / 自动允许浏览器连接"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, enabled ->
                if (!updatingSwitches) changeOption(startOnOpen = false, enabled = enabled)
            }
            column.addView(this)
        }
        state = label("").apply { setTextIsSelectable(true); textSize = 13f }
        forgetButton = button("Delete saved certificate and settings / 删除已保存证书和设置", ::confirmDelete)
        button("Copy HTTPS viewer URL / 复制 HTTPS 播放页地址") {
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("DiPlay HTTPS viewer", BrowserHttpsPolicy.VIEWER_URL))
            Toast.makeText(activity, "Copied / 已复制", Toast.LENGTH_SHORT).show()
        }
        button("Connection diagnostics / 连接诊断") {
            AlertDialog.Builder(activity).setTitle("HTTPS diagnostics / HTTPS 诊断")
                .setMessage("${BrowserHttpsVpnService.status}\n${BrowserHttpsVpnService.selfCheck}\n${BrowserHttpsVpnService.interfaceReport}\n\n${BrowserOutput.connectionDiagnosticReport()}")
                .setPositiveButton("Close / 关闭", null).show()
        }
        button("HTTPS details / HTTPS 说明") {
            AlertDialog.Builder(activity).setTitle("HTTPS / TLS")
                .setMessage("Your manually selected ZIP is validated, then encrypted with Android Keystore in app-private storage excluded from backup. No upload, export or saved file permission. Deleting removes the saved identity and both automatic options. Stop keeps the certificate.\n\n" +
                    "手动选择的 ZIP 经校验后使用 Android Keystore 加密保存在应用私有目录，不参与备份、不上传、不导出、不保留文件权限。删除会清除证书和两个自动开关；停止服务保留证书。\n\n" +
                    "Android VPN consent is still required. Only this app and 100.99.9.9/32 + 192.168.247.2/32 are routed; no default route, DNS rewrite or forwarding. Stop other VPNs first; do not use always-on VPN. Normal DNS must resolve tesla.mark4z.asia to 100.99.9.9. Never bypass certificate warnings.\n\n" +
                    "仍需安卓 VPN 授权。先停止其他 VPN，勿开启始终开启 VPN。车机需正常解析域名到 100.99.9.9，请勿绕过证书警告。服务仅在 DiPlay 前台运行，首页与 CarPlay 间切换不中断。")
                .setPositiveButton("OK / 知道了", null).show()
        }
        refreshControls()
        loadSaved()
        return column
    }

    private fun loadSaved() {
        if (loaded || loading || destroyed) return
        loading = true
        refreshControls()
        val generation = ++loadGeneration
        val revision = store.revision()
        BrowserHttpsVpnService.worker("browser-tls-load") {
            var saved: BrowserTlsIdentity? = null
            var result = "NOT_IMPORTED / 尚未导入"
            try {
                saved = store.load()
                if (saved != null) result = "Saved / 已保存: ${saved.description()}"
            } catch (_: Exception) { result = "SAVED_CERTIFICATE_UNAVAILABLE; reimport or delete / 保存的证书不可用，请重新导入或删除" }
            main.post {
                if (!destroyed && generation == loadGeneration) {
                    loading = false
                    if (revision != store.revision()) { loaded = false; identity = null; loadSaved(); return@post }
                    loaded = true; loadedRevision = revision; identity = saved; importStatus = result
                    refreshControls(); maybeAutoStart(); if (!pending) finishAutomaticStart()
                }
            }
        }
    }

    fun afterInitialAutoStart(action: () -> Unit) {
        if (!settings.autoStartOnOpen || !autoStartEligible || (loaded && !pending && !BrowserHttpsVpnService.hasPendingStart() &&
                (!BrowserHttpsVpnService.running || BrowserHttpsVpnService.ready))) action()
        else afterAutomaticStart = action
    }

    private fun finishAutomaticStart() {
        if (!resumed || destroyed || loading || pending || BrowserHttpsVpnService.hasPendingStart() ||
            (BrowserHttpsVpnService.running && !BrowserHttpsVpnService.ready)) return
        val action = afterAutomaticStart
        afterAutomaticStart = null
        action?.invoke()
    }

    private fun changeOption(startOnOpen: Boolean, enabled: Boolean) {
        if (!enabled) {
            saveSettings(if (startOnOpen) settings.copy(autoStartOnOpen = false) else settings.copy(autoAllowConnections = false))
            return
        }
        refreshControls() // The switch stays OFF until this one-time disclosure is accepted.
        disclosure?.dismiss()
        disclosure = AlertDialog.Builder(activity)
            .setTitle(if (startOnOpen) "Start on app open? / 打开时自动启动？" else "Trust this LAN? / 信任此局域网？")
            .setMessage(if (startOnOpen)
                "Whenever you open DiPlay, start HTTPS with the saved certificate. Only use while parked on a trusted LAN. Android may still ask for VPN/local-network consent. No HTTPS startup at device boot; leaving DiPlay stops the service. Stop or a declined prompt will not retry until you open the app again.\n\n每次打开 DiPlay 使用保存的证书启动。仅停车和可信局域网使用；系统权限仍需你确认。不随系统开机启动，离开应用会停止；手动停止或拒绝权限后本次不再自动重试。"
            else "While you have enabled the HTTPS service and DiPlay is in front, browsers on this LAN can view CarPlay and request audio or touch without another Android approval. A network address is not verified device identity. Anyone on the same network who can reach this service may connect. Only one browser at a time. You will receive a connection notice. Audio playback and touch ownership still need their own browser controls. Use only while parked on a trusted private LAN.\n\nHTTPS 服务开启且 DiPlay 在前台时，同网段可访问服务的浏览器都可能连接，仅通知、不再逐次确认。网络地址不代表已验证设备。仅允许一个浏览器；音频播放和触控仍有独立控制。仅停车和可信私有局域网使用。")
            .setNegativeButton("Cancel / 取消", null)
            .setPositiveButton("Enable / 开启") { _, _ ->
                saveSettings(if (startOnOpen) settings.copy(autoStartOnOpen = true) else settings.copy(autoAllowConnections = true))
            }.create().also { it.setOnDismissListener { disclosure = null; refreshControls() }; it.show() }
    }

    private fun saveSettings(next: BrowserHttpsPreferences.Settings) {
        try { preferences.save(next); settings = next }
        catch (_: Exception) { Toast.makeText(activity, "Could not save HTTPS settings / 无法保存 HTTPS 设置", Toast.LENGTH_LONG).show() }
        refreshControls()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(activity).setTitle("Delete HTTPS setup? / 删除 HTTPS 配置？")
            .setMessage("Stops HTTPS and deletes the saved certificate, private key and both automatic settings. Keep your original ZIP if you want to import again.\n停止 HTTPS，删除保存的证书、私钥和两个自动选项。如需再次导入，请保留原始 ZIP。")
            .setNegativeButton("Cancel / 取消", null)
            .setPositiveButton("Delete / 删除") { _, _ -> deleteSaved() }.show()
    }

    internal fun deleteSaved() {
        stop(); cancelImport("IMPORT_CANCELLED")
        ++loadGeneration; loading = true; identity = null; loaded = true
        BrowserHttpsVpnService.worker("browser-tls-delete") {
            var ok = true
            try { preferences.reset() } catch (_: Exception) { ok = false }
            try { store.delete() } catch (_: Exception) { ok = false }
            main.post {
                if (!destroyed) {
                    loading = false; loadedRevision = store.revision(); settings = preferences.load()
                    importStatus = if (ok) "NOT_IMPORTED / 尚未导入" else "DELETE_FAILED; retry deletion / 删除未完成，请重试"
                    refreshControls()
                }
            }
        }
        refreshControls()
    }

    private fun stop() {
        BrowserHttpsForeground.suppressAutoStart()
        cancelPending()
        BrowserHttpsVpnService.stop(activity)
        refreshControls()
    }

    private fun maybeAutoStart() {
        if (!resumed || !loaded || loading || !autoStartEligible || !settings.autoStartOnOpen || identity == null ||
            pending || BrowserHttpsVpnService.running || !BrowserHttpsForeground.claimAutoStart()) return
        parked.isChecked = true // The explicit saved opt-in contains the parked/trusted-LAN disclosure.
        prepareStart()
    }

    private fun refreshControls() {
        if (!::state.isInitialized || !::startButton.isInitialized) return
        val idle = !loading && !pending && !selecting && importTask == null && !BrowserHttpsVpnService.running && !BrowserHttpsVpnService.hasPendingStart()
        importButton.isEnabled = idle && !BrowserHttpsVpnService.recoveryRequired
        forgetButton.isEnabled = !loading && importTask == null && !selecting
        cancelButton.isEnabled = selecting || importTask != null
        parked.isEnabled = idle
        startButton.isEnabled = idle && identity != null && parked.isChecked && !BrowserHttpsVpnService.recoveryRequired && BrowserOutput.endpoint == null
        updatingSwitches = true
        serviceSwitch.isChecked = BrowserHttpsVpnService.running || pending
        serviceSwitch.isEnabled = BrowserHttpsVpnService.running || (idle && identity != null && parked.isChecked && !BrowserHttpsVpnService.recoveryRequired && BrowserOutput.endpoint == null)
        autoStart.isChecked = settings.autoStartOnOpen
        autoAllow.isChecked = settings.autoAllowConnections
        updatingSwitches = false
        state.text = "${BrowserHttpsVpnService.status}\n\nCertificate / 证书: $importStatus\n" +
            "Self-check / 自检: ${BrowserHttpsVpnService.selfCheck}" +
            if (BrowserOutput.endpoint != null && !BrowserHttpsVpnService.running) "\nStop the existing browser output first. / 请先停止现有浏览器输出。" else ""
    }

    private fun chooseZip() {
        if (!resumed || loading || pending || selecting || importTask != null || BrowserHttpsVpnService.running) return
        selecting = true
        importStatus = "Choose one local ZIP / 请选择本机 ZIP"
        try {
            activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), PICK_ZIP)
        } catch (_: RuntimeException) { cancelImport("FILE_PICKER_UNAVAILABLE") }
        refreshControls()
    }

    private fun beginImport(uri: Uri) {
        if (destroyed || importTask != null || uri.scheme != "content") { cancelImport("IMPORT_CONTENT_URI_REQUIRED"); return }
        selecting = false
        candidate = null
        val ticket = try { store.beginImport() } catch (_: Exception) { cancelImport("TLS_STORE_UNAVAILABLE"); return }
        storageTicket = ticket
        importStatus = "Validating locally / 正在本机校验"
        lateinit var task: BrowserSession
        task = BrowserSession(
            { action -> BrowserHttpsVpnService.worker("browser-import-close") { action.run() } },
            { main.post { finishImport(task) } })
        importTask = task
        main.postDelayed(importTimeout, 15_000)
        val resolver = applicationContext.contentResolver
        BrowserHttpsVpnService.worker("browser-import-zip") {
            var zip: ByteArray? = null
            try {
                val signal = CancellationSignal()
                val cancelOpen = Closeable { signal.cancel() }
                if (!task.own(cancelOpen)) return@worker
                var descriptor: ParcelFileDescriptor? = null
                try {
                    if (task.isCancelled) return@worker
                    descriptor = resolver.openFileDescriptor(uri, "r", signal) ?: throw IOException("IMPORT_READ_FAILED")
                    // AutoCloseInputStream takes this exact descriptor. Late provider returns are
                    // registered by read(), then closed even when cancellation already happened.
                    val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                    descriptor = null
                    zip = BrowserImportIO.read(stream, BrowserTlsBundle.MAX_ARCHIVE_BYTES, task)
                } finally {
                    descriptor?.let { if (task.own(it)) task.closeAsync(it) }
                    task.closeAsync(cancelOpen)
                }
                if (task.isCancelled) return@worker
                val imported = ticket.prepare(zip ?: throw IOException("IMPORT_READ_FAILED"))
                main.post {
                    if (importTask === task && !task.isCancelled && !destroyed) {
                        candidate = imported
                        importStatus = "IMPORT_VALIDATED"
                    }
                }
            } catch (failure: BrowserTlsBundle.Failure) {
                postImportFailure(task, failure.code)
            } catch (_: Exception) {
                postImportFailure(task, "ZIP_READ_FAILED_OR_TOO_LARGE")
            } finally {
                zip?.fill(0)
                main.post {
                    if (importTask === task) task.cancel()
                    task.workerFinished()
                }
            }
        }
        refreshControls()
    }

    private fun postImportFailure(task: BrowserSession, code: String) {
        main.post { if (importTask === task && !task.isCancelled && !destroyed) importStatus = code }
    }
    private fun finishImport(task: BrowserSession) {
        if (importTask !== task) return
        main.removeCallbacks(importTimeout)
        importTask = null
        val ticket = storageTicket
        storageTicket = null
        if (!destroyed && !task.didCloseFail() && candidate != null && ticket != null) {
            try {
                if (ticket.commit()) {
                    identity = candidate
                    loaded = true; loadedRevision = store.revision()
                    importStatus = "Saved / 已保存: ${identity!!.description()}"
                } else importStatus = "IMPORT_CANCELLED; previous certificate retained / 保留原证书"
            } catch (_: Exception) { importStatus = "SAVE_FAILED; previous certificate retained / 保存失败，保留原证书" }
        } else if (task.didCloseFail()) importStatus = "IMPORT_CLOSE_FAILED"
        runCatching { ticket?.close() }
        candidate = null
        if (!destroyed) refreshControls()
    }
    private fun cancelImport(code: String) {
        selecting = false
        candidate = null
        runCatching { storageTicket?.close() }; storageTicket = null
        importTask?.cancel()
        main.removeCallbacks(importTimeout)
        importStatus = code + if (identity != null) "; previous certificate retained / 保留原证书" else ""
        refreshControls()
    }

    private fun prepareStart() {
        if (loadedRevision != store.revision()) { identity = null; loaded = false; loadSaved(); refreshControls(); return }
        if (loading || BrowserHttpsVpnService.recoveryRequired || !resumed || pending || selecting || importTask != null || identity == null || !parked.isChecked || BrowserHttpsVpnService.running || BrowserOutput.endpoint != null) return
        BrowserHttpsForeground.suppressAutoStart()
        pending = true
        refreshControls()
        if (Build.VERSION.SDK_INT >= 37 && activity.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
            systemPrompt = true
            activity.requestPermissions(arrayOf("android.permission.ACCESS_LOCAL_NETWORK"), LOCAL_NETWORK)
        } else prepareVpn()
    }
    private fun prepareVpn() {
        if (!pending || identity == null || destroyed) return
        try {
            val connectivity = activity.getSystemService(ConnectivityManager::class.java)
            if (connectivity.allNetworks.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }) {
                startFailure("EXISTING_VPN_STOP_IT_FIRST"); return
            }
            val consent = VpnService.prepare(activity)
            if (consent != null) {
                systemPrompt = true
                activity.startActivityForResult(consent, VPN_CONSENT)
            } else if (resumed) beginSession() else beginAfterResume = true
        } catch (_: RuntimeException) { startFailure("VPN_CONSENT_UNAVAILABLE") }
    }
    private fun beginSession() {
        beginAfterResume = false
        if (!pending || !resumed || destroyed) return
        if (loadedRevision != store.revision()) {
            cancelPending(); identity = null; loaded = false; loadSaved(); return
        }
        val imported = identity ?: run { startFailure("IMPORT_REQUIRED"); return }
        try {
            // One-use process memory transfer. The Intent carries no URI, archive or credentials.
            val token = BrowserHttpsVpnService.armStart(imported, loadedRevision)
            waitingForReady = true
            importStatus = "Saved certificate in use / 正在使用已保存证书"
            activity.startForegroundService(Intent(activity, BrowserHttpsVpnService::class.java)
                .setAction(BrowserHttpsVpnService.START).putExtra("grant", token))
            main.postDelayed(grantTimeout, BrowserHttpsPolicy.STARTUP_TIMEOUT_MS)
        } catch (_: RuntimeException) { startFailure("START_REQUEST_FAILED / 启动失败，可重试") }
        refreshControls()
    }
    private fun startFailure(code: String) { cancelPending(); importStatus = code; refreshControls(); finishAutomaticStart() }
    private fun cancelPending() {
        pending = false; waitingForReady = false; systemPrompt = false; prepareAfterResume = false; beginAfterResume = false
        BrowserHttpsVpnService.cancelStart()
        main.removeCallbacks(grantTimeout)
    }

    @Deprecated("Platform Activity result API is used deliberately without retaining credentials")
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == PICK_ZIP) {
            if (!selecting || destroyed) return
            if (resultCode == Activity.RESULT_OK && data?.data != null) beginImport(data.data!!)
            else cancelImport("IMPORT_CANCELLED")
        } else if (requestCode == VPN_CONSENT && pending && systemPrompt) {
            systemPrompt = false
            if (resultCode == Activity.RESULT_OK) { if (resumed) beginSession() else beginAfterResume = true }
            else startFailure("VPN_CONSENT_CANCELLED")
        }
    }
    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != LOCAL_NETWORK || !pending || !systemPrompt) return
        systemPrompt = false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (resumed) prepareVpn() else prepareAfterResume = true
        } else startFailure("LOCAL_NETWORK_PERMISSION_DENIED")
    }
    fun onResume(allowAutoStart: Boolean = false) {
        resumed = true
        autoStartEligible = allowAutoStart
        settings = preferences.load()
        if (loaded && loadedRevision != store.revision()) { identity = null; loaded = false; loadSaved() }
        maybeAutoStart()
        if (prepareAfterResume) { prepareAfterResume = false; prepareVpn() }
        if (beginAfterResume) beginSession()
        if (loaded && !pending) finishAutomaticStart()
        main.removeCallbacks(refresh)
        refresh.run()
    }
    fun onPause() {
        resumed = false
        main.removeCallbacks(refresh)
        approvalUi.pause(); approvalRegistered = false

    }
    fun onStop() {
        // Picker and consent may temporarily cover this Activity; no pending operation survives recreation.
        if (!selecting && !systemPrompt) {
            if (importTask != null) cancelImport("IMPORT_CANCELLED")
            cancelPending()
        }

    }
    fun onDestroy() {
        destroyed = true
        afterAutomaticStart = null
        cancelPending()
        cancelImport("IMPORT_CANCELLED")
        identity = null
        candidate = null
        main.removeCallbacks(refresh)
        approvalUi.pause(); approvalRegistered = false
        disclosure?.dismiss(); disclosure = null
    }
    companion object { const val PICK_ZIP = 9911; const val VPN_CONSENT = 9912; const val LOCAL_NETWORK = 9913 }
}
