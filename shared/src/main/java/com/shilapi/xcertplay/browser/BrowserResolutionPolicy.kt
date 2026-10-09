package com.shilapi.xcertplay.browser

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlayUiScale

/** Untrusted browser content-box dimensions in rendering-device pixels; DPR is already applied. */
object BrowserResolutionPolicy {
    const val UNITS = "device-pixels"
    const val MIN_SIDE = 320
    const val MAX_INPUT_SIDE = 16_384
    const val MAX_LONG_SIDE = 3_840
    const val MAX_SHORT_SIDE = 2_160
    const val MAX_PIXELS = 8_294_400
    const val MAX_ASPECT = 3.0

    data class Size(val width: Int, val height: Int)

    /**
     * Validate and even-align the browser rendering pixels without a quality cap.
     * Resolution percentage and UI scale apply before the final transport resource ceiling.
     * Callers must compare the final negotiated output with the active stream, not this baseline.
     * A cap is a resource bound, not a promise that every iPhone/decoder supports the resulting size.
     */
    fun normalize(width: Double, height: Double): Size? {
        if (!width.isFinite() || !height.isFinite() || width != floor(width) || height != floor(height)) return null
        if (width < MIN_SIDE || height < MIN_SIDE || width > MAX_INPUT_SIDE || height > MAX_INPUT_SIDE) return null
        if (max(width, height) / min(width, height) > MAX_ASPECT) return null
        var w = width.toInt() and -2
        var h = height.toInt() and -2
        // Even rounding near 3:1 must not produce a result rejected on its next normalization.
        if (w > h * MAX_ASPECT) w = (h * MAX_ASPECT).toInt() and -2
        if (h > w * MAX_ASPECT) h = (w * MAX_ASPECT).toInt() and -2
        if (w < MIN_SIDE || h < MIN_SIDE) return null
        return Size(w, h)
    }

    /** Keep saved preferences intact: only the final even canvas is proportionally bounded. */
    fun outputSize(baseline: Size, resolutionPercent: Int, uiScalePercent: Int): Size {
        require(normalize(baseline.width.toDouble(), baseline.height.toDouble()) == baseline)
        val resolution = CarPlayDisplayScale.applyPercent(
            AirPlayDisplayConfig(baseline.width, baseline.height), resolutionPercent)
        val ui = CarPlayUiScale.sanitize(uiScalePercent)
        // CarPlayUiScale.apply intentionally refuses an oversized native canvas. For a browser,
        // apply the requested UI scale first, then fit the result to the shared final ceiling.
        fun uiPixels(value: Int) = ((value.toLong() * 100 / ui + 1) / 2 * 2).toInt()
        val width = uiPixels(resolution.widthPixels)
        val height = uiPixels(resolution.heightPixels)
        val fit = minOf(1.0, MAX_LONG_SIDE.toDouble() / max(width, height),
            MAX_SHORT_SIDE.toDouble() / min(width, height))
        var w = ((width * fit).toInt() and -2).coerceAtLeast(2)
        var h = ((height * fit).toInt() and -2).coerceAtLeast(2)
        if (w > h * MAX_ASPECT) w = (h * MAX_ASPECT).toInt() and -2
        if (h > w * MAX_ASPECT) h = (w * MAX_ASPECT).toInt() and -2
        return Size(w, h)
    }
}

/**
 * Single-owner state machine. Feed FINAL effective output dimensions after percentage and codec
 * fallback. It never starts work: the host owns authentication, persistence, lifecycle and reconnect.
 * Re-evaluate after [STABLE_MILLIS]/[COOLDOWN_MILLIS] when a target is pending. Complete each admitted
 * attempt on negotiated success or terminal failure; failure requires an explicit manual reset.
 */
class BrowserResolutionReconnectGate {
    companion object {
        const val STABLE_MILLIS = 1_500L
        const val COOLDOWN_MILLIS = 15_000L
    }

    private var desired: BrowserResolutionPolicy.Size? = null
    private var changedAt = 0L
    private var attempted: BrowserResolutionPolicy.Size? = null
    private var lastAttemptAt: Long? = null
    var inFlight = false
        private set
    var failureBlocked = false
        private set

    fun shouldReconnect(target: BrowserResolutionPolicy.Size,
                        active: BrowserResolutionPolicy.Size?, nowMillis: Long,
                        allowed: Boolean = true): Boolean {
        if (target != desired) {
            desired = target
            changedAt = nowMillis
        }
        if (!allowed || failureBlocked || inFlight || target == active || target == attempted) return false
        if (nowMillis < changedAt || nowMillis - changedAt < STABLE_MILLIS) return false
        val previous = lastAttemptAt
        if (previous != null && (nowMillis < previous || nowMillis - previous < COOLDOWN_MILLIS)) return false
        attempted = target
        lastAttemptAt = nowMillis
        inFlight = true
        return true
    }

    fun complete(success: Boolean) {
        if (!inFlight) return
        inFlight = false
        if (!success) failureBlocked = true
    }

    fun hasAttempted(target: BrowserResolutionPolicy.Size): Boolean = target == attempted

    /** Explicit user retry/reset only; viewport events must never reset this circuit breaker. */
    fun reset() {
        desired = null
        attempted = null
        // Even explicit retry/reset cannot bypass the global reconnect rate bound.
        changedAt = 0L
        inFlight = false
        failureBlocked = false
    }
}

