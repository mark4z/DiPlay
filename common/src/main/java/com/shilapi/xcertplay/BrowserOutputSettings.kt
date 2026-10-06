package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
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
        label("Video + two-finger touch only. Default OFF. Use only while parked, on a trusted private Wi-Fi network. The local video/control connection is unencrypted. No internet relay. While a viewer is connected it owns CarPlay touch; Android settings remain available.\n仅限停车使用；请使用可信局域网。视频和控制不经互联网中转，但局域网连接未加密。")
        val current = BrowserOutput.endpoint
        if (current != null) {
            label("Local endpoint / 本地地址:\n$current\n\nPairing token / 临时配对码:\n${BrowserOutput.pairingToken}").setTextIsSelectable(true)
            label("Open your deployed HTTPS viewer, enter these values, confirm parked, and personally allow the browser's Local Network Access prompt. Closing this dialog keeps the session running; Stop revokes it.\n打开已部署的 HTTPS 播放页，输入地址和配对码，手动允许浏览器访问局域网。")
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
        label("Exact HTTPS viewer origin (for example https://your-name.github.io). Deploy site/browser-carplay first; no viewer has been published automatically.")
        val origin = EditText(context).apply {
            hint = "https://your-viewer.example"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            body.addView(this)
        }
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
                    BrowserOutput.start(addresses[address.selectedItemPosition], origin.text.toString().trim())
                    dialog.dismiss(); show(context)
                } catch (_: Exception) { error.text = "Could not start. Check the private IP and exact HTTPS origin; no path or query is allowed." }
            }
        }
        dialog.show()
    }
}
