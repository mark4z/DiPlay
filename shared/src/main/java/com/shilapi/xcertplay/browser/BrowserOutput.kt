package com.shilapi.xcertplay.browser

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.MediaCodecSupport
import org.json.JSONObject
import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.SecureRandom

/** Process-local, explicitly started video/input session. Never persists pairing credentials. */
object BrowserOutput {
    private val lock = Any()
    @Volatile private var server: BrowserLanServer? = null
    @Volatile var endpoint: String? = null; private set
    @Volatile var pairingToken: String? = null; private set
    @Volatile var viewerConnected = false; private set
    private var serverGeneration = 0L
    private var mediaGeneration = 0L
    private var streamId = 0L
    private var codec = VideoCodec.H264
    private var format: BrowserVideoFormat? = null
    private var width = 0
    private var height = 0
    private var recovery: (() -> Unit)? = null
    private var cancelTouch: (() -> Unit)? = null
    private var touch: ((List<AirPlayContact>) -> Unit)? = null
    private var contacts = emptyList<AirPlayContact>()
    private var waitingForKey = true
    private val recoveryPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val recoveryExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "CarPlay-browser-recovery").apply { isDaemon = true }
    }
    private var lastRecoveryNs = 0L
    private val timestampOriginNs = System.nanoTime()
    private var lastTimestamp = 0L

    fun start(address: InetAddress, origin: String): String = synchronized(lock) {
        check(server == null) { "Stop the current browser session first" }
        val generation = ++serverGeneration
        val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
        val transport = BrowserLanServer(address, origin, token,
            onAuthenticated = { synchronized(lock) {
                if (generation != serverGeneration) return@synchronized
                viewerConnected = true
                cancelTouch?.invoke()
                waitingForKey = true
                server?.sendText("{\"type\":\"authenticated\"}")
                sendConfig()
                requestKeyframe()
            } },
            onText = { receive(it, generation) },
            onDisconnected = { synchronized(lock) {
                if (generation != serverGeneration) return@synchronized
                waitingForKey = true
                releaseTouches(force = true)
                viewerConnected = false
            } })
        server = transport
        try {
            val port = transport.start()
            endpoint = "ws://${address.hostAddress}:$port/carplay"
            pairingToken = token
            endpoint!!
        } catch (e: Exception) { server = null; transport.close(); throw e }
    }

    fun stop() {
        val old = synchronized(lock) {
            val old = server
            ++serverGeneration
            val wasConnected = viewerConnected
            server = null; endpoint = null; pairingToken = null
            waitingForKey = true; releaseTouches(force = wasConnected)
            viewerConnected = false
            old
        }
        old?.close()
    }

    /** Native sink continues unchanged; detached native surfaces do not close this tee. */
    fun tee(native: MediaSink, videoWidth: Int, videoHeight: Int,
            cancelTouches: () -> Unit, sendTouch: (List<AirPlayContact>) -> Unit): MediaSink {
        val generation = synchronized(lock) {
            releaseTouches()
            width = videoWidth; height = videoHeight; touch = sendTouch; cancelTouch = cancelTouches
            format = null; waitingForKey = true; recovery = null
            ++streamId
            ++mediaGeneration
        }
        return object : MediaSink by native {
            override fun onVideoCodec(type: Int, codec: VideoCodec) {
                native.onVideoCodec(type, codec)
                if (type == 110) synchronized(lock) { if (generation == mediaGeneration) BrowserOutput.codec = codec }
            }
            override fun onVideoConfig(type: Int, codecData: ByteArray) {
                native.onVideoConfig(type, codecData)
                if (type == 110) synchronized(lock) {
                    if (generation != mediaGeneration) return@synchronized
                    ++streamId
                    releaseTouches()
                    format = BrowserVideoFormat.parse(codec, codecData)
                    waitingForKey = true
                    if (format == null) {
                        releaseTouches()
                        if (viewerConnected) server?.sendText("{\"type\":\"error\",\"code\":\"unsupported-config\"}", resetVideo = true)
                        return@synchronized
                    }
                    sendConfig()
                    requestKeyframe()
                }
            }
            override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
                native.onVideoFrame(type, naluBytes)
                if (type == 110) frame(naluBytes, generation)
            }
            override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
                native.setVideoRecoveryHandler(type, handler)
                if (type == 110) synchronized(lock) { if (generation == mediaGeneration) recovery = handler }
            }
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                native.onScreenStreamActive(type, active)
                if (type == 110 && !active) synchronized(lock) {
                    if (generation != mediaGeneration) return@synchronized
                    ++streamId
                    format = null; recovery = null; waitingForKey = true; releaseTouches()
                    server?.sendText("{\"type\":\"status\",\"code\":\"disconnected\"}", resetVideo = true)
                }
            }
        }
    }

    private fun sendConfig() {
        if (!viewerConnected) return
        val f = format ?: return
        server?.sendText(JSONObject().put("type", "config").put("codec", f.codec)
            .put("width", width).put("height", height).put("streamId", streamId).toString(), resetVideo = true)
    }

    private fun frame(bytes: ByteArray, generation: Long) = synchronized(lock) {
        if (generation != mediaGeneration) return
        val transport = server ?: return
        if (!viewerConnected) return
        if (bytes.size > 4 * 1024 * 1024) { waitingForKey = true; requestKeyframe(); return }
        val f = format ?: return
        val annexB = MediaCodecSupport.toAnnexB(bytes)
        if (annexB.isEmpty()) { waitingForKey = true; requestKeyframe(); return }
        val key = MediaCodecSupport.isRandomAccess(annexB, codec)
        if (waitingForKey && !key) { requestKeyframe(); return }
        val prefix = if (key) f.parameterSets else ByteArray(0)
        if (annexB.size + prefix.size + 9 > 4 * 1024 * 1024) { waitingForKey = true; requestKeyframe(); return }
        val timestamp = maxOf((System.nanoTime() - timestampOriginNs) / 1000, lastTimestamp + 1)
        lastTimestamp = timestamp
        val packet = ByteBuffer.allocate(9 + prefix.size + annexB.size)
            .put(if (key) 1.toByte() else 2.toByte()).putLong(timestamp).put(prefix).put(annexB).array()
        waitingForKey = !transport.sendBinary(packet, keyFrame = key)
        if (waitingForKey) requestKeyframe()
    }

    private fun requestKeyframe() {
        if (!viewerConnected || server == null) return
        val now = System.nanoTime()
        if (now - lastRecoveryNs < 1_000_000_000L) return
        val handler = recovery ?: return
        if (!recoveryPending.compareAndSet(false, true)) return
        lastRecoveryNs = now
        val generation = mediaGeneration
        recoveryExecutor.execute {
            try {
                val current = synchronized(lock) { generation == mediaGeneration && viewerConnected }
                if (current) handler()
            } catch (_: Exception) {
                // Retry remains bounded by the next viewer/keyframe request.
            } finally { recoveryPending.set(false) }
        }
    }

    private fun receive(message: String, generation: Long) = synchronized(lock) {
        if (generation != serverGeneration || !viewerConnected) return
        try {
            val json = JSONObject(message)
            when (json.getString("type")) {
                "requestKeyframe" -> { waitingForKey = true; requestKeyframe() }
                "touch" -> {
                    if (json.optLong("streamId", -1L) != streamId) return
                    if (format == null) { releaseTouches(); return }
                    val input = json.getJSONArray("contacts")
                    require(input.length() <= 2)
                    val next = (0 until input.length()).map { index ->
                        val p = input.getJSONObject(index)
                        val id = p.getInt("id"); val x = p.getDouble("x"); val y = p.getDouble("y")
                        require(id in 0..1 && x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0)
                        AirPlayContact(id, x, y, true)
                    }
                    require(next.map { it.id }.distinct().size == next.size)
                    val report = (0..1).map { id -> next.find { it.id == id }
                        ?: contacts.find { it.id == id }?.copy(down = false)
                        ?: AirPlayContact(id, 0.0, 0.0, false) }
                    touch?.invoke(report)
                    contacts = next
                }
            }
        } catch (_: Exception) { releaseTouches() }
    }

    private fun releaseTouches(force: Boolean = false) {
        if (force || viewerConnected || contacts.isNotEmpty()) cancelTouch?.invoke()
        contacts = emptyList()
    }
}
