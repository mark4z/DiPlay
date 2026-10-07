package com.shilapi.xcertplay.browser

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.shilapi.xcertplay.airplay.DecodedAudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

/**
 * Source PCM adapter for WebRTC's 10 ms capture clock, not a network jitter buffer.
 *
 * The decoder tap precedes blocking AudioTrack.write and can deliver a whole native buffer at once.
 * Each source therefore owns a sample-count-bounded 1.5 s ring (including converter lookahead), not a
 * small queue of decoder callbacks. Overflow fails the route instead of silently dropping speech.
 * offer() only validates and copies borrowed bytes/gains. Conversion and mixing run on the capture
 * thread, outside the producer lock, with at most one ~10 ms input block per source per callback.
 * Sonic is retained for the entire source: its fractional resampling phase survives chunk boundaries.
 * There are no executors, microphone access, I/O, or native resources to close.
 */
@OptIn(markerClass = [UnstableApi::class])
internal class BrowserPcmMixer {
    companion object {
        const val OUTPUT_RATE = 48_000
        const val OUTPUT_FRAMES = 480
        const val OUTPUT_BYTES = OUTPUT_FRAMES * 4
        const val MAX_STREAMS = 4
        const val MAX_BUFFER_MS = 1_500
    }

    private val lock = Any()
    private val renderLock = Any()
    private val streams = LinkedHashMap<Long, Source>()
    private val mix = IntArray(OUTPUT_FRAMES * 2)
    @Volatile var failureCode: String? = null
        private set
    private var acceptedFrames = 0L
    private var renderedFrames = 0L
    private var underrunFrames = 0L
    private var overflows = 0L
    private var discontinuities = 0L

    internal data class SourceStats(
        val stream: Long,
        val sampleRate: Int,
        val queuedFrames: Int,
        val outstandingFrames: Long,
        val highWaterFrames: Long,
        val nextSample: Long,
        val stopped: Boolean,
    )

    internal data class Stats(
        val acceptedFrames: Long,
        val renderedFrames: Long,
        val underrunFrames: Long,
        val overflows: Long,
        val discontinuities: Long,
        val sources: List<SourceStats>,
    )

    /** Diagnostic counters are measured in source frames, except rendered/underrun at 48 kHz. */
    fun stats(): Stats = synchronized(lock) {
        Stats(acceptedFrames, renderedFrames, underrunFrames, overflows, discontinuities,
            streams.map { (id, s) -> SourceStats(id, s.format.sampleRate, s.queued,
                s.outstanding, s.highWater, s.nextSample, s.stopped) })
    }

    fun offer(stream: Long, format: DecodedAudioFormat, firstSample: Long,
              bytes: ByteArray, offset: Int, length: Int, gain: Float): String? = synchronized(lock) {
        failureCode?.let { return@synchronized it }
        if (!format.supported) return@synchronized fail("unsupported-pcm-format")
        val frameBytes = format.channels * 2
        if (firstSample < 0 || offset < 0 || length <= 0 || offset > bytes.size - length ||
            length % frameBytes != 0 || !gain.isFinite() || gain !in 0f..1f) {
            return@synchronized fail("audio-invalid-pcm")
        }
        val count = length / frameBytes
        if (firstSample > Long.MAX_VALUE - count) return@synchronized fail("audio-invalid-pcm")
        var source = streams[stream]
        if (source == null) {
            if (streams.size == MAX_STREAMS) return@synchronized fail("audio-source-limit")
            // Reject an oversized first callback before allocating the source ring.
            if (count > format.sampleRate * MAX_BUFFER_MS / 1_000) {
                overflows++
                return@synchronized fail("audio-source-overflow")
            }
            source = Source(format, firstSample)
            streams[stream] = source
        }
        if (source.format != format) return@synchronized fail("audio-source-format-changed")
        if (source.stopped || firstSample != source.nextSample) {
            discontinuities++
            return@synchronized fail("audio-source-discontinuity")
        }
        // outstanding includes frames in Sonic/output staging, not only the raw ring.
        if (count > source.capacity - source.outstanding || count > source.capacity - source.queued) {
            overflows++
            return@synchronized fail("audio-source-overflow")
        }
        val tail = (source.head + source.queued) % source.capacity
        val first = minOf(count, source.capacity - tail)
        bytes.copyInto(source.bytes, tail * frameBytes, offset, offset + first * frameBytes)
        Arrays.fill(source.gains, tail, tail + first, gain)
        if (first != count) {
            bytes.copyInto(source.bytes, 0, offset + first * frameBytes, offset + length)
            Arrays.fill(source.gains, 0, count - first, gain)
        }
        source.queued += count
        source.outstanding += count
        source.highWater = maxOf(source.highWater, source.outstanding)
        source.nextSample += count
        acceptedFrames += count
        null
    }

    /** lastSample is the exclusive end, matching the decoder's sample counter. */
    fun stopped(stream: Long, lastSample: Long): Unit = synchronized(lock) {
        if (failureCode != null) return@synchronized
        val source = streams[stream] ?: return@synchronized // A route can join after this source ended.
        if (lastSample != source.nextSample) {
            discontinuities++
            fail("audio-source-discontinuity")
        } else source.stopped = true
    }

    /** Exactly one 10 ms block of 48 kHz stereo PCM16 LE; advances position, leaves limit unchanged. */
    fun fill(target: ByteBuffer) = synchronized(renderLock) {
        require(target.remaining() == OUTPUT_BYTES)
        target.order(ByteOrder.LITTLE_ENDIAN)
        Arrays.fill(mix, 0)
        if (failureCode == null) {
            val current = synchronized(lock) { streams.entries.map { it.key to it.value } }
            try {
                for ((id, source) in current) {
                    if (failureCode != null) break
                    val converter = source.converter ?: Converter(source.format).also { source.converter = it }
                    var written = converter.mixInto(mix, 0)
                    if (written < OUTPUT_FRAMES && !converter.ended) {
                        val wanted = minOf(converter.maxInputFrames,
                            ((OUTPUT_FRAMES - written) * source.format.sampleRate + OUTPUT_RATE - 1) / OUTPUT_RATE + 2)
                        val count = synchronized(lock) { pull(source, converter, wanted) }
                        if (count > 0) {
                            converter.queue(count)
                            written += converter.mixInto(mix, written)
                        }
                        // A stop must flush the interpolation tail, even after a one-frame source.
                        val finish = synchronized(lock) { source.stopped && source.queued == 0 }
                        if (finish && !converter.hasOutput && !converter.ended) {
                            converter.end()
                            written += converter.mixInto(mix, written)
                        }
                    }
                    synchronized(lock) {
                        renderedFrames += written
                        // Use a remainder so tiny callbacks and fractional rates do not free capacity
                        // early. This arithmetic is bounded per callback, unlike lifetime rate products.
                        source.renderRemainder += written.toLong() * source.format.sampleRate
                        source.outstanding = maxOf(0L, source.outstanding - source.renderRemainder / OUTPUT_RATE)
                        source.renderRemainder %= OUTPUT_RATE
                        if (converter.ended && !converter.hasOutput && source.queued == 0) {
                            streams.remove(id)
                        } else if (!source.stopped) underrunFrames += OUTPUT_FRAMES - written
                    }
                }
            } catch (_: Exception) {
                synchronized(lock) { fail("audio-pcm-conversion-failed") }
            }
        }
        // Never transmit a partial mix after a fatal source/converter error.
        val failed = failureCode != null
        for (sample in mix) target.putShort(if (failed) 0.toShort() else sample.coerceIn(-32768, 32767).toShort())
    }

    /** Called only with lock held. The much slower gain/resampling loops never hold this lock. */
    private fun pull(source: Source, converter: Converter, wanted: Int): Int {
        val count = minOf(wanted, source.queued)
        val frameBytes = source.format.channels * 2
        val first = minOf(count, source.capacity - source.head)
        source.bytes.copyInto(converter.raw, 0, source.head * frameBytes, (source.head + first) * frameBytes)
        source.gains.copyInto(converter.gains, 0, source.head, source.head + first)
        if (first != count) {
            source.bytes.copyInto(converter.raw, first * frameBytes, 0, (count - first) * frameBytes)
            source.gains.copyInto(converter.gains, first, 0, count - first)
        }
        source.head = (source.head + count) % source.capacity
        source.queued -= count
        return count
    }

    private fun fail(code: String): String {
        if (failureCode == null) failureCode = code
        return failureCode!!
    }

    private class Source(val format: DecodedAudioFormat, var nextSample: Long) {
        val capacity = format.sampleRate * MAX_BUFFER_MS / 1_000
        val bytes = ByteArray(capacity * format.channels * 2)
        val gains = FloatArray(capacity)
        var head = 0
        var queued = 0
        var outstanding = 0L
        var highWater = 0L
        var renderRemainder = 0L
        var stopped = false
        // Capture-thread owned; created lazily so the producer does no converter work.
        var converter: Converter? = null
    }

    private class Converter(private val format: DecodedAudioFormat) {
        val maxInputFrames = (OUTPUT_FRAMES * format.sampleRate + OUTPUT_RATE - 1) / OUTPUT_RATE + 2
        val raw = ByteArray(maxInputFrames * format.channels * 2)
        val gains = FloatArray(maxInputFrames)
        private val input = ByteBuffer.allocateDirect(raw.size).order(ByteOrder.nativeOrder())
        private val sonic = if (format.sampleRate == OUTPUT_RATE) null else SonicAudioProcessor().apply {
            setOutputSampleRateHz(OUTPUT_RATE)
            configure(AudioProcessor.AudioFormat(format.sampleRate, format.channels, C.ENCODING_PCM_16BIT))
            flush(AudioProcessor.StreamMetadata.DEFAULT)
        }
        private var output = AudioProcessor.EMPTY_BUFFER
        var ended = false
            private set
        val hasOutput: Boolean get() = output.hasRemaining()

        fun queue(count: Int) {
            check(!ended && !hasOutput)
            input.clear()
            var position = 0
            repeat(count) { frame ->
                repeat(format.channels) {
                    val sample = ((raw[position].toInt() and 255) or (raw[position + 1].toInt() shl 8)).toShort()
                    input.putShort((sample * gains[frame]).toInt().toShort())
                    position += 2
                }
            }
            input.flip()
            output = if (sonic == null) input else {
                sonic.queueInput(input)
                check(!input.hasRemaining())
                sonic.output
            }
            // Pure resampling at speed/pitch 1 needs only one lookahead frame. Never let a library
            // behavior change turn this source adapter into an unbounded converted-output queue.
            check(output.remaining() <= (OUTPUT_FRAMES + 24) * format.channels * 2)
        }

        fun end() {
            check(!hasOutput)
            ended = true
            if (sonic != null) {
                sonic.queueEndOfStream()
                output = sonic.output
                check(output.remaining() <= 24 * format.channels * 2)
                check(sonic.isEnded)
            }
        }

        fun mixInto(target: IntArray, firstFrame: Int): Int {
            val count = minOf(OUTPUT_FRAMES - firstFrame, output.remaining() / (format.channels * 2))
            repeat(count) { frame ->
                val left = output.short.toInt()
                val right = if (format.channels == 1) left else output.short.toInt()
                val index = (firstFrame + frame) * 2
                target[index] += left
                target[index + 1] += right
            }
            return count
        }
    }
}
