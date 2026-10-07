package com.shilapi.xcertplay.browser

import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.After

class BrowserOutputTest {
    @After fun cleanUp() { BrowserOutput.stop() }

    @Test fun videoOnlyReconfigurationAndStopNeverCancelNativeTouches() {
        BrowserOutput.stop()
        var cancellations = 0
        var transfers = 0
        val native = object : MediaSink {}
        val tee = BrowserOutput.tee(native, 1280, 720, { cancellations++ }, {},
            setTouchOwnership = { _, complete -> transfers++; complete(true) })
        BrowserOutput::class.java.getDeclaredField("viewerConnected").apply { isAccessible = true }
            .setBoolean(null, true)
        // No server is installed: these exercise the connected output state without sockets.
        tee.onVideoCodec(110, VideoCodec.H264)
        tee.onVideoConfig(110, byteArrayOf(1,100,0,42,-1,-31,0,4,103,100,0,42,1,0,2,104,1))
        tee.onScreenStreamActive(110, false)
        BrowserOutput.stop()
        assertEquals(0, cancellations)
        assertEquals(0, transfers)
        assertFalse(BrowserOutput.browserTouchOwned)
    }

    @Test fun disabledOutputPreservesNativeCallbacksWithoutRemoteRecoveryOrTouch() {
        BrowserOutput.stop()
        var frames = 0; var configs = 0; var recovery = 0; var touches = 0
        val native = object : MediaSink {
            override fun onVideoFrame(type: Int, naluBytes: ByteArray) { frames++ }
            override fun onVideoConfig(type: Int, codecData: ByteArray) { configs++ }
        }
        val tee = BrowserOutput.tee(native, 1280, 720, { touches++ }, { touches++ })
        tee.setVideoRecoveryHandler(110) { recovery++ }
        tee.onVideoCodec(110, VideoCodec.H264)
        tee.onVideoConfig(110, byteArrayOf(1,100,0,42,-1,-31,0,4,103,100,0,42,1,0,2,104,1))
        tee.onVideoFrame(110, byteArrayOf(0,0,0,1,101,1))
        tee.onScreenStreamActive(110, false)
        BrowserOutput.stop()
        assertEquals(1, frames); assertEquals(1, configs)
        assertEquals(0, recovery); assertEquals(0, touches)
        assertNull(BrowserOutput.endpoint); assertFalse(BrowserOutput.viewerConnected)
    }
}
