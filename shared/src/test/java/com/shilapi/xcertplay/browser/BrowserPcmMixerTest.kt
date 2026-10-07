package com.shilapi.xcertplay.browser

import com.shilapi.xcertplay.airplay.DecodedAudioFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

/** Synthetic PCM only: no AudioTrack, microphone, timing sleeps, network, or real media. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BrowserPcmMixerTest {
    private val stereo = DecodedAudioFormat(48_000, 2)

    private fun pcm(frames: Int, channels: Int = 2, sample: (Int, Int) -> Int): ByteArray {
        val bytes = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { frame -> repeat(channels) { channel -> bytes.putShort(sample(frame, channel).toShort()) } }
        return bytes.array()
    }

    private fun offer(mixer: BrowserPcmMixer, bytes: ByteArray, stream: Long = 1,
                      format: DecodedAudioFormat = stereo, first: Long = 0, gain: Float = 1f) {
        assertNull(mixer.offer(stream, format, first, bytes, 0, bytes.size, gain))
    }

    private fun fill(mixer: BrowserPcmMixer): ShortArray {
        val target = ByteBuffer.allocate(BrowserPcmMixer.OUTPUT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        mixer.fill(target)
        assertEquals(BrowserPcmMixer.OUTPUT_BYTES, target.position())
        assertNull(mixer.failureCode)
        target.flip()
        return ShortArray(960) { target.short }
    }

    private fun drain(mixer: BrowserPcmMixer): ShortArray {
        val result = ArrayList<Short>()
        repeat(160) {
            if (mixer.stats().sources.isEmpty()) return result.take(mixer.stats().renderedFrames.toInt() * 2).toShortArray()
            result.addAll(fill(mixer).toList())
        }
        error("Stopped source did not drain within its 1.5 s bound")
    }

    @Test fun nativeOneSecondBurstSurvivesThousandsOfCallbacksWithoutEventQueueDrops() {
        val mixer = BrowserPcmMixer()
        val source = pcm(48_000) { frame, channel -> (frame % 20_000 + 1) * if (channel == 0) 1 else -1 }
        // 2,000 small decoder events, all before the first capture callback.
        repeat(2_000) { index ->
            assertNull(mixer.offer(1, stereo, index * 24L, source, index * 96, 96, 1f))
        }
        assertEquals(48_000L, mixer.stats().sources.single().highWaterFrames)
        source.fill(0) // Every offered slice was borrowed and may immediately be overwritten.
        repeat(100) { callback ->
            val rendered = fill(mixer)
            repeat(480) { frame ->
                val expected = ((callback * 480 + frame) % 20_000 + 1).toShort()
                assertEquals(expected, rendered[frame * 2])
                assertEquals((-expected).toShort(), rendered[frame * 2 + 1])
            }
        }
        assertEquals(48_000L, mixer.stats().acceptedFrames)
        assertEquals(48_000L, mixer.stats().renderedFrames)
        assertEquals(0L, mixer.stats().underrunFrames)
        assertEquals(0L, mixer.stats().sources.single().outstandingFrames)
    }

    @Test fun sourceRingWrapRetainsFrameOrderAndPerFrameGains() {
        val mixer = BrowserPcmMixer()
        val first = pcm(48_000) { _, channel -> if (channel == 0) 2000 else -4000 }
        offer(mixer, first, gain = 0.5f)
        repeat(100) {
            val output = fill(mixer)
            assertEquals(1000.toShort(), output[0]); assertEquals((-2000).toShort(), output[1])
        }
        val second = pcm(48_000) { _, channel -> if (channel == 0) 4000 else -8000 }
        offer(mixer, second, first = 48_000, gain = 0.25f)
        mixer.stopped(1, 96_000)
        repeat(100) {
            val output = fill(mixer)
            assertTrue(output.indices.all { index -> output[index] == (if (index % 2 == 0) 1000 else -2000).toShort() })
        }
        assertTrue(mixer.stats().sources.isEmpty())
        assertEquals(96_000L, mixer.stats().renderedFrames)
    }

    @Test fun gainChangesAreStoredWithSamplesRatherThanAppliedToAnEntirePrefetchedBuffer() {
        val mixer = BrowserPcmMixer()
        val source = pcm(240) { _, _ -> 10_000 }
        offer(mixer, source, gain = 0.2f)
        offer(mixer, source, first = 240, gain = 1f)
        val output = fill(mixer)
        assertTrue(output.take(480).all { it == 2000.toShort() })
        assertTrue(output.drop(480).all { it == 10_000.toShort() })
    }

    @Test fun musicAndNavigationMixWithSuppliedDuckingAndClipOnlyAfterSumming() {
        val mixer = BrowserPcmMixer()
        offer(mixer, pcm(960) { _, channel -> if (channel == 0) 20_000 else -20_000 }, gain = 0.2f)
        offer(mixer, pcm(320, 1) { _, _ -> 5000 }, stream = 2, format = DecodedAudioFormat(16_000, 1))
        val mixed = fill(mixer)
        assertTrue(mixed.indices.all { index -> mixed[index] == (if (index % 2 == 0) 9000 else 1000).toShort() })

        val clipping = BrowserPcmMixer()
        repeat(4) { stream -> offer(clipping, pcm(480) { _, channel -> if (channel == 0) 20_000 else -20_000 }, stream + 1L) }
        val saturated = fill(clipping)
        assertTrue(saturated.indices.all { index -> saturated[index] == (if (index % 2 == 0) 32767 else -32768).toShort() })
    }

    @Test fun allSupportedRateExtremesAndMonoStereoPreserveDurationChannelsAndGain() {
        for (rate in listOf(8_000, 11_025, 16_000, 22_050, 44_100, 48_000, 96_000, 192_000)) {
            for (channels in 1..2) {
                val mixer = BrowserPcmMixer()
                val count = rate / 10
                offer(mixer, pcm(count, channels) { _, channel -> if (channel == 0) 6000 else -4000 },
                    format = DecodedAudioFormat(rate, channels), gain = 0.5f)
                mixer.stopped(1, count.toLong())
                val output = drain(mixer)
                val expectedFrames = count.toDouble() * 48_000 / rate
                assertTrue("Duration at $rate Hz/$channels channels", abs(output.size / 2 - expectedFrames) <= 6)
                // EOS interpolates the final source sample toward silence. The interior stays exact.
                repeat(output.size / 2 - 12) { frame ->
                    assertEquals("Left at $rate Hz", 3000.toShort(), output[frame * 2])
                    assertEquals("Right at $rate Hz", (if (channels == 1) 3000 else -2000).toShort(), output[frame * 2 + 1])
                }
            }
        }
    }

    @Test fun fractionalResamplingIsIdenticalAcrossBorrowedChunkBoundariesAndStreamingCallbacks() {
        val rate = 44_100
        val format = DecodedAudioFormat(rate, 2)
        val source = pcm(rate) { frame, channel ->
            (sin(frame * 2.0 * Math.PI * 440 / rate) * (if (channel == 0) 12_000 else -6000)).toInt()
        }
        val burst = BrowserPcmMixer()
        offer(burst, source, format = format)
        burst.stopped(1, rate.toLong())
        val expected = drain(burst)

        val streaming = BrowserPcmMixer()
        val actual = ArrayList<Short>()
        assertNull(streaming.offer(1, format, 0, source, 0, 882 * 4, 1f))
        repeat(98) { chunk ->
            val first = 882 + chunk * 441
            assertNull(streaming.offer(1, format, first.toLong(), source, first * 4, 441 * 4, 1f))
            actual.addAll(fill(streaming).toList())
        }
        streaming.stopped(1, rate.toLong())
        repeat(4) { if (streaming.stats().sources.isNotEmpty()) actual.addAll(fill(streaming).toList()) }
        assertTrue(streaming.stats().sources.isEmpty())
        assertEquals(0L, streaming.stats().underrunFrames)
        assertArrayEquals(expected, actual.take(streaming.stats().renderedFrames.toInt() * 2).toShortArray())
    }

    @Test fun stopFlushesVeryShortResamplerTailAndReleasesTheStreamSlot() {
        val mixer = BrowserPcmMixer()
        offer(mixer, pcm(1, 1) { _, _ -> 12_000 }, format = DecodedAudioFormat(8_000, 1))
        mixer.stopped(1, 1)
        val output = drain(mixer)
        assertEquals(12, output.size)
        assertEquals(12_000.toShort(), output[0])
        assertEquals(12_000.toShort(), output[1])
        assertTrue(output.any { it != 0.toShort() })
        assertTrue(mixer.stats().sources.isEmpty())
        assertTrue(fill(mixer).all { it == 0.toShort() })
    }

    @Test fun stopDrainsQueuedMusicInsteadOfDroppingTheNativeBurst() {
        val mixer = BrowserPcmMixer()
        offer(mixer, pcm(48_000) { _, _ -> 1234 })
        mixer.stopped(1, 48_000)
        val output = drain(mixer)
        assertEquals(96_000, output.size)
        assertTrue(output.all { it == 1234.toShort() })
        assertEquals(0L, mixer.stats().underrunFrames)
    }

    @Test fun overflowIncludesConverterLookaheadAndFailsClosedWithoutSilentlyDroppingChunks() {
        val mixer = BrowserPcmMixer()
        offer(mixer, pcm(72_000) { _, _ -> 100 })
        fill(mixer)
        val source = mixer.stats().sources.single()
        assertEquals(71_518, source.queuedFrames)
        assertEquals(71_520L, source.outstandingFrames)
        val extra = ByteArray(482 * 4)
        assertEquals("audio-source-overflow", mixer.offer(1, stereo, 72_000, extra, 0, extra.size, 1f))
        assertEquals(1L, mixer.stats().overflows)
        assertEquals(72_000L, mixer.stats().acceptedFrames)
        val silence = ByteBuffer.allocate(1920)
        mixer.fill(silence)
        assertTrue(silence.array().all { it == 0.toByte() })
        assertEquals("audio-source-overflow", mixer.offer(1, stereo, 72_000, ByteArray(4), 0, 4, 1f))
        assertEquals(1L, mixer.stats().overflows)
    }

    @Test fun capacityIsFreedByPlaybackRatherThanByPullingIntoTheConverter() {
        val mixer = BrowserPcmMixer()
        offer(mixer, pcm(72_000) { _, _ -> 100 })
        fill(mixer)
        offer(mixer, pcm(480) { _, _ -> 200 }, first = 72_000)
        assertEquals(72_000L, mixer.stats().sources.single().outstandingFrames)
        assertEquals(72_000L, mixer.stats().sources.single().highWaterFrames)
    }

    @Test fun oversizedFirstBufferFailsBeforeAllocatingAStream() {
        val mixer = BrowserPcmMixer()
        val bytes = ByteArray(72_001 * 4)
        assertEquals("audio-source-overflow", mixer.offer(1, stereo, 0, bytes, 0, bytes.size, 1f))
        assertTrue(mixer.stats().sources.isEmpty())
        assertEquals(1L, mixer.stats().overflows)
    }

    @Test fun streamLimitIsBoundedAndDrainedStreamsReleaseTheirSlots() {
        val limited = BrowserPcmMixer()
        repeat(4) { offer(limited, ByteArray(4), stream = it.toLong()) }
        assertEquals("audio-source-limit", limited.offer(5, stereo, 0, ByteArray(4), 0, 4, 1f))
        assertEquals(4, limited.stats().sources.size)

        val reusable = BrowserPcmMixer()
        repeat(4) { offer(reusable, ByteArray(4), stream = it.toLong()); reusable.stopped(it.toLong(), 1) }
        fill(reusable)
        assertTrue(reusable.stats().sources.isEmpty())
        offer(reusable, ByteArray(4), stream = 5)
        assertEquals(1, reusable.stats().sources.size)
    }

    @Test fun initialMidStreamJoinIsAllowedButDuplicateGapAndRewindAreFatal() {
        for (next in listOf(9000L, 9001L, 9481L, 8999L)) {
            val mixer = BrowserPcmMixer()
            offer(mixer, ByteArray(480 * 4), first = 9000)
            assertEquals("audio-source-discontinuity", mixer.offer(1, stereo, next, ByteArray(4), 0, 4, 1f))
            assertEquals(1L, mixer.stats().discontinuities)
        }
        val valid = BrowserPcmMixer()
        offer(valid, ByteArray(480 * 4), first = 9000)
        offer(valid, ByteArray(4), first = 9480)
    }

    @Test fun stopChecksExclusiveSampleCountAndRejectsFurtherPcm() {
        val invalid = BrowserPcmMixer()
        offer(invalid, ByteArray(4), first = 100)
        invalid.stopped(1, 100)
        assertEquals("audio-source-discontinuity", invalid.failureCode)
        val stopped = BrowserPcmMixer()
        offer(stopped, ByteArray(4), first = 100)
        stopped.stopped(1, 101)
        assertEquals("audio-source-discontinuity", stopped.offer(1, stereo, 101, ByteArray(4), 0, 4, 1f))
    }

    @Test fun malformedOrChangingFormatsAndInvalidRangesFailClosed() {
        for (format in listOf(DecodedAudioFormat(7999, 2), DecodedAudioFormat(192001, 2),
            DecodedAudioFormat(48_000, 3), DecodedAudioFormat(48_000, 2, 4))) {
            assertEquals("unsupported-pcm-format", BrowserPcmMixer().offer(1, format, 0, ByteArray(4), 0, 4, 1f))
        }
        for (gain in listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.1f, 1.1f)) {
            assertEquals("audio-invalid-pcm", BrowserPcmMixer().offer(1, stereo, 0, ByteArray(4), 0, 4, gain))
        }
        for ((offset, length) in listOf(-1 to 4, 0 to -1, 0 to 0, 0 to 3, 1 to 4, Int.MAX_VALUE to 4)) {
            assertEquals("audio-invalid-pcm", BrowserPcmMixer().offer(1, stereo, 0, ByteArray(4), offset, length, 1f))
        }
        assertEquals("audio-invalid-pcm", BrowserPcmMixer().offer(1, stereo, Long.MAX_VALUE, ByteArray(4), 0, 4, 1f))
        val changing = BrowserPcmMixer()
        offer(changing, ByteArray(4))
        assertEquals("audio-source-format-changed", changing.offer(1, DecodedAudioFormat(44_100, 2), 1, ByteArray(4), 0, 4, 1f))
    }

    @Test fun underrunIsSilenceAndLaterPcmResumesWithoutInventingSamples() {
        val mixer = BrowserPcmMixer()
        offer(mixer, pcm(480) { _, _ -> 1000 })
        assertTrue(fill(mixer).all { it == 1000.toShort() })
        assertTrue(fill(mixer).all { it == 0.toShort() })
        assertEquals(480L, mixer.stats().underrunFrames)
        offer(mixer, pcm(480) { _, _ -> 2000 }, first = 480)
        assertTrue(fill(mixer).all { it == 2000.toShort() })
        assertEquals(960L, mixer.stats().renderedFrames)
    }

    @Test fun fillsOnlyTheRequestedBufferWindowAndForcesLittleEndian() {
        val mixer = BrowserPcmMixer()
        offer(mixer, pcm(480) { _, _ -> 0x1234 })
        val target = ByteBuffer.allocate(1928).order(ByteOrder.BIG_ENDIAN)
        target.putInt(0x01020304)
        target.limit(1924)
        mixer.fill(target)
        assertEquals(1924, target.position())
        assertEquals(1924, target.limit())
        assertEquals(ByteOrder.LITTLE_ENDIAN, target.order())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), target.array().copyOfRange(0, 4))
        assertEquals(0x34.toByte(), target.array()[4]); assertEquals(0x12.toByte(), target.array()[5])
        assertTrue(target.array().drop(1924).all { it == 0.toByte() })
    }
}
