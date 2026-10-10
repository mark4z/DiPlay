package com.shilapi.xcertplay.browser

import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.VideoCodec
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BrowserOutputRtcTest {
    @After fun cleanup() { BrowserOutput.stop() }
    @Test fun transportFallbackPreservesStreamSizeAndLeaseButRequiresFreshKey() {
        BrowserOutput.stop()
        val contacts = mutableListOf<List<AirPlayContact>>()
        val sink = BrowserOutput.tee(object : MediaSink {}, 2560, 1440, {}, contacts::add)
        sink.onVideoCodec(110, VideoCodec.H264)
        sink.onVideoConfig(110, byteArrayOf(1, 100, 0, 42, -1, -31, 0, 4, 103, 100, 0, 42, 1, 0, 2, 104, 1))
        val stream = field("streamId").getLong(null)
        field("viewerConnected").setBoolean(null, true)
        field("browserTouchOwned").setBoolean(null, true)
        field("contacts").set(null, listOf(AirPlayContact(0, .3, .5, true)))
        field("waitingForKey").setBoolean(null, false)
        val session = BrowserRtcSession(Any(), stream, "attempt", "h264", { true }, { _, _ -> true }, {}, { _, _ -> })
        field("rtc").set(null, session)
        val fallback = BrowserOutput::class.java.getDeclaredMethod("rtcFallback", BrowserRtcSession::class.java, String::class.java)
        fallback.isAccessible = true
        fallback.invoke(BrowserOutput, session, "user-selected-wss")
        assertEquals(stream, field("streamId").getLong(null))
        assertEquals(2560, field("width").getInt(null))
        assertEquals(1440, field("height").getInt(null))
        assertTrue(field("waitingForKey").getBoolean(null))
        assertTrue(BrowserOutput.browserTouchOwned)
        assertFalse(contacts.last().single().down)
        assertNull(field("rtc").get(null))
    }
    @Test fun detachedMediaClosesRtcAndInvalidatesOldCallbacks() {
        val native = object : MediaSink {}
        BrowserOutput.tee(native, 1280, 720, {}, {})
        val session = BrowserRtcSession(Any(), 1, "attempt", "h264", { true }, { _, _ -> true }, {}, { _, _ -> })
        field("rtc").set(null, session)
        BrowserOutput.detachMedia(native)
        assertNull(field("rtc").get(null))
        session.receive(JSONObject().put("type", "rtcReady").put("streamId", 1).put("negotiationId", "attempt"))
        assertFalse(session.active)
    }
    private fun field(name: String) = BrowserOutput::class.java.getDeclaredField(name).apply { isAccessible = true }
}
