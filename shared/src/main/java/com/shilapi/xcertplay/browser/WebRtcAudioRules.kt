package com.shilapi.xcertplay.browser

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.InetAddress
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

    fun isHostCandidate(candidate: String): Boolean {
        if (candidate.length !in 1..MAX_CANDIDATE_CHARS ||
            candidate.any { it.code !in 32..126 }) return false
        val fields = candidate.split(' ').filter { it.isNotEmpty() }
        return fields.size >= 8 && fields[0].startsWith("candidate:") &&
            fields[1] == "1" && fields[2].lowercase() in setOf("udp", "tcp") &&
            fields[6] == "typ" && fields[7] == "host" &&
            fields[5].toIntOrNull()?.let { it in 1..65535 } == true && isLocalAddress(fields[4])
    }

    private fun isLocalAddress(address: String): Boolean {
        if (address.endsWith(".local", ignoreCase = true)) {
            return address.length <= 253 && address.split('.').all { label ->
                label.length in 1..63 && label.first().isLetterOrDigit() &&
                    label.last().isLetterOrDigit() && label.all { it.isLetterOrDigit() || it == '-' }
            }
        }
        if (':' in address) {
            // Literal-only parsing: never resolve an arbitrary hostname or scoped interface name.
            if (address.any { it !in "0123456789abcdefABCDEF:." }) return false
            val parsed = try { InetAddress.getByName(address) } catch (_: Exception) { return false }
            return parsed.isLoopbackAddress || parsed.isLinkLocalAddress ||
                (parsed.address.size == 16 && (parsed.address[0].toInt() and 0xfe) == 0xfc) ||
                (parsed.address.size == 4 && isLocalAddress(parsed.hostAddress.orEmpty()))
        }
        val components = address.split('.')
        if (components.size != 4) return false
        val octets = components.map { value ->
            val number = value.toIntOrNull() ?: return false
            if (number !in 0..255 || value != number.toString()) return false
            number
        }
        return octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 169 && octets[1] == 254)
    }

    fun isReceiveOnlyAnswer(sdp: String): Boolean = isSingleAudioDescription(sdp, "recvonly")

    fun isSendOnlyOffer(sdp: String): Boolean = isSingleAudioDescription(sdp, "sendonly")

    private fun isSingleAudioDescription(sdp: String, direction: String): Boolean {
        if (sdp.length !in 1..MAX_SDP_CHARS ||
            sdp.any { it != '\r' && it != '\n' && it.code !in 32..126 }) return false
        val lines = sdp.lineSequence().filter { it.isNotEmpty() }.toList()
        val media = lines.filter { it.startsWith("m=") }
        val directions = setOf("a=recvonly", "a=sendonly", "a=sendrecv", "a=inactive")
        return lines.firstOrNull() == "v=0" && media.size == 1 &&
            media.single().startsWith("m=audio ") && !media.single().startsWith("m=audio 0 ") &&
            lines.filter { it in directions } == listOf("a=$direction") &&
            lines.filter { it.startsWith("a=candidate:") }.let { candidates ->
                candidates.size <= MAX_CANDIDATES && candidates.all { isHostCandidate(it.removePrefix("a=")) }
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
