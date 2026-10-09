package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.*
import com.shilapi.xcertplay.browser.BrowserOutput
import java.net.Inet4Address
import java.net.NetworkInterface

/** Legacy LAN controls and a shortcut to the home-screen HTTPS setup. No pairing token is persisted. */
internal object BrowserOutputSettings {
    fun create(context: Context): View = Button(context).apply {
        text = "Browser output (experimental) / 浏览器输出"
        setOnClickListener { show(context) }
    }

    private fun show(context: Context) {
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 16, 28, 16)
        }
        fun label(value: String) = TextView(context).apply { text = value; body.addView(this) }
        body.addView(Button(context).apply {
            text = "Embedded HTTPS viewer / 内嵌 HTTPS 播放页"
            setOnClickListener {
                context.startActivity(android.content.Intent(context, BrowserHttpsActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        })
        if (BrowserOutput.secure || BrowserHttpsVpnService.running) {
            label("Embedded HTTPS session / 内嵌 HTTPS 会话\n${BrowserHttpsVpnService.status}\n${com.shilapi.xcertplay.browser.BrowserHttpsPolicy.VIEWER_URL}")
                .setTextIsSelectable(true)
            body.addView(Button(context).apply {
                text = "Connection diagnostics / 连接诊断"
                setOnClickListener { showDiagnostics(context) }
            })
            AlertDialog.Builder(context).setTitle("HTTPS browser output")
                .setView(ScrollView(context).apply { addView(body) })
                .setNegativeButton("Stop / 停止") { _, _ -> BrowserHttpsVpnService.stop(context) }
                .setPositiveButton("Close / 关闭", null).show()
            return
        }
        label("Video + two-finger touch only. Default OFF. Use only while parked, on a trusted private Wi-Fi network. The local video/control connection is unencrypted. No internet relay. Each browser connection needs approval on this Android screen. Viewing starts without touch ownership; the browser can enable touch separately after approval. Android settings remain available.\n仅限停车使用；请使用可信局域网。视频和控制不经互联网中转，但局域网连接未加密。")
        val current = BrowserOutput.endpoint
        if (current != null) {
            val endpoint = java.net.URI(current)
            label("Android IP / 安卓 IP: ${endpoint.host}\nPort / 端口: ${endpoint.port}").setTextIsSelectable(true)
            label("Open the HTTPS viewer at https://mark4z.github.io/tesla-browser-lab/browser-carplay/, enter this IP and port, confirm parked, and personally allow the browser's Local Network Access prompt. Keep this Android app in front and approve the connection request here. Closing these settings keeps the listener running; Stop revokes it.\n在 HTTPS 播放页输入 IP 和端口，手动允许浏览器访问局域网，然后在此安卓界面允许本次连接。每次重连都需重新确认。")
            BrowserOutput.healthEndpoint?.let { healthUrl ->
                label("Manual reachability check / 手动连通测试:\n$healthUrl").setTextIsSelectable(true)
                label("Open this address directly in the car browser. A service response proves only that this IP and port are reachable; it does not test HTTPS-to-WebSocket permission or approval. HTTP is unencrypted. No video, identity, pairing secret or history is returned. Do not bypass browser security warnings.\n请在车机浏览器地址栏直接打开。成功只代表 IP 和端口可达，不代表 WebSocket 权限或握手成功。")
                body.addView(Button(context).apply {
                    text = "Copy health URL / 复制测试地址"
                    setOnClickListener { copy(context, "Browser health URL", healthUrl) }
                })
                body.addView(Button(context).apply {
                    text = "Open health on Android / 安卓本机打开"
                    setOnClickListener {
                        try {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(healthUrl)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: android.content.ActivityNotFoundException) {
                            Toast.makeText(context, "No browser available. Copy the URL instead.", Toast.LENGTH_LONG).show()
                        }
                    }
                })
            }
            body.addView(Button(context).apply {
                text = "Connection diagnostics / 连接诊断"
                setOnClickListener { showDiagnostics(context) }
            })
            AlertDialog.Builder(context).setTitle("Browser output is ON")
                .setView(ScrollView(context).apply { addView(body) })
                .setNegativeButton("Stop / 停止") { _, _ -> BrowserOutput.stop() }
                .setPositiveButton("Close / 关闭", null).show()
            return
        }
        val addresses = try {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }.distinctBy { it.hostAddress }
        } catch (_: Exception) { emptyList() }
        if (addresses.isEmpty()) {
            label("No private IPv4 interface. Join the same trusted Wi-Fi network as the browser first.")
            AlertDialog.Builder(context).setView(body).setPositiveButton("Close", null).show(); return
        }
        label("Select the Android Wi-Fi IP reachable by the viewing browser:")
        val address = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, addresses.map { it.hostAddress })
            body.addView(this)
        }
        label("Allowed viewer origin / 允许的播放页来源: ${BrowserOutput.VIEWER_ORIGIN}\nEach connection must be approved here. No pairing code or remembered device.")
        val parked = CheckBox(context).apply {
            text = "I am parked and trust this network / 已停车并信任此网络"
            body.addView(this)
        }
        val error = label("")
        val dialog = AlertDialog.Builder(context).setTitle("Start browser output")
            .setView(ScrollView(context).apply { addView(body) })
            .setNegativeButton("Cancel", null).setPositiveButton("Start / 启动", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!parked.isChecked) { error.text = "Confirm parked first."; return@setOnClickListener }
                if (android.os.Build.VERSION.SDK_INT >= 37 && context.checkSelfPermission(
                        "android.permission.ACCESS_LOCAL_NETWORK") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    error.text = "Android requires local-network permission. Choose whether to allow it, then press Start again."
                    (context as? android.app.Activity)?.requestPermissions(
                        arrayOf("android.permission.ACCESS_LOCAL_NETWORK"), 9871)
                    return@setOnClickListener
                }
                try {
                    BrowserOutput.start(addresses[address.selectedItemPosition])
                    dialog.dismiss(); show(context)
                } catch (_: Exception) { error.text = "Could not start. Check that the selected private Wi-Fi IP is still available." }
            }
        }
        dialog.show()
    }

    private fun copy(context: Context, name: String, value: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText(name, value))
        Toast.makeText(context, "Copied / 已复制", Toast.LENGTH_SHORT).show()
    }

    /** Polls only a small in-memory snapshot, never a socket or disk on the UI thread. */
    private fun showDiagnostics(context: Context) {
        val text = TextView(context).apply {
            setPadding(28, 16, 28, 16)
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
        }
        var hostContext = context
        while (hostContext is android.content.ContextWrapper && hostContext !is android.app.Activity) {
            val base = hostContext.baseContext
            if (base === hostContext) break
            hostContext = base
        }
        val host = hostContext as? android.app.Activity
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val dialog = AlertDialog.Builder(context)
            .setTitle("Connection diagnostics / 连接诊断")
            .setView(ScrollView(context).apply { addView(text) })
            .setNeutralButton("Copy report / 复制报告", null)
            .setPositiveButton("Close / 关闭", null).create()
        val refresh = object : Runnable {
            override fun run() {
                if (host?.isDestroyed == true || host?.isFinishing == true) {
                    handler.removeCallbacks(this)
                    runCatching { dialog.dismiss() }
                    return
                }
                if (!dialog.isShowing) return
                text.text = BrowserOutput.connectionDiagnosticReport()
                handler.postDelayed(this, 1_000)
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                copy(context, "Browser connection diagnostics", BrowserOutput.connectionDiagnosticReport())
            }
            refresh.run()
        }
        dialog.setOnDismissListener { handler.removeCallbacks(refresh) }
        dialog.show()
    }

}

