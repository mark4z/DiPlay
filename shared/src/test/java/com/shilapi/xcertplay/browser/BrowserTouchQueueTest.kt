package com.shilapi.xcertplay.browser

import com.shilapi.xcertplay.airplay.AirPlayContact
import org.junit.Assert.*
import org.junit.Test

class BrowserTouchQueueTest {
    private fun down(x: Double, id: Int = 0) = listOf(AirPlayContact(id, x, 0.5, true))
    private fun both(x: Double, y: Double) = down(x) + down(y, 1)

    @Test fun retainsLandingAndLiftBoundariesButCoalescesSubsequentMoves() {
        val q = BrowserTouchQueue()
        q.offer(down(0.1))
        q.offer(down(0.2))
        q.offer(down(0.3))
        q.offer(emptyList())
        q.offer(down(0.8))
        q.offer(down(0.9))
        assertEquals(down(0.1), q.poll())
        assertEquals(down(0.3), q.poll())
        assertEquals(emptyList<AirPlayContact>(), q.poll())
        assertEquals(down(0.8), q.poll())
        assertEquals(down(0.9), q.poll())
        assertNull(q.poll())
    }

    @Test fun fastSwipeRetainsLandingTerminalMovementAndRelease() {
        val q = BrowserTouchQueue()
        q.offer(down(0.25))
        q.offer(down(0.75))
        q.offer(emptyList())
        assertEquals(down(0.25), q.poll())
        assertEquals(down(0.75), q.poll())
        assertEquals(emptyList<AirPlayContact>(), q.poll())
        assertNull(q.poll())
    }

    @Test fun secondFingerLandingAndFirstFingerLiftRemainImmutableBoundaries() {
        val q = BrowserTouchQueue()
        val lifted = listOf(AirPlayContact(0, 0.4, 0.5, false)) + down(0.5, 1)
        val survivingMove = listOf(AirPlayContact(0, 0.4, 0.5, false)) + down(0.4, 1)
        q.offer(down(0.1))
        q.offer(both(0.2, 0.8))
        q.offer(both(0.3, 0.7))
        q.offer(both(0.4, 0.6))
        q.offer(lifted)
        q.offer(survivingMove)
        assertEquals(down(0.1), q.poll())
        assertEquals(both(0.2, 0.8), q.poll())
        assertEquals(both(0.4, 0.6), q.poll())
        assertEquals(lifted, q.poll())
        assertEquals(survivingMove, q.poll())
        assertNull(q.poll())
    }

    @Test fun movesStillCoalesceAfterLandingHasBeenPolled() {
        val q = BrowserTouchQueue()
        q.offer(down(0.1))
        assertEquals(down(0.1), q.poll())
        q.offer(down(0.2))
        q.offer(down(0.3))
        assertEquals(down(0.3), q.poll())
        assertNull(q.poll())
    }

    @Test fun clearInvalidatesOldInputAndPreservesNextGenerationsLanding() {
        val q = BrowserTouchQueue()
        q.offer(down(0.1))
        assertEquals(down(0.1), q.poll())
        q.offer(down(0.2))
        q.clear()
        assertNull(q.poll())
        q.offer(down(0.8))
        q.offer(down(0.9))
        assertEquals(down(0.8), q.poll())
        assertEquals(down(0.9), q.poll())
        assertNull(q.poll())
    }

    @Test fun overflowForcesReleaseAndBoundsQueue() {
        val q = BrowserTouchQueue(2)
        q.offer(down(0.1))
        q.offer(emptyList())
        q.offer(down(0.2))
        assertEquals(emptyList<AirPlayContact>(), q.poll())
        assertNull(q.poll())
        q.offer(down(0.8))
        q.offer(down(0.9))
        assertEquals(down(0.8), q.poll())
        assertEquals(down(0.9), q.poll())
        assertNull(q.poll())
    }

    @Test fun terminalReleaseStillCancelsWhenPreservedSnapshotsFillCapacity() {
        val q = BrowserTouchQueue(2)
        q.offer(down(0.1))
        q.offer(down(0.9))
        q.offer(emptyList())
        assertEquals(emptyList<AirPlayContact>(), q.poll())
        assertNull(q.poll())
    }

    @Test fun manyMovesUseOnlyLandingAndOneLatestMoveSlot() {
        val q = BrowserTouchQueue(2)
        q.offer(down(0.0))
        repeat(10_000) { q.offer(down((it + 1) / 10_000.0)) }
        assertEquals(down(0.0), q.poll())
        assertEquals(down(1.0), q.poll())
        assertNull(q.poll())
    }
}
