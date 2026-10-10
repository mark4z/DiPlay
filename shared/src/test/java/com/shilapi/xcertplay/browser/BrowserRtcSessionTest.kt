package com.shilapi.xcertplay.browser

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BrowserRtcSessionTest {
    private class Fake : BrowserRtcPeer, BrowserRtcPeer.Factory {
        override val available = true
        lateinit var listener: BrowserRtcPeer.Listener
        val operations = mutableListOf<String>()
        override fun create(config: BrowserRtcConfig, listener: BrowserRtcPeer.Listener): BrowserRtcPeer {
            this.listener = listener; return this
        }
        override fun start() { operations.add("start") }
        override fun answer(sdp: String) { operations.add("answer") }
        override fun candidate(candidate: String, mid: String) { operations.add("candidate") }
        override fun send(frame: ByteArray, timestampUs: Long, key: Boolean): Boolean {
            operations.add(if (key) "key" else "delta"); return true
        }
        override fun close() { operations.add("close") }
    }
    private class Fixture {
        val peer = Fake()
        val sent = mutableListOf<JSONObject>()
        val resets = mutableListOf<Boolean>()
        val failures = mutableListOf<String>()
        var current = true
        var recoveries = 0
        val session = BrowserRtcSession(Any(), 42, "attempt_1", "h265", { current },
            { text, reset -> sent.add(JSONObject(text)); resets.add(reset); true }, { recoveries++ },
            { owner, reason -> failures.add(reason); owner.close() })
        init { session.start(peer, BrowserRtcConfig("h265", "profile-id=1;tier-flag=0;level-id=120", listOf("192.168.1.2"))) }
        fun message(type: String, id: String = "attempt_1") = JSONObject().put("type", type).put("streamId", 42).put("negotiationId", id)
        fun answer() { peer.listener.offer(sdp(true)); session.receive(message("rtcAnswer").put("sdp", sdp(false))) }
    }
    @Test fun onlyPresentedFrameAcknowledgementStopsWss() {
        val f = Fixture()
        f.answer(); f.peer.listener.state("connected")
        assertFalse(f.session.active)
        f.session.receive(f.message("rtcReady"))
        assertTrue(f.session.active)
        assertEquals("active", f.sent.last().getString("state"))
        assertTrue(f.resets.last())
        f.session.close()
    }
    @Test fun reconnectedStaticSourceCancelsOnlyNetworkRecoveryDeadline() {
        val f = Fixture(); f.answer(); f.session.receive(f.message("rtcReady"))
        val deadline = BrowserRtcSession::class.java.getDeclaredField("recoveryDeadline").apply { isAccessible = true }
        f.peer.listener.state("disconnected")
        assertNotNull(deadline.get(f.session))
        f.peer.listener.state("connected")
        assertNull(deadline.get(f.session))
        assertTrue(f.session.active)
        assertTrue(f.recoveries >= 2)
        f.peer.listener.requestKeyframe() // Independent decoder recovery survives a network reconnect.
        f.peer.listener.state("disconnected"); f.peer.listener.state("connected")
        assertNotNull(deadline.get(f.session))
        f.session.close()
    }
    @Test fun staleViewerAndNegotiationCallbacksCannotActivateOrRecover() {
        val f = Fixture(); f.answer()
        f.session.receive(f.message("rtcReady", "old")); assertFalse(f.session.active)
        f.current = false
        f.peer.listener.requestKeyframe(); f.peer.listener.state("failed")
        f.session.receive(f.message("rtcReady"))
        assertEquals(0, f.recoveries); assertTrue(f.failures.isEmpty()); assertFalse(f.session.active)
        f.session.close()
    }
    @Test fun candidatesAreBoundedAndOrderedAfterOfferAndAnswer() {
        val f = Fixture()
        f.peer.listener.candidate("candidate:1", "0")
        assertEquals(1, f.sent.size)
        f.session.receive(f.message("rtcCandidate").put("candidate", "candidate:2").put("sdpMid", "0").put("sdpMLineIndex", 0))
        assertEquals(listOf("start"), f.peer.operations)
        f.answer()
        assertEquals(listOf("rtcState", "rtcOffer", "rtcCandidate"), f.sent.map { it.getString("type") })
        assertEquals(listOf("start", "answer", "candidate"), f.peer.operations)
        repeat(16) { f.peer.listener.candidate("candidate:$it", "0") }
        assertEquals(listOf("signaling-too-large"), f.failures)
    }
    @Test fun closeSuppressesLateEventsAndFrames() {
        val f = Fixture(); f.session.close()
        f.peer.listener.offer(sdp(true)); f.peer.listener.state("failed")
        f.session.frame(byteArrayOf(1), 1, true)
        assertEquals(listOf("start", "close"), f.peer.operations)
        assertEquals(1, f.sent.size)
    }
    @Test fun freshPeerNeedsKeyframeAndNeverTreatsEnqueueAsRendered() {
        val f = Fixture()
        f.session.frame(byteArrayOf(1), 1, false)
        assertEquals(1, f.recoveries)
        f.session.frame(byteArrayOf(1), 2, true)
        f.session.frame(byteArrayOf(1), 3, false)
        assertEquals(listOf("start", "key", "delta"), f.peer.operations)
        assertFalse(f.session.active); f.session.close()
    }
    @Test fun actualProfileAndLevelGateNeverChangesSourceCodec() {
        assertTrue(BrowserRtcSession.compatibleAnswer(sdp(false), "h265", "profile-id=1;tier-flag=0;level-id=120"))
        assertFalse(BrowserRtcSession.compatibleAnswer(sdp(false), "h264", "profile-level-id=64002a;packetization-mode=1"))
        assertFalse(BrowserRtcSession.compatibleAnswer(sdp(false), "h265", "profile-id=2;tier-flag=0;level-id=120"))
        assertFalse(BrowserRtcSession.compatibleAnswer(sdp(false), "h265", "profile-id=1;tier-flag=0;level-id=150"))
    }
    @Test fun realInterfacePrefixMatchingIncludesCgnatAndIpv6() {
        assertTrue(BrowserRtcSession.sameSubnet(byteArrayOf(100, 99, 9, 9), byteArrayOf(100, 99, 9, 10), 24))
        assertFalse(BrowserRtcSession.sameSubnet(byteArrayOf(100, 99, 9, 9), byteArrayOf(100, 99, 9, 10), 32))
        val a = ByteArray(16).apply { this[0] = 0xfe.toByte(); this[1] = 0x80.toByte(); this[15] = 1 }
        val b = a.copyOf().apply { this[15] = 2 }
        assertTrue(BrowserRtcSession.sameSubnet(a, b, 64))
        assertFalse(BrowserRtcSession.sameSubnet(a, byteArrayOf(1, 2, 3, 4), 24))
        assertFalse(BrowserRtcSession.sameSubnet(a, b, 0))
    }
    @Test fun malformedIdentitySdpAndCandidateAreRejected() {
        assertFalse(BrowserRtcSession.validIdentity(JSONObject().put("streamId", 42.5).put("negotiationId", "a"), 42))
        assertFalse(BrowserRtcSession.validIdentity(JSONObject().put("streamId", 42).put("negotiationId", "a\nb"), 42))
        assertFalse(BrowserRtcSession.validSdp(sdp(true) + "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n", true))
        assertFalse(BrowserRtcSession.validSdp(sdp(true) + "a=x:" + "x".repeat(6144), true))
        assertFalse(BrowserRtcSession.validCandidate("candidate:1\r\na=evil", "0"))
        assertFalse(BrowserRtcSession.validCandidate("x".repeat(1025), "0"))
    }
    companion object {
        private fun sdp(offer: Boolean) = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=rtcp-mux\r\na=rtpmap:96 H265/90000\r\na=fmtp:96 profile-id=1;tier-flag=0;level-id=120\r\na=${if (offer) "sendonly" else "recvonly"}\r\na=fingerprint:sha-256 AA\r\n"
    }
}
