package com.shilapi.xcertplay.browser

import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BrowserOutputOwnershipTest {
    private data class Transfer(val enabled: Boolean, val complete: (Boolean) -> Unit)
    private val transfers = mutableListOf<Transfer>()
    private val touches = mutableListOf<List<AirPlayContact>>()
    private lateinit var tee: MediaSink

    @Before fun setUp() {
        BrowserOutput.stop()
        tee = BrowserOutput.tee(object : MediaSink {}, 1280, 720, {}, touches::add,
            setTouchOwnership = { enabled, complete -> transfers.add(Transfer(enabled, complete)) })
        tee.onVideoCodec(110, VideoCodec.H264)
        configure()
        field("viewerConnected").setBoolean(null, true)
        field("lastOwnershipRequestId").setLong(null, 0L)
    }

    @After fun cleanUp() { BrowserOutput.stop() }

    @Test fun authenticatedViewingDoesNotGrantTouch() {
        touch()
        assertTrue(BrowserOutput.viewerConnected)
        assertFalse(BrowserOutput.browserTouchOwned)
        assertTrue(touches.isEmpty())
        ownership(false, 1)
        assertTrue("A video-only disable must not cancel native touch", transfers.isEmpty())
    }

    @Test fun enableRequiresCompletedCancellationAndDisableImmediatelyBlocksRemoteInput() {
        ownership(true, 1)
        assertTrue(transfers.single().enabled)
        touch()
        assertTrue(touches.isEmpty())
        transfers.single().complete(true)
        assertTrue(BrowserOutput.browserTouchOwned)
        touch()
        assertEquals(1, touches.size)
        assertTrue(touches.single().first().down)
        ownership(false, 2)
        assertFalse(BrowserOutput.browserTouchOwned)
        assertFalse(transfers.last().enabled)
        touch()
        assertEquals(1, touches.size)
        transfers.last().complete(true)
        assertFalse(BrowserOutput.browserTouchOwned)
    }

    @Test fun lateEnableCompletionCannotUndoANewerDisable() {
        ownership(true, 1)
        val enable = transfers.last()
        ownership(false, 2)
        val disable = transfers.last()
        enable.complete(true)
        assertFalse(BrowserOutput.browserTouchOwned)
        disable.complete(true)
        assertFalse(BrowserOutput.browserTouchOwned)
    }

    @Test fun streamReconfigurationRevokesOwnershipAndInvalidatesPendingCompletion() {
        ownership(true, 1)
        val oldEnable = transfers.last()
        val oldStream = stream()
        configure(2)
        assertFalse(transfers.last().enabled)
        oldEnable.complete(true)
        assertFalse(BrowserOutput.browserTouchOwned)
        ownership(true, 2, oldStream)
        assertEquals(2, transfers.size)
        ownership(true, 3)
        transfers.last().complete(true)
        assertTrue(BrowserOutput.browserTouchOwned)
    }

    @Test fun staleRequestIdsCannotOverrideTheLatestOwnershipDecision() {
        ownership(true, 4)
        transfers.last().complete(true)
        ownership(false, 3)
        ownership(false, 4)
        assertEquals(1, transfers.size)
        assertTrue(BrowserOutput.browserTouchOwned)
    }

    @Test fun stopInvalidatesPendingEnableAndRestoresNativeOwnership() {
        ownership(true, 1)
        val enable = transfers.last()
        BrowserOutput.stop()
        assertFalse(transfers.last().enabled)
        enable.complete(true)
        assertFalse(BrowserOutput.viewerConnected)
        assertFalse(BrowserOutput.browserTouchOwned)
    }

    @Test fun failedControllerTransferNeverGrantsTouch() {
        ownership(true, 1)
        transfers.last().complete(false)
        assertFalse(BrowserOutput.browserTouchOwned)
        touch()
        assertTrue(touches.isEmpty())
    }

    @Test fun repeatedParameterSetsDoNotRevokeOwnershipOrInvalidatePendingAck() {
        ownership(true, 1)
        val pending = transfers.single()
        val originalStream = stream()
        repeat(60) { configure() }
        assertEquals(originalStream, stream())
        assertEquals(1, transfers.size)
        pending.complete(true)
        assertTrue(BrowserOutput.browserTouchOwned)
        touch()
        assertEquals(1, touches.size)
        repeat(60) { configure() }
        assertTrue(BrowserOutput.browserTouchOwned)
        assertEquals(1, transfers.size)
        assertEquals(originalStream, stream())
    }

    @Test fun changedParameterSetsRevokeHeldContactsAndRequireFreshGenerationAck() {
        ownership(true, 1)
        transfers.last().complete(true)
        touch()
        val originalStream = stream()
        configure(2)
        assertFalse(BrowserOutput.browserTouchOwned)
        assertFalse(transfers.last().enabled)
        assertTrue(stream() > originalStream)
        ownership(true, 2, originalStream)
        assertEquals(2, transfers.size)
        ownership(true, 3)
        assertFalse(BrowserOutput.browserTouchOwned)
        transfers.last().complete(true)
        assertTrue(BrowserOutput.browserTouchOwned)
    }

    private fun configure(pps: Byte = 1) = tee.onVideoConfig(110,
        byteArrayOf(1,100,0,42,-1,-31,0,4,103,100,0,42,1,0,2,104,pps))

    private fun ownership(enabled: Boolean, request: Long, stream: Long = stream()) =
        receive("{\"type\":\"setTouchOwnership\",\"enabled\":$enabled,\"requestId\":$request,\"streamId\":$stream}")

    private fun touch() = receive("{\"type\":\"touch\",\"streamId\":${stream()},\"contacts\":[{\"id\":0,\"x\":0.2,\"y\":0.3}]}")

    private fun stream() = field("streamId").getLong(null)
    private fun field(name: String) = BrowserOutput::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun receive(message: String) {
        BrowserOutput::class.java.getDeclaredMethod("receive", String::class.java, java.lang.Long.TYPE)
            .apply { isAccessible = true }.invoke(BrowserOutput, message, field("serverGeneration").getLong(null))
    }
}
