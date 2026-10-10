package com.shilapi.xcertplay.browser

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encoded Annex-B access units only. No MediaCodec, PCM, or data channel exists here.
 * All JNI calls run on one worker. JNI callbacks only enqueue notifications.
 */
class NativeBrowserRtcPeer private constructor(
    private val config: BrowserRtcConfig,
    private val listener: BrowserRtcPeer.Listener,
) : BrowserRtcPeer {
    object Factory : BrowserRtcPeer.Factory {
        override val available: Boolean by lazy {
            try { System.loadLibrary("diplay-rtc"); true }
            catch (_: LinkageError) { false }
            catch (_: SecurityException) { false }
        }
        override fun create(config: BrowserRtcConfig, listener: BrowserRtcPeer.Listener): BrowserRtcPeer {
            check(available) { "native-unavailable" }
            require(config.codec == "h264" || config.codec == "h265") { "unsupported-codec" }
            require(config.fmtp.length <= 1024) { "invalid-fmtp" }
            require(config.lanAddresses.isNotEmpty()) { "no-lan-route" }
            return NativeBrowserRtcPeer(config, listener)
        }
    }

    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "DiPlayRtcSend").apply { isDaemon = true }
    }
    private val events = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(32), { task ->
            Thread(task, "DiPlayRtcEvents").apply { isDaemon = true }
        }, ThreadPoolExecutor.AbortPolicy())
    private val eventOverflow = AtomicBoolean(false)
    @Volatile private var closed = false
    private var started = false
    private var handle = 0L // Worker thread only.
    private var queuedFrames = 0
    private var queuedBytes = 0
    private var generation = 0L
    private var waitingForKeyframe = true
    private var incomingCandidates = 0
    private var answerQueued = false

    override fun start() {
        synchronized(lock) {
            if (closed || started) return
            started = true
        }
        control {
            handle = nativeCreate(config.fmtp, config.codec == "h265", config.lanAddresses.first())
            check(handle != 0L) { "native-create-failed" }
            nativeStart(handle)
        }
    }

    override fun answer(sdp: String) {
        require(sdp.toByteArray(Charsets.UTF_8).size <= 6144) { "oversized-sdp" }
        synchronized(lock) {
            if (closed || !started || answerQueued) return
            answerQueued = true
        }
        control { nativeAnswer(handle, sdp) }
    }

    override fun candidate(candidate: String, mid: String) {
        require(candidate.length <= 1024 && mid.length <= 32) { "oversized-candidate" }
        synchronized(lock) {
            if (closed || !started || incomingCandidates >= 16) return
            incomingCandidates++
        }
        control { nativeCandidate(handle, candidate, mid) }
    }

    override fun send(frame: ByteArray, timestampUs: Long, key: Boolean): Boolean {
        if (frame.isEmpty() || frame.size > MAX_BYTES || timestampUs < 0) return false
        synchronized(lock) {
            if (closed || eventOverflow.get() || !started || (waitingForKeyframe && !key)) return false
            if (queuedFrames >= MAX_FRAMES || queuedBytes + frame.size > MAX_BYTES) {
                generation++
                waitingForKeyframe = true
                emit { listener.requestKeyframe() }
                return false
            }
            if (key) waitingForKeyframe = false
            val epoch = generation
            val owned = frame.copyOf()
            val enqueuedAt = System.nanoTime()
            queuedFrames++
            queuedBytes += owned.size
            try {
                worker.execute {
                    try {
                        val valid = synchronized(lock) { !closed && epoch == generation }
                        if (valid && (System.nanoTime() - enqueuedAt > MAX_AGE_NS ||
                                !nativeSend(handle, owned, timestampUs))) recover()
                    } catch (_: Exception) {
                        recover()
                    } finally {
                        synchronized(lock) {
                            queuedFrames--
                            queuedBytes -= owned.size
                        }
                    }
                }
            } catch (_: RejectedExecutionException) {
                queuedFrames--
                queuedBytes -= owned.size
                return false
            }
            return true // Queued, never proof of delivery.
        }
    }

    private fun recover() {
        synchronized(lock) {
            if (closed) return
            generation++
            waitingForKeyframe = true
        }
        emit { listener.requestKeyframe() }
    }

    private fun control(action: () -> Unit) {
        synchronized(lock) {
            if (closed) return
            try {
                worker.execute {
                    if (closed) return@execute
                    try { action() }
                    catch (_: Exception) { emit { listener.state("failed") } }
                }
            } catch (_: RejectedExecutionException) { /* close won the race */ }
        }
    }

    private fun emit(action: () -> Unit) {
        if (closed || eventOverflow.get()) return
        try {
            events.execute {
                if (!closed) try { action() } catch (_: Exception) { /* Listener cannot kill callback worker. */ }
            }
        } catch (_: RejectedExecutionException) {
            if (!closed && eventOverflow.compareAndSet(false, true)) {
                // Never invoke a listener on a native callback thread. Replace stale events
                // with one terminal failure so overload cannot silently lose state or PLI.
                synchronized(lock) { generation++; waitingForKeyframe = true }
                events.queue.clear()
                try {
                    events.execute { if (!closed) listener.state("failed") }
                } catch (_: RejectedExecutionException) { /* Concurrent close. */ }
            }
        }
    }

    @Suppress("unused") // Called by JNI; preserved by consumer-rules.pro.
    private fun onNativeEvent(kind: String, value: String, extra: String) {
        when (kind) {
            "offer" -> emit { listener.offer(value) }
            "candidate" -> emit { listener.candidate(value, extra) }
            "keyframe" -> recover()
            "connected", "disconnected", "failed", "closed" -> emit { listener.state(kind) }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            generation++
            // Runs after any in-flight JNI call; never deletes a peer from its native callback.
            worker.execute {
                if (handle != 0L) {
                    nativeClose(handle)
                    handle = 0L
                }
            }
            worker.shutdown()
            events.shutdownNow()
        }
    }

    private external fun nativeCreate(fmtp: String, hevc: Boolean, bindAddress: String): Long
    private external fun nativeStart(handle: Long)
    private external fun nativeAnswer(handle: Long, sdp: String)
    private external fun nativeCandidate(handle: Long, candidate: String, mid: String)
    private external fun nativeSend(handle: Long, frame: ByteArray, timestampUs: Long): Boolean
    private external fun nativeClose(handle: Long)

    private companion object {
        const val MAX_FRAMES = 3
        const val MAX_BYTES = 4 * 1024 * 1024
        const val MAX_AGE_NS = 50_000_000L
    }
}

