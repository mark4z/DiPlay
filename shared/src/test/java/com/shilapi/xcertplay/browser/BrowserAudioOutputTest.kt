package com.shilapi.xcertplay.browser

import com.shilapi.xcertplay.airplay.*
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BrowserAudioOutputTest {
    private class Source : MediaSink {
        var output: DecodedAudioOutput? = null
        var native = true
        override fun setDecodedAudioOutput(output: DecodedAudioOutput?): Boolean {
            this.output = output; if (output == null) native = true; return true
        }
        override fun setNativeAudioEnabled(enabled: Boolean) { native = enabled }
    }
    private class Peer(val fill: (ByteBuffer) -> Unit, val callbacks: BrowserAudioPeerCallbacks,
                       val emitOffer: Boolean = true) : BrowserAudioPeer {
        var closed = false
        var answers = 0
        var candidates = 0
        override fun start() { if (emitOffer) callbacks.offer("test-offer") }
        override fun answer(sdp: String) { answers++ }
        override fun addIce(candidate: String, mid: String?, index: Int) { candidates++ }
        override fun close() { closed = true }
    }
    private val originalFactory = BrowserAudioOutput.peerFactory
    private var clock = 1_000_000_000L
    private val messages = mutableListOf<JSONObject>()
    private lateinit var peer: Peer
    private lateinit var source: Source
    @Before fun setup() {
        BrowserAudioOutput.disconnect()
        BrowserAudioOutput.nowNs = { clock }
        BrowserAudioOutput.peerFactory = { _, fill, cb -> Peer(fill, cb).also { peer = it } }
        source = Source(); BrowserAudioOutput.attach(source)
        BrowserAudioOutput.connect { messages.add(JSONObject(it)); true }
    }
    @After fun cleanup() {
        BrowserAudioOutput.disconnect()
        BrowserAudioOutput.attach(object : MediaSink {})
        BrowserAudioOutput.peerFactory = originalFactory
        BrowserAudioOutput.nowNs = System::nanoTime
    }
    private fun start(test: Boolean = false, request: Long = 1) {
        BrowserAudioOutput.setEnabled(true, request, BrowserAudioOutput.TRANSPORT, test)
    }
    private fun signal(type: String, request: Long = 1, epoch: Int = messages.last { it.optString("type") == "audioOffer" }.getInt("epoch")) =
        JSONObject().put("type", type).put("transport", BrowserAudioOutput.TRANSPORT).put("requestId", request).put("epoch", epoch)
    private fun ready() {
        BrowserAudioOutput.receive(signal("audioAnswer").put("sdp", "test-answer"))
        peer.callbacks.connected(true)
        BrowserAudioOutput.receive(signal("audioReady"))
    }

    @Test fun slowNegotiationDoesNotQueueSourceBeforeCaptureClockStarts() {
        start()
        val format = DecodedAudioFormat(48_000, 2)
        val bytes = ByteArray(48_000 * 4)
        repeat(3) { index -> source.output!!.pcm(1, AudioStreamId(100, "media"), format,
            index * 48_000L, bytes, 0, bytes.size, 1f) }
        val buffer = ByteBuffer.allocate(1920).order(ByteOrder.LITTLE_ENDIAN)
        peer.fill(buffer) // Capture clock begins; earlier audio remained on native output.
        source.output!!.pcm(1, AudioStreamId(100, "media"), format, 144_000,
            ByteArray(1920), 0, 1920, 1f)
        BrowserAudioOutput.checkDeadline()
        assertFalse(peer.closed); assertTrue(source.native)
        ready(); assertFalse(source.native)
    }

    @Test fun nativeRemainsUntilAnsweredConnectedAndBrowserPlaybackReady() {
        start(); assertTrue(source.native)
        BrowserAudioOutput.receive(signal("audioReady")); assertTrue(source.native)
        BrowserAudioOutput.receive(signal("audioAnswer").put("sdp", "test-answer"))
        BrowserAudioOutput.receive(signal("audioReady")); assertTrue(source.native)
        peer.callbacks.connected(true); assertFalse(source.native)
        assertTrue(messages.last().getBoolean("enabled"))
        BrowserAudioOutput.disconnect(); assertTrue(source.native); assertTrue(peer.closed)
    }
    @Test fun readinessAckMustBeSuccessfullyQueuedBeforeMute() {
        BrowserAudioOutput.connect {
            val msg = JSONObject(it); messages.add(msg)
            if (msg.optBoolean("enabled")) { assertTrue(source.native); false } else true
        }
        start(); ready(); assertTrue(source.native); assertTrue(peer.closed)
    }
    @Test fun reentrantDisconnectFromAckCannotMute() {
        BrowserAudioOutput.connect {
            val msg = JSONObject(it); messages.add(msg)
            if (msg.optBoolean("enabled")) BrowserAudioOutput.disconnect()
            true
        }
        start(); ready(); assertTrue(source.native); assertTrue(peer.closed)
    }
    @Test fun connectionLossRestoresNativeImmediately() {
        start(); ready(); assertFalse(source.native)
        peer.callbacks.connected(false); assertTrue(source.native); assertTrue(peer.closed)
        assertEquals("audio-connection-lost", messages.last().getString("code"))
    }
    @Test fun noBrowserHeartbeatRestoresNative() {
        start(); ready(); clock += 4_000_000_001L
        BrowserAudioOutput.checkDeadline(); assertTrue(source.native); assertTrue(peer.closed)
    }
    @Test fun validHeartbeatExtendsOnlyActiveMatchingRoute() {
        start(); ready(); clock += 3_000_000_000L
        BrowserAudioOutput.receive(signal("audioAlive")); clock += 3_000_000_000L
        BrowserAudioOutput.checkDeadline(); assertFalse(source.native)
        BrowserAudioOutput.receive(signal("audioAlive", request = 2)); clock += 2_000_000_000L
        BrowserAudioOutput.checkDeadline(); assertTrue(source.native)
    }
    @Test fun negotiationDeadlineClosesPeerWithoutMutingNative() {
        start(); clock += 15_000_000_001L; BrowserAudioOutput.checkDeadline()
        assertTrue(source.native); assertTrue(peer.closed)
        assertEquals("audio-answer-timeout", messages.last().getString("code"))
    }
    @Test fun setupDeadlineDistinguishesMissingOffer() {
        BrowserAudioOutput.peerFactory = { _, fill, cb -> Peer(fill, cb, emitOffer = false).also { peer = it } }
        start(); clock += 15_000_000_001L; BrowserAudioOutput.checkDeadline()
        assertEquals("audio-offer-timeout", messages.last().getString("code"))
        assertTrue(source.native); assertTrue(peer.closed)
    }
    @Test fun setupDeadlineDistinguishesIceFromCaptureAndBrowserPlayback() {
        for ((index, expected) in listOf("audio-ice-timeout", "audio-capture-timeout", "audio-readiness-timeout").withIndex()) {
            val request = index + 1L
            start(request = request)
            BrowserAudioOutput.receive(signal("audioAnswer", request).put("sdp", "test-answer"))
            if (index >= 1) peer.callbacks.connected(true)
            if (index >= 2) peer.fill(ByteBuffer.allocate(1920))
            clock += 15_000_000_001L; BrowserAudioOutput.checkDeadline()
            assertEquals(expected, messages.last().getString("code"))
            assertTrue(source.native); assertTrue(peer.closed)
        }
    }
    @Test fun setupTimeoutCannotBeExtendedByPrematureAliveMessages() {
        start()
        BrowserAudioOutput.receive(signal("audioAnswer").put("sdp", "test-answer"))
        repeat(3) { clock += 4_000_000_000L; BrowserAudioOutput.receive(signal("audioAlive")) }
        clock += 3_000_000_001L; BrowserAudioOutput.checkDeadline()
        assertEquals("audio-ice-timeout", messages.last().getString("code"))
        assertTrue(source.native); assertTrue(peer.closed)
    }
    @Test fun sourceReplacementRejectsRetiredCallbacksAndReadiness() {
        start(); val retired = peer; val stale = signal("audioReady")
        val replacement = Source(); BrowserAudioOutput.attach(replacement)
        assertTrue(source.native); assertTrue(retired.closed); assertNull(source.output)
        BrowserAudioOutput.receive(stale); retired.callbacks.connected(true)
        assertTrue(replacement.native)
    }
    @Test fun oldTransportIsRejectedWithoutCreatingPeer() {
        BrowserAudioOutput.setEnabled(true, 1)
        assertTrue(source.native)
        assertEquals("audio-upgrade-required", messages.last().getString("code"))
        assertFalse(this::peer.isInitialized)
    }
    @Test fun unsupportedSourceCannotMuteNative() {
        BrowserAudioOutput.attach(object : MediaSink {}); start()
        assertEquals("audio-source-unavailable", messages.last().getString("code"))
        assertFalse(this::peer.isInitialized)
    }
    @Test fun syntheticToneUsesSamePeerNeverMutesAndEndsAfterThreeSeconds() {
        start(test = true); ready(); assertTrue(source.native)
        val buffer = ByteBuffer.allocate(1920).order(ByteOrder.LITTLE_ENDIAN)
        peer.fill(buffer); assertTrue(buffer.array().any { it.toInt() != 0 })
        clock += 3_000_000_001L; BrowserAudioOutput.checkDeadline()
        assertTrue(peer.closed); assertTrue(source.native)
        assertEquals("test-complete", messages.last().getString("code"))
    }
    @Test fun staleStopCannotTearDownNewerRoute() {
        start(); ready(); BrowserAudioOutput.setEnabled(false, 1)
        clock += 2_000_000_000L; start(request = 2)
        BrowserAudioOutput.setEnabled(false, 1); assertFalse(peer.closed)
        BrowserAudioOutput.setEnabled(false, 2); assertTrue(peer.closed)
    }
    @Test fun duplicateAnswerIsIgnoredAndCandidatesAreBounded() {
        start()
        repeat(2) { BrowserAudioOutput.receive(signal("audioAnswer").put("sdp", "answer")) }
        assertEquals(1, peer.answers)
        repeat(33) { BrowserAudioOutput.receive(signal("audioIce").put("candidate", "host")
            .put("sdpMid", "0").put("sdpMLineIndex", 0)) }
        assertEquals(32, peer.candidates); assertTrue(peer.closed); assertTrue(source.native)
    }
    @Test fun oversizeAnswerFailsClosed() {
        start(); BrowserAudioOutput.receive(signal("audioAnswer").put("sdp", "x".repeat(6001)))
        assertTrue(peer.closed); assertTrue(source.native)
    }
}

