package com.shilapi.xcertplay.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserTouchOwnershipTest {
    @Test fun aNewViewerKeepsNativeOwnershipUntilAnExplicitTransfer() {
        val ownership = BrowserTouchOwnership()
        val native = ownership.nativeEpoch()!!
        assertTrue(ownership.acceptsNative(native))
        assertNull(ownership.browserEpoch())
    }

    @Test fun enableBlocksBothSourcesUntilCancellationHasCompleted() {
        val ownership = BrowserTouchOwnership()
        val native = ownership.nativeEpoch()!!
        val epoch = ownership.request(true)
        assertFalse(ownership.acceptsNative(native))
        assertNull(ownership.nativeEpoch())
        assertNull(ownership.browserEpoch())
        assertTrue(ownership.commit(epoch))
        assertTrue(ownership.acceptsBrowser(epoch))
        assertNull(ownership.nativeEpoch())
    }

    @Test fun disableDropsQueuedRemoteInputBeforeRestoringNative() {
        val ownership = BrowserTouchOwnership()
        val browser = ownership.request(true)
        ownership.commit(browser)
        val native = ownership.request(false)
        assertFalse(ownership.acceptsBrowser(browser))
        assertNull(ownership.browserEpoch())
        assertNull(ownership.nativeEpoch())
        assertTrue(ownership.commit(native))
        assertTrue(ownership.acceptsNative(native))
    }

    @Test fun aNativeBrowserNativeRoundTripDoesNotReplayOldNativeInput() {
        val ownership = BrowserTouchOwnership()
        val oldNative = ownership.nativeEpoch()!!
        ownership.commit(ownership.request(true))
        ownership.commit(ownership.request(false))
        assertFalse(ownership.acceptsNative(oldNative))
        assertTrue(ownership.acceptsNative(ownership.nativeEpoch()!!))
    }

    @Test fun oldOwnershipCompletionCannotWinRapidEnableDisableEnable() {
        val ownership = BrowserTouchOwnership()
        val enable = ownership.request(true)
        val disable = ownership.request(false)
        val newest = ownership.request(true)
        assertFalse(ownership.commit(enable))
        assertFalse(ownership.commit(disable))
        ownership.fail(disable)
        assertNull(ownership.browserEpoch())
        assertTrue(ownership.commit(newest))
        assertTrue(ownership.acceptsBrowser(newest))
        assertFalse(ownership.acceptsBrowser(enable))
    }

    @Test fun repeatedEnableAlsoWaitsForItsCancellation() {
        val ownership = BrowserTouchOwnership()
        val first = ownership.request(true)
        ownership.commit(first)
        val second = ownership.request(true)
        assertNull(ownership.browserEpoch())
        assertFalse(ownership.acceptsBrowser(first))
        ownership.commit(second)
        assertTrue(ownership.acceptsBrowser(second))
    }

    @Test fun unavailableControllerOrFailedCancellationCannotGrantControl() {
        val ownership = BrowserTouchOwnership()
        val epoch = ownership.request(true)
        ownership.fail(epoch)
        assertFalse(ownership.commit(epoch))
        assertNull(ownership.browserEpoch())
        assertTrue(ownership.acceptsNative(ownership.nativeEpoch()!!))
    }

    @Test fun ownershipFloodCoalescesToOneLatestTransferWhileTheWorkerIsBlocked() {
        val pending = BrowserTouchTransfers()
        var workers = 0
        var calls = 0
        repeat(100_000) { index ->
            if (pending.offer(BrowserTouchTransfer(index.toLong(), index % 2 == 0) { calls++ })) workers++
        }
        assertEquals(1, workers)
        val latest = pending.poll()!!
        assertEquals(99_999L, latest.epoch)
        assertFalse(latest.enabled)
        latest.completed(true)
        assertEquals(1, calls)
        // One replacement can wait behind the now-in-flight transfer, without another worker.
        assertFalse(pending.offer(BrowserTouchTransfer(100_000L, true) { calls++ }))
        assertEquals(100_000L, pending.poll()!!.epoch)
        assertNull(pending.poll())
        assertTrue(pending.offer(BrowserTouchTransfer(100_001L, false) {}))
    }

}
