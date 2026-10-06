package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/**
 * Compatibility boundary retaining phone navigation/song/call state without vendor outputs.
 * The historical name is kept to avoid changing the original controller and host lifecycle.
 */
object BydNavigationOutputs {
    /** Recover only standard Android Wi-Fi state when the app opens. */
    fun onAppOpened(context: Context) {
        // This is a standard Android Wi-Fi recovery operation, independent of BYD hardware.
        com.shilapi.xcertplay.network.WifiScanPause.restoreIfNeeded(context)
    }
    fun setDiagnosticHold(hold: Boolean) = Unit
    private val songState = ClusterSongState()
    private val callState = CarPlayCallState()
    @Volatile private var overlayListener: ((ClusterTurnGuidance?) -> Unit)? = null
    private val overlayLock = Any()
    private var publishedOverlay: ClusterTurnGuidance? = null
    private val overlayRoute = BydHudRouteState(
        staleRouteNs = 120_000_000_000L,
        emptyListHideNs = 8_000_000_000L,
        keepAcrossNoRoute = true,
    )
    // The physical cluster no longer controls the iPhone's virtual/launcher map stream.
    fun setClusterMapShown(shown: Boolean) = Unit
    fun setClusterStreamControl(control: (Boolean) -> Unit) = Unit
    fun clearClusterStreamControl(control: (Boolean) -> Unit) = Unit

    /** No vehicle reading is fabricated when the proprietary provider is absent. */
    fun batteryStatus(context: Context): com.shilapi.xcertplay.transport.VehicleStatusProvider =
        com.shilapi.xcertplay.transport.VehicleStatusProvider { null }

    fun wheelSpeed(context: Context): com.shilapi.xcertplay.transport.VehicleSpeedSource =
        object : com.shilapi.xcertplay.transport.VehicleSpeedSource {
            override fun start() = Unit
            override fun stop() = Unit
            override fun drain(): com.shilapi.xcertplay.transport.VehicleSpeedReading? = null
        }

    /** Unknown gear is never considered parked. */
    fun parked(context: Context): Boolean? = null

    fun start(context: Context) = Unit

    internal fun onFrame(frame: Iap2Frame) {
        if (frame.messageId == ClusterSongState.NOW_PLAYING_UPDATE) {
            synchronized(songState) { songState.accept(frame) }
            return
        }
        if (frame.messageId == CarPlayCallState.CALL_STATE_UPDATE) {
            synchronized(callState) { callState.accept(frame) }
            return
        }
        if (frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_UPDATE &&
            frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE) return
        val owned = frame // Iap2Frame is immutable and defensively copies its payload.
        updateOverlay(owned)
    }

    /** Live next-turn state for the dashboard overlay. Called from the iAP2 thread. */
    fun setTurnOverlayListener(listener: ((ClusterTurnGuidance?) -> Unit)?) {
        overlayListener = listener
        val next = currentOverlay()
        synchronized(overlayLock) { publishedOverlay = next }
        listener?.invoke(next)
    }

    private fun updateOverlay(frame: Iap2Frame) {
        val change = synchronized(overlayLock) { overlayRoute.accept(frame.messageId, frame.payload) }
        if (change != BydHudRouteChange.NONE) refreshTurnOverlay()
    }

    /** Called every second while the presentation owner lives, even without incoming frames. */
    fun refreshTurnOverlay() {
        val next = synchronized(overlayLock) {
            val current = currentOverlay()
            if (current == publishedOverlay) return
            publishedOverlay = current
            current
        }
        overlayListener?.invoke(next)
    }

    private fun currentOverlay(): ClusterTurnGuidance? = synchronized(overlayLock) {
        overlayRoute.currentApple()?.let { apple ->
            ClusterTurnGuidance.from(BydClusterFrame.from(apple)).copy(
                arrivalEpochSeconds = apple.arrivalEpochSeconds,
                remainingSeconds = apple.remainingSeconds,
                remainingMeters = apple.remainingMeters,
            )
        }
    }

    /** Legacy dashboard output callback, intentionally inert. */
    fun clusterSongChanged(enabled: Boolean) = Unit

    /** Legacy vehicle call-output callback, intentionally inert. */
    fun carPlayCallsChanged(enabled: Boolean) = Unit

    /** The iPhone's current call, for the steering wheel's call keys. */
    fun carPlayCall(): CarPlayCallCard? = synchronized(callState) { callState.current() }

    /** Legacy dashboard timing callback, intentionally inert. */
    fun clusterSongOnChangeChanged() = Unit

    /** Legacy dashboard note callback, intentionally inert. */
    fun dashboardNote(text: String, source: Int? = null) = Unit

    /** Best effort while alive; Android does not guarantee callbacks before force-stop. */
    fun endNow(preserveTurnOverlay: Boolean = false) {
        synchronized(songState) { songState.clear() }
        synchronized(callState) { callState.clear() }
        // Only a wireless session replacement retains the card. Explicit controller close
        // and wired disconnect still clear it immediately.
        if (!preserveTurnOverlay) {
            synchronized(overlayLock) { overlayRoute.clear() }
            refreshTurnOverlay()
        }
    }
}
