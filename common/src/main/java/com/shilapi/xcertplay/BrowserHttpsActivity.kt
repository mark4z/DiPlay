package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.widget.*
import com.shilapi.xcertplay.browser.*
import java.io.Closeable
import java.io.IOException

/** One explicit local import and consent flow. Secrets/URI grants never enter saved state or disk. */
class BrowserHttpsActivity : Activity() {
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
    private val approvalUi by lazy { BrowserApprovalUi(this) }
    private var approvalRegistered = false
    private val importTimeout = Runnable { cancelImport("IMPORT_TIMEOUT") }
    private val grantTimeout = Runnable {
        if (!BrowserHttpsVpnService.running) {
            BrowserHttpsVpnService.cancelStart()
            pending = false
            importStatus = "START_NOT_ACCEPTED; import again / 启动未成功，请重新导入"
            refreshControls()
        }
    }
    private val refresh = object : Runnable {
        override fun run() {
            if (!resumed || destroyed) return
            if (BrowserHttpsVpnService.running) pending = false
            val mayApprove = BrowserHttpsVpnService.ready
            if (mayApprove && !approvalRegistered) { approvalUi.resume(); approvalRegistered = true }
            if (!mayApprove && approvalRegistered) { approvalUi.pause(); approvalRegistered = false }
            refreshControls()
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 28)
        }
        fun label(text: String) = TextView(this).also { it.text = text; column.addView(it) }
        fun button(text: String, action: () -> Unit) = Button(this).also {
            it.text = text; it.setOnClickListener { action() }; column.addView(it)
        }
        label("Embedded HTTPS viewer / 内嵌 HTTPS 播放页\n\n${BrowserHttpsPolicy.VIEWER_URL}\n${BrowserHttpsPolicy.WEBSOCKET_URL}\n\n" +
            "Import your nginx certificate ZIP from this Android device. The app reads the certificate chain and matching unencrypted key in memory only. No upload, extraction, saved file permission, bundled key or automatic startup.\n" +
            "请在本机选择 nginx 证书 ZIP。证书链和私钥仅保留在本次内存会话，不上传、不解压写盘、不保存权限、不内置私钥。\n\n" +
            "Start requires Android VPN consent. First creates 100.99.9.9/32, then 192.168.247.2/32, retaining both descriptors until Stop. Only those /32 routes and this app are allowed; no default route, DNS rewrite or forwarding. The first interface may be DOWN after handover. Stop other VPNs first. Do not enable always-on VPN.\n" +
            "先建立主地址，再建立兼容地址；仅本应用和各自 /32 路由。第一个接口可能显示 DOWN。请先停止其他 VPN，勿开启始终开启 VPN。\n\n" +
            "The car browser must normally resolve tesla.mark4z.asia to 100.99.9.9. Never bypass certificate warnings. After Start and the trusted TLS self-check, return to CarPlay if needed and approve each browser connection on Android. Closing this page keeps a running session; Stop or the notification closes both interfaces and forgets the identity. Pending imports and consent are cancelled when this page is closed or recreated.\n" +
            "车机需正常解析域名到 100.99.9.9，请勿绕过证书警告。启动并自检通过后可返回 CarPlay，每次连接需在安卓端确认。已启动会话关闭本页仍运行；停止会关闭全部接口并清除证书。")
        importButton = button("Import local certificate ZIP / 本机导入证书 ZIP", ::chooseZip)
        cancelButton = button("Cancel import / 取消导入") { cancelImport("IMPORT_CANCELLED") }
        forgetButton = button("Forget imported identity / 清除已导入证书") {
            identity = null; candidate = null; importStatus = "NOT_IMPORTED / 尚未导入"; refreshControls()
        }
        parked = CheckBox(this).apply {
            text = "I am parked, trust this network and want this temporary dual-TUN session / 已停车并信任网络，同意本次临时双 TUN 会话"
            setOnCheckedChangeListener { _, _ -> refreshControls() }
            column.addView(this)
        }
        state = label("").apply { setTextIsSelectable(true) }
        startButton = button("Start HTTPS viewer / 启动 HTTPS 播放页", ::prepareStart)
        button("Stop and forget / 停止并清除") {
            cancelPending()
            cancelImport("IMPORT_CANCELLED")
            identity = null
            importStatus = "NOT_IMPORTED / 尚未导入"
            BrowserHttpsVpnService.stop(this)
            refreshControls()
        }
        button("Copy HTTPS viewer URL / 复制 HTTPS 播放页地址") {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("DiPlay HTTPS viewer", BrowserHttpsPolicy.VIEWER_URL))
            Toast.makeText(this, "Copied / 已复制", Toast.LENGTH_SHORT).show()
        }
        button("Back to CarPlay / 返回 CarPlay") { finish() }
        setContentView(ScrollView(this).apply { addView(column) })
        refreshControls()
    }

    private fun refreshControls() {
        if (!::state.isInitialized || !::startButton.isInitialized) return
        val idle = !pending && !selecting && importTask == null && !BrowserHttpsVpnService.running && !BrowserHttpsVpnService.hasPendingStart()
        importButton.isEnabled = idle && !BrowserHttpsVpnService.recoveryRequired
        forgetButton.isEnabled = idle && identity != null
        cancelButton.isEnabled = selecting || importTask != null
        parked.isEnabled = idle
        startButton.isEnabled = idle && identity != null && parked.isChecked && !BrowserHttpsVpnService.recoveryRequired && BrowserOutput.endpoint == null
        state.text = "${BrowserHttpsVpnService.status}\n\nCertificate / 证书: $importStatus\n" +
            "Self-check / 自检: ${BrowserHttpsVpnService.selfCheck}\n\n${BrowserHttpsVpnService.interfaceReport}" +
            if (BrowserOutput.endpoint != null && !BrowserHttpsVpnService.running) "\nStop the existing browser output first. / 请先停止现有浏览器输出。" else ""
    }

    private fun chooseZip() {
        if (!resumed || pending || selecting || importTask != null || BrowserHttpsVpnService.running) return
        selecting = true
        importStatus = "Choose one local ZIP / 请选择本机 ZIP"
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT)
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
                val imported = BrowserTlsBundle.read(zip, BrowserHttpsPolicy.HOSTNAME)
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
        if (!destroyed && !task.didCloseFail() && candidate != null) {
            identity = candidate
            importStatus = "Imported / 已导入: ${identity!!.description()}"
        } else if (task.didCloseFail()) importStatus = "IMPORT_CLOSE_FAILED"
        candidate = null
        if (!destroyed) refreshControls()
    }
    private fun cancelImport(code: String) {
        selecting = false
        candidate = null
        importTask?.cancel()
        main.removeCallbacks(importTimeout)
        importStatus = code + if (identity != null) "; previous certificate retained / 保留原证书" else ""
        refreshControls()
    }

    private fun prepareStart() {
        if (!resumed || pending || selecting || importTask != null || identity == null || !parked.isChecked || BrowserHttpsVpnService.running || BrowserOutput.endpoint != null) return
        pending = true
        refreshControls()
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
            systemPrompt = true
            requestPermissions(arrayOf("android.permission.ACCESS_LOCAL_NETWORK"), LOCAL_NETWORK)
        } else prepareVpn()
    }
    private fun prepareVpn() {
        if (!pending || identity == null || destroyed) return
        try {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            if (connectivity.allNetworks.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }) {
                startFailure("EXISTING_VPN_STOP_IT_FIRST"); return
            }
            val consent = VpnService.prepare(this)
            if (consent != null) {
                systemPrompt = true
                startActivityForResult(consent, VPN_CONSENT)
            } else if (resumed) beginSession() else beginAfterResume = true
        } catch (_: RuntimeException) { startFailure("VPN_CONSENT_UNAVAILABLE") }
    }
    private fun beginSession() {
        beginAfterResume = false
        if (!pending || !resumed || destroyed) return
        val imported = identity ?: run { startFailure("IMPORT_REQUIRED"); return }
        try {
            // One-use process memory transfer. The Intent carries no URI, archive or credentials.
            val token = BrowserHttpsVpnService.armStart(imported)
            identity = null
            importStatus = "Identity transferred to current session / 证书已交给本次会话"
            startForegroundService(Intent(this, BrowserHttpsVpnService::class.java)
                .setAction(BrowserHttpsVpnService.START).putExtra("grant", token))
            main.postDelayed(grantTimeout, BrowserHttpsPolicy.STARTUP_TIMEOUT_MS)
        } catch (_: RuntimeException) { startFailure("START_REQUEST_FAILED; import again / 请重新导入") }
        refreshControls()
    }
    private fun startFailure(code: String) { cancelPending(); importStatus = code; refreshControls() }
    private fun cancelPending() {
        pending = false; systemPrompt = false; prepareAfterResume = false; beginAfterResume = false
        BrowserHttpsVpnService.cancelStart()
        main.removeCallbacks(grantTimeout)
    }

    @Deprecated("Platform Activity result API is used deliberately without retaining credentials")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_ZIP) {
            if (!selecting || destroyed) return
            if (resultCode == RESULT_OK && data?.data != null) beginImport(data.data!!)
            else cancelImport("IMPORT_CANCELLED")
        } else if (requestCode == VPN_CONSENT && pending && systemPrompt) {
            systemPrompt = false
            if (resultCode == RESULT_OK) { if (resumed) beginSession() else beginAfterResume = true }
            else startFailure("VPN_CONSENT_CANCELLED")
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LOCAL_NETWORK || !pending || !systemPrompt) return
        systemPrompt = false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (resumed) prepareVpn() else prepareAfterResume = true
        } else startFailure("LOCAL_NETWORK_PERMISSION_DENIED")
    }
    override fun onResume() {
        super.onResume()
        resumed = true
        if (prepareAfterResume) { prepareAfterResume = false; prepareVpn() }
        if (beginAfterResume) beginSession()
        main.removeCallbacks(refresh)
        refresh.run()
    }
    override fun onPause() {
        resumed = false
        main.removeCallbacks(refresh)
        approvalUi.pause(); approvalRegistered = false
        super.onPause()
    }
    override fun onStop() {
        // Temporary system picker/consent transitions are allowed. Nothing survives recreation.
        if (!selecting && !systemPrompt) {
            cancelImport("IMPORT_CANCELLED")
            identity = null
            cancelPending()
        }
        super.onStop()
    }
    override fun onDestroy() {
        destroyed = true
        cancelPending()
        cancelImport("IMPORT_CANCELLED")
        identity = null
        candidate = null
        main.removeCallbacks(refresh)
        approvalUi.pause(); approvalRegistered = false
        super.onDestroy()
    }
    private companion object { const val PICK_ZIP = 9911; const val VPN_CONSENT = 9912; const val LOCAL_NETWORK = 9913 }
}
