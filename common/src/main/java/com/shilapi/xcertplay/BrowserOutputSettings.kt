package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.*
import com.shilapi.xcertplay.browser.BrowserOutput
import java.net.Inet4Address
import java.net.NetworkInterface

/** Deliberately session-only controls: no preference, startup service, or persisted token. */
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
        label("Video + two-finger touch only. Default OFF. Use only while parked, on a trusted private Wi-Fi network. The local video/control connection is unencrypted. No internet relay. Each browser connection needs approval on this Android screen. Viewing starts without touch ownership; the browser can enable touch separately after approval. Android settings remain available.\n仅限停车使用；请使用可信局域网。视频和控制不经互联网中转，但局域网连接未加密。")
        val current = BrowserOutput.endpoint
        if (current != null) {
            val endpoint = java.net.URI(current)
            label("Android IP / 安卓 IP: ${endpoint.host}\nPort / 端口: ${endpoint.port}").setTextIsSelectable(true)
            label("Open the HTTPS viewer at https://mark4z.github.io/tesla-browser-lab/browser-carplay/, enter this IP and port, confirm parked, and personally allow the browser's Local Network Access prompt. Keep this Android app in front and approve the connection request here. Closing these settings keeps the listener running; Stop revokes it.\n在 HTTPS 播放页输入 IP 和端口，手动允许浏览器访问局域网，然后在此安卓界面允许本次连接。每次重连都需重新确认。")
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
}
