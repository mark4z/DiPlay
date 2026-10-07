package com.shilapi.xcertplay.media

import java.util.Locale
import kotlin.math.ceil

/** Numeric, process-local capture. No contacts, frame contents, addresses or identifiers. */
object PerformanceDiagnostics {
    private val recorder = PerformanceRecorder()
    val enabled: Boolean get() = recorder.enabled
    fun setEnabled(enabled: Boolean) = recorder.setEnabled(enabled)
    fun snapshot(): String = recorder.snapshot()
    internal fun token(): Long = recorder.token()
    internal fun now(token: Long): Long = recorder.now(token)
    internal fun record(metric: PerformanceMetric, elapsedNs: Long, token: Long) = recorder.record(metric, elapsedNs, token)
    internal fun count(counter: PerformanceCounter, token: Long, amount: Long = 1) = recorder.count(counter, token, amount)
    internal fun depth(counter: PerformanceCounter, depth: Int, token: Long) = recorder.depth(counter, depth, token)
    internal fun touchQueued(token: Long) = recorder.touchQueued(token)
    internal fun touchStarted(token: Long) = recorder.touchStarted(token)
    internal fun touchRejected(token: Long) = recorder.touchRejected(token)
    internal fun touchSent(token: Long, nowNs: Long) = recorder.touchSent(token, nowNs)
    internal fun onMainFrameReceived(token: Long, nowNs: Long) = recorder.onMainFrameReceived(token, nowNs)
    internal fun resetSession() = recorder.resetSession()
    internal fun logIfDue(): String? = recorder.logIfDue()
}

internal enum class PerformanceMetric {
    TOUCH_QUEUE_WAIT, TOUCH_WORKER, TOUCH_SEND, TOUCH_ENQUEUE_TO_COMPLETE,
    TOUCH_SENT_TO_NEXT_FRAME_PROXY,
    VIDEO_QUEUE_WAIT, VIDEO_INPUT_ACQUIRE, VIDEO_WORKER_TO_FEED,
    VIDEO_FEED_TO_RELEASE, VIDEO_RECEIVE_TO_RELEASE, VIDEO_RELEASE_TO_RENDER,
    VIDEO_RECEIVE_TO_RENDER, VIDEO_RECEIVE_GAP, VIDEO_RELEASE_GAP, VIDEO_RENDER_GAP,
}

internal enum class PerformanceCounter {
    SESSION_RESET, TOUCH_QUEUED, TOUCH_STARTED, TOUCH_SENT, TOUCH_FAILED, TOUCH_REJECTED,
    TOUCH_QUEUE_DEPTH, TOUCH_PROXY_COALESCED,
    VIDEO_RECEIVED, VIDEO_FED, VIDEO_RELEASED, VIDEO_RENDERED, VIDEO_QUEUE_DEPTH,
    VIDEO_QUEUE_DROPPED, VIDEO_STALE_DROPPED, VIDEO_REFERENCE_DROPPED, VIDEO_UNAVAILABLE_DROPPED,
    VIDEO_INVALID_DROPPED, VIDEO_CAPACITY_DROPPED, VIDEO_CODEC_FAILURE,
    VIDEO_INPUT_STALL, VIDEO_RECOVERY, VIDEO_KEYFRAME_REQUEST, VIDEO_PTS_AMBIGUOUS,
    VIDEO_OUTPUT_UNMATCHED, VIDEO_RENDER_UNMATCHED, VIDEO_TRACKING_EVICTED, VIDEO_CALLBACK_UNAVAILABLE,
}

/** Fixed memory per metric; percentiles describe the latest 512 samples, totals/max the full capture. */
internal class PerformanceRecorder(private val clock: () -> Long = System::nanoTime) {
    @Volatile var enabled = false
        private set
    @Volatile private var generation = 1L
    private val samples = Array(PerformanceMetric.entries.size) { Samples() }
    private val counters = LongArray(PerformanceCounter.entries.size)
    private val peaks = LongArray(PerformanceCounter.entries.size)
    private var startedNs = 0L
    private var stoppedNs = 0L
    private var lastSummaryNs = 0L
    private var pendingTouchNs: Long? = null

    @Synchronized fun setEnabled(value: Boolean) {
        if (enabled == value) return
        generation++
        pendingTouchNs = null
        if (value) {
            samples.forEach { it.clear() }
            counters.fill(0); peaks.fill(0)
            startedNs = clock(); lastSummaryNs = startedNs; stoppedNs = 0L
        } else {
            stoppedNs = clock()
            counters[PerformanceCounter.TOUCH_QUEUE_DEPTH.ordinal] = 0
        }
        enabled = value
    }

    fun token(): Long = if (enabled) generation else 0L
    private fun valid(token: Long): Boolean = enabled && token != 0L && token == generation
    fun now(token: Long): Long = if (valid(token)) clock() else 0L

    fun record(metric: PerformanceMetric, elapsedNs: Long, token: Long) {
        if (!valid(token) || elapsedNs < 0) return
        synchronized(this) { if (valid(token)) samples[metric.ordinal].add(elapsedNs) }
    }
    fun count(counter: PerformanceCounter, token: Long, amount: Long = 1) {
        if (!valid(token)) return
        synchronized(this) { if (valid(token)) counters[counter.ordinal] += amount }
    }
    fun depth(counter: PerformanceCounter, depth: Int, token: Long) {
        if (!valid(token)) return
        synchronized(this) {
            if (valid(token)) {
                counters[counter.ordinal] = depth.coerceAtLeast(0).toLong()
                peaks[counter.ordinal] = maxOf(peaks[counter.ordinal], depth.toLong())
            }
        }
    }
    fun touchQueued(token: Long) {
        if (!valid(token)) return
        synchronized(this) {
            if (!valid(token)) return
            count(PerformanceCounter.TOUCH_QUEUED, token)
            depth(PerformanceCounter.TOUCH_QUEUE_DEPTH, (counters[PerformanceCounter.TOUCH_QUEUE_DEPTH.ordinal] + 1).toInt(), token)
        }
    }
    fun touchStarted(token: Long) {
        if (!valid(token)) return
        synchronized(this) {
            if (!valid(token)) return
            count(PerformanceCounter.TOUCH_STARTED, token)
            depth(PerformanceCounter.TOUCH_QUEUE_DEPTH, (counters[PerformanceCounter.TOUCH_QUEUE_DEPTH.ordinal] - 1).toInt(), token)
        }
    }
    fun touchRejected(token: Long) {
        if (!valid(token)) return
        synchronized(this) {
            if (!valid(token)) return
            count(PerformanceCounter.TOUCH_REJECTED, token)
            depth(PerformanceCounter.TOUCH_QUEUE_DEPTH, (counters[PerformanceCounter.TOUCH_QUEUE_DEPTH.ordinal] - 1).toInt(), token)
        }
    }
    fun touchSent(token: Long, nowNs: Long) {
        if (!valid(token)) return
        synchronized(this) {
            if (!valid(token)) return
            if (pendingTouchNs == null) pendingTouchNs = nowNs
            else count(PerformanceCounter.TOUCH_PROXY_COALESCED, token)
        }
    }
    fun onMainFrameReceived(token: Long, nowNs: Long) {
        if (!valid(token)) return
        synchronized(this) {
            if (!valid(token)) return
            pendingTouchNs?.let {
                // A receive thread can take its timestamp before send completion but acquire
                // this lock afterwards. Keep the pending send for an actually later frame.
                if (nowNs >= it) {
                    record(PerformanceMetric.TOUCH_SENT_TO_NEXT_FRAME_PROXY, nowNs - it, token)
                    pendingTouchNs = null
                }
            }
        }
    }
    fun resetSession() {
        if (!enabled) return
        synchronized(this) {
            if (!enabled) return
            generation++
            pendingTouchNs = null
            counters[PerformanceCounter.SESSION_RESET.ordinal]++
            counters[PerformanceCounter.TOUCH_QUEUE_DEPTH.ordinal] = 0
        }
    }
    fun logIfDue(): String? {
        if (!enabled) return null
        synchronized(this) {
            if (!enabled) return null
            val now = clock()
            if (now - lastSummaryNs < 10_000_000_000L) return null
            lastSummaryNs = now
            // Keep decoder-thread publication small. The full, per-stage distribution is
            // generated only for an explicit report; periodic output is three bounded lines.
            return listOf(
                PerformanceMetric.TOUCH_QUEUE_WAIT,
                PerformanceMetric.VIDEO_INPUT_ACQUIRE,
                PerformanceMetric.VIDEO_RECEIVE_TO_RELEASE,
            ).joinToString("\n") { metric ->
                "performance summary ${metric.name.lowercase(Locale.ROOT)} ms ${samples[metric.ordinal].summary()}"
            }
        }
    }

    @Synchronized fun snapshot(): String = buildString {
        appendLine("Performance diagnostics: ${if (enabled) "ON" else "OFF (last capture retained)"}")
        appendLine("Local monotonic timing; ms. Percentiles: latest 512 samples per stage; n/max/over250ms/over2000ms: entire capture.")
        appendLine("All video streams aggregated. Receive starts at decoder submission, after transport/decryption; network and iPhone processing are unmeasured.")
        appendLine("Touch next-frame is an uncorrelated proxy from first completed send, not actual response latency. Extra touches before a frame are counted as coalesced samples only.")
        appendLine("Release means decoder output released to Surface, not shown. Render callback is codec-reported rendering, not glass-to-glass; zero samples means unavailable or not observed.")
        appendLine("Gap thresholds include long gaps and static screens; they are stall candidates, not proof of network loss. Capture ends on process exit; enabling starts a fresh capture.")
        val duration = if (enabled) clock() - startedNs else stoppedNs - startedNs
        appendLine("video_queue_depth is the last sampled decoder queue; peak is the maximum single queue. Touch depth covers touch tasks only, not other executor commands.")
        appendLine("captureDurationMs=${duration.coerceAtLeast(0) / 1_000_000}")
        PerformanceMetric.entries.forEach { metric ->
            appendLine("${metric.name.lowercase(Locale.ROOT)} ${samples[metric.ordinal].summary()}")
        }
        PerformanceCounter.entries.forEach { counter ->
            appendLine("${counter.name.lowercase(Locale.ROOT)}=${counters[counter.ordinal]}" +
                if (counter == PerformanceCounter.VIDEO_QUEUE_DEPTH || counter == PerformanceCounter.TOUCH_QUEUE_DEPTH) " peak=${peaks[counter.ordinal]}" else "")
        }
    }

    private class Samples {
        private val ring = LongArray(512)
        private var count = 0L
        private var max = 0L
        private var over250 = 0L
        private var over2000 = 0L
        fun clear() { count = 0; max = 0; over250 = 0; over2000 = 0; ring.fill(0) }
        fun add(ns: Long) {
            ring[(count % ring.size).toInt()] = ns
            count++; max = maxOf(max, ns)
            if (ns >= 250_000_000L) over250++
            if (ns >= 2_000_000_000L) over2000++
        }
        fun summary(): String {
            val size = minOf(count, ring.size.toLong()).toInt()
            if (size == 0) return "n=0 window=0 (not observed)"
            val sorted = ring.copyOf(size).apply { sort() }
            fun percentile(p: Double) = sorted[(ceil(size * p).toInt() - 1).coerceAtLeast(0)] / 1e6
            return String.format(Locale.ROOT,
                "n=%d window=%d p50=%.3f p95=%.3f p99=%.3f max=%.3f over250ms=%d over2000ms=%d",
                count, size, percentile(.5), percentile(.95), percentile(.99), max / 1e6, over250, over2000)
        }
    }
}
