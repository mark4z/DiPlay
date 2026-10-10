package com.shilapi.xcertplay.browser

import org.json.JSONObject
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One authenticated viewer/stream negotiation. All callbacks re-enter the owner's generation lock. */
internal class BrowserRtcSession(
    private val lock: Any,
    private val streamId: Long,
    val negotiationId: String,
    private val codec: String,
    private val current: () -> Boolean,
    private val send: (String, Boolean) -> Boolean,
    private val recover: () -> Unit,
    private val fallback: (BrowserRtcSession, String) -> Unit,
) : BrowserRtcPeer.Listener {
    var active = false; private set
    private var closed = false
    private var peer: BrowserRtcPeer? = null
    private var offered = false
    private var answered = false
    private var sourceFmtp = ""
    private var candidatesIn = 0
    private var candidatesOut = 0
    private val pendingCandidates = ArrayList<Pair<String, String>>()
    private val remoteCandidates = ArrayList<Pair<String, String>>()
    private var deadline: ScheduledFuture<*>? = null
    private var recoveryDeadline: ScheduledFuture<*>? = null
    private var waitingKey = true
    private var lastReportId = -1L
    private var lastStatsNs = 0L
    private var lastPresented = -1L
    private var framesSinceProgress = false
    private var lastFrameNs = 0L
    private var recoveryIsNetwork = false
    private var recoveryHasDecodeFailure = false

    fun start(factory: BrowserRtcPeer.Factory, config: BrowserRtcConfig): Unit = synchronized(lock) {
        if (!valid()) return@synchronized
        if (config.lanAddresses.isEmpty()) { fail("no-lan-route"); return@synchronized }
        try {
            sourceFmtp = config.fmtp
            peer = factory.create(config, this)
            if (!emit(stateMessage("negotiating", "starting"))) return@synchronized
            deadline = timers.schedule({ synchronized(lock) { if (valid() && !active) fail("first-frame-timeout") } }, 10, TimeUnit.SECONDS)
            peer!!.start()
        } catch (_: Exception) { fail("native-unavailable") }
    }

    private fun valid() = !closed && current()
    private fun envelope(type: String) = JSONObject().put("type", type).put("streamId", streamId).put("negotiationId", negotiationId)
    fun stateMessage(state: String, reason: String) = envelope("rtcState").put("state", state).put("reason", reason)
    private fun emit(json: JSONObject, resetVideo: Boolean = false): Boolean {
        val text = json.toString()
        if (text.toByteArray(Charsets.UTF_8).size > 8192) { fail("signaling-too-large"); return false }
        if (!send(text, resetVideo)) { fail("signaling-failed"); return false }
        return true
    }
    private fun fail(reason: String) { if (valid()) fallback(this, reason) }

    override fun offer(sdp: String): Unit = synchronized(lock) {
        if (!valid() || offered) return@synchronized
        if (!validSdp(sdp, offer = true)) { fail("invalid-sdp"); return@synchronized }
        offered = emit(envelope("rtcOffer").put("sdp", sdp).put("codec", codec)
            .put("firstFrameTimeoutMs", 10000).put("recoveryGraceMs", 10000))
        if (offered) {
            val queued = pendingCandidates.toList(); pendingCandidates.clear()
            queued.forEach { emitCandidate(it.first, it.second) }
        }
    }
    override fun candidate(candidate: String, mid: String): Unit = synchronized(lock) {
        if (!valid()) return@synchronized
        if (!validCandidate(candidate, mid) || ++candidatesOut > 16) { fail("signaling-too-large"); return@synchronized }
        if (offered) emitCandidate(candidate, mid) else pendingCandidates.add(candidate to mid)
    }
    private fun emitCandidate(candidate: String, mid: String) {
        emit(envelope("rtcCandidate").put("candidate", candidate).put("sdpMid", mid).put("sdpMLineIndex", 0))
    }
    override fun state(state: String): Unit = synchronized(lock) {
        if (!valid()) return@synchronized
        when (state) {
            "failed", "closed" -> fail("ice-failed")
            "disconnected" -> beginRecovery(network = true)
            "connected" -> {
                if (recoveryIsNetwork) {
                    recoveryIsNetwork = false
                    if (!recoveryHasDecodeFailure) { recoveryDeadline?.cancel(false); recoveryDeadline = null }
                    // A restored UDP path may be a static screen; do not require a new source frame
                    // just to recognize transport recovery. Still refresh its decoder chain.
                    waitingKey = true
                    recover()
                }
            }
        }
    }
    override fun requestKeyframe(): Unit = synchronized(lock) {
        if (valid()) { waitingKey = true; recover(); if (active) beginRecovery() }
    }
    private fun beginRecovery(network: Boolean = false) {
        recoveryIsNetwork = recoveryIsNetwork || network
        recoveryHasDecodeFailure = recoveryHasDecodeFailure || !network
        if (recoveryDeadline != null) return
        recover()
        recoveryDeadline = timers.schedule({ synchronized(lock) {
            if (valid()) {
                if (recoveryIsNetwork || System.nanoTime() - lastFrameNs < 2_000_000_000L) fail("recovery-timeout")
                else { recoveryDeadline = null; recoveryIsNetwork = false; recoveryHasDecodeFailure = false }
            }
        } }, 10, TimeUnit.SECONDS)
    }

    fun receive(json: JSONObject): Unit = synchronized(lock) {
        if (!valid() || !validIdentity(json, streamId) || json.optString("negotiationId") != negotiationId) return@synchronized
        try {
            when (json.getString("type")) {
                "rtcAnswer" -> {
                    if (!offered || answered) return@synchronized
                    val sdp = json.getString("sdp")
                    if (!validSdp(sdp, offer = false) || !compatibleAnswer(sdp, codec, sourceFmtp)) { fail("unsupported-codec"); return@synchronized }
                    peer?.answer(sdp)
                    answered = true
                    remoteCandidates.forEach { peer?.candidate(it.first, it.second) }
                    remoteCandidates.clear()
                }
                "rtcCandidate" -> {
                    val candidate = json.getString("candidate")
                    val mid = json.getString("sdpMid")
                    if (!validCandidate(candidate, mid) || json.getInt("sdpMLineIndex") != 0 || ++candidatesIn > 16) {
                        fail("invalid-candidate"); return@synchronized
                    }
                    if (candidate.isNotEmpty()) {
                        if (answered) peer?.candidate(candidate, mid) else remoteCandidates.add(candidate to mid)
                    }
                }
                "rtcReady" -> {
                    if (!answered || active) return@synchronized
                    active = true
                    deadline?.cancel(false); deadline = null
                    emit(stateMessage("active", "first-frame-presented"), resetVideo = true)
                }
                "rtcStop" -> {
                    val reason = json.getString("reason")
                    fail(if (reason in stopReasons) reason else "negotiation-failed")
                }
                "rtcStats" -> receiveStats(json)
            }
        } catch (_: Exception) { fail("invalid-signaling") }
    }

    private fun receiveStats(json: JSONObject) {
        val now = System.nanoTime()
        if (now - lastStatsNs < 900_000_000L) return
        val id = safeInteger(json, "reportId") ?: return
        if (id <= lastReportId) return
        val timestamp = json.optDouble("timestampMs", Double.NaN)
        if (!timestamp.isFinite() || timestamp < 0) return
        for (key in listOf("framesDecoded", "framesPresented", "packetsReceived", "bytesReceived")) {
            if (json.has(key) && safeInteger(json, key) == null) return
        }
        for (key in listOf("jitterMs", "roundTripTimeMs")) {
            if (json.has(key) && (!json.optDouble(key).isFinite() || json.optDouble(key) < 0)) return
        }
        if (json.has("packetsLost")) {
            val lost = json.opt("packetsLost") as? Number ?: return
            val value = lost.toDouble()
            if (!value.isFinite() || value != lost.toLong().toDouble() || kotlin.math.abs(value) > 9_007_199_254_740_991.0) return
        }
        lastReportId = id; lastStatsNs = now
        val presented = safeInteger(json, "framesPresented") ?: safeInteger(json, "framesDecoded") ?: return
        if (lastPresented >= 0 && presented > lastPresented) {
            recoveryDeadline?.cancel(false); recoveryDeadline = null; recoveryIsNetwork = false; recoveryHasDecodeFailure = false
        } else if (lastPresented >= 0 && active && framesSinceProgress) beginRecovery()
        lastPresented = presented
        framesSinceProgress = false
    }

    fun frame(bytes: ByteArray, timestampUs: Long, key: Boolean) {
        if (!valid()) return
        lastFrameNs = System.nanoTime()
        if (waitingKey && !key) { recover(); if (active) beginRecovery(); return }
        val accepted = peer?.send(bytes, timestampUs, key) == true
        waitingKey = !accepted
        if (!accepted) { recover(); if (active) beginRecovery(); return }
        framesSinceProgress = true
    }
    fun close(): Unit = synchronized(lock) {
        if (closed) return@synchronized
        closed = true; active = false
        deadline?.cancel(false); recoveryDeadline?.cancel(false)
        pendingCandidates.clear(); remoteCandidates.clear()
        peer?.close(); peer = null
    }

    companion object {
        private val timers = Executors.newSingleThreadScheduledExecutor { Thread(it, "Browser-RTC-deadlines").apply { isDaemon = true } }
        private val stopReasons = setOf("user-selected-wss", "unsupported-codec", "negotiation-failed", "first-frame-timeout", "connection-failed", "recovery-timeout", "render-failed")
        private fun safeInteger(json: JSONObject, key: String): Long? {
            val value = json.opt(key) as? Number ?: return null
            val d = value.toDouble(); val n = value.toLong()
            return n.takeIf { d.isFinite() && d == n.toDouble() && n in 0..9_007_199_254_740_991L }
        }
        fun validIdentity(json: JSONObject, streamId: Long): Boolean =
            safeInteger(json, "streamId") == streamId && json.opt("negotiationId") is String && Regex("[A-Za-z0-9_-]{1,64}").matches(json.optString("negotiationId"))
        internal fun validCandidate(candidate: String, mid: String) =
            candidate.toByteArray(Charsets.UTF_8).size <= 1024 && mid.toByteArray(Charsets.UTF_8).size <= 32 &&
                !candidate.contains('\r') && !candidate.contains('\n') && !mid.contains('\r') && !mid.contains('\n')
        internal fun validSdp(sdp: String, offer: Boolean): Boolean {
            if (sdp.toByteArray(Charsets.UTF_8).size > 6144) return false
            val lines = sdp.lineSequence().map { it.trimEnd('\r') }.toList()
            return lines.count { it.startsWith("m=") } == 1 && lines.any { it.startsWith("m=video ") } &&
                lines.contains("a=rtcp-mux") && lines.contains(if (offer) "a=sendonly" else "a=recvonly") &&
                lines.any { it.startsWith("a=fingerprint:sha-256 ") }
        }
        /** Fail closed on unknown codec/profile negotiation; never change the source codec. */
        internal fun compatibleAnswer(sdp: String, codec: String, sourceFmtp: String): Boolean {
            val lines = sdp.lineSequence().map { it.trimEnd('\r') }.toList()
            val media = lines.singleOrNull { it.startsWith("m=") }?.split(Regex("\\s+")) ?: return false
            if (media.size < 4 || media[0] != "m=video" || media[1] == "0" || "96" !in media.drop(3)) return false
            val expected = if (codec == "h264") "H264/90000" else if (codec == "h265") "H265/90000" else return false
            if (lines.count { it.startsWith("a=rtpmap:96 ") } != 1 ||
                lines.none { it.equals("a=rtpmap:96 $expected", ignoreCase = true) }) return false
            fun parameters(text: String): Map<String, String> = text.split(';').mapNotNull {
                val pair = it.trim().split('=', limit = 2)
                if (pair.size == 2) pair[0].lowercase() to pair[1].lowercase() else null
            }.toMap()
            val descriptions = lines.filter { it.startsWith("a=fmtp:96 ") }
            if (descriptions.size != 1) return false
            val actual = parameters(descriptions.single().removePrefix("a=fmtp:96 "))
            val source = parameters(sourceFmtp)
            return if (codec == "h264") {
                val offered = source["profile-level-id"] ?: return false
                val accepted = actual["profile-level-id"] ?: return false
                actual["packetization-mode"] == "1" && offered.length == 6 && accepted.length == 6 &&
                    offered.take(4) == accepted.take(4) &&
                    (accepted.takeLast(2).toIntOrNull(16) ?: -1) >= (offered.takeLast(2).toIntOrNull(16) ?: 256)
            } else {
                (actual["profile-space"] ?: "0") == (source["profile-space"] ?: "0") &&
                    (actual["profile-id"] ?: "1") == source["profile-id"] &&
                    (actual["tier-flag"] ?: "0") == source["tier-flag"] &&
                    (actual["level-id"]?.toIntOrNull() ?: -1) >= (source["level-id"]?.toIntOrNull() ?: 256)
            }
        }

        /** Enumerate real interfaces, ranking the approved peer's on-link route before its WSS alias.
         * No hard-coded private ranges: hotspot, Bluetooth PAN, CGNAT and scoped IPv6 are valid.
         * Cellular interfaces remain excluded. Native bind fails closed if an interface disappears.
         */
        fun lanAddresses(route: Pair<java.net.InetAddress, java.net.InetAddress>? = null): List<String> = runCatching {
            val local = route?.first
            val peer = route?.second
            // UDP connect asks the kernel for a route without sending any application packet.
            val routed = peer?.let { destination -> runCatching {
                java.net.DatagramSocket().use { socket -> socket.connect(destination, 9); socket.localAddress }
            }.getOrNull() }
            NetworkInterface.getNetworkInterfaces().toList().filter {
                it.isUp && !it.isLoopback &&
                    !Regex("^(rmnet|ccmni|pdp|wwan)").containsMatchIn(it.name)
            }.flatMap { iface -> iface.interfaceAddresses.mapNotNull { entry ->
                val address = entry.address ?: return@mapNotNull null
                if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) return@mapNotNull null
                val scopeMatches = (peer as? java.net.Inet6Address)?.scopedInterface?.index?.let { it == iface.index } ?: true
                val onLink = peer != null && scopeMatches && sameSubnet(address.address, peer.address, entry.networkPrefixLength.toInt())
                val physical = Regex("^(wlan|wifi|ap|swlan|eth|rndis|bnep|bt)").containsMatchIn(iface.name)
                val rank = when { address == routed -> 0; onLink -> 1; address == local -> 2; physical -> 3; !iface.isPointToPoint -> 4; else -> 5 }
                rank to address.hostAddress
            } }.sortedBy { it.first }.mapNotNull { it.second }.distinct().take(16)
        }.getOrDefault(emptyList())

        internal fun sameSubnet(local: ByteArray, peer: ByteArray, prefix: Int): Boolean {
            if (local.size != peer.size || prefix !in 1..local.size * 8) return false
            val full = prefix / 8
            if ((0 until full).any { local[it] != peer[it] }) return false
            val tail = prefix % 8
            if (tail == 0) return true
            val mask = (0xff shl (8 - tail)) and 0xff
            return (local[full].toInt() and mask) == (peer[full].toInt() and mask)
        }
    }
}
