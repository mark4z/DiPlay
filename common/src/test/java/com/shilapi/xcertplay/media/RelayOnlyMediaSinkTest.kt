package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.mockito.Mockito.mock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RelayOnlyMediaSinkTest {
    @Test fun relayVideoNeverAllocatesNativeOrMirrorDecoders() {
        val sink = AndroidMediaSink(relayOnly = true)
        try {
            sink.onVideoCodec(110, VideoCodec.H264)
            sink.onVideoConfig(110, byteArrayOf(0, 0, 0, 1, 0x67))
            repeat(5) { sink.onVideoFrame(110, byteArrayOf(0, 0, 0, 1, 0x65)) }
            for (name in listOf("videoDecoders", "mirrorDecoders", "lastVideoConfig")) {
                val value = AndroidMediaSink::class.java.getDeclaredField(name).apply { isAccessible = true }.get(sink)
                assertTrue(name, (value as Map<*, *>).isEmpty())
            }
        } finally { sink.close() }
    }

    @Test fun relayPcmIsTappedWithoutCreatingAudioTrackOrMicrophone() {
        val sink = AndroidMediaSink(relayOnly = true)
        val id = AudioStreamId(96, "media")
        val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96)
        val received = CountDownLatch(1)
        try {
            assertTrue(sink.setDecodedAudioOutput(object : DecodedAudioOutput {
                override fun pcm(stream: Long, id: AudioStreamId, format: DecodedAudioFormat,
                    firstSample: Long, bytes: ByteArray, offset: Int, length: Int, gain: Float) {
                    if (length == 960 && format.supported && format.sampleRate == 48_000) received.countDown()
                }
            }))
            sink.onAudioStarted(id, format, 0)
            sink.onAudioRtp(id, format, ByteArray(12 + 960), 0)
            assertTrue("Decoded PCM must reach the WebRTC tap without a speaker track", received.await(2, TimeUnit.SECONDS))
            val renderers = AndroidMediaSink::class.java.getDeclaredField("audioRenderers").apply { isAccessible = true }.get(sink) as Map<*, *>
            val renderer = checkNotNull(renderers[id])
            assertNull(renderer.javaClass.getDeclaredField("track").apply { isAccessible = true }.get(renderer))
            sink.onMicrophoneStarted(id, mock(MicrophoneConfig::class.java))
            val uplinks = AndroidMediaSink::class.java.getDeclaredField("microphoneUplinks").apply { isAccessible = true }.get(sink) as Map<*, *>
            assertTrue(uplinks.isEmpty())
        } finally { sink.close() }
    }

    @Test fun browserFailureOrDetachCannotUnmuteBackend() {
        val sink = AndroidMediaSink(relayOnly = true)
        try {
            sink.setNativeAudioEnabled(true)
            sink.setDecodedAudioOutput(null)
            val field = AndroidMediaSink::class.java.getDeclaredField("nativeAudioEnabled").apply { isAccessible = true }
            assertFalse(field.getBoolean(sink))
        } finally { sink.close() }
    }
}
