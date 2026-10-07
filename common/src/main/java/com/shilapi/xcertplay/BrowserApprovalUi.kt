package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.browser.BrowserApprovalRequest
import com.shilapi.xcertplay.browser.BrowserOutput

/** A connection can be approved only by a foreground Android host, never by the viewer. */
internal class BrowserApprovalUi(private val activity: Activity) {
    private val main = Handler(Looper.getMainLooper())
    private var resumed = false
    private var request: BrowserApprovalRequest? = null
    private var dialog: AlertDialog? = null

    fun resume() {
        resumed = true
        BrowserOutput.registerApprovalUi(this,
            onRequest = { pending -> main.post { show(pending) } },
            onFinished = { id -> main.post { if (request?.id == id) dismiss() } })
    }

    fun pause() {
        resumed = false
        BrowserOutput.unregisterApprovalUi(this)
        dismiss()
    }

    private fun show(pending: BrowserApprovalRequest) {
        if (!resumed || activity.isFinishing || activity.isDestroyed || !BrowserOutput.isApprovalPending(pending)) {
            pending.markPromptUnavailable()
            pending.reject()
            return
        }
        dismiss()
        request = pending
        var approved = false
        val transportNotice = if (BrowserOutput.secure)
            "The local page/video/control link uses HTTPS/WSS; WebRTC audio uses DTLS-SRTP. "
        else "The video/control link is unencrypted; WebRTC audio uses DTLS-SRTP. "
        val transportNoticeZh = if (BrowserOutput.secure) "本地页面、视频和控制使用 HTTPS/WSS，音频使用 WebRTC 加密。"
            else "局域网视频和控制未加密，音频使用 WebRTC 加密。"
        val alert = AlertDialog.Builder(activity)
            .setTitle("Allow browser connection? / 允许浏览器连接？")
            .setMessage("Browser network address / 浏览器网络地址: ${pending.remoteAddress}\n\n" +
                "Allow this browser to view CarPlay and optionally play audio or enable touch control for this connection? " +
                "This is an observed network address, not verified device identity. " +
                "Use only while parked on a trusted private network. " + transportNotice +
                "Every reconnect needs your approval; no device is remembered.\n\n" +
                "允许此浏览器查看 CarPlay，并可选择播放音频或启用触控？仅限本次连接，每次重连都需确认。" +
                "请停车后使用。" + transportNoticeZh)
            .setNegativeButton("Reject / 拒绝") { _, _ -> pending.reject() }
            .setPositiveButton("Allow / 允许") { _, _ ->
                if (resumed && !activity.isFinishing && !activity.isDestroyed && BrowserOutput.isApprovalPending(pending)) {
                    approved = pending.approve()
                } else pending.reject()
            }
            .setOnCancelListener { pending.reject() }
            .create()
        dialog = alert
        alert.setCanceledOnTouchOutside(true)
        alert.setOnDismissListener {
            if (!approved) pending.reject()
            if (request === pending) { request = null; dialog = null }
        }
        try {
            alert.show()
            pending.markPromptShown()
        } catch (_: RuntimeException) {
            pending.markPromptUnavailable()
            dismiss()
        }
    }

    private fun dismiss() {
        request?.reject()
        request = null
        val old = dialog
        dialog = null
        old?.dismiss()
    }
}

