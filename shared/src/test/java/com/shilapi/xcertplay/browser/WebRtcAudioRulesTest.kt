package com.shilapi.xcertplay.browser

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WebRtcAudioRulesTest {
    private val candidate = "candidate:123 1 udp 2122260223 192.168.1.3 51234 typ host generation 0"
    private val answer = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n" +
        "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=recvonly\r\na=rtpmap:111 opus/48000/2\r\n"

    @Test fun onlyHostCandidatesAreAllowed() {
        assertTrue(WebRtcAudioRules.isHostCandidate(candidate))
        assertTrue(WebRtcAudioRules.isHostCandidate(candidate.replace("192.168.1.3", "abc-123.local")))
        assertTrue(WebRtcAudioRules.isHostCandidate(candidate.replace("192.168.1.3", "fe80::1")))
        assertTrue(WebRtcAudioRules.isHostCandidate(candidate.replace("192.168.1.3", "fd00::1")))
        for (address in listOf("8.8.8.8", "172.15.1.2", "172.32.1.2", "100.64.1.2",
            "2606:4700:4700::1111", "example.com", "a.local.example.com", "192.168.1.256", "010.1.1.1")) {
            assertFalse(address, WebRtcAudioRules.isHostCandidate(candidate.replace("192.168.1.3", address)))
        }
        for (kind in listOf("srflx", "prflx", "relay")) {
            assertFalse(WebRtcAudioRules.isHostCandidate(candidate.replace("typ host", "typ $kind")))
        }
        assertFalse(WebRtcAudioRules.isHostCandidate(candidate.replace(" 1 udp", " 2 udp")))
        assertFalse(WebRtcAudioRules.isHostCandidate("$candidate\r\na=sendrecv"))
        assertFalse(WebRtcAudioRules.isHostCandidate("$candidate " + "x".repeat(1024)))
    }

    @Test fun answersAreBoundedSingleAudioReceiveOnlyWithoutRelayCandidates() {
        assertTrue(WebRtcAudioRules.isReceiveOnlyAnswer(answer))
        assertTrue(WebRtcAudioRules.isReceiveOnlyAnswer(answer + "a=$candidate\r\n"))
        for (direction in listOf("sendrecv", "sendonly", "inactive")) {
            assertFalse(WebRtcAudioRules.isReceiveOnlyAnswer(answer.replace("recvonly", direction)))
        }
        assertFalse(WebRtcAudioRules.isReceiveOnlyAnswer(answer + "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n"))
        assertFalse(WebRtcAudioRules.isReceiveOnlyAnswer(answer + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"))
        assertFalse(WebRtcAudioRules.isReceiveOnlyAnswer(answer + "a=" + candidate.replace("host", "relay") + "\r\n"))
        assertFalse(WebRtcAudioRules.isReceiveOnlyAnswer(answer.replace("m=audio 9 ", "m=audio 0 ")))
        assertFalse(WebRtcAudioRules.isReceiveOnlyAnswer(answer + "a=" + candidate.replace("192.168.1.3", "8.8.8.8") + "\r\n"))
        assertFalse(WebRtcAudioRules.isReceiveOnlyAnswer(answer + "a=x:" + "x".repeat(6000)))
        assertFalse(WebRtcAudioRules.isReceiveOnlyAnswer(answer + "\u0000"))
        assertFalse(WebRtcAudioRules.isReceiveOnlyAnswer(answer + ("a=$candidate\r\n").repeat(33)))
    }

    @Test fun generatedOfferAlsoRejectsEmbeddedPublicCandidatesAndExtraMedia() {
        val offer = answer.replace("recvonly", "sendonly")
        assertTrue(WebRtcAudioRules.isSendOnlyOffer(offer))
        assertFalse(WebRtcAudioRules.isSendOnlyOffer(answer))
        assertFalse(WebRtcAudioRules.isSendOnlyOffer(offer + "a=" + candidate.replace("192.168.1.3", "8.8.8.8") + "\r\n"))
        assertFalse(WebRtcAudioRules.isSendOnlyOffer(offer + "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n"))
    }

    @Test fun eachOwnedPcmFrameIsLittleEndianAndStartsSilent() {
        val bytes = ByteArray(WebRtcAudioRules.FRAME_BYTES) { 0x7f }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        WebRtcAudioRules.writePcm(buffer) {
            assertEquals(0, it.position())
            assertEquals(1920, it.remaining())
            assertEquals(ByteOrder.LITTLE_ENDIAN, it.order())
            assertTrue(bytes.all { sample -> sample == 0.toByte() })
            it.putShort(0x1234.toShort())
            it.putShort((-2).toShort())
        }
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0xfe.toByte(), 0xff.toByte()), bytes.copyOf(4))
        assertTrue(bytes.drop(4).all { it == 0.toByte() })
        assertEquals(0, buffer.position())
        WebRtcAudioRules.writePcm(buffer) { }
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test fun failedProviderLeavesSilenceInsteadOfAPartialFrame() {
        val buffer = ByteBuffer.allocate(WebRtcAudioRules.FRAME_BYTES)
        assertThrows(IllegalStateException::class.java) {
            WebRtcAudioRules.writePcm(buffer) { it.putInt(123); error("provider failed") }
        }
        assertTrue(buffer.array().all { it == 0.toByte() })
        assertEquals(0, buffer.position())
        assertThrows(IllegalArgumentException::class.java) {
            WebRtcAudioRules.writePcm(ByteBuffer.allocate(8)) { }
        }
    }

    @Test fun cadenceSleepsTenMillisecondsInsteadOfSpinning() {
        var now = 1_000_000L
        val waits = mutableListOf<Long>()
        val pacer = WebRtcAudioPacer({ now }, { waits.add(it); now += it })
        assertEquals(1_000_000L, pacer.awaitTick())
        repeat(100) { pacer.awaitTick() }
        assertEquals(100, waits.size)
        assertTrue(waits.all { it == 10_000_000L })
        assertEquals(1_001_000_000L, now)
    }

    @Test fun schedulerStallRebasesRatherThanSendingACatchupBurst() {
        var now = 0L
        val waits = mutableListOf<Long>()
        val pacer = WebRtcAudioPacer({ now }, { waits.add(it); now += it })
        pacer.awaitTick()
        now = 1_000_000_000L
        assertEquals(now, pacer.awaitTick())
        assertEquals(1_010_000_000L, pacer.awaitTick())
        assertEquals(listOf(10_000_000L), waits)
    }

    @Test fun earlyWakeIsRecheckedAndWaitRemainsInterruptible() {
        var now = 0L
        val waits = mutableListOf<Long>()
        val pacer = WebRtcAudioPacer({ now }, {
            waits.add(it)
            now += if (waits.size == 1) it / 2 else it
        })
        pacer.awaitTick(); pacer.awaitTick()
        assertEquals(listOf(10_000_000L, 5_000_000L), waits)
        val interrupted = WebRtcAudioPacer({ 0L }, { throw InterruptedException() })
        interrupted.awaitTick()
        assertThrows(InterruptedException::class.java) { interrupted.awaitTick() }
    }
}
