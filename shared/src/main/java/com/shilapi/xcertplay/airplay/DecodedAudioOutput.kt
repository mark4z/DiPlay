package com.shilapi.xcertplay.airplay

/** Borrowed, decoded PCM. Consumers must copy before returning and must never do I/O here. */
interface DecodedAudioOutput {
    fun pcm(stream: Long, id: AudioStreamId, format: DecodedAudioFormat, firstSample: Long,
            bytes: ByteArray, offset: Int, length: Int, gain: Float)
    fun stopped(stream: Long, lastSample: Long) {}
}

/** Encoding uses Android's PCM constants; only signed 16-bit little endian is currently exported. */
data class DecodedAudioFormat(val sampleRate: Int, val channels: Int, val encoding: Int = PCM_S16_LE) {
    val supported: Boolean get() = encoding == PCM_S16_LE && sampleRate in 8_000..192_000 && channels in 1..2
    companion object { const val PCM_S16_LE = 2 }
}
