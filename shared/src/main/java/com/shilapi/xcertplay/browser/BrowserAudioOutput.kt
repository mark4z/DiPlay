package com.shilapi.xcertplay.browser

import android.content.Context
import com.shilapi.xcertplay.airplay.*
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

/** One explicitly requested, approved-connection-scoped WebRTC route. Never captures a microphone. */
object BrowserAudioOutput {
    const val TRANSPORT = "webrtc-opus"
    private val lock = Any()
    private var source: MediaSink? = null
    private var sourceSupported = false
    private var sourceGeneration = 0L
    private var transport: ((String) -> Boolean)? = null
    private var context: Context? = null
    private var epoch = 0
    private var lastRequest = 0L
    private var lastStartNs = Long.MIN_VALUE
    @Volatile private var route: Route? = null
    internal var peerFactory: ((Context?, (ByteBuffer) -> Unit, BrowserAudioPeerCallbacks) -> BrowserAudioPeer) =
        { app, fill, callbacks -> WebRtcAudioPeer(requireNotNull(app), fill, callbacks, embeddedHttps = BrowserOutput.secure) }
    internal var nowNs: () -> Long = System::nanoTime
    private val timer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "CarPlay-WebRTC-watchdog").apply { isDaemon = true }
    }
    private class Route(val epoch: Int, val generation: Long, val request: Long,
                        val send: (String) -> Boolean, val test: Boolean, val started: Long) {
        val pcm = BrowserPcmMixer()
        var peer: BrowserAudioPeer? = null
        var connected = false
        var offered = false
        var answered = false
        @Volatile var ready = false
        var active = false
        var activating = false
        val failureQueued = AtomicBoolean()
        val captureStarted = AtomicBoolean()
        var lastAlive = started
        var activeAt = 0L
        var testFrame = 0L
        var candidates = 0
    }
    init { timer.scheduleAtFixedRate({ runCatching { checkDeadline() } }, 250, 250, TimeUnit.MILLISECONDS) }

    fun initialize(context: Context) { synchronized(lock) { this.context = context.applicationContext } }

    fun attach(native: MediaSink) {
        disable("audio-source-changed")
        synchronized(lock) {
            source?.setDecodedAudioOutput(null)
            source = native
            val generation = ++sourceGeneration
            sourceSupported = native.setDecodedAudioOutput(object : DecodedAudioOutput {
                override fun pcm(stream: Long, id: AudioStreamId, format: DecodedAudioFormat,
                                 firstSample: Long, bytes: ByteArray, offset: Int, length: Int, gain: Float) {
                    val current = route?.takeIf { it.generation == generation && !it.test && it.captureStarted.get() } ?: return
                    val error = current.pcm.offer(stream, format, firstSample, bytes, offset, length, gain)
                    if (error != null) queueFailure(current, error)
                }
                override fun stopped(stream: Long, lastSample: Long) {
                    route?.takeIf { it.generation == generation && !it.test }?.pcm?.stopped(stream, lastSample)
                }
            })
        }
    }

    fun detach(native: MediaSink) {
        val old = synchronized(lock) {
            if (source !== native) return
            val old = route
            route = null
            source?.setDecodedAudioOutput(null)
            source = null
            sourceSupported = false
            ++sourceGeneration
            old
        }
        old?.peer?.close()
    }

    fun connect(sendText: (String) -> Boolean) {
        disconnect()
        synchronized(lock) { transport = sendText; lastRequest = 0; lastStartNs = Long.MIN_VALUE }
        sendText(JSONObject().put("type", "audioState").put("transport", TRANSPORT)
            .put("enabled", false).put("epoch", nextEpoch()).toString())
    }

    fun disconnect() {
        val old = synchronized(lock) {
            val old = route; route = null; transport = null
            source?.setNativeAudioEnabled(true)
            old
        }
        old?.peer?.close()
    }

    /** Legacy clients fail closed: PCM over WebSocket is no longer an audio transport. */
    fun setEnabled(enabled: Boolean, requestId: Long, requestedTransport: String = "", test: Boolean = false) {
        if (!enabled) {
            val current = route
            if (current != null && requestId == current.request) fail(current, null)
            return
        }
        var rejection: Pair<(String) -> Boolean, JSONObject>? = null
        val current = synchronized(lock) {
            val output = transport ?: return
            if (requestId !in 1..9_007_199_254_740_991L || requestId <= lastRequest) return
            lastRequest = requestId
            // Starting another negotiation never steals an existing route or allocates another peer.
            if (route != null) return
            val now = nowNs()
            val next = nextEpoch()
            val reason = when {
                requestedTransport != TRANSPORT -> "audio-upgrade-required"
                !test && !sourceSupported -> "audio-source-unavailable"
                lastStartNs != Long.MIN_VALUE && now - lastStartNs < 1_000_000_000L -> "audio-start-rate-limit"
                else -> null
            }
            if (reason != null) {
                rejection = output to state(next, requestId, false, reason)
                null
            } else {
                lastStartNs = now
                Route(next, sourceGeneration, requestId, output, test, now).also { route = it }
            }
        }
        if (current == null) {
            rejection?.let { (output, message) -> runCatching { output(message.toString()) } }
            return
        }
        try {
            val peer = peerFactory(context, { fill(current, it) }, object : BrowserAudioPeerCallbacks {
                override fun offer(sdp: String) {
                    if (sdp.length > 6000) { fail(current, "audio-sdp-too-large"); return }
                    synchronized(lock) { if (route === current) current.offered = true }
                    send(current, envelope(current, "audioOffer").put("sdp", sdp))
                }
                override fun ice(candidate: String, mid: String?, index: Int) {
                    send(current, envelope(current, "audioIce").put("candidate", candidate)
                        .put("sdpMid", mid ?: JSONObject.NULL).put("sdpMLineIndex", index))
                }
                override fun connected(connected: Boolean) {
                    synchronized(lock) { if (route === current) current.connected = connected }
                    if (connected) activate(current) else fail(current, "audio-connection-lost")
                }
                override fun failed(code: String) { fail(current, code) }
            })
            val start = synchronized(lock) { if (route === current) { current.peer = peer; true } else false }
            if (start) peer.start() else peer.close()
        } catch (_: Exception) { fail(current, "audio-peer-unavailable") }
    }

    fun receive(json: JSONObject) {
        val current = synchronized(lock) {
            val current = route ?: return
            if (json.optString("transport") != TRANSPORT || exactLong(json, "requestId") != current.request ||
                exactLong(json, "epoch") != current.epoch.toLong()) return
            current
        }
        try {
            when (json.getString("type")) {
                "audioAnswer" -> {
                    val sdp = json.getString("sdp")
                    require(sdp.length in 1..6000)
                    val accept = synchronized(lock) {
                        if (route !== current || current.answered) false else { current.answered = true; true }
                    }
                    if (accept) current.peer?.answer(sdp)
                }
                "audioIce" -> {
                    val candidate = json.getString("candidate")
                    require(candidate.length in 1..1024)
                    val mid = if (json.isNull("sdpMid")) null else json.getString("sdpMid")
                    require(mid == null || mid.length <= 32)
                    val index = exactLong(json, "sdpMLineIndex") ?: throw IllegalArgumentException()
                    require(index == 0L)
                    val accept = synchronized(lock) { route === current && ++current.candidates <= 32 }
                    require(accept)
                    current.peer?.addIce(candidate, mid, index.toInt())
                }
                "audioReady" -> {
                    synchronized(lock) { if (route === current && current.answered) current.ready = true }
                    activate(current)
                }
                "audioAlive" -> synchronized(lock) {
                    if (route === current && current.active) current.lastAlive = nowNs()
                }
            }
        } catch (_: Exception) { fail(current, "audio-invalid-signaling") }
    }

    private fun activate(current: Route) {
        val should = synchronized(lock) {
            if (route === current && current.connected && current.ready && !current.active && !current.activating) {
                current.activating = true; true
            } else false
        }
        if (!should) return
        if (!send(current, state(current.epoch, current.request, true, null).put("source", if (current.test) "test" else "carplay"))) return
        synchronized(lock) {
            if (route === current && current.connected && current.ready) {
                current.active = true; current.activeAt = nowNs(); current.lastAlive = current.activeAt
                if (!current.test && current.generation == sourceGeneration) source?.setNativeAudioEnabled(false)
            }
        }
    }

    private fun fill(current: Route, target: ByteBuffer) {
        target.order(ByteOrder.LITTLE_ENDIAN)
        if (route !== current) { while (target.hasRemaining()) target.put(0); return }
        current.captureStarted.set(true)
        if (current.test) {
            repeat(target.remaining() / 4) {
                val value = if (current.ready && current.testFrame < 144_000)
                    (sin(2.0 * Math.PI * 440.0 * current.testFrame++ / 48_000) * 1000).toInt().toShort() else 0.toShort()
                target.putShort(value); target.putShort(value)
            }
        } else {
            current.pcm.fill(target)
            current.pcm.failureCode?.let { queueFailure(current, it) }
        }
    }

    internal fun checkDeadline() {
        val current = route ?: return
        val now = nowNs()
        val reason = synchronized(lock) {
            if (route !== current) null
            else if (current.pcm.failureCode != null) current.pcm.failureCode
            else if (!current.active && now - current.started > 15_000_000_000L) when {
                !current.offered -> "audio-offer-timeout"
                !current.answered -> "audio-answer-timeout"
                !current.connected -> "audio-ice-timeout"
                !current.captureStarted.get() -> "audio-capture-timeout"
                else -> "audio-readiness-timeout"
            }
            else if (current.active && current.test && now - current.activeAt > 3_000_000_000L) "test-complete"
            else if (current.active && now - current.lastAlive > 4_000_000_000L) "audio-playback-timeout"
            else null
        }
        if (reason != null) fail(current, reason)
    }
    private fun queueFailure(current: Route, code: String) {
        if (current.failureQueued.compareAndSet(false, true)) timer.execute { fail(current, code) }
    }
    fun restoreNative() { disable("audio-source-stopped") }
    private fun disable(code: String?) { route?.let { fail(it, code) } }
    private fun fail(current: Route, code: String?) {
        val removed = synchronized(lock) {
            if (route !== current) false else { route = null; source?.setNativeAudioEnabled(true); true }
        }
        if (!removed) return
        current.peer?.close()
        runCatching { current.send(state(current.epoch, current.request, false, code).toString()) }
    }
    private fun send(current: Route, json: JSONObject): Boolean {
        if (route !== current) return false
        val sent = runCatching { current.send(json.toString()) }.getOrDefault(false)
        if (!sent) fail(current, "audio-signaling-unavailable")
        return sent
    }
    private fun envelope(current: Route, type: String) = JSONObject().put("type", type)
        .put("transport", TRANSPORT).put("epoch", current.epoch).put("requestId", current.request)
    private fun state(epoch: Int, request: Long, enabled: Boolean, code: String?) = JSONObject()
        .put("type", "audioState").put("transport", TRANSPORT).put("epoch", epoch)
        .put("requestId", request).put("enabled", enabled).also { if (code != null) it.put("code", code) }
    private fun nextEpoch(): Int = synchronized(lock) { epoch = if (epoch == Int.MAX_VALUE) 1 else epoch + 1; epoch }
    private fun exactLong(json: JSONObject, key: String): Long? {
        val number = json.opt(key) as? Number ?: return null
        return number.toLong().takeIf { number.toDouble().isFinite() && number.toDouble() == it.toDouble() }
    }
}
