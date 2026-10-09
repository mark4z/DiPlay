package com.shilapi.xcertplay.browser

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WebRtcAudioRulesTest {
    private val candidate = "candidate:123 1 udp 2122260223 192.168.1.3 51234 typ host generation 0"
    private val fingerprint = List(32) { "AB" }.joinToString(":")
    private val answer = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n" +
        "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\nc=IN IP4 0.0.0.0\r\na=mid:0\r\na=recvonly\r\n" +
        "a=rtcp-mux\r\na=ice-ufrag:test\r\na=ice-pwd:testpasswordtestpassword\r\na=setup:active\r\n" +
        "a=fingerprint:sha-256 $fingerprint\r\na=rtpmap:111 opus/48000/2\r\n"

    @Test fun standardCandidateTypesAndAddressRangesDoNotGateConnectivity() {
        for (kind in listOf("host", "srflx", "prflx", "relay")) {
            for (address in listOf("192.168.1.3", "10.0.0.1", "172.16.0.1", "127.0.0.1", "169.254.1.2",
                "8.8.8.8", "172.15.1.2", "172.32.1.2", "100.64.1.2", "0.0.0.0", "255.255.255.255",
                "abc-123.local", "ABC.local", "fe80::1", "fd00::1", "::1", "::", "2606:4700:4700::1111",
                "::ffff:192.0.2.1")) {
                val value = candidate.replace("typ host", "typ $kind").replace("192.168.1.3", address)
                assertTrue("$kind $address", WebRtcAudioRules.isValidCandidate(value))
            }
        }
        assertTrue(WebRtcAudioRules.isValidCandidate(candidate.replace(" udp ", " TCP ") + " tcptype passive"))
        assertTrue(WebRtcAudioRules.isValidCandidate(candidate.replace("2122260223", "4294967295")))
        assertTrue(WebRtcAudioRules.isValidCandidate(candidate.replace("2122260223", "0")))
        assertTrue(WebRtcAudioRules.isValidCandidate(candidate.replace("51234", "65535")))
        assertTrue(WebRtcAudioRules.isValidCandidate(candidate + " raddr 100.64.1.2 rport 4000"))
    }

    @Test fun candidateMediaIdMatchesTheBrowserSignalingEnvelope() {
        for (mid in listOf("0", "audio_1-2", "a".repeat(32))) {
            assertTrue(WebRtcAudioRules.isValidCandidateMid(mid))
        }
        for (mid in listOf(null, "", "a".repeat(33), "audio track", "0\r\na=sendrecv", "0!")) {
            assertFalse(WebRtcAudioRules.isValidCandidateMid(mid))
        }
    }

    @Test fun malformedCandidatesAndAddressesRemainRejected() {
        for (address in listOf("example.com", "a.local.example.com", "-a.local", "a-.local", "a..local",
            "192.168.1.256", "010.1.1.1", "192.168.1", "192.168.-1.1", "fe80::1%wlan0", "2001:::1",
            "gggg::1", "::ffff:999.1.1.1", "a".repeat(64) + ".local")) {
            assertFalse(address, WebRtcAudioRules.isValidCandidate(candidate.replace("192.168.1.3", address)))
        }
        for (value in listOf("", candidate.replace("candidate:123", "candidate:"),
            candidate.replace("candidate:123", "candidate:bad!"),
            candidate.replace(" 1 udp", " 2 udp"), candidate.replace(" udp ", " sctp "),
            candidate.replace("typ host", "typ unknown"), candidate.replace("2122260223", "4294967296"),
            candidate.replace("2122260223", "-1"), candidate.replace("2122260223", "priority"),
            candidate.replace("51234", "0"), candidate.replace("51234", "65536"),
            candidate.replace("51234", "-1"), candidate + " unpaired-extension", candidate + "\u0000",
            "$candidate\r\na=sendrecv", "$candidate " + "x".repeat(1024))) {
            assertFalse(value, WebRtcAudioRules.isValidCandidate(value))
        }
    }

    @Test fun boundedSingleAudioAnswerAllowsAllStandardCandidateTypes() {
        assertTrue(WebRtcAudioRules.isReceiveOnlyAnswer(answer))
        for (kind in listOf("host", "srflx", "prflx", "relay")) {
            val value = candidate.replace("typ host", "typ $kind").replace("192.168.1.3", "8.8.8.8")
            assertTrue(kind, WebRtcAudioRules.isReceiveOnlyAnswer(answer + "a=$value\r\n"))
        }
        assertTrue(WebRtcAudioRules.isReceiveOnlyAnswer(answer + ("a=$candidate\r\n").repeat(32)))
        for (direction in listOf("sendrecv", "sendonly", "inactive")) {
            assertFalse(WebRtcAudioRules.isReceiveOnlyAnswer(answer.replace("recvonly", direction)))
        }
        assertFalse(WebRtcAudioRules.isReceiveOnlyAnswer(answer + "a=recvonly\r\n"))
        assertFalse(WebRtcAudioRules.isReceiveOnlyAnswer(answer + "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n"))
        assertFalse(WebRtcAudioRules.isReceiveOnlyAnswer(answer + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"))
        assertFalse(WebRtcAudioRules.isReceiveOnlyAnswer(answer.replace("m=audio 9 ", "m=audio 0 ")))
        assertFalse(WebRtcAudioRules.isReceiveOnlyAnswer(answer + "a=" + candidate.replace("host", "unknown") + "\r\n"))
        assertFalse(WebRtcAudioRules.isReceiveOnlyAnswer(answer + "a=x:" + "x".repeat(1024)))
        assertFalse(WebRtcAudioRules.isReceiveOnlyAnswer(answer + "a=x:" + "x".repeat(6000)))
        assertFalse(WebRtcAudioRules.isReceiveOnlyAnswer(answer + "\u0000"))
        assertFalse(WebRtcAudioRules.isReceiveOnlyAnswer(answer + ("a=$candidate\r\n").repeat(33)))
    }

    @Test fun dtlsFingerprintRtcpMuxAndNegotiatedStereoOpusRemainRequired() {
        for (sdp in listOf(answer.replace("UDP/TLS/RTP/SAVPF", "RTP/AVP"),
            answer.replace("a=rtcp-mux\r\n", ""),
            answer.replace("a=fingerprint:sha-256 $fingerprint\r\n", ""),
            answer.replace("a=fingerprint:sha-256", "a=fingerprint:sha-1"),
            answer.replace(fingerprint, "AB:CD"), answer.replace(fingerprint, "GG:" + fingerprint.drop(3)),
            answer.replace("opus/48000/2", "PCMU/8000"), answer.replace("opus/48000/2", "opus/48000/1"),
            answer.replace("a=rtpmap:111", "a=rtpmap:112"), answer + "a=crypto:1 AES_CM_128_HMAC_SHA1_80 inline:invalid\r\n")) {
            assertFalse(sdp, WebRtcAudioRules.isReceiveOnlyAnswer(sdp))
            assertFalse(sdp, WebRtcAudioRules.isSendOnlyOffer(sdp.replace("recvonly", "sendonly")))
        }
    }

    @Test fun generatedOfferUsesTheSameSecurityAndCandidateValidation() {
        val offer = answer.replace("recvonly", "sendonly").replace("setup:active", "setup:actpass")
        assertTrue(WebRtcAudioRules.isSendOnlyOffer(offer))
        assertFalse(WebRtcAudioRules.isSendOnlyOffer(answer))
        for (kind in listOf("host", "srflx", "prflx", "relay")) {
            val value = candidate.replace("typ host", "typ $kind").replace("192.168.1.3", "100.64.1.2")
            assertTrue(kind, WebRtcAudioRules.isSendOnlyOffer(offer + "a=$value\r\n"))
        }
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

