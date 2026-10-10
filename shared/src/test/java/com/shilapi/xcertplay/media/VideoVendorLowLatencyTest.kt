package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.Closeable
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder
import org.robolectric.shadows.ShadowMediaCodecList

/** Exercises the actual configure/fallback path without a device codec or background decoding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 30], manifest = Config.NONE, shadows = [RecordingVideoCodec::class])
class VideoVendorLowLatencyTest {
    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1E)
    private val pps = byteArrayOf(0x68, 0xCE.toByte())
    private val config = VideoJob.Config(VideoCodec.H264,
        byteArrayOf(1, 0x42, 0xC0.toByte(), 0x1E, 0xFF.toByte(), 0xE1.toByte(), 0, sps.size.toByte()) +
            sps + byteArrayOf(1, 0, pps.size.toByte()) + pps)

    @Before fun setup() {
        RecordingVideoCodec.reset()
        if (Build.VERSION.SDK_INT >= 29) ShadowMediaCodecList.addCodec(RecordingVideoCodec.info(SOFTWARE))
    }

    @After fun teardown() { RecordingVideoCodec.reset() }

    @Test fun onlyKnownQualcommCodecPrefixesReceiveVendorKeys() {
        for (name in listOf("c2.qti.avc.decoder", "c2.qti.hevc.decoder", "OMX.qcom.video.decoder.avc")) {
            assertEquals(listOf(LOW_LATENCY, PICTURE_ORDER), vendorLowLatencyKeys(name))
        }
        for (name in listOf("", "c2.mtk.avc.decoder", SOFTWARE, "OMX.google.h264.decoder", "other.c2.qti.avc")) {
            assertTrue(name, vendorLowLatencyKeys(name).isEmpty())
        }
    }

    @Test fun defaultOffKeepsTheOriginalTunedFormatIncludingStandardLowLatency() = withDecoder() { decoder, _ ->
        configure(decoder)
        val attempt = RecordingVideoCodec.attempts.single()
        assertFalse(attempt.vendor)
        assertTrue(attempt.tuned)
        assertEquals(Build.VERSION.SDK_INT >= 30, attempt.standardLowLatency)
    }

    @Test fun enabledQualcommAddsBothKeysWithoutReplacingStandardLowLatency() = withDecoder(enabled = true) { decoder, _ ->
        configure(decoder)
        val attempt = RecordingVideoCodec.attempts.single()
        assertEquals(1, attempt.format.getInteger(LOW_LATENCY))
        assertEquals(1, attempt.format.getInteger(PICTURE_ORDER))
        assertEquals(Build.VERSION.SDK_INT >= 30, attempt.standardLowLatency)
        assertEquals(true, field(decoder, "configuredVendorLowLatency"))
    }

    @Test @Config(sdk = [30]) fun unsupportedStandardFeatureIsNotForcedOnByTheOption() = withDecoder(enabled = true) { decoder, _ ->
        RecordingVideoCodec.supportsStandard = false
        configure(decoder)
        assertTrue(RecordingVideoCodec.attempts.single().vendor)
        assertFalse(RecordingVideoCodec.attempts.single().standardLowLatency)
    }

    @Test fun qualcommOmxUsesTheSameBoundedVendorFallback() = withDecoder(enabled = true) { decoder, _ ->
        RecordingVideoCodec.defaultName = "OMX.qcom.video.decoder.avc"
        RecordingVideoCodec.reject = { it.vendor }
        configure(decoder)
        assertEquals(listOf(true, false), RecordingVideoCodec.attempts.map { it.vendor })
        assertTrue(RecordingVideoCodec.attempts.all { it.tuned })
        assertTrue(RecordingVideoCodec.created.first().released)
    }

    @Test fun nonQualcommKeepsOneTunedAttemptAndTheExistingMinimalFallback() = withDecoder(enabled = true) { decoder, _ ->
        RecordingVideoCodec.defaultName = "c2.mtk.avc.decoder"
        RecordingVideoCodec.reject = { it.tuned }
        configure(decoder)
        assertEquals(listOf(true, false), RecordingVideoCodec.attempts.map { it.tuned })
        assertTrue(RecordingVideoCodec.attempts.none { it.vendor })
    }

    @Test fun vendorRejectionRetriesOriginalTunedBeforeAnyMinimalFormat() = withDecoder(enabled = true) { decoder, _ ->
        RecordingVideoCodec.reject = { it.vendor }
        configure(decoder)
        assertEquals(listOf(true, false), RecordingVideoCodec.attempts.map { it.vendor })
        assertTrue(RecordingVideoCodec.attempts.all { it.tuned })
        assertTrue(RecordingVideoCodec.attempts.all { it.standardLowLatency == (Build.VERSION.SDK_INT >= 30) })
        val (vendor, original) = RecordingVideoCodec.attempts.map { it.format }
        for (key in listOf(MediaFormat.KEY_MIME, MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT,
            MediaFormat.KEY_MAX_INPUT_SIZE, MediaFormat.KEY_PRIORITY, "csd-0", "csd-1")) {
            assertEquals("same tuned format: $key", vendor.getObjectForTest(key), original.getObjectForTest(key))
        }
        assertTrue("rejected candidate must be released", RecordingVideoCodec.created.first().released)
        assertEquals(false, field(decoder, "configuredVendorLowLatency"))
    }

    @Test fun vendorAndOriginalTunedRejectionReachTheOriginalMinimalFormat() = withDecoder(enabled = true) { decoder, _ ->
        RecordingVideoCodec.reject = { it.tuned }
        configure(decoder)
        assertEquals(listOf(true, false, false), RecordingVideoCodec.attempts.map { it.vendor })
        assertEquals(listOf(true, true, false), RecordingVideoCodec.attempts.map { it.tuned })
        assertTrue(RecordingVideoCodec.created.take(2).all { it.released })
        assertNotNull(field(decoder, "decoder"))
    }

    @Test @Config(sdk = [30]) fun allHardwareFailuresRetainTheSoftwareFallback() = withDecoder(enabled = true) { decoder, _ ->
        RecordingVideoCodec.reject = { it.name != SOFTWARE }
        configure(decoder)
        assertEquals(listOf(QUALCOMM, QUALCOMM, QUALCOMM, SOFTWARE), RecordingVideoCodec.attempts.map { it.name })
        assertFalse(RecordingVideoCodec.attempts.last().tuned)
        assertFalse(RecordingVideoCodec.attempts.last().vendor)
        assertTrue(RecordingVideoCodec.created.take(3).all { it.released })
    }

    @Test @Config(sdk = [30]) fun capabilityLookupFailureKeepsTheOriginalMinimalFallback() = withDecoder(enabled = true) { decoder, reports ->
        RecordingVideoCodec.failCapabilityLookup = true
        configure(decoder)
        assertEquals(2, RecordingVideoCodec.created.size)
        assertTrue(RecordingVideoCodec.created.first().released)
        assertFalse(RecordingVideoCodec.attempts.single().tuned)
        assertFalse(RecordingVideoCodec.attempts.single().vendor)
        assertEquals(1, reports.count { it.startsWith("decoder failed") })
        assertFalse(reports.any { it.contains("private-test-error-message") })
    }

    @Test fun startFailureAlsoRetriesWithoutVendorKeys() = withDecoder(enabled = true) { decoder, _ ->
        RecordingVideoCodec.rejectAtStart = true
        configure(decoder)
        assertEquals(listOf(true, false), RecordingVideoCodec.attempts.map { it.vendor })
        assertTrue(RecordingVideoCodec.created.first().released)
        assertNotNull(field(decoder, "decoder"))
    }

    @Test fun failureDiagnosticsStayBoundedAcrossRepeatedConfigurations() = withDecoder(enabled = true) { decoder, reports ->
        RecordingVideoCodec.reject = { true }
        repeat(6) { configure(decoder) }
        assertEquals(8, reports.count { it.startsWith("decoder failed") })
        assertTrue(reports.filter { it.startsWith("decoder failed") }.all { it.length < 2048 })
        assertFalse(reports.any { it.contains("private-test-error-message") })
        assertTrue(RecordingVideoCodec.created.all { it.released })
    }

    @Test fun secondaryAndMirrorDecodersNeverReceiveTheOption() {
        for ((type, label) in listOf(110 to " mirror=test", 111 to null, 120 to null)) {
            withDecoder(enabled = true, type = type, label = label) { decoder, _ ->
                assertEquals(false, field(decoder, "vendorLowLatency"))
                configure(decoder)
                assertTrue(RecordingVideoCodec.attempts.none { it.vendor })
            }
            RecordingVideoCodec.reset()
        }
    }

    private fun withDecoder(enabled: Boolean = false, type: Int = 110, label: String? = null,
        test: (Any, MutableList<String>) -> Unit) {
        val reports = mutableListOf<String>()
        val sink = if (enabled) AndroidMediaSink(vendorLowLatencyDecoder = true) else AndroidMediaSink()
        sink.setVideoDiagnosticHandler(type, reports::add)
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val decoder = AndroidMediaSink::class.java.getDeclaredMethod("newVideoDecoder",
            Int::class.javaPrimitiveType, Surface::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(sink, type, surface, label)
        // Quiesce the existing worker before invoking its configure path synchronously. No output
        // thread or lifecycle behavior is added by this feature or replaced by this test.
        (decoder as Closeable).close()
        val worker = field(decoder, "thread") as Thread
        worker.join(5_000)
        assertFalse("worker stopped before the synchronous test", worker.isAlive)
        try { test(decoder, reports) } finally {
            decoder.javaClass.getDeclaredMethod("releaseDecoder").apply { isAccessible = true }.invoke(decoder)
            sink.close()
            surface.release()
            texture.release()
        }
    }

    private fun configure(decoder: Any) = decoder.javaClass.getDeclaredMethod("configureDecoder", VideoJob.Config::class.java)
        .apply { isAccessible = true }.invoke(decoder, config)

    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)
}

private const val QUALCOMM = "c2.qti.avc.decoder"
private const val SOFTWARE = "c2.android.avc.decoder"
private const val LOW_LATENCY = "vendor.qti-ext-dec-low-latency.enable"
private const val PICTURE_ORDER = "vendor.qti-ext-dec-picture-order.enable"

@Implements(MediaCodec::class)
class RecordingVideoCodec {
    data class Attempt(val name: String, val format: MediaFormat) {
        val vendor get() = format.containsKey(LOW_LATENCY)
        val tuned get() = format.containsKey(MediaFormat.KEY_PRIORITY)
        val standardLowLatency get() = format.containsKey("low-latency")
    }
    private var name = ""
    private var configured: Attempt? = null
    var released = false

    @Implementation fun __constructor__(name: String, nameIsType: Boolean, encoder: Boolean) {
        this.name = if (nameIsType) defaultName else name
        created += this
    }
    @Implementation fun getName(): String = name
    @Implementation fun getCodecInfo(): MediaCodecInfo {
        if (failCapabilityLookup) throw IllegalArgumentException("private-test-error-message")
        return info(name)
    }
    @Implementation fun configure(format: MediaFormat, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
        val attempt = Attempt(name, format)
        configured = attempt
        attempts += attempt
        if (reject(attempt)) throw IllegalArgumentException("private-test-error-message")
    }
    @Implementation fun start() {
        if (rejectAtStart && configured?.vendor == true) throw IllegalStateException("private-test-error-message")
    }
    @Implementation fun stop() = Unit
    @Implementation fun release() { released = true }

    companion object {
        var defaultName = QUALCOMM
        var reject: (Attempt) -> Boolean = { false }
        var rejectAtStart = false
        var failCapabilityLookup = false
        var supportsStandard = true
        val attempts = mutableListOf<Attempt>()
        val created = mutableListOf<RecordingVideoCodec>()
        fun reset() {
            defaultName = QUALCOMM
            reject = { false }
            rejectAtStart = false
            failCapabilityLookup = false
            supportsStandard = true
            attempts.clear()
            created.clear()
        }
        fun info(name: String): MediaCodecInfo {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)
            if (Build.VERSION.SDK_INT >= 30 && supportsStandard) format.setFeatureEnabled("low-latency", true)
            val capabilities = CodecCapabilitiesBuilder.newBuilder().setMediaFormat(format)
                .setProfileLevels(emptyArray<MediaCodecInfo.CodecProfileLevel>())
                .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)).build()
            return MediaCodecInfoBuilder.newBuilder().setName(name).setIsEncoder(false)
                .setIsSoftwareOnly(name == SOFTWARE).setIsHardwareAccelerated(name != SOFTWARE)
                .setCapabilities(capabilities).build()
        }
    }
}

private fun MediaFormat.getObjectForTest(key: String): Any? = when (key) {
    MediaFormat.KEY_MIME -> getString(key)
    "csd-0", "csd-1" -> getByteBuffer(key)
    else -> getInteger(key)
}
