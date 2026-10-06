package com.shilapi.xcertplay.platform

import android.content.Context
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Optional platform outputs. Protocol, authentication and media never require a vendor service. */
interface CarPlayVendorIntegration {
    fun start(context: Context) {}
    fun setClusterStreamControl(control: (Boolean) -> Unit) {}
    fun clearClusterStreamControl(control: (Boolean) -> Unit) {}
    fun onFrame(frame: Iap2Frame) {}
    fun endNow(preserveTurnOverlay: Boolean = false) {}
}

/** No hardware reads, shell commands, broadcasts or platform outputs. */
object NoVendorIntegration : CarPlayVendorIntegration
