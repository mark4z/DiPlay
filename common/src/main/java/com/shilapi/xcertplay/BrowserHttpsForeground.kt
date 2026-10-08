package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.IdentityHashMap

/** A visible DiPlay task owns HTTPS. Short activity handoffs do not widen background access. */
internal object BrowserHttpsForeground {
    private val main = Handler(Looper.getMainLooper())
    private val owners = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    private var context: Context? = null
    private var autoStartClaimed = false
    private var preserveVisit = false
    @Volatile var visible = false; private set
    private val background = Runnable {
        if (owners.isEmpty()) {
            visible = false
            if (!preserveVisit) autoStartClaimed = false
            context?.let { BrowserHttpsVpnService.stop(it, userStop = false) }
        }
    }
    fun enter(activity: Activity) {
        main.removeCallbacks(background)
        owners.add(activity)
        context = activity.applicationContext
        visible = true
    }
    fun leave(activity: Activity, preserveAutoStart: Boolean = false) {
        preserveVisit = preserveAutoStart
        owners.remove(activity)
        // A new internal Activity normally starts before the old one's onStop. The bounded
        // grace also covers configuration recreation, without keeping a hidden service alive.
        if (owners.isEmpty()) main.postDelayed(background, 700)
    }
    fun claimAutoStart(): Boolean {
        if (!visible || autoStartClaimed) return false
        autoStartClaimed = true
        return true
    }
    fun suppressAutoStart() { autoStartClaimed = true }
}
