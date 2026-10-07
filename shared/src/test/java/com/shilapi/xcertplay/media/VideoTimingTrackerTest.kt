package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoTimingTrackerTest {
    @Test fun reorderedOutputsMatchTheirOwnPtsAndRenderTimes() {
        val tracker = VideoTimingTracker()
        tracker.onInput(10, 100, 150)
        tracker.onInput(20, 110, 160)
        assertEquals(VideoTimingTracker.Timing(110, 160, 200), tracker.onRelease(20, 200, true).timing)
        assertEquals(VideoTimingTracker.Timing(100, 150, 210), tracker.onRelease(10, 210, true).timing)
        assertEquals(110L, tracker.onRender(20, 230).timing?.receivedNs)
        assertEquals(100L, tracker.onRender(10, 240).timing?.receivedNs)
        assertNull(tracker.onRender(10, 250).timing)
    }

    @Test fun duplicatePtsInvalidatesBothPossibleInputMatches() {
        val tracker = VideoTimingTracker()
        assertFalse(tracker.onInput(10, 100, 150).ambiguous)
        assertTrue(tracker.onInput(10, 110, 160).ambiguous)
        assertNull(tracker.onRelease(10, 200, true).timing)
        assertNull(tracker.onRelease(10, 210, true).timing)
        assertNull(tracker.onRender(10, 220).timing)
    }

    @Test fun duplicatePtsAfterReleaseCannotMatchEarlierRender() {
        val tracker = VideoTimingTracker()
        tracker.onInput(10, 100, 150)
        assertNotNull(tracker.onRelease(10, 200, true).timing)
        assertTrue(tracker.onInput(10, 210, 220).ambiguous)
        assertNull(tracker.onRender(10, 230).timing)
        assertNull(tracker.onRelease(10, 240, true).timing)
    }

    @Test fun completedPtsRemainBoundedTombstones() {
        val tracker = VideoTimingTracker(capacity = 2)
        tracker.onInput(10, 100, 150)
        tracker.onRelease(10, 200, true)
        tracker.onRender(10, 210)
        assertTrue(tracker.onInput(10, 220, 230).ambiguous)
        assertNull(tracker.onRelease(10, 240, true).timing)
        tracker.onInput(20, 300, 310)
        assertEquals(0, tracker.onInput(30, 400, 410).evicted)
        assertEquals(2, tracker.size())
        assertEquals(1, tracker.onInput(40, 500, 510).evicted)
        assertEquals(2, tracker.size())
        assertNull(tracker.onRelease(20, 520, true).timing)
    }

    @Test fun noRenderListenerCompletesAtReleaseAndDoesNotCountTrackingLoss() {
        val tracker = VideoTimingTracker(capacity = 1)
        tracker.onInput(10, 100, 150)
        assertNotNull(tracker.onRelease(10, 200, false).timing)
        assertNull(tracker.onRender(10, 210).timing)
        assertEquals(0, tracker.onInput(20, 220, 230).evicted)
    }

    @Test fun configOutputAndItsCallbacksNeverMatchPictures() {
        val tracker = VideoTimingTracker()
        tracker.onInput(10, 100, 150)
        tracker.excludeOutput(10)
        assertNull(tracker.onRelease(10, 200, true).timing)
        assertTrue(tracker.onRender(10, 210).excluded)
        assertTrue(tracker.onInput(10, 220, 230).ambiguous)
        assertTrue(tracker.onRender(10, 240).excluded)
    }

    @Test fun configExclusionsAlsoHaveBoundedStorage() {
        val tracker = VideoTimingTracker(capacity = 2)
        tracker.onInput(1, 100, 110)
        tracker.excludeOutput(2)
        assertEquals(1, tracker.excludeOutput(3))
        assertEquals(2, tracker.size())
        assertNull(tracker.onRelease(1, 120, true).timing)
    }

    @Test fun resetInvalidatesOutstandingInputsAndLateCallbacks() {
        val tracker = VideoTimingTracker()
        tracker.onInput(10, 100, 150)
        tracker.onRelease(10, 200, true)
        tracker.invalidate()
        assertEquals(0, tracker.size())
        assertNull(tracker.onRender(10, 210).timing)
        tracker.onInput(20, 220, 230)
        assertEquals(0, tracker.size())
        assertNull(tracker.onRelease(20, 240, true).timing)
    }

    @Test fun gapsIncludeLongStaticIntervalsAndIgnoreBackwardsTimestamps() {
        val tracker = VideoTimingTracker()
        tracker.onInput(1, 100, 110)
        tracker.onRelease(1, 200, true)
        tracker.onRender(1, 300)
        tracker.onInput(2, 2_000_000_100, 2_000_000_110)
        assertEquals(2_000_000_000L, tracker.onRelease(2, 2_000_000_200, true).gapNs)
        assertEquals(2_000_000_000L, tracker.onRender(2, 2_000_000_300).gapNs)
        assertEquals(0L, tracker.onRender(99, 250).gapNs)
        assertEquals(100L, tracker.onRender(98, 2_000_000_400).gapNs)
    }

    @Test fun invalidRenderTimestampCannotCreateNegativeLatency() {
        val tracker = VideoTimingTracker()
        tracker.onInput(10, 100, 150)
        tracker.onRelease(10, 200, true)
        assertNull(tracker.onRender(10, 190).timing)
    }
}
