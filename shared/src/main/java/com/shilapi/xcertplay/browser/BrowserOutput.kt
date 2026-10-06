package com.shilapi.xcertplay.browser

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.MediaCodecSupport
import org.json.JSONObject
import java.net.InetAddress
import java.nio.ByteBuffer

/** Process-local, explicitly started video/input session. Never persists pairing credentials. */
object BrowserOutput {
    private val lock = Any()
    @Volatile private var server: BrowserLanServer? = null
    @Volatile var endpoint: String? = null; private set
    const val VIEWER_ORIGIN = "https://mark4z.github.io"
    @Volatile var viewerConnected = false; private set
    @Volatile var browserTouchOwned = false; private set
    private data class ApprovalUi(val owner: Any, val requested: (BrowserApprovalRequest) -> Unit,
        val finished: (Long) -> Unit)
    private var approvalUi: ApprovalUi? = null
    private var pendingApproval: BrowserApprovalRequest? = null
    private var viewerGeneration = 0L
    private var touchGeneration = 0L
    private var touchOwnershipRequested = false
    private var lastOwnershipRequestId = 0L
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
    private var setTouchOwnership: ((Boolean, (Boolean) -> Unit) -> Unit)? = null
    private var contacts = emptyList<AirPlayContact>()
    private var waitingForKey = true
    private val recoveryPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val recoveryExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "CarPlay-browser-recovery").apply { isDaemon = true }
    }
    private var lastRecoveryNs = 0L
    private val timestampOriginNs = System.nanoTime()
    private var lastTimestamp = 0L

    /** Only a resumed, visible Android host may approve a new connection. */
    fun registerApprovalUi(owner: Any, onRequest: (BrowserApprovalRequest) -> Unit, onFinished: (Long) -> Unit) {
        val previous = synchronized(lock) {
            val old = approvalUi
            val pending = pendingApproval
            if (old?.owner !== owner) pendingApproval = null
            approvalUi = ApprovalUi(owner, onRequest, onFinished)
            if (old?.owner !== owner && pending != null) old to pending else null
        }
        previous?.let { (ui, request) -> request.reject(); ui?.finished?.invoke(request.id) }
    }

    fun unregisterApprovalUi(owner: Any) {
        val previous = synchronized(lock) {
            val ui = approvalUi ?: return
            if (ui.owner !== owner) return
            approvalUi = null
            val request = pendingApproval
            pendingApproval = null
            ui to request
        }
        previous.second?.let { it.reject(); previous.first.finished(it.id) }
    }

    fun isApprovalPending(request: BrowserApprovalRequest): Boolean = synchronized(lock) {
        pendingApproval === request && approvalUi != null
    }

    private fun requestApproval(request: BrowserApprovalRequest, generation: Long) {
        val ui = synchronized(lock) {
            if (generation != serverGeneration || approvalUi == null) null else {
                pendingApproval = request
                approvalUi
            }
        }
        if (ui == null) request.reject() else try { ui.requested(request) } catch (_: Exception) { request.reject() }
    }

    private fun finishApproval(id: Long, generation: Long) {
        val ui = synchronized(lock) {
            if (generation != serverGeneration || pendingApproval?.id != id) return
            pendingApproval = null
            approvalUi
        }
        ui?.finished?.invoke(id)
    }

    fun start(address: InetAddress, origin: String = VIEWER_ORIGIN): String = synchronized(lock) {
        check(server == null) { "Stop the current browser session first" }
        val generation = ++serverGeneration
        val transport = BrowserLanServer(address, origin,
            onApprovalRequested = { requestApproval(it, generation) },
            onApprovalFinished = { finishApproval(it, generation) },
            onAuthenticated = { synchronized(lock) {
                if (generation != serverGeneration) return@synchronized
                ++viewerGeneration
                viewerConnected = true
                lastOwnershipRequestId = 0L
                browserTouchOwned = false
                waitingForKey = true
                server?.sendText("{\"type\":\"authenticated\",\"version\":2}")
                server?.bindAudioTransport()?.let {
                    BrowserAudioOutput.connect(it.sendText, it.sendAudio, it.resetAudio)
                }
                sendConfig()
                requestKeyframe()
            } },
            onText = { receive(it, generation) },
            onDisconnected = { synchronized(lock) {
                if (generation != serverGeneration) return@synchronized
                ++viewerGeneration
                waitingForKey = true
                releaseTouches()
                BrowserAudioOutput.disconnect()
                viewerConnected = false
            } })
        server = transport
        try {
            val port = transport.start()
            endpoint = "ws://${address.hostAddress}:$port/carplay"
            endpoint!!
        } catch (e: Exception) { server = null; transport.close(); throw e }
    }

    fun stop() {
        val stopped = synchronized(lock) {
            val old = server
            ++serverGeneration
            ++viewerGeneration
            server = null; endpoint = null
            waitingForKey = true; releaseTouches()
            BrowserAudioOutput.disconnect()
            viewerConnected = false
            val pending = pendingApproval
            pendingApproval = null
            Triple(old, pending, approvalUi)
        }
        stopped.second?.let { it.reject(); stopped.third?.finished?.invoke(it.id) }
        stopped.first?.close()
    }

    /** Native sink continues unchanged; detached native surfaces do not close this tee. */
    fun tee(native: MediaSink, videoWidth: Int, videoHeight: Int,
            cancelTouches: () -> Unit, sendTouch: (List<AirPlayContact>) -> Unit,
            setTouchOwnership: ((Boolean, (Boolean) -> Unit) -> Unit)? = null): MediaSink {
        val generation = synchronized(lock) {
            releaseTouches()
            BrowserAudioOutput.attach(native)
            if (viewerConnected && format != null) {
                server?.sendText("{\"type\":\"status\",\"code\":\"waiting\"}", resetVideo = true)
            }
            width = videoWidth; height = videoHeight; touch = sendTouch; cancelTouch = cancelTouches
            BrowserOutput.setTouchOwnership = setTouchOwnership
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
                    val updated = BrowserVideoFormat.parse(codec, codecData)
                    // Encoders may repeat SPS/PPS with every recovery keyframe. Those
                    // bytes do not replace the media stream or its touch generation.
                    val previous = format
                    if (updated != null && previous != null && updated.codec == previous.codec &&
                        updated.parameterSets.contentEquals(previous.parameterSets)) return@synchronized
                    ++streamId
                    releaseTouches()
                    format = updated
                    waitingForKey = true
                    if (format == null) {
                        releaseTouches()
                        if (viewerConnected) server?.sendText("{\"type\":\"error\",\"code\":\"unsupported-config\",\"version\":2}", resetVideo = true)
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
                    BrowserAudioOutput.setEnabled(false)
                    if (viewerConnected) server?.sendText("{\"type\":\"status\",\"code\":\"disconnected\"}", resetVideo = true)
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
                "setTouchOwnership" -> changeTouchOwnership(json)
                "audioMode" -> {
                    val id = json.get("requestId")
                    require(id is Number && id.toDouble().isFinite() && id.toDouble() == id.toLong().toDouble())
                    require(id.toLong() in 1..9_007_199_254_740_991L)
                    BrowserAudioOutput.setEnabled(json.get("enabled") as? Boolean
                        ?: throw IllegalArgumentException("Boolean required"), id.toLong())
                }
                "touch" -> {
                    if (!browserTouchOwned || json.optLong("streamId", -1L) != streamId) return
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

    private fun changeTouchOwnership(json: JSONObject) {
        if (json.optLong("streamId", -1L) != streamId) return
        val id = json.get("requestId")
        require(id is Number && id.toDouble().isFinite() && id.toDouble() == id.toLong().toDouble())
        val requestId = id.toLong()
        require(requestId in 1..9_007_199_254_740_991L)
        if (requestId <= lastOwnershipRequestId) return
        val enabled = json.get("enabled") as? Boolean ?: throw IllegalArgumentException("Boolean required")
        lastOwnershipRequestId = requestId
        val requested = enabled && format != null
        val wasRequested = browserTouchOwned || touchOwnershipRequested
        val generation = ++touchGeneration
        val currentViewer = viewerGeneration
        val currentStream = streamId
        browserTouchOwned = false
        touchOwnershipRequested = requested
        contacts = emptyList()
        val complete: (Boolean) -> Unit = { applied -> synchronized(lock) {
            if (generation == touchGeneration && currentViewer == viewerGeneration &&
                currentStream == streamId && viewerConnected) {
                browserTouchOwned = applied && requested
                touchOwnershipRequested = browserTouchOwned
                server?.sendText(JSONObject().put("type", "touchOwnership")
                    .put("enabled", browserTouchOwned).put("streamId", currentStream)
                    .put("requestId", requestId).toString())
            }
        } }
        val change = setTouchOwnership
        when {
            !requested && !wasRequested -> complete(true)
            change == null -> complete(false)
            else -> change(requested, complete)
        }
    }

    /** Viewing alone never cancels native input. Revocation also invalidates delayed ACKs. */
    private fun releaseTouches() {
        ++touchGeneration
        val revoke = browserTouchOwned || touchOwnershipRequested || contacts.isNotEmpty()
        browserTouchOwned = false
        touchOwnershipRequested = false
        contacts = emptyList()
        if (revoke) {
            val change = setTouchOwnership
            if (change != null) change(false) { } else cancelTouch?.invoke()
        }
    }
}
