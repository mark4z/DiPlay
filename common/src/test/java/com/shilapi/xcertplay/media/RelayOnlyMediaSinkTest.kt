package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.*
import java.util.concurrent.CopyOnWriteArrayList
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

    @Test fun relayAudioNeverCreatesAudioTrackOrMicrophone() {
        val diagnostics = CopyOnWriteArrayList<String>()
        val sink = AndroidMediaSink(relayOnly = true, onAudioDiagnostic = diagnostics::add)
        try {
            for (codec in AudioCodecKind.values()) {
                val id = AudioStreamId(96, "media")
                val format = AudioFormat(codec, 48_000, 2, 96)
                sink.onAudioStarted(id, format, 0)
                sink.onAudioRtp(id, format, ByteArray(12 + 960), 0)
                val renderer = checkNotNull(entries(sink, "audioRenderers")[id])
                assertFalse(field(renderer, "started") as Boolean)
                assertEquals(Thread.State.NEW, (field(renderer, "thread") as Thread).state)
                assertNull(field(renderer, "codec"))
                assertNull(field(renderer, "track"))
                sink.onMicrophoneStarted(id, mock(MicrophoneConfig::class.java))
                assertTrue(entries(sink, "microphoneUplinks").isEmpty())
                sink.onAudioStopped(id)
            }
            assertFalse(diagnostics.any { it.startsWith("Audio: ready") })
        } finally { sink.close() }
    }

    @Test fun relayRetainsMediaAndRealtimeStreamBookkeeping() {
        val mediaChanges = mutableListOf<Boolean>()
        val sink = AndroidMediaSink(relayOnly = true, onMediaAudioChanged = mediaChanges::add)
        val media = AudioStreamId(96, "media")
        val phone = AudioStreamId(96, "telephony")
        val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96)
        try {
            sink.onAudioStarted(media, format, 0)
            sink.onAudioStarted(phone, format.copy(audioType = "telephony"), 0)
            assertEquals(listOf(true), mediaChanges)
            assertTrue(sink.hasRealtimeAudio())
            sink.onAudioStopped(phone)
            assertFalse(sink.hasRealtimeAudio())
            assertEquals(listOf(true), mediaChanges)
            sink.onAudioStopped(media)
            assertEquals(listOf(true, false), mediaChanges)
            assertTrue(entries(sink, "audioRenderers").isEmpty())
        } finally { sink.close() }
    }

    private fun entries(sink: AndroidMediaSink, name: String) = field(sink, name) as Map<*, *>

    private fun field(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
}
