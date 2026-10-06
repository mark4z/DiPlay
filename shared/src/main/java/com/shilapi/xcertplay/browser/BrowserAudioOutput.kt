package com.shilapi.xcertplay.browser

import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.DecodedAudioFormat
import com.shilapi.xcertplay.airplay.DecodedAudioOutput
import com.shilapi.xcertplay.airplay.MediaSink
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Explicit, connection-scoped PCM route. The renderer only copies/offers; this worker owns sends. */
object BrowserAudioOutput {
    private data class Transport(val text: (String) -> Boolean, val audio: (ByteArray) -> Boolean,
                                 val reset: () -> Unit)
    private class Route(val epoch: Int, val sourceGeneration: Long, val transport: Transport, val requestId: Long?) {
        val nextStream = AtomicInteger()
        val streams = ConcurrentHashMap<Long, Int>()
    }
    private data class Event(val route: Route, val bytes: ByteArray? = null, val text: String? = null,
                             val error: String? = null)
    // At most four 16KiB chunks here, independently of the LAN queue and all video/control traffic.
    private val lock = Any()
    private val queue = ArrayBlockingQueue<Event>(4)
    private var source: MediaSink? = null
    private var sourceSupported = false
    private var sourceGeneration = 0L
    private var transport: Transport? = null
    private var epoch = 0
    @Volatile private var route: Route? = null

    init {
        Thread({
            while (true) {
                val event = try { queue.take() } catch (_: InterruptedException) { continue }
                if (route !== event.route) continue
                if (event.error != null) {
                    disableRoute(event.route, event.error)
                } else {
                    // A route change can leave one in-flight old-epoch message; the viewer rejects it.
                    runCatching {
                        event.bytes?.let(event.route.transport.audio)
                        event.text?.let(event.route.transport.text)
                    }
                }
            }
        }, "CarPlay-browser-audio").apply { isDaemon = true; start() }
    }

    fun attach(native: MediaSink) {
        setEnabled(false)
        synchronized(lock) {
            source?.setDecodedAudioOutput(null)
            source = native
            val generation = ++sourceGeneration
            nextEpoch() // Invalidates an enable handoff that began against the replaced native sink.
            sourceSupported = native.setDecodedAudioOutput(object : DecodedAudioOutput {
                override fun pcm(stream: Long, id: AudioStreamId, format: DecodedAudioFormat,
                                 firstSample: Long, bytes: ByteArray, offset: Int, length: Int, gain: Float) {
                    val current = route?.takeIf { it.sourceGeneration == generation } ?: return
                    if (!format.supported || length % (format.channels * 2).coerceAtLeast(1) != 0) {
                        offer(Event(current, error = "unsupported-pcm-format")); return
                    }
                    val streamId = current.streams.computeIfAbsent(stream) { current.nextStream.incrementAndGet() }
                    val frameBytes = format.channels * 2
                    var position = 0
                    while (position < length && route === current) {
                        val count = minOf(length - position, BrowserAudioPacket.MAX_FRAMES * frameBytes)
                        val packet = BrowserAudioPacket.encode(current.epoch, streamId, format,
                            firstSample + position / frameBytes, bytes, offset + position, count,
                            if (gain.isFinite()) gain.coerceIn(0f, 1f) else 1f)
                        offer(Event(current, bytes = packet))
                        position += count
                    }
                }
                override fun stopped(stream: Long, lastSample: Long) {
                    val current = route?.takeIf { it.sourceGeneration == generation } ?: return
                    val streamId = current.streams.remove(stream) ?: return
                    offer(Event(current, text = JSONObject().put("type", "audioStopped")
                        .put("epoch", current.epoch).put("streamId", streamId).put("lastSample", lastSample).toString()))
                }
            })
        }
    }

    fun connect(sendText: (String) -> Boolean, sendAudio: (ByteArray) -> Boolean, resetAudio: () -> Unit) {
        disconnect()
        synchronized(lock) { transport = Transport(sendText, sendAudio, resetAudio) }
        setEnabled(false)
    }

    fun disconnect() {
        val previous = synchronized(lock) {
            route = null
            queue.clear()
            source?.setNativeAudioEnabled(true)
            val previous = transport
            transport = null
            nextEpoch()
            previous
        }
        previous?.reset?.invoke()
    }

    /** No transport callback runs under the route monitor (disconnect may reenter BrowserOutput). */
    fun setEnabled(enabled: Boolean, requestId: Long? = null) {
        changeMode(enabled, requestId)
    }

    private fun disableRoute(expected: Route, code: String): Boolean =
        changeMode(false, expected = expected, code = code)

    private fun changeMode(enabled: Boolean, requestId: Long? = null, expected: Route? = null, code: String? = null): Boolean {
        val change = synchronized(lock) {
            if (expected != null && route !== expected) return false
            route = null
            queue.clear()
            source?.setNativeAudioEnabled(true)
            val next = nextEpoch()
            val output = transport ?: return false
            Triple(next, output, enabled && sourceSupported)
        }
        val (next, output, accepted) = change
        runCatching { output.reset() }
        val state = JSONObject().put("type", "audioState").put("enabled", accepted).put("epoch", next)
        val replyRequestId = requestId ?: expected?.requestId
        if (replyRequestId != null) state.put("requestId", replyRequestId)
        if (code != null) state.put("code", code)
        else if (enabled && !accepted) state.put("code", "audio-source-unavailable")
        val sent = runCatching { output.text(state.toString()) }.getOrDefault(false)
        synchronized(lock) {
            if (sent && accepted && epoch == next && transport === output) {
                route = Route(next, sourceGeneration, output, requestId)
                source?.setNativeAudioEnabled(false)
            }
        }
        return true
    }

    private fun nextEpoch(): Int {
        epoch = if (epoch == Int.MAX_VALUE) 1 else epoch + 1
        return epoch
    }

    private fun offer(event: Event) {
        if (queue.offer(event)) return
        queue.poll() // Drop oldest; sample counters make the discontinuity visible to the mixer.
        queue.offer(event)
    }
}
