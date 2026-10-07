package com.shilapi.xcertplay.browser

import com.shilapi.xcertplay.airplay.DecodedAudioFormat
import java.nio.ByteBuffer

/** Self-describing downlink PCM envelope. Metadata is big endian, PCM payload is signed 16-bit LE. */
internal object BrowserAudioPacket {
    const val HEADER_BYTES = 36
    const val MAX_FRAMES = 4096
    const val MAX_BYTES = HEADER_BYTES + MAX_FRAMES * 4

    fun encode(epoch: Int, stream: Int, format: DecodedAudioFormat, firstSample: Long,
               pcm: ByteArray, offset: Int, length: Int, gain: Float): ByteArray {
        require(epoch > 0 && stream > 0 && format.supported && firstSample >= 0)
        val frameBytes = format.channels * 2
        require(offset >= 0 && length > 0 && offset <= pcm.size - length)
        require(length % frameBytes == 0 && length / frameBytes <= MAX_FRAMES)
        require(gain.isFinite() && gain in 0f..1f)
        return ByteBuffer.allocate(HEADER_BYTES + length)
            .put(3.toByte()).put(1.toByte()).put(1.toByte()).put(format.channels.toByte())
            .putInt(epoch).putInt(stream).putInt(format.sampleRate).putLong(firstSample)
            .putInt(length / frameBytes).putFloat(gain).putInt(0)
            .put(pcm, offset, length).array()
    }
}
