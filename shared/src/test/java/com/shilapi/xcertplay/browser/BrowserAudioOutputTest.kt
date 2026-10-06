package com.shilapi.xcertplay.browser

import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.DecodedAudioFormat
import com.shilapi.xcertplay.airplay.DecodedAudioOutput
import com.shilapi.xcertplay.airplay.MediaSink
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BrowserAudioOutputTest {
    private class Source : MediaSink {
        var output: DecodedAudioOutput? = null
        @Volatile var native = true
        override fun setDecodedAudioOutput(output: DecodedAudioOutput?): Boolean {
            this.output = output
            if (output == null) native = true
            return true
        }
        override fun setNativeAudioEnabled(enabled: Boolean) { native = enabled }
        fun emit(stream: Long = 1, format: DecodedAudioFormat = DecodedAudioFormat(48_000, 2),
                 data: ByteArray = byteArrayOf(0, 0x40, 0, 0xe0.toByte())) {
            output?.pcm(stream, AudioStreamId(100, "media"), format, 0, data, 0, data.size, 0.2f)
        }
    }
    @After fun cleanUp() { BrowserAudioOutput.disconnect(); BrowserAudioOutput.attach(object : MediaSink {}) }

    @Test fun onlyAcknowledgedGestureMutesNativeAndBorrowedPcmIsCopied() {
        val source = Source(); val audio = LinkedBlockingQueue<ByteArray>(); val messages = mutableListOf<JSONObject>()
        BrowserAudioOutput.attach(source)
        BrowserAudioOutput.connect({ text ->
            val message = JSONObject(text)
            if (message.optBoolean("enabled")) assertTrue("Native stays enabled until ACK is queued", source.native)
            messages.add(message); true
        }, { audio.offer(it) }, {})
        assertTrue(source.native); source.emit(); assertTrue(audio.isEmpty())
        BrowserAudioOutput.setEnabled(true, 7)
        assertFalse(source.native); assertEquals(7L, messages.last().getLong("requestId"))
        val bytes = byteArrayOf(0, 0x40, 0, 0xe0.toByte()); source.emit(data = bytes); bytes.fill(0)
        val packet = audio.poll(2, TimeUnit.SECONDS) ?: error("PCM was not sent")
        assertArrayEquals(byteArrayOf(0, 0x40, 0, 0xe0.toByte()), packet.copyOfRange(36, 40))
        assertEquals(messages.last().getInt("epoch"), ByteBuffer.wrap(packet).getInt(4))
        BrowserAudioOutput.disconnect(); assertTrue(source.native)
    }

    @Test fun failedOrReenteredAcknowledgementNeverLeavesNativeMuted() {
        val source = Source(); BrowserAudioOutput.attach(source)
        BrowserAudioOutput.connect({ false }, { true }, {})
        BrowserAudioOutput.setEnabled(true, 1); assertTrue(source.native)
        BrowserAudioOutput.connect({ text ->
            if (JSONObject(text).optBoolean("enabled")) BrowserAudioOutput.disconnect()
            true
        }, { true }, {})
        BrowserAudioOutput.setEnabled(true, 2); assertTrue(source.native)
    }

    @Test fun sourceReplacementRestoresNativeAndDiscardsOldRendererCallbacks() {
        val first = Source(); val second = Source(); val audio = LinkedBlockingQueue<ByteArray>()
        BrowserAudioOutput.attach(first)
        BrowserAudioOutput.connect({ true }, { audio.offer(it) }, {})
        BrowserAudioOutput.setEnabled(true, 1)
        val retiredTap = first.output!!
        BrowserAudioOutput.attach(second)
        assertTrue(first.native); assertNull(first.output); assertTrue(second.native)
        BrowserAudioOutput.setEnabled(true, 2)
        retiredTap.pcm(1, AudioStreamId(100, "media"), DecodedAudioFormat(48_000, 2), 0,
            ByteArray(4), 0, 4, 1f)
        assertNull(audio.poll(50, TimeUnit.MILLISECONDS))
        second.emit(); assertNotNull(audio.poll(2, TimeUnit.SECONDS))
        BrowserAudioOutput.disconnect(); assertTrue(second.native)
    }

    @Test fun unsupportedActualDecoderFormatRestoresNativeAndReportsWhy() {
        val source = Source(); val messages = LinkedBlockingQueue<JSONObject>()
        BrowserAudioOutput.attach(source)
        BrowserAudioOutput.connect({ messages.offer(JSONObject(it)) }, { true }, {})
        BrowserAudioOutput.setEnabled(true, 1); messages.clear()
        source.emit(format = DecodedAudioFormat(48_000, 2, 4))
        val failure = messages.poll(2, TimeUnit.SECONDS) ?: error("No format diagnostic")
        assertFalse(failure.getBoolean("enabled"))
        assertEquals("unsupported-pcm-format", failure.getString("code")); assertEquals(1L, failure.getLong("requestId"))
        assertTrue(source.native)
    }

    @Test fun unsupportedSinkCannotMuteNative() {
        val messages = mutableListOf<JSONObject>()
        BrowserAudioOutput.attach(object : MediaSink {})
        BrowserAudioOutput.connect({ messages.add(JSONObject(it)); true }, { true }, {})
        BrowserAudioOutput.setEnabled(true, 1)
        assertFalse(messages.last().getBoolean("enabled"))
        assertEquals("audio-source-unavailable", messages.last().getString("code"))
    }
}
