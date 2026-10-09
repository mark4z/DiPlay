package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29], manifest = Config.NONE)
class AndroidMediaSinkAudioTest {
    @Test fun nativeStartCallbackStillRunsAudioWorker() {
        val ready = CountDownLatch(1)
        val sink = AndroidMediaSink(onAudioDiagnostic = { if (it.startsWith("Audio: ready")) ready.countDown() })
        val id = AudioStreamId(96, "media")
        val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96)
        var renderer: Any? = null
        try {
            sink.onAudioStarted(id, format, 0)
            renderer = checkNotNull((field(sink, "audioRenderers") as Map<*, *>)[id])
            assertTrue("Native AudioTrack must become ready", ready.await(2, TimeUnit.SECONDS))
            assertEquals(true, field(renderer, "started"))
            assertNotNull(field(renderer, "track"))
        } finally {
            sink.close()
            renderer?.let {
                val worker = field(it, "thread") as Thread
                worker.join(2_000)
                assertFalse("Audio worker must exit on session teardown", worker.isAlive)
                assertNull(field(it, "track"))
            }
        }
    }

    @Test fun nativePcmStillCreatesPlaysAndReleasesAudioTrack() = withRenderer("media") { _, renderer ->
        invoke(renderer, "createTrack")
        val track = field(renderer, "track") as AudioTrack
        assertEquals(AudioTrack.STATE_INITIALIZED, track.state)
        assertEquals(48_000, track.sampleRate)
        assertEquals(2, track.channelCount)
        val pcm = ByteArray(64_000)
        renderer.javaClass.getDeclaredMethod(
            "writePcm", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(renderer, pcm, 0, pcm.size)
        assertEquals(16_000L, field(renderer, "totalWrittenFrames"))
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, track.playState)
        invoke(renderer, "release")
        assertNull(field(renderer, "track"))
        assertEquals(AudioTrack.STATE_UNINITIALIZED, track.state)
    }

    @Test fun nativeRoutingKeepsMediaNavigationPhoneAndAssistantUsages() {
        val usages = mapOf(
            "media" to AudioAttributes.USAGE_MEDIA,
            "alert" to AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            "telephony" to AudioAttributes.USAGE_VOICE_COMMUNICATION,
            "speechrecognition" to AudioAttributes.USAGE_ASSISTANT,
        )
        for ((audioType, usage) in usages) withRenderer(audioType) { _, renderer ->
            invoke(renderer, "createTrack")
            val attributes = field(renderer, "trackAttributes") as AudioAttributes
            assertEquals(audioType, usage, attributes.usage)
        }
    }

    @Test fun nativeAudioFocusStillDucksRestoresAndReleases() = withRenderer("media") { sink, renderer ->
        invoke(renderer, "createTrack")
        // Keep the configured route, but observe focus changes without depending on HAL volume getters.
        (field(renderer, "track") as AudioTrack).release()
        val track = mock(AudioTrack::class.java)
        setField(renderer, "track", track)
        invoke(renderer, "requestAudioFocus")
        verify(track).setStereoVolume(1f, 1f)

        val coordinator = checkNotNull(field(sink, "audioFocusCoordinator"))
        val listener = field(coordinator, "listener") as AudioManager.OnAudioFocusChangeListener
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        verify(track).setStereoVolume(0.2f, 0.2f)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        verify(track, times(2)).setStereoVolume(1f, 1f)

        invoke(renderer, "release")
        assertTrue((field(coordinator, "active") as Map<*, *>).isEmpty())
        assertNull(field(coordinator, "request"))
        verify(track).pause()
        verify(track).flush()
        verify(track).release()
    }

    @Test fun navigationStillSkipsAudioFocus() = withRenderer("alert") { sink, renderer ->
        invoke(renderer, "createTrack")
        invoke(renderer, "requestAudioFocus")
        val coordinator = checkNotNull(field(sink, "audioFocusCoordinator"))
        assertTrue((field(coordinator, "active") as Map<*, *>).isEmpty())
        assertNull(field(coordinator, "request"))
    }

    private fun withRenderer(audioType: String, test: (AndroidMediaSink, Any) -> Unit) {
        val sink = AndroidMediaSink(context = RuntimeEnvironment.getApplication(), audioFocusEnabled = true)
        val id = AudioStreamId(96, audioType)
        val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, audioType)
        // RTP received before the start callback creates a dormant renderer. Exercise its local
        // track path synchronously so assertions do not race the audio worker or Android's HAL.
        sink.onAudioRtp(id, format, ByteArray(12), 0)
        val renderer = checkNotNull((field(sink, "audioRenderers") as Map<*, *>)[id])
        try {
            test(sink, renderer)
        } finally {
            invoke(renderer, "release")
            sink.close()
        }
    }

    private fun invoke(instance: Any, name: String) =
        instance.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(instance)

    private fun field(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

    private fun setField(instance: Any, name: String, value: Any?) =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
}
