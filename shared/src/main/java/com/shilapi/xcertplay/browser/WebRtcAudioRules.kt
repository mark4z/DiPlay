package com.shilapi.xcertplay.browser

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Pure policy shared by the peer and its tests. No microphone or platform audio access. */
internal object WebRtcAudioRules {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    const val FRAME_BYTES = SAMPLE_RATE / 100 * CHANNELS * 2
    const val MAX_SDP_CHARS = 6_000
    const val MAX_CANDIDATE_CHARS = 1_024
    const val MAX_CANDIDATES = 32
    private val silence = ByteArray(FRAME_BYTES)

    private val candidatePattern = Regex(
        """candidate:[A-Za-z0-9+/]{1,64} 1 (?:udp|tcp) \d{1,10} [A-Za-z0-9.:-]{1,253} \d{1,5} typ (?:host|srflx|prflx|relay)(?: [A-Za-z0-9.:%_+/-]+ [A-Za-z0-9.:%_+/-]+)*""",
        RegexOption.IGNORE_CASE,
    )
    private val candidateMidPattern = Regex("[A-Za-z0-9_-]{1,32}")
    private val audioMediaPattern = Regex("""m=audio [1-9]\d* UDP/TLS/RTP/SAVPF \d+(?: \d+)*""")
    private val fingerprintPattern = Regex(
        """a=fingerprint:sha-256 (?:[0-9a-f]{2}:){31}[0-9a-f]{2}""", RegexOption.IGNORE_CASE,
    )
    private val opusPattern = Regex("""a=rtpmap:(\d+) opus/48000/2""", RegexOption.IGNORE_CASE)

    /** Validate bounded ICE syntax, leaving address reachability and pair selection to WebRTC. */
    fun isValidCandidate(candidate: String): Boolean {
        if (candidate.length !in 1..MAX_CANDIDATE_CHARS || !candidatePattern.matches(candidate)) return false
        val fields = candidate.split(' ')
        return fields[3].toLongOrNull()?.let { it in 0..0xffff_ffffL } == true &&
            fields[5].toIntOrNull()?.let { it in 1..65535 } == true && isValidIceAddress(fields[4])
    }

    fun isValidCandidateMid(mid: String?): Boolean = mid != null && candidateMidPattern.matches(mid)

    private fun isValidIceAddress(address: String): Boolean {
        if (address.endsWith(".local", ignoreCase = true)) {
            return address.length <= 253 && address.split('.').all { label ->
                label.length in 1..63 && label.first().isLetterOrDigit() &&
                    label.last().isLetterOrDigit() && label.all { it.isLetterOrDigit() || it == '-' }
            }
        }
        if (':' in address) {
            // Literal-only parsing: never resolve an arbitrary hostname or scoped interface name.
            // Public, CGNAT, private, link-local and mapped addresses are not topology policy.
            if (address.any { it !in "0123456789abcdefABCDEF:." }) return false
            return try { InetAddress.getByName(address); true } catch (_: Exception) { false }
        }
        val components = address.split('.')
        if (components.size != 4) return false
        return components.all { value ->
            val number = value.toIntOrNull()
            number != null && number in 0..255 && value == number.toString()
        }
    }

    fun isReceiveOnlyAnswer(sdp: String): Boolean = isSingleAudioDescription(sdp, "recvonly")

    fun isSendOnlyOffer(sdp: String): Boolean = isSingleAudioDescription(sdp, "sendonly")

    private fun isSingleAudioDescription(sdp: String, direction: String): Boolean {
        if (sdp.length !in 1..MAX_SDP_CHARS ||
            sdp.any { it != '\r' && it != '\n' && it.code !in 32..126 }) return false
        val lines = sdp.lineSequence().filter { it.isNotEmpty() }.toList()
        if (lines.any { it.length > MAX_CANDIDATE_CHARS }) return false
        val media = lines.filter { it.startsWith("m=") }
        if (lines.firstOrNull() != "v=0" || media.size != 1 || !audioMediaPattern.matches(media.single())) return false
        val directions = setOf("a=recvonly", "a=sendonly", "a=sendrecv", "a=inactive")
        if (lines.filter { it in directions } != listOf("a=$direction") || "a=rtcp-mux" !in lines ||
            lines.none { fingerprintPattern.matches(it) } || lines.any { it.startsWith("a=crypto:") }) return false
        val opus = lines.firstNotNullOfOrNull { opusPattern.matchEntire(it) } ?: return false
        if (opus.groupValues[1] !in media.single().split(' ').drop(3)) return false
        return lines.filter { it.startsWith("a=candidate:") }.let { candidates ->
            candidates.size <= MAX_CANDIDATES && candidates.all { isValidCandidate(it.removePrefix("a=")) }
        }
    }

    /** The provider must not retain this buffer or block. Unwritten samples remain silent. */
    fun writePcm(buffer: ByteBuffer, fill: (ByteBuffer) -> Unit) {
        require(buffer.capacity() == FRAME_BYTES)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.clear()
        buffer.put(silence)
        buffer.clear()
        try {
            fill(buffer)
        } catch (failure: Exception) {
            buffer.clear()
            buffer.put(silence)
            throw failure
        } finally {
            buffer.clear()
        }
    }
}

/** WebRTC's disabled-AudioRecord branch has no blocking read: we must supply the 10 ms clock. */
internal class WebRtcAudioPacer(
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleepNanos: (Long) -> Unit = { TimeUnit.NANOSECONDS.sleep(it) },
) {
    private var nextTick: Long? = null

    @Throws(InterruptedException::class)
    fun awaitTick(): Long {
        var now = nanoTime()
        val target = nextTick ?: now
        while (target - now > 0) {
            sleepNanos(target - now)
            now = nanoTime()
        }
        // Never emit a burst to catch up after a stalled source/scheduler.
        nextTick = if (now - target >= PERIOD_NANOS) now + PERIOD_NANOS else target + PERIOD_NANOS
        return now
    }

    companion object { const val PERIOD_NANOS = 10_000_000L }
}

