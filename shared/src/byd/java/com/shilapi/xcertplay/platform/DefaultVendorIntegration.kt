package com.shilapi.xcertplay.platform

import android.content.Context
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Kept solely in the vendor source set; existing vehicle behavior is unchanged. */
internal object DefaultVendorIntegration : CarPlayVendorIntegration {
    fun create(): CarPlayVendorIntegration = this
    override fun start(context: Context) = BydNavigationOutputs.start(context)
    override fun setClusterStreamControl(control: (Boolean) -> Unit) =
        BydNavigationOutputs.setClusterStreamControl(control)
    override fun clearClusterStreamControl(control: (Boolean) -> Unit) =
        BydNavigationOutputs.clearClusterStreamControl(control)
    override fun onFrame(frame: Iap2Frame) = BydNavigationOutputs.onFrame(frame)
    override fun endNow(preserveTurnOverlay: Boolean) =
        BydNavigationOutputs.endNow(preserveTurnOverlay)
}
